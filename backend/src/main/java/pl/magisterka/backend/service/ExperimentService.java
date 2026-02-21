package pl.magisterka.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.magisterka.backend.api.dto.WorkloadDto;
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

    public ExperimentService(
            ExperimentRepository experimentRepository,
            ManualPolicyRunner manualPolicyRunner,
            SchedulePolicyRunner schedulePolicyRunner,
            EnergySummaryService energySummaryService,
            SimTimeService simTimeService
    ) {
        this.experimentRepository = experimentRepository;
        this.manualPolicyRunner = manualPolicyRunner;
        this.schedulePolicyRunner = schedulePolicyRunner;
        this.energySummaryService = energySummaryService;
        this.simTimeService = simTimeService;
    }

    public Optional<ExperimentEntity> getActiveExperiment() {
        return experimentRepository.findByActiveTrue();
    }

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

        if (saved.getType() == ExperimentType.MANUAL) {
            manualPolicyRunner.start(saved);
        }
        if (saved.getType() == ExperimentType.SCHEDULE) {
            schedulePolicyRunner.start(saved);
        }

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

        // 1) Create MANUAL experiment
        ExperimentEntity manual = new ExperimentEntity();
        manual.setName("Compare-MANUAL");
        manual.setType(ExperimentType.MANUAL);
        manual.setSeed(workload.seed);
        manual.setActive(false);
        manual = experimentRepository.save(manual);

        // 2) Create SCHEDULE experiment
        ExperimentEntity schedule = new ExperimentEntity();
        schedule.setName("Compare-SCHEDULE");
        schedule.setType(ExperimentType.SCHEDULE);
        schedule.setSeed(workload.seed);
        schedule.setActive(false);
        schedule = experimentRepository.save(schedule);

        manualPolicyRunner.setWorkload(workload);
        schedulePolicyRunner.setWorkload(workload);

        RunUntilResult manualRes;
        RunUntilResult scheduleRes;

        try {
            // RUN MANUAL
            start(manual.getId());
            manualRes = runUntilTargetsOrTimeout(manual.getId(), workload);
            stopActive();

            // RUN SCHEDULE
            start(schedule.getId());
            scheduleRes = runUntilTargetsOrTimeout(schedule.getId(), workload);
            stopActive();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        // 3) summaries
        Map<String, Object> manualSummary = new HashMap<>();
        manualSummary.put("meta", energySummaryService.computeExperimentMetaBetweenSim(
                manual.getId(), manualRes.getStartSimTimeMs(), manualRes.getEndSimTimeMs()
        ));
        manualSummary.put("kwhPerDevice", energySummaryService.computeKwhPerDeviceForExperimentBetweenSim(
                manual.getId(), manualRes.getStartSimTimeMs(), manualRes.getEndSimTimeMs()
        ));

        Map<String, Object> scheduleSummary = new HashMap<>();
        scheduleSummary.put("meta", energySummaryService.computeExperimentMetaBetweenSim(
                schedule.getId(), scheduleRes.getStartSimTimeMs(), scheduleRes.getEndSimTimeMs()
        ));
        scheduleSummary.put("kwhPerDevice", energySummaryService.computeKwhPerDeviceForExperimentBetweenSim(
                schedule.getId(), scheduleRes.getStartSimTimeMs(), scheduleRes.getEndSimTimeMs()
        ));

        Map<String, Object> out = new HashMap<>();
        out.put("manual", manualSummary);
        out.put("schedule", scheduleSummary);

        out.put("manualAchieved", manualRes.isAchieved());
        out.put("scheduleAchieved", scheduleRes.isAchieved());

        // ✅ nowe pola: "time to achieve" w SIM TIME
        out.put("manualTimeToAchieveSimMs", manualRes.getEndSimTimeMs());
        out.put("scheduleTimeToAchieveSimMs", scheduleRes.getEndSimTimeMs());
        out.put("manualAchievedSimDurationMs", manualRes.getSimDurationMs());
        out.put("scheduleAchievedSimDurationMs", scheduleRes.getSimDurationMs());

        // 4) diffs (schedule - manual)
        Map<String, Object> manualMeta = (Map<String, Object>) manualSummary.get("meta");
        Map<String, Object> scheduleMeta = (Map<String, Object>) scheduleSummary.get("meta");

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

        Map<String, Object> diffPct = new HashMap<>();
        diffPct.put("totalKwh", round2(pct(sTotal, mTotal)));
        diffPct.put("avgPowerW", round2(pct(sAvg, mAvg)));
        diffPct.put("peakTotalPowerW", round2(pct(sPeak, mPeak)));
        diffPct.put("peakToAvgRatio", round2(pct(sRatio, mRatio)));

        out.put("diffAbs", diffAbs);
        out.put("diffPct", diffPct);

        return out;
    }

    /**
     * Czeka aż workload osiągnie targety (kWh per device) albo minie timeout real-time.
     * Zwraca też czasy w sim_time_ms.
     */
    private RunUntilResult runUntilTargetsOrTimeout(long experimentId, WorkloadDto workload) throws InterruptedException {
        long startReal = System.currentTimeMillis();

        // ✅ u Ciebie jest POLE durationSeconds (z requesta)
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

            // ✅ szybciej
            Thread.sleep(200);
        }

        long endSim = simTimeService.getCurrentSimTimeMs(experimentId);
        return new RunUntilResult(false, startSim, endSim);
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