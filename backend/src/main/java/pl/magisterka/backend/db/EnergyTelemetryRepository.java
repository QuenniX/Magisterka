package pl.magisterka.backend.db;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EnergyTelemetryRepository extends JpaRepository<EnergyTelemetryEntity, Long> {
}
