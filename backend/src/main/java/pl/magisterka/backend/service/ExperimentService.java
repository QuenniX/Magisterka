package pl.magisterka.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.magisterka.backend.db.ExperimentEntity;
import pl.magisterka.backend.db.ExperimentRepository;
import pl.magisterka.backend.model.ExperimentType;

import java.util.Optional;

@Service
public class ExperimentService {

    private final ExperimentRepository experimentRepository;
    private final ManualPolicyRunner manualPolicyRunner;

    public ExperimentService(ExperimentRepository experimentRepository, ManualPolicyRunner manualPolicyRunner) {
        this.experimentRepository = experimentRepository;
        this.manualPolicyRunner = manualPolicyRunner;
    }

    public Optional<ExperimentEntity> getActiveExperiment() {
        return experimentRepository.findByActiveTrue();
    }

    @Transactional
    public ExperimentEntity start(long experimentId) {
        // stop runner na wszelki wypadek (żeby nie zostały stare wątki)
        manualPolicyRunner.stop();

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

        return saved;
    }

    @Transactional
    public void stopActive() {
        // zatrzymaj runner zawsze
        manualPolicyRunner.stop();

        experimentRepository.findByActiveTrue().ifPresent(active -> {
            active.setActive(false);
            experimentRepository.save(active);
        });
    }
}