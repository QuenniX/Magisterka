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

    /** Tylko rekordy z seedu (schemaName = "seed-forecast"). */
    List<EnergyTelemetryEntity> findByDeviceIdAndTsBetweenAndSchemaNameOrderByTsAsc(String deviceId, Instant from, Instant to, String schemaName);

    /** Rekordy z pominięciem seedu (MQTT / inne: schemaName IS NULL OR schemaName != 'seed-forecast'). */
    @Query("SELECT e FROM EnergyTelemetryEntity e WHERE e.deviceId = :deviceId AND e.ts >= :from AND e.ts <= :to AND (e.schemaName IS NULL OR e.schemaName != 'seed-forecast') ORDER BY e.ts ASC")
    List<EnergyTelemetryEntity> findByDeviceIdAndTsBetweenExcludingSeedOrderByTsAsc(@Param("deviceId") String deviceId, @Param("from") Instant from, @Param("to") Instant to);

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

    @Query(value = """
    select coalesce(max(sim_time_ms), 0)
    from energy_telemetry
    where experiment_id = :experimentId
""", nativeQuery = true)
    long findMaxSimTimeMs(@Param("experimentId") long experimentId);

    /** Min sim_time_ms for experiment; null when no rows. */
    @Query(value = """
    select min(sim_time_ms)
    from energy_telemetry
    where experiment_id = :experimentId
""", nativeQuery = true)
    Long findMinSimTimeMs(@Param("experimentId") long experimentId);

    /** Max sim_time_ms for experiment; null when no rows. */
    @Query(value = """
    select max(sim_time_ms)
    from energy_telemetry
    where experiment_id = :experimentId
""", nativeQuery = true)
    Long findMaxSimTimeMsNullable(@Param("experimentId") long experimentId);

    @Query(value = """
    select *
    from energy_telemetry
    where experiment_id = :experimentId
      and sim_time_ms between :fromSim and :toSim
    order by device_id asc, sim_time_ms asc
""", nativeQuery = true)
    List<EnergyTelemetryEntity> findByExperimentIdAndSimRangeOrderByDeviceIdAscSimTimeAsc(
            @Param("experimentId") long experimentId,
            @Param("fromSim") long fromSim,
            @Param("toSim") long toSim
    );
}