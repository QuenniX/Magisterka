package pl.magisterka.backend.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EnergyTelemetryRepository extends JpaRepository<EnergyTelemetryEntity, Long> {

    // Najnowszy pomiar dla konkretnego deviceId
    Optional<EnergyTelemetryEntity> findTopByDeviceIdOrderByTsDesc(String deviceId);

    // Najnowszy pomiar dla każdego deviceId (1 rekord na device)
    @Query(value = """
        select distinct on (device_id) *
        from energy_telemetry
        order by device_id, ts desc
    """, nativeQuery = true)
    List<EnergyTelemetryEntity> findLatestTelemetryPerDevice();
}
