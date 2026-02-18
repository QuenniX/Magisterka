package pl.magisterka.backend.api.dto;

import java.util.List;

public class EnergyDailyDto {
    public String deviceId;
    public String from;
    public String to;
    public List<DayKwhDto> days;

    public static class DayKwhDto {
        public String day;   // YYYY-MM-DD
        public Double kwh;

        public DayKwhDto() {}
        public DayKwhDto(String day, Double kwh) {
            this.day = day;
            this.kwh = kwh;
        }
    }
}
