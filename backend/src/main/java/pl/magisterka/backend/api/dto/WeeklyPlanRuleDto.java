package pl.magisterka.backend.api.dto;

import pl.magisterka.backend.db.WeeklyPlanRuleEntity;

public class WeeklyPlanRuleDto {
    public Long id;
    public String scenarioId;
    public String deviceId;
    public String deviceType;
    public WeeklyPlanRuleEntity.Kind kind; // WINDOW / EVENT
    public String daysOfWeek; // "MON,TUE"
    public String from;       // "18:00"
    public String to;         // "22:00" (null dla EVENT)
    public String timezone;
    public boolean enabled;
}