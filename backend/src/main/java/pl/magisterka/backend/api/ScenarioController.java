package pl.magisterka.backend.api;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pl.magisterka.backend.db.DeviceScheduleRepository;
import pl.magisterka.backend.db.ScenarioEntity;
import pl.magisterka.backend.db.ScenarioRepository;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioRepository scenarioRepo;
    private final DeviceScheduleRepository scheduleRepo;

    public ScenarioController(ScenarioRepository scenarioRepo, DeviceScheduleRepository scheduleRepo) {
        this.scenarioRepo = scenarioRepo;
        this.scheduleRepo = scheduleRepo;
    }

    public static class CreateScenarioRequest {
        public String name;
    }

    @PostMapping
    public ScenarioEntity create(@RequestBody CreateScenarioRequest req) {
        if (req == null || req.name == null || req.name.isBlank()) {
            throw new IllegalArgumentException("name required");
        }

        ScenarioEntity s = new ScenarioEntity();
        s.setId(UUID.randomUUID().toString());
        s.setName(req.name.trim());
        s.setEnabled(false);
        return scenarioRepo.save(s);
    }

    @GetMapping
    public List<ScenarioEntity> list() {
        return scenarioRepo.findAll();
    }

    @PatchMapping("/{scenarioId}/enabled")
    @Transactional
    public void setEnabled(@PathVariable String scenarioId, @RequestParam boolean value) {
        ScenarioEntity scenario = scenarioRepo.findById(scenarioId)
                .orElseThrow(() -> new RuntimeException("Scenario not found"));

        scenario.setEnabled(value);
        scenarioRepo.save(scenario);

        var schedules = scheduleRepo.findByScenarioId(scenarioId);
        for (var sch : schedules) {
            sch.setEnabled(value);
        }
        scheduleRepo.saveAll(schedules);
    }
}
