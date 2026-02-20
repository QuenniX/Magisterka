package pl.magisterka.backend.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExperimentRepository extends JpaRepository<ExperimentEntity, Long> {
    Optional<ExperimentEntity> findByActiveTrue();
}