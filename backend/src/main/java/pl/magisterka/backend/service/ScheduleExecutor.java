package pl.magisterka.backend.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;
import pl.magisterka.backend.db.DeviceScheduleEntity;
import pl.magisterka.backend.db.DeviceScheduleRepository;
import pl.magisterka.backend.mqtt.MqttCommandPublisher;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Component
public class ScheduleExecutor {

    private final DeviceScheduleRepository repository;
    private final MqttCommandPublisher publisher;

    public ScheduleExecutor(DeviceScheduleRepository repository,
                            MqttCommandPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Scheduled(fixedRate = 10000)
    @Transactional
    public void checkSchedules() {

        Instant now = Instant.now();

        List<DeviceScheduleEntity> schedules = repository.findByEnabledTrue();

        for (DeviceScheduleEntity s : schedules) {
            try {
                ZoneId zone = ZoneId.of(s.getTimezone());
                ZonedDateTime nowZoned = ZonedDateTime.ofInstant(now, zone);

                CronExpression cron = CronExpression.parse(s.getCron());

                ZonedDateTime windowStart = nowZoned.minusSeconds(10);
                ZonedDateTime fire = cron.next(windowStart.minusSeconds(1));

                if (fire == null) continue;
                if (fire.isAfter(nowZoned)) continue;

                Instant fireInstant = fire.toInstant();
                Instant lastExecuted = s.getLastExecutedAt();

                if (lastExecuted != null && !fireInstant.isAfter(lastExecuted)) {
                    continue;
                }

                publisher.publishCommand(
                        s.getDeviceType(),
                        s.getDeviceId(),
                        s.getCmd()
                );

                s.setLastExecutedAt(fireInstant);
                repository.save(s);

                System.out.println("Executed schedule id=" + s.getId() + " at=" + fireInstant);

            } catch (Exception e) {
                System.err.println("Schedule error id=" + s.getId() + " msg=" + e.getMessage());
            }
        }
    }
}
