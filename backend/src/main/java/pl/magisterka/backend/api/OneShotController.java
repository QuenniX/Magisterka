package pl.magisterka.backend.api;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pl.magisterka.backend.api.dto.OneShotDto;
import pl.magisterka.backend.db.DeviceScheduleEntity;
import pl.magisterka.backend.db.DeviceScheduleRepository;
import pl.magisterka.backend.model.CommandType;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/one-shots")
public class OneShotController {

    private final DeviceScheduleRepository repository;

    public OneShotController(DeviceScheduleRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public OneShotDto create(@RequestBody OneShotDto dto) {
        if (dto.deviceId == null || dto.deviceId.isBlank())
            throw new IllegalArgumentException("deviceId required");
        if (dto.deviceType == null || dto.deviceType.isBlank())
            throw new IllegalArgumentException("deviceType required");
        if (dto.at == null || dto.at.isBlank())
            throw new IllegalArgumentException("at required");

        // na razie tylko START (washer)
        CommandType cmd = CommandType.START;

        LocalTime at = LocalTime.parse(dto.at);
        String timezone = dto.timezone != null ? dto.timezone : "Europe/Warsaw";

        String oneShotId = UUID.randomUUID().toString();

        DeviceScheduleEntity e = new DeviceScheduleEntity();
        e.setDeviceId(dto.deviceId);
        e.setDeviceType(dto.deviceType);
        e.setCmd(cmd);
        e.setCron(String.format("0 %d %d * * *", at.getMinute(), at.getHour()));
        e.setTimezone(timezone);
        e.setEnabled(true);
        e.setOneShotId(oneShotId);
        e.setScenarioId(dto.scenarioId);

        repository.save(e);

        OneShotDto out = new OneShotDto();
        out.oneShotId = oneShotId;
        out.deviceId = e.getDeviceId();
        out.deviceType = e.getDeviceType();
        out.cmd = e.getCmd().name();
        out.at = dto.at;
        out.timezone = e.getTimezone();
        out.enabled = e.isEnabled();
        return out;
    }

    @GetMapping
    public List<OneShotDto> list() {
        return repository.findAll().stream()
                .filter(s -> s.getOneShotId() != null)
                .map(s -> {
                    OneShotDto dto = new OneShotDto();
                    dto.oneShotId = s.getOneShotId();
                    dto.deviceId = s.getDeviceId();
                    dto.deviceType = s.getDeviceType();
                    dto.cmd = s.getCmd().name();
                    dto.at = extractTime(s.getCron());
                    dto.timezone = s.getTimezone();
                    dto.enabled = s.isEnabled();
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @PatchMapping("/{oneShotId}/enabled")
    @Transactional
    public void setEnabled(@PathVariable String oneShotId, @RequestParam boolean value) {
        var list = repository.findByOneShotId(oneShotId);
        for (var s : list) s.setEnabled(value);
        repository.saveAll(list);
    }

    @DeleteMapping("/{oneShotId}")
    @Transactional
    public void delete(@PathVariable String oneShotId) {
        repository.deleteByOneShotId(oneShotId);
    }

    private String extractTime(String cron) {
        String[] parts = cron.split(" ");
        int minute = Integer.parseInt(parts[1]);
        int hour = Integer.parseInt(parts[2]);
        return String.format("%02d:%02d", hour, minute);
    }
}
