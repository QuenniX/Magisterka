package pl.magisterka.backend.db;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "weekly_plan_rule")
public class WeeklyPlanRuleEntity {

    public enum Kind {
        WINDOW, // heater/plug/bulb/fridge: ON in [from..to]
        EVENT   // washer: START at from
    }

    public enum Dow {
        MON, TUE, WED, THU, FRI, SAT, SUN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scenario_id", nullable = false, length = 64)
    private String scenarioId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "device_type", nullable = false, length = 64)
    private String deviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    // np. "MON,TUE,FRI"
    @Column(name = "days_of_week", nullable = false, length = 64)
    private String daysOfWeek;

    // "18:00"
    @Column(name = "from_time", nullable = false, length = 16)
    private String fromTime;

    // "22:00" (nullable dla EVENT)
    @Column(name = "to_time", length = 16)
    private String toTime;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "Europe/Warsaw";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // getters/setters
    public Long getId() { return id; }

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }

    public String getDaysOfWeek() { return daysOfWeek; }
    public void setDaysOfWeek(String daysOfWeek) { this.daysOfWeek = daysOfWeek; }

    public String getFromTime() { return fromTime; }
    public void setFromTime(String fromTime) { this.fromTime = fromTime; }

    public String getToTime() { return toTime; }
    public void setToTime(String toTime) { this.toTime = toTime; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}