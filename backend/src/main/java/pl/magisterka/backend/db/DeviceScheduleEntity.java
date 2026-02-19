package pl.magisterka.backend.db;
import pl.magisterka.backend.model.CommandType;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "device_schedule")
public class DeviceScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "window_id", length = 64)
    private String windowId;

    public String getWindowId() { return windowId; }
    public void setWindowId(String windowId) { this.windowId = windowId; }

    @Column(name = "one_shot_id", length = 64)
    private String oneShotId;

    public String getOneShotId() { return oneShotId; }
    public void setOneShotId(String oneShotId) { this.oneShotId = oneShotId; }

    @Column(name = "scenario_id", length = 64)
    private String scenarioId;

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }


    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "device_type", nullable = false, length = 64)
    private String deviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "cmd", nullable = false, length = 16)
    private CommandType cmd;

    @Column(name = "cron", nullable = false, length = 64)
    private String cron;

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

    // --- getters/setters ---

    public Long getId() { return id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    public CommandType getCmd() { return cmd; }
    public void setCmd(CommandType cmd) { this.cmd = cmd; }

    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Column(name = "last_executed_at")
    private Instant lastExecutedAt;

    public Instant getLastExecutedAt() { return lastExecutedAt; }
    public void setLastExecutedAt(Instant lastExecutedAt) { this.lastExecutedAt = lastExecutedAt; }

}
