package pl.magisterka.backend.api;

import org.springframework.web.bind.annotation.*;
import pl.magisterka.backend.api.dto.WeeklyPlanRuleDto;
import pl.magisterka.backend.db.WeeklyPlanRuleEntity;
import pl.magisterka.backend.db.WeeklyPlanRuleRepository;
import pl.magisterka.backend.service.WeeklyPlanCompilerService;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/weekly-plan")
public class WeeklyPlanController {

    private final WeeklyPlanRuleRepository repo;
    private final WeeklyPlanCompilerService compiler;

    public WeeklyPlanController(WeeklyPlanRuleRepository repo, WeeklyPlanCompilerService compiler) {
        this.repo = repo;
        this.compiler = compiler;
    }

    @GetMapping("/{scenarioId}")
    public List<WeeklyPlanRuleDto> get(@PathVariable String scenarioId) {
        return repo.findByScenarioId(scenarioId).stream().map(this::toDto).collect(Collectors.toList());
    }

    // zapisuje cały plan (replace) i od razu kompiluje do cronów
    @PutMapping("/{scenarioId}")
    public List<WeeklyPlanRuleDto> put(@PathVariable String scenarioId, @RequestBody List<WeeklyPlanRuleDto> rules) {
        repo.deleteByScenarioId(scenarioId);

        List<WeeklyPlanRuleEntity> saved = rules.stream().map(dto -> {
            WeeklyPlanRuleEntity e = new WeeklyPlanRuleEntity();
            e.setScenarioId(scenarioId);
            e.setDeviceId(dto.deviceId);
            e.setDeviceType(dto.deviceType);
            e.setKind(dto.kind);
            e.setDaysOfWeek(dto.daysOfWeek);
            e.setFromTime(dto.from);
            e.setToTime(dto.to);
            e.setTimezone(dto.timezone != null ? dto.timezone : "Europe/Warsaw");
            e.setEnabled(dto.enabled);
            return e;
        }).map(repo::save).collect(Collectors.toList());

        compiler.compileScenario(scenarioId);

        return saved.stream().map(this::toDto).collect(Collectors.toList());
    }

    @PostMapping("/{scenarioId}/compile")
    public void compile(@PathVariable String scenarioId) {
        compiler.compileScenario(scenarioId);
    }

    private WeeklyPlanRuleDto toDto(WeeklyPlanRuleEntity e) {
        WeeklyPlanRuleDto dto = new WeeklyPlanRuleDto();
        dto.id = e.getId();
        dto.scenarioId = e.getScenarioId();
        dto.deviceId = e.getDeviceId();
        dto.deviceType = e.getDeviceType();
        dto.kind = e.getKind();
        dto.daysOfWeek = e.getDaysOfWeek();
        dto.from = e.getFromTime();
        dto.to = e.getToTime();
        dto.timezone = e.getTimezone();
        dto.enabled = e.isEnabled();
        return dto;
    }
}