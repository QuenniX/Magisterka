package pl.magisterka.backend.api.dto;

import java.time.Instant;

public record LatestDeviceStateDto(
        String deviceId,
        String deviceType,
        String state,
        Double powerW,
        Double voltageV,
        Instant ts
) {}
