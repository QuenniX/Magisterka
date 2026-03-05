package pl.magisterka.backend.db;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "energy_telemetry",
        indexes = {
                @Index(name = "idx_tel_device_ts", columnList = "deviceId, ts"),
                @Index(name = "idx_tel_device_sim", columnList = "deviceId, simTimeMs"),
                @Index(name = "idx_tel_exp_sim", columnList = "experiment_id, sim_time_ms")
        }
)
public class EnergyTelemetryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String schemaName;

    private String deviceId;
    private String deviceType;

    private Instant ts;
    private Long simTimeMs;

    /**
     * Real time when telemetry was ingested by backend.
     * Used for \"latest\" queries to avoid relying on simulated ts,
     * which can move backwards after simulator resets.
     */
    private Instant receivedAt;

    private Double powerW;
    private Double voltageV;

    private String state;
    private String mode;

    public EnergyTelemetryEntity() {}

    public Long getId() { return id; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public Long getSimTimeMs() { return simTimeMs; }
    public void setSimTimeMs(Long simTimeMs) { this.simTimeMs = simTimeMs; }

    public Double getPowerW() { return powerW; }
    public void setPowerW(Double powerW) { this.powerW = powerW; }

    public Double getVoltageV() { return voltageV; }
    public void setVoltageV(Double voltageV) { this.voltageV = voltageV; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    @ManyToOne
    @JoinColumn(name = "experiment_id")
    private ExperimentEntity experiment;

    public ExperimentEntity getExperiment() { return experiment; }
    public void setExperiment(ExperimentEntity experiment) { this.experiment = experiment; }
}
