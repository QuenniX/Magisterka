package pl.magisterka.backend.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import pl.magisterka.backend.api.dto.ScenarioSummaryDto;
import pl.magisterka.backend.api.dto.WeeklyPlanRuleDto;
import pl.magisterka.backend.db.WeeklyPlanRuleEntity;
import pl.magisterka.backend.db.WeeklyPlanRuleRepository;
import pl.magisterka.backend.service.WeeklyPlanCompilerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/weekly-plan")
public class WeeklyPlanController {

    private static final Logger log = LoggerFactory.getLogger(WeeklyPlanController.class);
    private static final Pattern SCENARIO_ID_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,32}");

    private final WeeklyPlanRuleRepository repo;
    private final WeeklyPlanCompilerService compiler;

    public WeeklyPlanController(WeeklyPlanRuleRepository repo, WeeklyPlanCompilerService compiler) {
        this.repo = repo;
        this.compiler = compiler;
    }

    @GetMapping("/scenarios")
    public List<ScenarioSummaryDto> listScenarios() {
        return repo.findScenarioSummaries().stream()
                .map(row -> new ScenarioSummaryDto(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        (Instant) row[2]
                ))
                .collect(Collectors.toList());
    }

    @DeleteMapping("/scenarios/{scenarioId}")
    @Transactional
    public ResponseEntity<Map<String, String>> deleteScenario(@PathVariable String scenarioId) {
        String trimmed = scenarioId != null ? scenarioId.trim() : "";
        validateScenarioId(trimmed);
        if ("default".equalsIgnoreCase(trimmed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie można usunąć scenariusza default");
        }
        repo.deleteByScenarioId(trimmed);
        return ResponseEntity.ok(Map.of("message", "Usunięto scenariusz " + trimmed));
    }

    /** DELETE by path /{scenarioId}. Protects default. */
    @DeleteMapping("/{scenarioId}")
    @Transactional
    public ResponseEntity<Map<String, String>> deleteScenarioById(@PathVariable String scenarioId) {
        String trimmed = scenarioId != null ? scenarioId.trim() : "";
        validateScenarioId(trimmed);
        if ("default".equalsIgnoreCase(trimmed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie można usunąć scenariusza default");
        }
        repo.deleteByScenarioId(trimmed);
        return ResponseEntity.ok(Map.of("message", "Usunięto scenariusz " + trimmed));
    }

    @GetMapping("/{scenarioId}")
    public List<WeeklyPlanRuleDto> get(@PathVariable String scenarioId) {
        log.info("GET weekly-plan scenarioId={}", scenarioId);
        try {
            return repo.findByScenarioId(scenarioId).stream()
                    .filter(r -> r.getKind() != null && r.getDeviceType() != null)
                    .map(this::toDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("GET weekly-plan failed for scenario={}", scenarioId, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie można załadować reguł scenariusza");
        }
    }

    /** Saves rules only (no compile). Replaces all rules for this scenario. URL is source of truth for scenarioId; id in body is ignored. Never returns 500. */
    @PutMapping("/{scenarioId}")
    @Transactional
    public List<WeeklyPlanRuleDto> put(@PathVariable String scenarioId, @RequestBody List<WeeklyPlanRuleDto> rules) {
        log.info("PUT weekly-plan scenarioId={}, rulesCount={}", scenarioId, rules != null ? rules.size() : null);
        try {
            validateScenarioId(scenarioId);
            if (rules == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rules required");
            }
            for (int i = 0; i < rules.size(); i++) {
                WeeklyPlanRuleDto r = rules.get(i);
                log.info("PUT rule[{}] deviceId={} deviceType={} kind={} daysOfWeek='{}' from={} to={} timezone={} enabled={}",
                        i, r.deviceId, r.deviceType, r.kind, r.daysOfWeek, r.from, r.to, r.timezone, r.enabled);
            }
            String err = validateRules(rules);
            if (err != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err);
            }
            repo.deleteByScenarioId(scenarioId);
            List<WeeklyPlanRuleEntity> entities = rules.stream()
                    .map(this::fromDto)
                    .peek(e -> e.setScenarioId(scenarioId))
                    .collect(Collectors.toList());
            repo.saveAll(entities);
            repo.flush();
            return repo.findByScenarioId(scenarioId).stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
        } catch (DataIntegrityViolationException e) {
            log.error("Constraint error saving scenario {}", scenarioId, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Błąd zapisu (constraint)");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error saving scenario {}", scenarioId, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Błąd zapisu scenariusza");
        }
    }

    private void validateScenarioId(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scenarioId required");
        }
        if (!SCENARIO_ID_PATTERN.matcher(scenarioId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid scenario id (use letters, digits, underscore, hyphen; max 32)");
        }
    }

    private WeeklyPlanRuleEntity fromDto(WeeklyPlanRuleDto dto) {
        WeeklyPlanRuleEntity e = new WeeklyPlanRuleEntity();
        e.setDeviceId(dto.deviceId);
        e.setDeviceType(dto.deviceType);
        e.setKind(dto.kind);
        e.setDaysOfWeek(dto.daysOfWeek != null ? dto.daysOfWeek.trim() : "");
        e.setFromTime(dto.from);
        e.setToTime(dto.to);
        e.setTimezone(dto.timezone != null ? dto.timezone : "Europe/Warsaw");
        e.setEnabled(dto.enabled);
        return e;
    }

    private static String validateRules(List<WeeklyPlanRuleDto> rules) {
        if (rules == null) return "rules required";
        for (int i = 0; i < rules.size(); i++) {
            WeeklyPlanRuleDto r = rules.get(i);
            if (r.deviceId == null || r.deviceId.isBlank()) {
                return "Rule " + (i + 1) + ": deviceId required";
            }
            if (r.deviceType == null || r.deviceType.isBlank()) {
                return "Rule " + (i + 1) + ": deviceType required";
            }
            if (r.kind == null) {
                return "Rule " + (i + 1) + ": kind required (WINDOW or EVENT)";
            }
            if (r.daysOfWeek == null || r.daysOfWeek.isBlank()) {
                return "Rule " + (i + 1) + ": daysOfWeek required";
            }
            String daysErr = validateDaysOfWeek(r.daysOfWeek);
            if (daysErr != null) {
                return "Rule " + (i + 1) + ": " + daysErr;
            }
            if (r.from == null || r.from.isBlank()) {
                return "Rule " + (i + 1) + ": from time required";
            }
            if (!isValidHm(r.from)) return "Rule " + (i + 1) + ": invalid from time (use HH:mm)";
            String to = r.to != null ? r.to : "00:00";
            if (!isValidHm(to)) return "Rule " + (i + 1) + ": invalid to time (use HH:mm)";
            if (r.kind == pl.magisterka.backend.db.WeeklyPlanRuleEntity.Kind.WINDOW && r.from.equals(to)) {
                return "Rule " + (i + 1) + ": from and to cannot be equal for WINDOW";
            }
        }
        return null;
    }

    private static final java.util.Set<String> VALID_DAYS = java.util.Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private static String validateDaysOfWeek(String daysOfWeek) {
        if (daysOfWeek == null || daysOfWeek.isBlank()) return "daysOfWeek required";
        for (String token : daysOfWeek.split(",")) {
            String d = token != null ? token.trim() : "";
            if (d.isEmpty()) return "daysOfWeek: empty token (use MON,TUE,...,SUN)";
            if (!VALID_DAYS.contains(d.toUpperCase())) {
                return "daysOfWeek: invalid day '" + d + "' (allowed: MON, TUE, WED, THU, FRI, SAT, SUN)";
            }
        }
        return null;
    }

    private static boolean isValidHm(String hm) {
        if (hm == null || hm.isBlank()) return false;
        String[] parts = hm.trim().split(":");
        if (parts.length < 1) return false;
        try {
            int h = Integer.parseInt(parts[0]);
            int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return h >= 0 && h <= 23 && m >= 0 && m <= 59;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @PostMapping("/{scenarioId}/compile")
    public ResponseEntity<Void> compile(@PathVariable String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scenarioId required");
        }
        try {
            compiler.compileScenario(scenarioId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            String msg = "Compile failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }
    }

    /**
     * Clone rules from source to new scenario (API). newId: [a-zA-Z0-9_-]{1,32}. 409 if newId exists. 400 if source has no rules.
     * UI may use GET+PUT instead (cloneScenarioViaPut) to send exact server state.
     */
    @PostMapping("/{newScenarioId}/clone-from/{sourceScenarioId}")
    public List<WeeklyPlanRuleDto> cloneScenario(
            @PathVariable String newScenarioId,
            @PathVariable String sourceScenarioId
    ) {
        if (!SCENARIO_ID_PATTERN.matcher(newScenarioId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid scenario id (use letters, digits, underscore, hyphen; max 32)");
        }
        if (!repo.findByScenarioId(newScenarioId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Scenario already exists: " + newScenarioId);
        }
        List<WeeklyPlanRuleEntity> source = repo.findByScenarioId(sourceScenarioId);
        if (source.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source scenario has no rules");
        }
        List<WeeklyPlanRuleEntity> saved = new java.util.ArrayList<>();
        for (WeeklyPlanRuleEntity e : source) {
            WeeklyPlanRuleEntity copy = new WeeklyPlanRuleEntity();
            copy.setScenarioId(newScenarioId);
            copy.setDeviceId(e.getDeviceId());
            copy.setDeviceType(e.getDeviceType());
            copy.setKind(e.getKind());
            copy.setDaysOfWeek(e.getDaysOfWeek());
            copy.setFromTime(e.getFromTime());
            copy.setToTime(e.getToTime());
            copy.setTimezone(e.getTimezone() != null ? e.getTimezone() : "Europe/Warsaw");
            copy.setEnabled(e.isEnabled());
            saved.add(repo.save(copy));
        }
        compiler.compileScenario(newScenarioId);
        return saved.stream().map(this::toDto).collect(Collectors.toList());
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

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatusException(ResponseStatusException ex) {
        String msg = ex.getReason();
        if (msg == null || msg.isBlank()) {
            msg = ex.getMessage() != null ? ex.getMessage() : "Błąd zapisu scenariusza";
        }
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status).body(Map.of("message", msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.error("Invalid JSON / Content-Type in weekly-plan request", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", "Niepoprawny JSON / Content-Type"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.error("Constraint violation in weekly-plan request", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", "Błąd zapisu (constraint)"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        log.error("Unexpected error in WeeklyPlanController", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", "Błąd zapisu scenariusza"));
    }
}