package pl.magisterka.backend.db;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WeeklyPlanRuleRepository extends JpaRepository<WeeklyPlanRuleEntity, Long> {
    List<WeeklyPlanRuleEntity> findByScenarioId(String scenarioId);
    void deleteByScenarioId(String scenarioId);
    List<WeeklyPlanRuleEntity> findByScenarioIdAndEnabledTrue(String scenarioId);
}