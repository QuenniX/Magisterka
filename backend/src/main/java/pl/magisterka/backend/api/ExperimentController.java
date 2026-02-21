package pl.magisterka.backend.api;

import org.springframework.web.bind.annotation.*;
import pl.magisterka.backend.db.ExperimentEntity;
import pl.magisterka.backend.db.ExperimentRepository;
import pl.magisterka.backend.model.ExperimentType;
import pl.magisterka.backend.service.ExperimentService;

import java.util.List;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentRepository experimentRepository;
    private final ExperimentService experimentService;

    public ExperimentController(ExperimentRepository experimentRepository, ExperimentService experimentService) {
        this.experimentRepository = experimentRepository;
        this.experimentService = experimentService;
    }

    @GetMapping
    public List<ExperimentEntity> list() {
        return experimentRepository.findAll();
    }

    @PostMapping
    public ExperimentEntity create(
            @RequestParam String name,
            @RequestParam ExperimentType type,
            @RequestParam(required = false) Long seed
    ) {
        ExperimentEntity e = new ExperimentEntity();
        e.setName(name);
        e.setType(type);
        e.setActive(false);
        e.setSeed(seed != null ? seed : 1L);
        return experimentRepository.save(e);
    }

    @PostMapping("/{id}/start")
    public ExperimentEntity start(@PathVariable long id) {
        return experimentService.start(id);
    }

    @PostMapping("/stop")
    public void stop() {
        experimentService.stopActive();
    }

    /**
     * Minimalny helper do badań: uruchom eksperyment na X sekund realnego czasu.
     * Przykład:
     * POST /api/experiments/2/run?durationSeconds=60
     */
    @PostMapping("/{id}/run")
    public ExperimentEntity run(
            @PathVariable long id,
            @RequestParam(defaultValue = "60") long durationSeconds
    ) throws InterruptedException {

        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be > 0");
        }

        ExperimentEntity started = experimentService.start(id);

        Thread.sleep(durationSeconds * 1000L);

        experimentService.stopActive();
        return started;
    }
}