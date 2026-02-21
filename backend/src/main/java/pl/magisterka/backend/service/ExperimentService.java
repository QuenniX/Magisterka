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

        // start manual policy tylko dla MANUAL
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
        manualPolicyRunner.setWorkload(workload);
        schedulePolicyRunner.setWorkload(workload);

        try {
            // 3. RUN MANUAL
            start(manual.getId());
            Thread.sleep(workload.durationSeconds * 1000L);
            stopActive();

            // 4. RUN SCHEDULE
            start(schedule.getId());
            Thread.sleep(workload.durationSeconds * 1000L);
            stopActive();

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // 5. Get summaries
        Map<String, Object> manualSummary = new HashMap<>();
        manualSummary.put("meta", energySummaryService.computeExperimentMeta(manual.getId()));
        manualSummary.put("kwhPerDevice", energySummaryService.computeKwhPerDeviceForExperiment(manual.getId()));

        Map<String, Object> scheduleSummary = new HashMap<>();
        scheduleSummary.put("meta", energySummaryService.computeExperimentMeta(schedule.getId()));
        scheduleSummary.put("kwhPerDevice", energySummaryService.computeKwhPerDeviceForExperiment(schedule.getId()));

        Map<String, Object> out = new HashMap<>();
        out.put("manual", manualSummary);
        out.put("schedule", scheduleSummary);

        return out;
    }
}