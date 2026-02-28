package pl.magisterka.backend.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.magisterka.backend.api.dto.WeeklyPlanBatchRequestDto;
import pl.magisterka.backend.api.dto.WeeklyPlanCompareRequestDto;
import pl.magisterka.backend.service.ExperimentService;

import java.util.Map;

@RestController
@RequestMapping("/api/experiments")
public class WeeklyPlanExperimentController {

    private final ExperimentService experimentService;

    public WeeklyPlanExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @PostMapping("/compare-weekly-plan")
    public Map<String, Object> compareWeeklyPlan(@Valid @RequestBody WeeklyPlanCompareRequestDto req) {
        return experimentService.compareWeeklyPlanRun(req);
    }

    @PostMapping("/compare-weekly-plan-isolated")
    public Map<String, Object> compareWeeklyPlanIsolated(@Valid @RequestBody WeeklyPlanCompareRequestDto req) {
        return experimentService.compareWeeklyPlanRunIsolated(req);
    }

    @PostMapping("/compare-weekly-plan-batch")
    public ResponseEntity<?> compareWeeklyPlanBatch(@Valid @RequestBody WeeklyPlanCompareRequestDto req) {
        try {
            return ResponseEntity.ok(experimentService.compareWeeklyPlanRunBatch(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/weekly-plan/batch")
    public Map<String, Object> batchCompareWeeklyPlan(@Valid @RequestBody WeeklyPlanBatchRequestDto req) {
        return experimentService.batchCompareWeeklyPlanRun(req);
    }
}