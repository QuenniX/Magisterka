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

}

