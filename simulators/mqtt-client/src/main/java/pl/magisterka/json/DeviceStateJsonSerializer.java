package pl.magisterka.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import pl.magisterka.model.DeviceStateMessage;

public class DeviceStateJsonSerializer {
    private final ObjectMapper mapper;

    public DeviceStateJsonSerializer() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public String toJson(DeviceStateMessage msg) {
        try {
            return mapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize DeviceStateMessage", e);
        }
    }
}
