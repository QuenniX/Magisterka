package pl.magisterka.backend.api.dto;

import java.time.Instant;

public class LatestStateDto {
    public String deviceId;
    public String deviceType;
    public String state;
    public String phase;
    public Instant ts;

    public LatestStateDto(String deviceId, String deviceType, String state, String phase, Instant ts) {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.state = state;
        this.phase = phase;
        this.ts = ts;
    }
}
