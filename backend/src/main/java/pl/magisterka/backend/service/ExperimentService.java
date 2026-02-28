package pl.magisterka.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.magisterka.backend.api.dto.WorkloadDto;
import pl.magisterka.backend.api.dto.WeeklyPlanBatchRequestDto;
import pl.magisterka.backend.api.dto.WeeklyPlanCompareRequestDto;
import pl.magisterka.backend.batch.SimulationEngine;
import pl.magisterka.backend.batch.SimulationPolicy;
import pl.magisterka.backend.batch.SimulationResult;
import pl.magisterka.backend.batch.spec.ConfigDeviceSpecProvider;
import pl.magisterka.backend.db.ExperimentEntity;
import pl.magisterka.backend.db.ExperimentRepository;
import pl.magisterka.backend.db.WeeklyPlanRuleEntity;
import pl.magisterka.backend.db.WeeklyPlanRuleRepository;
import pl.magisterka.backend.model.ExperimentType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ExperimentService {

    /** Safety buffer (real seconds) added to min duration so sim has time to complete. */
    private static final int DURATION_BUFFER_SEC = 30;

    /** Isolated 1B only: multiply speed factor so real-time timeout is shorter (same sim duration). */
    private static final int ISOLATED_SPEED_MULTIPLIER = 4;

    private final ExperimentRepository experimentRepository;
    private final ManualPolicyRunner manualPolicyRunner;
    private final SchedulePolicyRunner schedulePolicyRunner;
    private final EnergySummaryService energySummaryService;
    private final SimTimeService simTimeService;
    private final WeeklyPlanManualRunner weeklyPlanManualRunner;
    private final WeeklyPlanScheduleRunner weeklyPlanScheduleRunner;
    private final SimulatorResetService simulatorResetService;
    private final WeeklyPlanRuleRepository weeklyPlanRuleRepository;
    private final ObjectMapper objectMapper;
    private final SimulationEngine simulationEngine;
    private final SimulationAnalyticsEngine simulationAnalyticsEngine;
    private final ConfigDeviceSpecProvider configDeviceSpecProvider;
    private final int speedFactor;
    private final int maxRunRealSecondsPerPolicy;

    private static final int BATCH_MAX_POINTS = 500_000;

    public ExperimentService(
            ExperimentRepository experimentRepository,
            ManualPolicyRunner manualPolicyRunner,
            SchedulePolicyRunner schedulePolicyRunner,
            EnergySummaryService energySummaryService,
            SimTimeService simTimeService,
            WeeklyPlanManualRunner weeklyPlanManualRunner,
            WeeklyPlanScheduleRunner weeklyPlanScheduleRunner,
            SimulatorResetService simulatorResetService,
            WeeklyPlanRuleRepository weeklyPlanRuleRepository,
            ObjectMapper objectMapper,
            SimulationEngine simulationEngine,
            SimulationAnalyticsEngine simulationAnalyticsEngine,
            ConfigDeviceSpecProvider configDeviceSpecProvider,
            @Value("${sim.speedFactor:600}") int speedFactor,
            @Value("${sim.maxRunRealSecondsPerPolicy:300}") int maxRunRealSecondsPerPolicy
    ) {
        this.experimentRepository = experimentRepository;
        this.manualPolicyRunner = manualPolicyRunner;
        this.schedulePolicyRunner = schedulePolicyRunner;
        this.energySummaryService = energySummaryService;
        this.simTimeService = simTimeService;
        this.weeklyPlanManualRunner = weeklyPlanManualRunner;
        this.weeklyPlanScheduleRunner = weeklyPlanScheduleRunner;
        this.simulatorResetService = simulatorResetService;
        this.weeklyPlanRuleRepository = weeklyPlanRuleRepository;
        this.objectMapper = objectMapper;
        this.simulationEngine = simulationEngine;
        this.simulationAnalyticsEngine = simulationAnalyticsEngine;
        this.configDeviceSpecProvider = configDeviceSpecProvider;
        this.speedFactor = speedFactor;
        this.maxRunRealSecondsPerPolicy = maxRunRealSecondsPerPolicy;
    }

    public Optional<ExperimentEntity> getActiveExperiment() {
        return experimentRepository.findByActiveTrue();
    }

    // =========================
    // 1A: Workload study
    // =========================

    @Transactional
    public ExperimentEntity start(long experimentId) {
        manualPolicyRunner.stop();
        schedulePolicyRunner.stop();

        experimentRepository.findByActiveTrue().ifPresent(active -> {
            active.setActive(false);
            experimentRepository.save(active);
        });

        ExperimentEntity e = experimentRepository.findById(experimentId)
                .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + experimentId));

        e.setActive(true);
        ExperimentEntity saved = experimentRepository.save(e);

        if (saved.getType() == ExperimentType.MANUAL) manualPolicyRunner.start(saved);
        if (saved.getType() == ExperimentType.SCHEDULE) schedulePolicyRunner.start(saved);

        return saved;
    }

    @Transactional
    public void stopActive() {
        manualPolicyRunner.stop();
        schedulePolicyRunner.stop();

        experimentRepository.findByActiveTrue().ifPresent(active -> {
            active.setActive(false);
            experimentRepository.save(active);
        });
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> compareRun(WorkloadDto workload) {
        if (workload.durationSeconds > maxRunRealSecondsPerPolicy) {
            throw new IllegalArgumentException(
                    "durationSeconds (" + workload.durationSeconds + ") exceeds max " + maxRunRealSecondsPerPolicy + " s per policy. Limit real run time for fast tests.");
        }

        ExperimentEntity manual = createExperiment("Compare-MANUAL", ExperimentType.MANUAL, workload.seed);
        ExperimentEntity schedule = createExperiment("Compare-SCHEDULE", ExperimentType.SCHEDULE, workload.seed);

        manualPolicyRunner.setWorkload(workload);
        schedulePolicyRunner.setWorkload(workload);

        RunUntilResult manualRes;
        RunUntilResult scheduleRes;

        try {
            start(manual.getId());
            manualRes = runUntilTargetsOrTimeout(manual.getId(), workload);
            stopActive();

            start(schedule.getId());
            scheduleRes = runUntilTargetsOrTimeout(schedule.getId(), workload);
            stopActive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        Integer stepOverride = workload.stepSimMs;
        int durationSeconds = workload.durationSeconds;
        Map<String, Double> targetKwh = workload.devices == null ? Map.of()
                : workload.devices.stream().collect(Collectors.toMap(d -> d.deviceId, d -> d.targetKwh, (a, b) -> a));
        Map<String, Object> manualSummary = buildSummary(manual.getId(), manualRes, stepOverride, durationSeconds, targetKwh);
        Map<String, Object> scheduleSummary = buildSummary(schedule.getId(), scheduleRes, stepOverride, durationSeconds, targetKwh);

        Map<String, Object> out = new HashMap<>();
        out.put("manual", manualSummary);
        out.put("schedule", scheduleSummary);

        out.put("manualAchieved", manualRes.isAchieved());
        out.put("scheduleAchieved", scheduleRes.isAchieved());

        out.put("manualTimeToAchieveSimMs", manualRes.getAchievedAtSimTimeMs());
        out.put("scheduleTimeToAchieveSimMs", scheduleRes.getAchievedAtSimTimeMs());
        out.put("manualAnalysisWindowSimMs", manualRes.getSummaryEndSimTimeMs() - manualRes.getStartSimTimeMs());
        out.put("scheduleAnalysisWindowSimMs", scheduleRes.getSummaryEndSimTimeMs() - scheduleRes.getStartSimTimeMs());

        Map<String, Object> diffAbs = diffAbs(
                (Map<String, Object>) manualSummary.get("meta"),
                (Map<String, Object>) scheduleSummary.get("meta")
        );
        Map<String, Object> diffPct = diffPct(
                (Map<String, Object>) manualSummary.get("meta"),
                (Map<String, Object>) scheduleSummary.get("meta")
        );

        out.put("diffAbs", diffAbs);
        out.put("diffPct", diffPct);
        out.put("manualExperimentId", manual.getId());
        out.put("scheduleExperimentId", schedule.getId());
        out.put("manualStartSimMs", manualRes.getStartSimTimeMs());
        out.put("manualEndSimMs", manualRes.getSummaryEndSimTimeMs());
        out.put("scheduleStartSimMs", scheduleRes.getStartSimTimeMs());
        out.put("scheduleEndSimMs", scheduleRes.getSummaryEndSimTimeMs());
        return out;
    }

    private RunUntilResult runUntilTargetsOrTimeout(long experimentId, WorkloadDto workload) throws InterruptedException {
        long startReal = System.currentTimeMillis();
        long deadlineReal = startReal + (long) workload.durationSeconds * 1000L;

        long timeoutMs = 5000;
        long startWait = System.currentTimeMillis();
        Long startSim;
        do {
            startSim = simTimeService.getMinSimTimeMs(experimentId);
            if (startSim != null) break;
            Thread.sleep(50);
        } while (System.currentTimeMillis() - startWait < timeoutMs);

        if (startSim == null) {
            throw new IllegalStateException("No telemetry received after experiment start");
        }

        Map<String, Double> targetKwh = workload.devices == null ? Map.of()
                : workload.devices.stream().collect(Collectors.toMap(d -> d.deviceId, d -> d.targetKwh, (a, b) -> a));

        while (System.currentTimeMillis() < deadlineReal) {
            Long toSimMs = simTimeService.getMaxSimTimeMsNullable(experimentId);
            if (toSimMs == null || toSimMs <= startSim) {
                Thread.sleep(200);
                continue;
            }
            ExperimentMetricsResult res = energySummaryService.computeExperimentMetricsWithTargets(
                    experimentId, startSim, toSimMs, targetKwh, workload.stepSimMs);

            if (res.isAllTargetsAchieved()) {
                long endSim = simTimeService.getCurrentSimTimeMs(experimentId);
                Long achievedAt = res.getExperimentTimeToAchieveSimMs();
                return new RunUntilResult(true, startSim, endSim, achievedAt != null ? achievedAt : endSim, null);
            }

            Thread.sleep(200);
        }

        long endSim = simTimeService.getCurrentSimTimeMs(experimentId);
        ExperimentMetricsResult lastRes = energySummaryService.computeExperimentMetricsWithTargets(
                experimentId, startSim, endSim, targetKwh, workload.stepSimMs);
        long effectiveTo = lastRes.getEffectiveToSimMs();
        return new RunUntilResult(false, startSim, endSim, null, effectiveTo > 0 ? effectiveTo : endSim);
    }

    // =========================
    // 1B: Weekly plan study
    // =========================

    @SuppressWarnings("unchecked")
    public Map<String, Object> compareWeeklyPlanRun(WeeklyPlanCompareRequestDto req) {

        if (req == null || req.scenarioId == null || req.scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId required");
        }

        int weeks = req.weeks <= 0 ? 4 : req.weeks;
        long simDurationMs = weeks * 7L * 24L * 60L * 60L * 1000L;

        // Ensure real-time timeout covers full sim duration (1B fix).
        long minDurationSec = computeMinRealDurationSec(weeks, speedFactor);
        long effectiveDurationSec = req.durationSeconds < minDurationSec
                ? minDurationSec + DURATION_BUFFER_SEC
                : req.durationSeconds;

        ExperimentEntity manual = createExperiment("CompareWeeklyPlan-MANUAL", ExperimentType.MANUAL, req.seed);
        ExperimentEntity schedule = createExperiment("CompareWeeklyPlan-SCHEDULE", ExperimentType.SCHEDULE, req.seed);

        RunUntilResult manualRes;
        RunUntilResult scheduleRes;

        try {
            stopAllRunnersAndDeactivate();

            manual.setActive(true);
            experimentRepository.save(manual);
            weeklyPlanManualRunner.start(manual, req.scenarioId);
            manualRes = runForSimDurationOrTimeout(manual.getId(), simDurationMs, effectiveDurationSec);
            weeklyPlanManualRunner.stop();
            manual.setActive(false);
            experimentRepository.save(manual);

            schedule.setActive(true);
            experimentRepository.save(schedule);
            weeklyPlanScheduleRunner.start(schedule, req.scenarioId);
            // Symulator NIE resetuje czasu – po Manual jest już na ~1 tydzień. Drugi przebieg musi czekać
            // na kolejny tydzień sym (od końca Manual), inaczej startSim=0 i warunek spełnia się od razu → Schedule ~0 kWh.
            scheduleRes = runForSimDurationOrTimeout(schedule.getId(), simDurationMs, effectiveDurationSec, manualRes.getEndSimTimeMs());
            weeklyPlanScheduleRunner.stop();
            schedule.setActive(false);
            experimentRepository.save(schedule);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        Map<String, Object> manualSummary = buildSummary(manual.getId(), manualRes, null, (int) effectiveDurationSec, null);
        Map<String, Object> scheduleSummary = buildSummary(schedule.getId(), scheduleRes, null, (int) effectiveDurationSec, null);

        Map<String, Object> out = new HashMap<>();
        out.put("manual", manualSummary);
        out.put("schedule", scheduleSummary);

        out.put("manualSimDurationMs", manualRes.getSimDurationMs());
        out.put("scheduleSimDurationMs", scheduleRes.getSimDurationMs());
        out.put("weeks", weeks);
        out.put("scenarioId", req.scenarioId);

        Map<String, Object> diffAbs = diffAbs(
                (Map<String, Object>) manualSummary.get("meta"),
                (Map<String, Object>) scheduleSummary.get("meta")
        );
        Map<String, Object> diffPct = diffPct(
                (Map<String, Object>) manualSummary.get("meta"),
                (Map<String, Object>) scheduleSummary.get("meta")
        );

        out.put("diffAbs", diffAbs);
        out.put("diffPct", diffPct);

        double manualWaste = energySummaryService.computeEnergyOutsidePlanKwh(

                manual.getId(),
                req.scenarioId,
                manualRes.getStartSimTimeMs(),
                manualRes.getEndSimTimeMs()


        );

        double scheduleWaste = energySummaryService.computeEnergyOutsidePlanKwh(
                schedule.getId(),
                req.scenarioId,
                scheduleRes.getStartSimTimeMs(),
                scheduleRes.getEndSimTimeMs()
        );

        // 1) do meta (żeby było obok totalKwh/avg/peak)
        ((Map<String, Object>) manualSummary.get("meta")).put("wasteKwh", round4(manualWaste));
        ((Map<String, Object>) scheduleSummary.get("meta")).put("wasteKwh", round4(scheduleWaste));

        // 2) do diffów (żeby front miał spójnie w diffAbs/diffPct)
        diffAbs.put("wasteKwh", round4(scheduleWaste - manualWaste));
        diffPct.put("wasteKwh", round2(pct(scheduleWaste, manualWaste)));

        return out;
    }

    /**
     * Isolated 1B: Manual and Schedule each run from sim 0..T after simulator reset.
     * Same seed, scenarioId, weeks; metrics on identical window [0, T]. No change to legacy compareWeeklyPlanRun.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> compareWeeklyPlanRunIsolated(WeeklyPlanCompareRequestDto req) {
        if (req == null || req.scenarioId == null || req.scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId required");
        }

        int weeks = req.weeks <= 0 ? 4 : req.weeks;
        long simDurationMs = weeks * 7L * 24L * 60L * 60L * 1000L;
        int effectiveSpeedFactor = speedFactor * ISOLATED_SPEED_MULTIPLIER;
        long minDurationSec = computeMinRealDurationSec(weeks, effectiveSpeedFactor);
        long effectiveDurationSec = req.durationSeconds < minDurationSec
                ? minDurationSec + DURATION_BUFFER_SEC
                : req.durationSeconds;

        stopAllRunnersAndDeactivate();

        // --- Manual: reset -> run 0..T ---
        simulatorResetService.resetAndAwaitAck(Duration.ofSeconds(15));

        ExperimentEntity manual = createExperiment("WEEKLY_PLAN_MANUAL", ExperimentType.MANUAL, req.seed);
        List<WeeklyPlanRuleEntity> rulesManual = weeklyPlanRuleRepository.findByScenarioIdAndEnabledTrue(req.scenarioId);
        try {
            manual.setWeeklyPlanSnapshotJson(objectMapper.writeValueAsString(rulesManual));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize weekly plan snapshot", e);
        }
        experimentRepository.save(manual);

        manual.setActive(true);
        experimentRepository.save(manual);
        weeklyPlanManualRunner.start(manual, req.scenarioId);
        RunUntilResult manualRes;
        try {
            manualRes = runForSimDurationOrTimeout(manual.getId(), simDurationMs, effectiveDurationSec);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            weeklyPlanManualRunner.stop();
            manual.setActive(false);
            experimentRepository.save(manual);
            throw new RuntimeException(e);
        }
        weeklyPlanManualRunner.stop();
        manual.setActive(false);
        experimentRepository.save(manual);

        // --- Schedule: reset -> run 0..T ---
        simulatorResetService.resetAndAwaitAck(Duration.ofSeconds(15));

        ExperimentEntity schedule = createExperiment("WEEKLY_PLAN_SCHEDULE", ExperimentType.SCHEDULE, req.seed);
        List<WeeklyPlanRuleEntity> rulesSchedule = weeklyPlanRuleRepository.findByScenarioIdAndEnabledTrue(req.scenarioId);
        try {
            schedule.setWeeklyPlanSnapshotJson(objectMapper.writeValueAsString(rulesSchedule));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize weekly plan snapshot", e);
        }
        experimentRepository.save(schedule);

        schedule.setActive(true);
        experimentRepository.save(schedule);
        weeklyPlanScheduleRunner.start(schedule, req.scenarioId);
        RunUntilResult scheduleRes;
        try {
            scheduleRes = runForSimDurationOrTimeout(schedule.getId(), simDurationMs, effectiveDurationSec);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            weeklyPlanScheduleRunner.stop();
            schedule.setActive(false);
            experimentRepository.save(schedule);
            throw new RuntimeException(e);
        }
        weeklyPlanScheduleRunner.stop();
        schedule.setActive(false);
        experimentRepository.save(schedule);

        Set<String> plannedDeviceIds = new LinkedHashSet<>(buildPlannedDeviceIds(rulesManual));
        Map<String, Object> manualSummary = buildSummaryForWeeklyPlan(manual.getId(), manualRes, null, (int) effectiveDurationSec, plannedDeviceIds);
        Map<String, Object> scheduleSummary = buildSummaryForWeeklyPlan(schedule.getId(), scheduleRes, null, (int) effectiveDurationSec, plannedDeviceIds);

        Map<String, Object> out = new HashMap<>();
        out.put("manual", manualSummary);
        out.put("schedule", scheduleSummary);
        out.put("manualSimDurationMs", manualRes.getSimDurationMs());
        out.put("scheduleSimDurationMs", scheduleRes.getSimDurationMs());
        out.put("weeks", weeks);
        out.put("scenarioId", req.scenarioId);
        out.put("isolated", true);

        Map<String, Object> debug = new HashMap<>();
        debug.put("plannedDeviceIds", new ArrayList<>(plannedDeviceIds));
        debug.put("telemetryDeviceIdsManual", energySummaryService.findDistinctDeviceIdsByExperimentId(manual.getId()));
        debug.put("telemetryDeviceIdsSchedule", energySummaryService.findDistinctDeviceIdsByExperimentId(schedule.getId()));
        Map<String, Double> manualKwh = (Map<String, Double>) manualSummary.get("kwhPerDevice");
        Map<String, Double> scheduleKwh = (Map<String, Double>) scheduleSummary.get("kwhPerDevice");
        debug.put("usedDeviceIdsManual", manualKwh != null ? new ArrayList<>(manualKwh.keySet()) : List.of());
        debug.put("usedDeviceIdsSchedule", scheduleKwh != null ? new ArrayList<>(scheduleKwh.keySet()) : List.of());
        out.put("debug", debug);

        Map<String, Object> diffAbs = diffAbs(
                (Map<String, Object>) manualSummary.get("meta"),
                (Map<String, Object>) scheduleSummary.get("meta")
        );
        Map<String, Object> diffPct = diffPct(
                (Map<String, Object>) manualSummary.get("meta"),
                (Map<String, Object>) scheduleSummary.get("meta")
        );
        out.put("diffAbs", diffAbs);
        out.put("diffPct", diffPct);

        List<WeeklyPlanRuleEntity> manualRulesForWaste = parseSnapshotRules(manual.getWeeklyPlanSnapshotJson());
        List<WeeklyPlanRuleEntity> scheduleRulesForWaste = parseSnapshotRules(schedule.getWeeklyPlanSnapshotJson());
        double manualWaste = energySummaryService.computeEnergyOutsidePlanKwh(
                manual.getId(), manualRes.getStartSimTimeMs(), manualRes.getEndSimTimeMs(),
                manualRulesForWaste != null ? manualRulesForWaste : rulesManual
        );
        double scheduleWaste = energySummaryService.computeEnergyOutsidePlanKwh(
                schedule.getId(), scheduleRes.getStartSimTimeMs(), scheduleRes.getEndSimTimeMs(),
                scheduleRulesForWaste != null ? scheduleRulesForWaste : rulesSchedule
        );

        ((Map<String, Object>) manualSummary.get("meta")).put("wasteKwh", round4(manualWaste));
        ((Map<String, Object>) scheduleSummary.get("meta")).put("wasteKwh", round4(scheduleWaste));
        diffAbs.put("wasteKwh", round4(scheduleWaste - manualWaste));
        diffPct.put("wasteKwh", round2(pct(scheduleWaste, manualWaste)));

        return out;
    }

    private List<WeeklyPlanRuleEntity> parseSnapshotRules(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Batch 1B: in-memory simulation, no MQTT, no DB. Adaptive step (≤500k points).
     * Returns usedBatchStepMs, deviceCount, plannedDeviceIds for UI "Advanced".
     */
    public Map<String, Object> compareWeeklyPlanRunBatch(WeeklyPlanCompareRequestDto req) {
        if (req == null || req.scenarioId == null || req.scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId required");
        }
        int weeks = req.weeks <= 0 ? 4 : req.weeks;
        long simDurationMs = weeks * 7L * 24L * 60L * 60L * 1000L;
        List<WeeklyPlanRuleEntity> rules = weeklyPlanRuleRepository.findByScenarioIdAndEnabledTrue(req.scenarioId);

        List<String> plannedDeviceIds = buildPlannedDeviceIds(rules);
        int deviceCount = plannedDeviceIds.size();
        if (deviceCount == 0) {
            throw new IllegalArgumentException("No enabled rules for scenario; add at least one rule with a device.");
        }
        int usedBatchStepMs = computeAdaptiveBatchStepMs(weeks, simDurationMs, deviceCount);

        SimulationResult manualResult = simulationEngine.runBatchSimulation(
                simDurationMs, usedBatchStepMs, rules, req.seed, SimulationPolicy.MANUAL);
        SimulationResult scheduleResult = simulationEngine.runBatchSimulation(
                simDurationMs, usedBatchStepMs, rules, req.seed, SimulationPolicy.SCHEDULE);

        var manualMetrics = simulationAnalyticsEngine.computeMetricsFromTelemetry(
                manualResult.telemetry(), 0L, simDurationMs, usedBatchStepMs);
        var scheduleMetrics = simulationAnalyticsEngine.computeMetricsFromTelemetry(
                scheduleResult.telemetry(), 0L, simDurationMs, usedBatchStepMs);

        Map<String, Object> manualMeta = energySummaryService.buildMetaFromResult(manualMetrics);
        Map<String, Object> scheduleMeta = energySummaryService.buildMetaFromResult(scheduleMetrics);

        double manualWaste = energySummaryService.computeWasteFromTelemetry(manualResult.telemetry(), rules);
        double scheduleWaste = energySummaryService.computeWasteFromTelemetry(scheduleResult.telemetry(), rules);
        manualMeta.put("wasteKwh", round4(manualWaste));
        scheduleMeta.put("wasteKwh", round4(scheduleWaste));

        Map<String, Object> manualSummary = new HashMap<>();
        manualSummary.put("meta", manualMeta);
        manualSummary.put("kwhPerDevice", manualMetrics.getKwhPerDevice());
        Map<String, Object> scheduleSummary = new HashMap<>();
        scheduleSummary.put("meta", scheduleMeta);
        scheduleSummary.put("kwhPerDevice", scheduleMetrics.getKwhPerDevice());

        Map<String, Object> diffAbs = diffAbs(manualMeta, scheduleMeta);
        Map<String, Object> diffPct = diffPct(manualMeta, scheduleMeta);
        diffAbs.put("wasteKwh", round4(scheduleWaste - manualWaste));
        diffPct.put("wasteKwh", round2(pct(scheduleWaste, manualWaste)));

        Map<String, Object> out = new HashMap<>();
        out.put("manual", manualSummary);
        out.put("schedule", scheduleSummary);
        out.put("manualSimDurationMs", simDurationMs);
        out.put("scheduleSimDurationMs", simDurationMs);
        out.put("weeks", weeks);
        out.put("scenarioId", req.scenarioId);
        out.put("batch", true);
        out.put("usedBatchStepMs", usedBatchStepMs);
        out.put("deviceCount", deviceCount);
        out.put("plannedDeviceIds", plannedDeviceIds);
        out.put("manualOnProbability", configDeviceSpecProvider.getManualOnProbability());
        out.put("diffAbs", diffAbs);
        out.put("diffPct", diffPct);
        return out;
    }

    private List<String> buildPlannedDeviceIds(List<WeeklyPlanRuleEntity> rules) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        if (rules != null) {
            for (WeeklyPlanRuleEntity r : rules) {
                if (r.isEnabled() && r.getDeviceId() != null) ids.add(r.getDeviceId());
            }
        }
        return new ArrayList<>(ids);
    }

    /** ≤4 tyg: 5s, ≤12 tyg: 10s, else 60s; cap so total points ≤ BATCH_MAX_POINTS. */
    private static int computeAdaptiveBatchStepMs(int weeks, long simDurationMs, int deviceCount) {
        int minStep = weeks <= 4 ? 5_000 : (weeks <= 12 ? 10_000 : 60_000);
        long maxSteps = BATCH_MAX_POINTS / Math.max(1, deviceCount);
        if (maxSteps <= 0) return minStep;
        long stepFromLimit = simDurationMs / maxSteps;
        int stepMs = (int) Math.max(minStep, stepFromLimit);
        if (stepMs < 5_000) stepMs = 5_000;
        else if (stepMs < 10_000) stepMs = 5_000;
        else if (stepMs < 60_000) stepMs = 10_000;
        else stepMs = 60_000;
        return stepMs;
    }

    /**
     * Runs compareWeeklyPlanRun N times (e.g. N=20) with varying seeds for stable statistics.
     */
    public Map<String, Object> batchCompareWeeklyPlanRun(WeeklyPlanBatchRequestDto batchReq) {
        if (batchReq == null || batchReq.scenarioId == null || batchReq.scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId required");
        }
        int repeats = Math.max(1, Math.min(batchReq.repeats, 100));
        WeeklyPlanCompareRequestDto req = new WeeklyPlanCompareRequestDto();
        req.scenarioId = batchReq.scenarioId;
        req.weeks = batchReq.weeks <= 0 ? 4 : batchReq.weeks;
        req.durationSeconds = batchReq.durationSeconds;
        List<Map<String, Object>> runs = new ArrayList<>();
        for (int i = 0; i < repeats; i++) {
            req.seed = batchReq.seed + i;
            runs.add(compareWeeklyPlanRun(req));
        }
        Map<String, Object> out = new HashMap<>();
        out.put("repeats", repeats);
        out.put("scenarioId", batchReq.scenarioId);
        out.put("weeks", req.weeks);
        out.put("runs", runs);
        return out;
    }

    /**
     * Runs until the requested sim duration is reached or real-time timeout expires.
     * Primary exit condition: sim time reaches simDurationMs (full weeks of sim).
     * durationSeconds is a safety timeout only (prevents runaway if sim stalls).
     * @param startSimTimeMsOverride when >= 0, used as startSim (dla drugiego przebiegu – symulator nie resetuje czasu).
     */
    private RunUntilResult runForSimDurationOrTimeout(long experimentId, long simDurationMs, long durationSeconds, long startSimTimeMsOverride) throws InterruptedException {
        long startReal = System.currentTimeMillis();
        long deadlineReal = startReal + Math.max(10, durationSeconds) * 1000L;

        long startSim = startSimTimeMsOverride >= 0
                ? startSimTimeMsOverride
                : simTimeService.getCurrentSimTimeMs(experimentId);

        while (System.currentTimeMillis() < deadlineReal) {
            long nowSim = simTimeService.getCurrentSimTimeMs(experimentId);
            if (nowSim - startSim >= simDurationMs) {
                return new RunUntilResult(true, startSim, nowSim);
            }
            Thread.sleep(200);
        }

        long endSim = simTimeService.getCurrentSimTimeMs(experimentId);
        return new RunUntilResult(false, startSim, endSim);
    }

    private RunUntilResult runForSimDurationOrTimeout(long experimentId, long simDurationMs, long durationSeconds) throws InterruptedException {
        return runForSimDurationOrTimeout(experimentId, simDurationMs, durationSeconds, -1L);
    }

    /**
     * Minimum real-time duration (seconds) required to cover the given sim weeks.
     * Formula: simDurationMs / 1000 / speedFactor (speedFactor = sim seconds per real second).
     */
    public static long computeMinRealDurationSec(int weeks, int speedFactor) {
        if (weeks <= 0 || speedFactor <= 0) return 60L;
        long simDurationMs = weeks * 7L * 24L * 60L * 60L * 1000L;
        return (long) Math.ceil((double) simDurationMs / 1000.0 / speedFactor);
    }

    // =========================
    // Helpers
    // =========================

    private void stopAllRunnersAndDeactivate() {
        manualPolicyRunner.stop();
        schedulePolicyRunner.stop();
        weeklyPlanScheduleRunner.stop();
        weeklyPlanManualRunner.stop();

        experimentRepository.findByActiveTrue().ifPresent(active -> {
            active.setActive(false);
            experimentRepository.save(active);
        });
    }

    private ExperimentEntity createExperiment(String name, ExperimentType type, long seed) {
        ExperimentEntity e = new ExperimentEntity();
        e.setName(name);
        e.setType(type);
        e.setSeed(seed);
        e.setActive(false);
        return experimentRepository.save(e);
    }

    private Map<String, Object> buildSummary(long experimentId, RunUntilResult res, Integer stepSimMsOverride, int durationSeconds, Map<String, Double> targetKwhPerDevice) {
        long toSimMs = res.getSummaryEndSimTimeMs();
        Map<String, Object> summary = new HashMap<>();
        Map<String, Object> meta = energySummaryService.computeExperimentMetaBetweenSim(
                experimentId, res.getStartSimTimeMs(), toSimMs, stepSimMsOverride, durationSeconds, targetKwhPerDevice
        );
        meta.put("energyScope", res.isAchieved() ? "UNTIL_TARGET" : "FULL_WINDOW");
        summary.put("meta", meta);
        summary.put("kwhPerDevice", energySummaryService.computeKwhPerDeviceForExperimentBetweenSim(
                experimentId, res.getStartSimTimeMs(), toSimMs, stepSimMsOverride
        ));
        return summary;
    }

    /** 1B isolated: metrics and kwhPerDevice only for planned devices. */
    private Map<String, Object> buildSummaryForWeeklyPlan(long experimentId, RunUntilResult res, Integer stepSimMsOverride, int durationSeconds, Set<String> plannedDeviceIds) {
        long toSimMs = res.getSummaryEndSimTimeMs();
        Map<String, Object> summary = new HashMap<>();
        Map<String, Object> meta = energySummaryService.computeExperimentMetaBetweenSim(
                experimentId, res.getStartSimTimeMs(), toSimMs, stepSimMsOverride, durationSeconds, null, plannedDeviceIds
        );
        meta.put("energyScope", res.isAchieved() ? "UNTIL_TARGET" : "FULL_WINDOW");
        summary.put("meta", meta);
        summary.put("kwhPerDevice", energySummaryService.computeKwhPerDeviceForExperimentBetweenSim(
                experimentId, res.getStartSimTimeMs(), toSimMs, stepSimMsOverride, plannedDeviceIds
        ));
        return summary;
    }

    private static Map<String, Object> diffAbs(Map<String, Object> manualMeta, Map<String, Object> scheduleMeta) {
        double mTotal = toDouble(manualMeta.get("totalKwh"));
        double sTotal = toDouble(scheduleMeta.get("totalKwh"));
        double mAvg = toDouble(manualMeta.get("avgPowerW"));
        double sAvg = toDouble(scheduleMeta.get("avgPowerW"));
        double mPeak = toDouble(manualMeta.get("peakTotalPowerW"));
        double sPeak = toDouble(scheduleMeta.get("peakTotalPowerW"));
        double mRatio = toDouble(manualMeta.get("peakToAvgRatio"));
        double sRatio = toDouble(scheduleMeta.get("peakToAvgRatio"));

        Map<String, Object> diffAbs = new HashMap<>();
        diffAbs.put("totalKwh", round4(sTotal - mTotal));
        diffAbs.put("avgPowerW", round2(sAvg - mAvg));
        diffAbs.put("peakTotalPowerW", round2(sPeak - mPeak));
        diffAbs.put("peakToAvgRatio", round2(sRatio - mRatio));
        return diffAbs;
    }

    private static Map<String, Object> diffPct(Map<String, Object> manualMeta, Map<String, Object> scheduleMeta) {
        double mTotal = toDouble(manualMeta.get("totalKwh"));
        double sTotal = toDouble(scheduleMeta.get("totalKwh"));

        double mAvg = toDouble(manualMeta.get("avgPowerW"));
        double sAvg = toDouble(scheduleMeta.get("avgPowerW"));

        double mPeak = toDouble(manualMeta.get("peakTotalPowerW"));
        double sPeak = toDouble(scheduleMeta.get("peakTotalPowerW"));

        double mRatio = toDouble(manualMeta.get("peakToAvgRatio"));
        double sRatio = toDouble(scheduleMeta.get("peakToAvgRatio"));

        Map<String, Object> diffPct = new HashMap<>();
        diffPct.put("totalKwh", round2(pct(sTotal, mTotal)));
        diffPct.put("avgPowerW", round2(pct(sAvg, mAvg)));
        diffPct.put("peakTotalPowerW", round2(pct(sPeak, mPeak)));
        diffPct.put("peakToAvgRatio", round2(pct(sRatio, mRatio)));
        return diffPct;
    }

    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    private static double pct(double value, double base) {
        if (base == 0.0) return 0.0;
        return (value - base) / base * 100.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}