package pl.magisterka.backend.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    List<EnergyTelemetryEntity> findByDeviceIdAndTsBetweenOrderByTsAsc(String deviceId, Instant from, Instant to);

    List<EnergyTelemetryEntity> findByTsBetweenOrderByDeviceIdAscTsAsc(Instant from, Instant to);

    // -------------------------
    // EXPERIMENT QUERIES (SIM TIME)
    // -------------------------

    @Query(value = """
        select *
        from energy_telemetry
        where experiment_id = :experimentId
        order by device_id asc, sim_time_ms asc
    """, nativeQuery = true)
    List<EnergyTelemetryEntity> findByExperimentIdOrderByDeviceIdAscSimTimeAsc(@Param("experimentId") long experimentId);

    interface ExperimentStatsRow {
        Long getMinSim();
        Long getMaxSim();
        Double getPeakDevicePowerW();   // max pojedynczego rekordu
        Double getPeakTotalPowerW();    // max sumy po czasie
        Long getSamples();
    }

    @Query(value = """
    select
      min(sim_time_ms) as minSim,
      max(sim_time_ms) as maxSim,
      max(powerw)      as peakDevicePowerW,
      (
        select max(sum_power)
        from (
          select floor(sim_time_ms / 1000) as bucket,
                 sum(powerw) as sum_power
          from energy_telemetry
          where experiment_id = :experimentId
          group by bucket
        ) t
      ) as peakTotalPowerW,
      count(*)         as samples
    from energy_telemetry
    where experiment_id = :experimentId
""", nativeQuery = true)
    ExperimentStatsRow findExperimentStats(@Param("experimentId") long experimentId);
}