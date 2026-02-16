package pl.magisterka.model;

import java.time.Instant;

public class EnergyTelemetry {

    private final String schema = "smarthome.energy.telemetry.v1";

    private final String deviceId;
    private final String deviceType;
    private final Instant ts;
    private final double powerW;
    private final double voltageV;
    private final DeviceState state;
    private final DeviceMode mode;
    private final long simTimeMs;

    public EnergyTelemetry(
            String deviceId,
            String deviceType,
            Instant ts,
            long simTimeMs,
            double powerW,
            double voltageV,
            DeviceState state,
            DeviceMode mode
    ) {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.ts = ts;
        this.simTimeMs = simTimeMs;
        this.powerW = powerW;
        this.voltageV = voltageV;
        this.state = state;
        this.mode = mode;
    }


    public String getSchema() { return schema; }
    public String getDeviceId() { return deviceId; }
    public String getDeviceType() { return deviceType; }
    public Instant getTs() { return ts; }
    public double getPowerW() { return powerW; }
    public double getVoltageV() { return voltageV; }
    public DeviceState getState() { return state; }
    public DeviceMode getMode() { return mode; }

    public long getSimTimeMs() {
        return simTimeMs;
    }

}
