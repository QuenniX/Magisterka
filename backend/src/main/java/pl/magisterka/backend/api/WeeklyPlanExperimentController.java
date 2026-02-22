package pl.magisterka.backend.api;

import org.springframework.web.bind.annotation.*;
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
    public Map<String, Object> compareWeeklyPlan(@RequestBody WeeklyPlanCompareRequestDto req) {
        return experimentService.compareWeeklyPlanRun(req);
    }
}