package pl.magisterka.backend.api.dto;

import java.time.Instant;

public class LatestTelemetryDto {
    public String deviceId;
    public String deviceType;

    public Double powerW;
    public Double voltageV;

    public String state;
    public String mode;

    public Instant ts;
    public Long simTimeMs;

    public LatestTelemetryDto(String deviceId,
                              String deviceType,
                              Double powerW,
                              Double voltageV,
                              String state,
                              String mode,
                              Instant ts,
                              Long simTimeMs) {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.powerW = powerW;
        this.voltageV = voltageV;
        this.state = state;
        this.mode = mode;
        this.ts = ts;
        this.simTimeMs = simTimeMs;
    }
}
