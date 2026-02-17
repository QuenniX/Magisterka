package pl.magisterka.backend.mqtt;

import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class MqttCommandPublisher {
    private static final Logger log = LoggerFactory.getLogger(MqttCommandPublisher.class);

    private final MqttProperties props;
    private MqttClient client;

    public MqttCommandPublisher(MqttProperties props) {
        this.props = props;
    }

    @jakarta.annotation.PostConstruct
    public void start() throws MqttException {
        String clientId = props.getClientIdPrefix() + "cmd-" + UUID.randomUUID();
        client = new MqttClient(props.getBroker(), clientId);

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);

        client.connect(options);
        log.info("MQTT(cmd) connected to {}", props.getBroker());
    }

    public void publishCommand(String deviceType, String deviceId, String cmd) throws MqttException {
        String topic = String.format("smarthome/v1/devices/%s/%s/cmd", deviceType, deviceId);
        String payload = "{\"cmd\":\"" + cmd + "\"}";

        MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        msg.setQos(1);

        client.publish(topic, msg);
        log.info("MQTT(cmd) published topic={} payload={}", topic, payload);
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        try {
            if (client != null && client.isConnected()) client.disconnect();
        } catch (Exception e) {
            log.warn("Error disconnecting MQTT(cmd): {}", e.getMessage());
        }
    }
}
