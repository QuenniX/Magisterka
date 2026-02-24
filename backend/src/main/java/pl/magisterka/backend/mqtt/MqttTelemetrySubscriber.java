package pl.magisterka.backend.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.magisterka.backend.db.EnergyTelemetryEntity;
import pl.magisterka.backend.db.EnergyTelemetryRepository;
import pl.magisterka.backend.service.ExperimentService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Component
public class MqttTelemetrySubscriber {
    private static final Logger log = LoggerFactory.getLogger(MqttTelemetrySubscriber.class);

    private final MqttProperties props;
    private final EnergyTelemetryRepository telemetryRepository;
    private final ObjectMapper objectMapper;
    private final ExperimentService experimentService;
    private MqttClient client;

    public MqttTelemetrySubscriber(
            MqttProperties props,
            EnergyTelemetryRepository telemetryRepository,
            ObjectMapper objectMapper,
            ExperimentService experimentService
    ) {
        this.props = props;
        this.telemetryRepository = telemetryRepository;
        this.objectMapper = objectMapper;
        this.experimentService = experimentService;
    }

    @jakarta.annotation.PostConstruct
    public void start() throws MqttException {
        String clientId = props.getClientIdPrefix() + UUID.randomUUID();
        client = new MqttClient(props.getBroker(), clientId);

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        if (props.getUsername() != null && !props.getUsername().isBlank()) {
            options.setUserName(props.getUsername());
            if (props.getPassword() != null) options.setPassword(props.getPassword().toCharArray());
        }
        client.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) {
                log.warn("MQTT connection lost: {}", cause.getMessage());
            }

            @Override public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload(), StandardCharsets.UTF_8);

                try {
                    JsonNode n = objectMapper.readTree(payload);

                    EnergyTelemetryEntity e = new EnergyTelemetryEntity();

                    // payload: "schema" -> entity: schemaName
                    e.setSchemaName(text(n, "schema"));

                    e.setDeviceId(text(n, "deviceId"));
                    e.setDeviceType(text(n, "deviceType"));

                    // payload has ISO-8601 string: "ts":"2026-02-17T14:19:08.085..." (czas symulacji z symulatora)
                    String tsStr = text(n, "ts");
                    e.setTs(tsStr != null ? Instant.parse(tsStr) : Instant.now());

                    e.setSimTimeMs(longVal(n, "simTimeMs"));

                    e.setPowerW(doubleVal(n, "powerW"));
                    e.setVoltageV(doubleVal(n, "voltageV"));

                    e.setState(text(n, "state"));
                    e.setMode(text(n, "mode"));

                    experimentService.getActiveExperiment()
                            .ifPresent(e::setExperiment);


                    telemetryRepository.save(e);

                    // loguj rzadziej, bo telemetry jest często
                    log.debug("Saved telemetry deviceId={} ts={} powerW={}",
                            e.getDeviceId(), e.getTs(), e.getPowerW());

                } catch (Exception ex) {
                    // nie wywracamy callbacka
                    log.warn("Failed to parse/save telemetry. topic={} payload={} err={}",
                            topic, payload, ex.toString());
                }
            }

            @Override public void deliveryComplete(IMqttDeliveryToken token) { }
        });

        client.connect(options);
        client.subscribe(props.getTelemetryTopic(), 0);

        log.info("MQTT connected to {} and subscribed to {}", props.getBroker(), props.getTelemetryTopic());
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static Long longVal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        return v.asLong();
    }

    private static Double doubleVal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        return v.asDouble();
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        try {
            if (client != null && client.isConnected()) client.disconnect();
        } catch (Exception e) {
            log.warn("Error disconnecting MQTT: {}", e.getMessage());
        }
    }

}
