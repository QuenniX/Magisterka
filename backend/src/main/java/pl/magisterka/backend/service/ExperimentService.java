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

    public ExperimentService(
            ExperimentRepository experimentRepository,
            ManualPolicyRunner manualPolicyRunner,
            SchedulePolicyRunner schedulePolicyRunner,
            EnergySummaryService energySummaryService
    ) {
        this.experimentRepository = experimentRepository;
        this.manualPolicyRunner = manualPolicyRunner;
        this.schedulePolicyRunner = schedulePolicyRunner;
        this.energySummaryService = energySummaryService;
    }

    public Optional<ExperimentEntity> getActiveExperiment() {
        return experimentRepository.findByActiveTrue();
    }

    @Transactional
    public ExperimentEntity start(long experimentId) {
        // stop runner na wszelki wypadek (żeby nie zostały stare wątki)
        manualPolicyRunner.stop();
        schedulePolicyRunner.stop();

        // wyłącz wszystkie aktywne (żeby zawsze był max 1)
        experimentRepository.findByActiveTrue().ifPresent(active -> {
            active.setActive(false);
            experimentRepository.save(active);
        });

        ExperimentEntity e = experimentRepository.findById(experimentId)
                .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + experimentId));

        e.setActive(true);
        ExperimentEntity saved = experimentRepository.save(e);

        // start policy wg typu
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
        // zatrzymaj runner zawsze
        manualPolicyRunner.stop();
        schedulePolicyRunner.stop();

        experimentRepository.findByActiveTrue().ifPresent(active -> {
            active.setActive(false);
            experimentRepository.save(active);
        });
    }

    public Map<String, Object> compareRun(WorkloadDto workload) {

        // 1. Create MANUAL experiment
        ExperimentEntity manual = new ExperimentEntity();
        manual.setName("Compare-MANUAL");
        manual.setType(ExperimentType.MANUAL);
        manual.setSeed(workload.seed);
        manual.setActive(false);
        manual = experimentRepository.save(manual);

        // 2. Create SCHEDULE experiment
        ExperimentEntity schedule = new ExperimentEntity();
        schedule.setName("Compare-SCHEDULE");
        schedule.setType(ExperimentType.SCHEDULE);
        schedule.setSeed(workload.seed);
        schedule.setActive(false);
        schedule = experimentRepository.save(schedule);

        // (opcjonalne) jeśli runner kiedyś będzie używał workload
        manualPolicyRunner.setWorkload(workload);
        schedulePolicyRunner.setWorkload(workload);

        boolean manualAchieved;
        boolean scheduleAchieved;

        try {
            // RUN MANUAL
            start(manual.getId());
            manualAchieved = runUntilTargetsOrTimeout(manual.getId(), workload);
            stopActive();

            // RUN SCHEDULE
            start(schedule.getId());
            scheduleAchieved = runUntilTargetsOrTimeout(schedule.getId(), workload);
            stopActive();

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // 3. Get summaries
        Map<String, Object> manualSummary = new HashMap<>();
        manualSummary.put("meta", energySummaryService.computeExperimentMeta(manual.getId()));
        manualSummary.put("kwhPerDevice", energySummaryService.computeKwhPerDeviceForExperiment(manual.getId()));

        Map<String, Object> scheduleSummary = new HashMap<>();
        scheduleSummary.put("meta", energySummaryService.computeExperimentMeta(schedule.getId()));
        scheduleSummary.put("kwhPerDevice", energySummaryService.computeKwhPerDeviceForExperiment(schedule.getId()));

        Map<String, Object> out = new HashMap<>();
        out.put("manual", manualSummary);
        out.put("schedule", scheduleSummary);
        out.put("manualAchieved", manualAchieved);
        out.put("scheduleAchieved", scheduleAchieved);

        // 4. Diffs (schedule - manual)
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

    private boolean runUntilTargetsOrTimeout(long experimentId, WorkloadDto workload) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (long) workload.durationSeconds * 1000L;

        while (System.currentTimeMillis() < deadline) {

            Map<String, Double> kwh = energySummaryService.computeKwhPerDeviceForExperiment(experimentId);

            boolean allOk = true;
            if (workload.devices != null && !workload.devices.isEmpty()) {
                for (WorkloadDto.DeviceRequirementDto d : workload.devices) {
                    double current = kwh.getOrDefault(d.deviceId, 0.0);
                    if (current + 1e-9 < d.targetKwh) {
                        allOk = false;
                        break;
                    }
                }
            }

            if (allOk) {
                return true; // workload spełniony
            }

            Thread.sleep(2000L); // co 2s sprawdzamy progres
        }

        return false; // timeout
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