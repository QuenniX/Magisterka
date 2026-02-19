package pl.magisterka.backend.db;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DeviceScheduleRepository extends JpaRepository<DeviceScheduleEntity, Long> {
    List<DeviceScheduleEntity> findByEnabledTrue();

    boolean existsByDeviceIdAndDeviceTypeAndCmdAndCronAndTimezone(
            String deviceId,
            String deviceType,
            pl.magisterka.backend.model.CommandType cmd,
            String cron,
            String timezone

    );
    void deleteByWindowId(String windowId);
    List<DeviceScheduleEntity> findByWindowId(String windowId);

    List<DeviceScheduleEntity> findByOneShotId(String oneShotId);
    void deleteByOneShotId(String oneShotId);

    List<DeviceScheduleEntity> findByScenarioId(String scenarioId);
}

