package pl.magisterka.backend.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface WeeklyPlanRuleRepository extends JpaRepository<WeeklyPlanRuleEntity, Long> {
    List<WeeklyPlanRuleEntity> findByScenarioId(String scenarioId);
    void deleteByScenarioId(String scenarioId);
    List<WeeklyPlanRuleEntity> findByScenarioIdAndEnabledTrue(String scenarioId);

    @Query("SELECT r.scenarioId, COUNT(r), MAX(r.updatedAt) FROM WeeklyPlanRuleEntity r GROUP BY r.scenarioId")
    List<Object[]> findScenarioSummaries();
}