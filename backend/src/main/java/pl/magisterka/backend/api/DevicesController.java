package pl.magisterka.backend.api;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.magisterka.backend.api.dto.DeviceDto;
import pl.magisterka.backend.api.dto.LatestDeviceStateDto;
import pl.magisterka.backend.db.DeviceStateEventRepository;
import pl.magisterka.backend.db.EnergyTelemetryRepository;


import java.util.List;
@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api")
public class DevicesController {

    private final DeviceStateEventRepository stateRepo;
    private final EnergyTelemetryRepository telemetryRepo;

    public DevicesController(DeviceStateEventRepository stateRepo,
                             EnergyTelemetryRepository telemetryRepo) {
        this.stateRepo = stateRepo;
        this.telemetryRepo = telemetryRepo;
    }

    /**
     * Zwraca listę wszystkich znanych urządzeń (deviceId + deviceType)
     */
    @GetMapping("/devices")
    public List<DeviceDto> devices() {
        return stateRepo.findDistinctDevices()
                .stream()
                .map(row -> new DeviceDto(
                        (String) row[0],
                        (String) row[1]
                ))
                .toList();
    }

    /**
     * Zwraca latest telemetry per device
     * Fundament pod dashboard
     */
    @GetMapping("/devices/latest")
    public List<LatestDeviceStateDto> latestDevices() {
        return telemetryRepo.findLatestTelemetryPerDevice()
                .stream()
                .map(e -> new LatestDeviceStateDto(
                        e.getDeviceId(),
                        e.getDeviceType(),
                        e.getState(),
                        e.getPowerW(),
                        e.getVoltageV(),
                        e.getTs()
                ))
                .toList();
    }
}
