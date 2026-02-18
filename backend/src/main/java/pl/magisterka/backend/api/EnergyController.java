package pl.magisterka.backend.api;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import pl.magisterka.backend.api.dto.EnergyDailyDto;
import pl.magisterka.backend.api.dto.EnergySummaryDto;
import pl.magisterka.backend.service.EnergySummaryService;

import java.time.Instant;

@RestController
@RequestMapping("/api/energy")
public class EnergyController {

    private final EnergySummaryService service;

    public EnergyController(EnergySummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public EnergySummaryDto summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        var perDevice = service.computeKwhPerDevice(from, to);

        var devices = perDevice.entrySet().stream()
                .map(e -> new EnergySummaryDto.DeviceEnergyDto(e.getKey(), e.getValue()))
                .sorted((a, b) -> Double.compare(b.kwh, a.kwh))
                .toList();

        double total = devices.stream().mapToDouble(d -> d.kwh).sum();

        EnergySummaryDto dto = new EnergySummaryDto();
        dto.from = from.toString();
        dto.to = to.toString();
        dto.devices = devices;
        dto.totalKwh = Math.round(total * 10000.0) / 10000.0;
        return dto;
    }


    @GetMapping("/daily")
    public EnergyDailyDto daily(
            @RequestParam String deviceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        var days = service.computeDailyKwh(deviceId, from, to);

        EnergyDailyDto dto = new EnergyDailyDto();
        dto.deviceId = deviceId;
        dto.from = from.toString();
        dto.to = to.toString();
        dto.days = days;
        return dto;
    }
}
