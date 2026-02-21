package pl.magisterka.backend.api.dto;

import java.util.List;

public class EnergySummaryDto {
    public String from;
    public String to;
    public Double totalKwh;
    public List<DeviceEnergyDto> devices;
    private double avgPowerW;
    private double peakToAvgRatio;

    public static class DeviceEnergyDto {
        public String deviceId;
        public Double kwh;

        public DeviceEnergyDto() {}

        public DeviceEnergyDto(String deviceId, Double kwh) {
            this.deviceId = deviceId;
            this.kwh = kwh;
        }
    }
}
