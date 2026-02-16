package pl.magisterka.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import pl.magisterka.model.EnergyTelemetry;

public class TelemetryJsonSerializer {

    private static final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static String toJson(EnergyTelemetry telemetry) {
        try {
            return mapper.writeValueAsString(telemetry);
        } catch (Exception e) {
            throw new RuntimeException("Error serializing telemetry to JSON", e);
        }
    }
}
