package pl.magisterka.backend.api;

import org.springframework.web.bind.annotation.*;
import pl.magisterka.backend.service.EnergySummaryService;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentAnalyticsController {

    private final EnergySummaryService energySummaryService;

    public ExperimentAnalyticsController(EnergySummaryService energySummaryService) {
        this.energySummaryService = energySummaryService;
    }

    @GetMapping("/{id}/energy-summary")
    public Map<String, Object> energySummary(@PathVariable("id") long experimentId) {

        Map<String, Object> meta = energySummaryService.computeExperimentMeta(experimentId);
        Map<String, Double> kwhPerDevice = energySummaryService.computeKwhPerDeviceForExperiment(experimentId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", meta);
        out.put("kwhPerDevice", kwhPerDevice);

        // opcjonalnie total kWh (suma po urządzeniach)
        double total = kwhPerDevice.values().stream().mapToDouble(Double::doubleValue).sum();
        out.put("totalKwh", Math.round(total * 10000.0) / 10000.0);

        return out;
    }
}