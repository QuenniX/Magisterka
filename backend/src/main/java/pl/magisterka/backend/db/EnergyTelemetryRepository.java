package pl.magisterka.backend.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EnergyTelemetryRepository extends JpaRepository<EnergyTelemetryEntity, Long> {

    Optional<EnergyTelemetryEntity> findTopByDeviceIdOrderByTsDesc(String deviceId);

    @Query(value = """
        select distinct on (device_id) *
        from energy_telemetry
        order by device_id, ts desc
    """, nativeQuery = true)
    List<EnergyTelemetryEntity> findLatestTelemetryPerDevice();

    List<EnergyTelemetryEntity>
    findByDeviceIdAndTsBetweenOrderByTsAsc(String deviceId, Instant from, Instant to);
}
