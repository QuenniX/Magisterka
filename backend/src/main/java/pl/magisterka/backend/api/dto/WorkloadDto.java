package pl.magisterka.backend.api.dto;

import java.util.List;

public class WorkloadDto {

    public int durationSeconds;      // czas całego runa
    public long seed;                // seed wspólny (albo na start jeden)
    public int powerLimitW = 2000;   // na start default

    public List<DeviceRequirementDto> devices;

    public static class DeviceRequirementDto {
        public String deviceType;        // "heater", "plug", "washer", "bulb"
        public String deviceId;          // "heater-01" itd.
        public int requiredOnSeconds;    // ile sekund ma być ON w trakcie runa
    }
}