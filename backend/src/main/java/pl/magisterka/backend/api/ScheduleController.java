package pl.magisterka.backend.api;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.*;
import pl.magisterka.backend.api.dto.ScheduleDto;
import pl.magisterka.backend.db.DeviceScheduleEntity;
import pl.magisterka.backend.db.DeviceScheduleRepository;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final DeviceScheduleRepository repository;

    public ScheduleController(DeviceScheduleRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public ScheduleDto create(@RequestBody ScheduleDto dto) {

        if (dto == null) {
            throw new IllegalArgumentException("Body is required");
        }
        if (dto.deviceId == null || dto.deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        if (dto.deviceType == null || dto.deviceType.isBlank()) {
            throw new IllegalArgumentException("deviceType is required");
        }
        if (dto.cmd == null) {
            throw new IllegalArgumentException("cmd is required");
        }
        if (dto.cron == null || dto.cron.isBlank()) {
            throw new IllegalArgumentException("cron is required");
        }


        // walidacja cron
        try {
            CronExpression.parse(dto.cron);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cron expression");
        }

        // walidacja timezone
        String timezone = dto.timezone != null ? dto.timezone : "Europe/Warsaw";
        try {
            java.time.ZoneId.of(timezone);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid timezone");
        }

        DeviceScheduleEntity entity = new DeviceScheduleEntity();
        entity.setDeviceId(dto.deviceId);
        entity.setDeviceType(dto.deviceType);
        entity.setCmd(dto.cmd);
        entity.setCron(dto.cron);
        entity.setTimezone(timezone);
        entity.setEnabled(dto.enabled);


        entity = repository.save(entity);
        return toDto(entity);
    }

    @GetMapping
    public List<ScheduleDto> getAll() {
        return repository.findAll()
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        repository.deleteById(id);
    }

    @PatchMapping("/{id}/enabled")
    public ScheduleDto setEnabled(@PathVariable Long id, @RequestParam boolean value) {

        DeviceScheduleEntity entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Schedule not found"));

        entity.setEnabled(value);
        repository.save(entity);

        return toDto(entity);
    }

    private ScheduleDto toDto(DeviceScheduleEntity e) {
        ScheduleDto dto = new ScheduleDto();
        dto.id = e.getId();
        dto.deviceId = e.getDeviceId();
        dto.deviceType = e.getDeviceType();
        dto.cmd = e.getCmd();
        dto.cron = e.getCron();
        dto.timezone = e.getTimezone();
        dto.enabled = e.isEnabled();
        return dto;
    }
}
