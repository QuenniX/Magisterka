package pl.magisterka.backend.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DeviceStateEventRepository extends JpaRepository<DeviceStateEventEntity, Long> {

    Optional<DeviceStateEventEntity> findTopByDeviceIdOrderByTsDesc(String deviceId);

    @Query(value = """
        select distinct device_id, device_type
        from device_state_event
        order by device_id
    """, nativeQuery = true)
    List<Object[]> findDistinctDevices();

    @Query(value = """
        select distinct on (device_id) *
        from device_state_event
        order by device_id, ts desc
    """, nativeQuery = true)
    List<DeviceStateEventEntity> findLatestStatePerDevice();
}