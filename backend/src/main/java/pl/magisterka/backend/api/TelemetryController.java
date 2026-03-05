package pl.magisterka.backend.api;

import org.springframework.web.bind.annotation.*;
import pl.magisterka.backend.api.dto.LatestTelemetryDto;
import pl.magisterka.backend.db.EnergyTelemetryEntity;
import pl.magisterka.backend.db.EnergyTelemetryRepository;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final EnergyTelemetryRepository repo;

    public TelemetryController(EnergyTelemetryRepository repo) {
        this.repo = repo;
    }

    // 1) Latest telemetry dla wszystkich urządzeń
    // GET /api/telemetry/latest
    @GetMapping("/latest")
    public List<LatestTelemetryDto> latestAll() {
        return repo.findLatestTelemetryPerDevice()
                .stream()
                .map(this::toDto)
                .toList();
    }

    // 2) Latest telemetry dla konkretnego deviceId
    // GET /api/telemetry/latest?deviceId=plug-01
    @GetMapping(value = "/latest", params = "deviceId")
    public LatestTelemetryDto latestOne(@RequestParam String deviceId) {
        // Prefer latest MQTT by ingestion time (receivedAt) with NULLS LAST; fall back to legacy ts-based lookup.
        EnergyTelemetryEntity e = repo.findLatestMqttByDevice(deviceId)
                .or(() -> repo.findTopByDeviceIdOrderByTsDesc(deviceId))
                .orElseThrow(() -> new IllegalArgumentException("No telemetry for deviceId=" + deviceId));
        return toDto(e);
    }

    private LatestTelemetryDto toDto(EnergyTelemetryEntity e) {
        return new LatestTelemetryDto(
                e.getDeviceId(),
                e.getDeviceType(),
                e.getPowerW(),
                e.getVoltageV(),
                e.getState(),
                e.getMode(),
                e.getTs(),
                e.getSimTimeMs()
        );
    }

    @GetMapping("/range")
    public List<LatestTelemetryDto> range(
            @RequestParam String deviceId,
            @RequestParam Instant from,
            @RequestParam Instant to
    ) {
        return repo.findByDeviceIdAndTsBetweenOrderByTsAsc(deviceId, from, to)
                .stream()
                .map(this::toDto)
                .toList();
    }
}
