package pl.magisterka.backend.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.magisterka.backend.db.DeviceStateEventEntity;
import pl.magisterka.backend.db.DeviceStateEventRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Component
public class MqttStateSubscriber {
    private static final Logger log = LoggerFactory.getLogger(MqttStateSubscriber.class);

    private final MqttProperties props;
    private final DeviceStateEventRepository stateRepo;
    private final ObjectMapper objectMapper;

    private MqttClient client;

    public MqttStateSubscriber(MqttProperties props,
                               DeviceStateEventRepository stateRepo,
                               ObjectMapper objectMapper) {
        this.props = props;
        this.stateRepo = stateRepo;
        this.objectMapper = objectMapper;
    }

    @jakarta.annotation.PostConstruct
    public void start() throws MqttException {
        String clientId = props.getClientIdPrefix() + "state-" + UUID.randomUUID();
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
                log.warn("MQTT(state) connection lost: {}", cause.getMessage());
            }

            @Override public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload(), StandardCharsets.UTF_8);

                try {
                    JsonNode n = objectMapper.readTree(payload);

                    DeviceStateEventEntity e = new DeviceStateEventEntity();

                    // payload: "schema" -> entity: schemaName (jak w telemetry)
                    e.setSchemaName(text(n, "schema"));

                    e.setDeviceId(text(n, "deviceId"));
                    e.setDeviceType(text(n, "deviceType"));

                    // jeśli ts jest w payloadzie (ISO) bierzemy; jak nie ma → teraz
                    String tsStr = text(n, "ts");
                    e.setTs(tsStr != null ? Instant.parse(tsStr) : Instant.now());

                    // w state payload spodziewamy się "state" i opcjonalnie "phase"
                    e.setState(text(n, "state"));
                    e.setPhase(text(n, "phase"));

                    stateRepo.save(e);

                    log.debug("Saved state event deviceId={} ts={} state={} phase={}",
                            e.getDeviceId(), e.getTs(), e.getState(), e.getPhase());
                } catch (Exception ex) {
                    log.warn("Failed to parse/save state. topic={} payload={} err={}",
                            topic, payload, ex.toString());
                }
            }

            @Override public void deliveryComplete(IMqttDeliveryToken token) { }
        });

        client.connect(options);

        // QoS1 zgodnie z notatką (retained i tak dostaniesz od brokera)
        client.subscribe(props.getStateTopic(), 1);

        log.info("MQTT(state) connected to {} and subscribed to {}", props.getBroker(), props.getStateTopic());
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        try {
            if (client != null && client.isConnected()) client.disconnect();
        } catch (Exception e) {
            log.warn("Error disconnecting MQTT(state): {}", e.getMessage());
        }
    }
}
