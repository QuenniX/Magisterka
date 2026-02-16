package pl.magisterka.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import pl.magisterka.model.DeviceCommand;

public class CommandJsonParser {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static DeviceCommand parseCommand(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            String cmd = node.get("cmd").asText();
            return DeviceCommand.valueOf(cmd.toUpperCase());
        } catch (Exception e) {
            throw new RuntimeException("Invalid command JSON: " + json, e);
        }
    }
}
