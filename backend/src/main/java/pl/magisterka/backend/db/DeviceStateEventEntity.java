package pl.magisterka.backend.db;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "device_state_event",
        indexes = {
                @Index(name = "idx_state_device_ts", columnList = "deviceId, ts")
        }
)
public class DeviceStateEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String schemaName;

    private String deviceId;
    private String deviceType;

    private Instant ts;

    private String state;
    private String phase; // nullable

    public DeviceStateEventEntity() {}

    public Long getId() { return id; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
}
