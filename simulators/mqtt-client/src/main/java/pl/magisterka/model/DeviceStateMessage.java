package pl.magisterka.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceStateMessage {

    private final String schema;
    private final String deviceId;
    private final String deviceType;
    private final Instant ts;
    private final long simTimeMs;
    private final String state;
    private final String phase; // opcjonalne

    public DeviceStateMessage(String schema,
                              String deviceId,
                              String deviceType,
                              Instant ts,
                              long simTimeMs,
                              String state,
                              String phase) {
        this.schema = schema;
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.ts = ts;
        this.simTimeMs = simTimeMs;
        this.state = state;
        this.phase = phase;
    }

    public String getSchema() {
        return schema;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public Instant getTs() {
        return ts;
    }

    public long getSimTimeMs() {
        return simTimeMs;
    }

    public String getState() {
        return state;
    }

    public String getPhase() {
        return phase;
    }
}
