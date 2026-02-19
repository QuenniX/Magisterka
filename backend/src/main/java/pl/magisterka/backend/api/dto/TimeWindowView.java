package pl.magisterka.backend.api.dto;

public class TimeWindowView {
    public String windowId;
    public String deviceId;
    public String deviceType;
    public String from;      // "18:00"
    public String to;        // "22:00"
    public String timezone;
    public boolean enabled;
}
