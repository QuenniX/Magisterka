package pl.magisterka.backend.api;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pl.magisterka.backend.api.dto.TimeWindowDto;
import pl.magisterka.backend.db.DeviceScheduleEntity;
import pl.magisterka.backend.db.DeviceScheduleRepository;
import pl.magisterka.backend.model.CommandType;
import pl.magisterka.backend.api.dto.TimeWindowView;
import java.util.List;
import java.util.stream.Collectors;

import java.time.LocalTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/time-windows")
public class TimeWindowController {

    private final DeviceScheduleRepository repository;

    public TimeWindowController(DeviceScheduleRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public void create(@RequestBody TimeWindowDto dto) {

        if (dto == null) {
            throw new IllegalArgumentException("body required");
        }

        if (dto.deviceId == null || dto.deviceId.isBlank())
            throw new IllegalArgumentException("deviceId required");

        if (dto.deviceType == null || dto.deviceType.isBlank())
            throw new IllegalArgumentException("deviceType required");

        if (dto.from == null || dto.from.isBlank() || dto.to == null || dto.to.isBlank())
            throw new IllegalArgumentException("from/to required");

        LocalTime from = LocalTime.parse(dto.from);
        LocalTime to = LocalTime.parse(dto.to);

        String timezone = (dto.timezone != null && !dto.timezone.isBlank())
                ? dto.timezone
                : "Europe/Warsaw";

        // scenarioId jest opcjonalny: jeśli null/blank -> zapisujemy null
        String scenarioId = (dto.scenarioId != null && !dto.scenarioId.isBlank())
                ? dto.scenarioId
                : null;

        String windowId = UUID.randomUUID().toString();

        // START cron
        DeviceScheduleEntity start = new DeviceScheduleEntity();
        start.setDeviceId(dto.deviceId);
        start.setDeviceType(dto.deviceType);
        start.setCmd(CommandType.START);
        start.setCron(String.format("0 %d %d * * *", from.getMinute(), from.getHour()));
        start.setTimezone(timezone);
        start.setEnabled(true);
        start.setWindowId(windowId);
        start.setScenarioId(scenarioId);

        // STOP cron
        DeviceScheduleEntity stop = new DeviceScheduleEntity();
        stop.setDeviceId(dto.deviceId);
        stop.setDeviceType(dto.deviceType);
        stop.setCmd(CommandType.STOP);
        stop.setCron(String.format("0 %d %d * * *", to.getMinute(), to.getHour()));
        stop.setTimezone(timezone);
        stop.setEnabled(true);
        stop.setWindowId(windowId);
        stop.setScenarioId(scenarioId);

        repository.save(start);
        repository.save(stop);
    }

    @GetMapping
    public List<TimeWindowView> getAll() {

        return repository.findAll().stream()
                .filter(s -> s.getWindowId() != null)
                .collect(Collectors.groupingBy(DeviceScheduleEntity::getWindowId))
                .values()
                .stream()
                .filter(group -> group.stream().anyMatch(s -> s.getCmd() == CommandType.START)
                        && group.stream().anyMatch(s -> s.getCmd() == CommandType.STOP))
                .map(group -> {
                    DeviceScheduleEntity start = group.stream()
                            .filter(s -> s.getCmd() == CommandType.START)
                            .findFirst()
                            .orElseThrow();

                    DeviceScheduleEntity stop = group.stream()
                            .filter(s -> s.getCmd() == CommandType.STOP)
                            .findFirst()
                            .orElseThrow();

                    TimeWindowView view = new TimeWindowView();
                    view.windowId = start.getWindowId();
                    view.deviceId = start.getDeviceId();
                    view.deviceType = start.getDeviceType();
                    view.from = extractTime(start.getCron());
                    view.to = extractTime(stop.getCron());
                    view.timezone = start.getTimezone();
                    view.enabled = start.isEnabled() && stop.isEnabled();

                    return view;
                })
                .toList();
    }
    private String extractTime(String cron) {
        String[] parts = cron.split(" ");
        int minute = Integer.parseInt(parts[1]);
        int hour = Integer.parseInt(parts[2]);
        return String.format("%02d:%02d", hour, minute);
    }

    @DeleteMapping("/{windowId}")
    @Transactional
    public void delete(@PathVariable String windowId) {
        repository.deleteByWindowId(windowId);
    }

    @PatchMapping("/{windowId}/enabled")
    @Transactional
    public void setEnabled(@PathVariable String windowId, @RequestParam boolean value) {

        var schedules = repository.findByWindowId(windowId);

        for (var s : schedules) {
            s.setEnabled(value);
        }

        repository.saveAll(schedules);
    }


}
