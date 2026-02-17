package pl.magisterka.backend.api.dto;

public class DeviceDto {
    public String deviceId;
    public String deviceType;

    public DeviceDto(String deviceId, String deviceType) {
        this.deviceId = deviceId;
        this.deviceType = deviceType;
    }
}
