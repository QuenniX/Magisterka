package pl.magisterka.backend.api.dto;

public class OneShotDto {
    public String oneShotId; // w odpowiedzi
    public String deviceId;
    public String deviceType;
    public String cmd;       // np. "START" (na razie tylko START)
    public String at;        // "18:00"
    public String timezone;
    public boolean enabled;
    public String scenarioId;

}
