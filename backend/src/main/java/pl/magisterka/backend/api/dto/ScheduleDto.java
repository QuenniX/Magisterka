package pl.magisterka.backend.api.dto;
import pl.magisterka.backend.model.CommandType;
public class ScheduleDto {

    public Long id;
    public String deviceId;
    public String deviceType;
    public CommandType cmd;
    public String cron;
    public String timezone;
    public boolean enabled;
}
