package pl.magisterka.backend.api.dto;

import java.util.List;

public class WorkloadDto {

    public int durationSeconds;      // czas całego runa
    public long seed;                // seed wspólny (albo na start jeden)
    public int powerLimitW = 2000;   // na start default
    /** Optional: step sim ms for this run (1000 or 5000). UI "accuracy" toggle. */
    public Integer stepSimMs;

    public List<DeviceRequirementDto> devices;

    public static class DeviceRequirementDto {
        public double targetKwh;   // ile energii ma "wypracować" urządzenie w trakcie runa
        public String deviceType;        // "heater", "plug", "washer", "bulb"
        public String deviceId;          // "heater-01" itd.
        public int requiredOnSeconds;    // ile sekund ma być ON w trakcie runa
    }

}