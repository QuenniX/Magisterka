package pl.magisterka.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.magisterka.backend.api.dto.WorkloadDto;
import pl.magisterka.backend.api.dto.WeeklyPlanCompareRequestDto;
import pl.magisterka.backend.db.ExperimentEntity;
import pl.magisterka.backend.db.ExperimentRepository;
import pl.magisterka.backend.model.ExperimentType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class ExperimentService {

    private final ExperimentRepository experimentRepository;
    private final ManualPolicyRunner manualPolicyRunner;
    private final SchedulePolicyRunner schedulePolicyRunner;
    private final EnergySummaryService energySummaryService;
    private final SimTimeService simTimeService;
    private final WeeklyPlanManualRunner weeklyPlanManualRunner;
    private final WeeklyPlanScheduleRunner weeklyPlanScheduleRunner;

    public ExperimentService(
            ExperimentRepository experimentRepository,
            ManualPolicyRunner manualPolicyRunner,
            SchedulePolicyRunner schedulePolicyRunner,
            EnergySummaryService energySummaryService,
            SimTimeService simTimeService,
            WeeklyPlanManualRunner weeklyPlanManualRunner,
            WeeklyPlanScheduleRunner weeklyPlanScheduleRunner
    ) {
        this.experimentRepository = experimentRepository;
        this.manualPolicyRunner = manualPolicyRunner;
        this.schedulePolicyRunner = schedulePolicyRunner;
        this.energySummaryService = energySummaryService;
        this.simTimeService = simTimeService;
        this.weeklyPlanManualRunner = weeklyPlanManualRunner;
        this.weeklyPlanScheduleRunner = weeklyPlanScheduleRunner;
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

        Map<String, Object> manualSummary = buildSummary(manual.getId(), manualRes);
        Map<String, Object> scheduleSummary = buildSummary(schedule.getId(), scheduleRes);

        Map<String, Object> out = new HashMap<>();
        out.put("manual", manualSummary);
        out.put("schedule", scheduleSummary);

        out.put("manualAchieved", manualRes.isAchieved());
        out.put("scheduleAchieved", scheduleRes.isAchieved());

        out.put("manualTimeToAchieveSimMs", manualRes.getEndSimTimeMs());
        out.put("scheduleTimeToAchieveSimMs", scheduleRes.getEndSimTimeMs());
        out.put("manualAchievedSimDurationMs", manualRes.getSimDurationMs());
        out.put("scheduleAchievedSimDurationMs", scheduleRes.getSimDurationMs());

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
        return out;
    }

    private RunUntilResult runUntilTargetsOrTimeout(long experimentId, WorkloadDto workload) throws InterruptedException {
        long startReal = System.currentTimeMillis();
        long deadlineReal = startReal + (long) workload.durationSeconds * 1000L;

        long startSim = simTimeService.getCurrentSimTimeMs(experimentId);

        while (System.currentTimeMillis() < deadlineReal) {

            Map<String, Double> kwhPerDevice =
                    energySummaryService.computeKwhPerDeviceForExperiment(experimentId);

            boolean allMet = true;
            for (var req : workload.devices) {
                double current = kwhPerDevice.getOrDefault(req.deviceId, 0.0);
                if (current < req.targetKwh) {
                    allMet = false;
                    break;
                }
            }

            if (allMet) {
                long endSim = simTimeService.getCurrentSimTimeMs(experimentId);
                return new RunUntilResult(true, startSim, endSim);
            }

            Thread.sleep(200);
        }

        long endSim = simTimeService.getCurrentSimTimeMs(experimentId);
        return new RunUntilResult(false, startSim, endSim);
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

        ExperimentEntity manual = createExperiment("CompareWeeklyPlan-MANUAL", ExperimentType.MANUAL, req.seed);
        ExperimentEntity schedule = createExperiment("CompareWeeklyPlan-SCHEDULE", ExperimentType.SCHEDULE, req.seed);

        RunUntilResult manualRes;
        RunUntilResult scheduleRes;

        try {
            stopAllRunnersAndDeactivate();

            manual.setActive(true);
            experimentRepository.save(manual);
            weeklyPlanManualRunner.start(manual, req.scenarioId);
            manualRes = runForSimDurationOrTimeout(manual.getId(), simDurationMs, req.durationSeconds);
            weeklyPlanManualRunner.stop();
            manual.setActive(false);
            experimentRepository.save(manual);

            schedule.setActive(true);
            experimentRepository.save(schedule);
            weeklyPlanScheduleRunner.start(schedule, req.scenarioId);
            scheduleRes = runForSimDurationOrTimeout(schedule.getId(), simDurationMs, req.durationSeconds);
            weeklyPlanScheduleRunner.stop();
            schedule.setActive(false);
            experimentRepository.save(schedule);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        Map<String, Object> manualSummary = buildSummary(manual.getId(), manualRes);
        Map<String, Object> scheduleSummary = buildSummary(schedule.getId(), scheduleRes);

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
        return out;
    }

    private RunUntilResult runForSimDurationOrTimeout(long experimentId, long simDurationMs, long durationSeconds) throws InterruptedException {
        long startReal = System.currentTimeMillis();
        long deadlineReal = startReal + Math.max(10, durationSeconds) * 1000L;

        long startSim = simTimeService.getCurrentSimTimeMs(experimentId);

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

    private Map<String, Object> buildSummary(long experimentId, RunUntilResult res) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("meta", energySummaryService.computeExperimentMetaBetweenSim(
                experimentId, res.getStartSimTimeMs(), res.getEndSimTimeMs()
        ));
        summary.put("kwhPerDevice", energySummaryService.computeKwhPerDeviceForExperimentBetweenSim(
                experimentId, res.getStartSimTimeMs(), res.getEndSimTimeMs()
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