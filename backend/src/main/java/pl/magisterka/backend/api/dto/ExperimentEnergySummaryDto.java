package pl.magisterka.backend.api.dto;

import java.util.List;

public record ExperimentEnergySummaryDto(
        long experimentId,
        Long startSimTimeMs,
        Long endSimTimeMs,
        Long durationMs,
        Double totalEnergyWh,
        Double peakPowerW,
        List<DeviceEnergyDto> perDevice
) {
    public record DeviceEnergyDto(
            String deviceId,
            String deviceType,
            Double energyWh,
            Double avgPowerW,
            Double peakPowerW,
            Long samples
    ) {}
}