package pl.magisterka.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import pl.magisterka.json.TelemetryJsonSerializer;
import pl.magisterka.model.EnergyTelemetry;

import java.nio.charset.StandardCharsets;

public class MqttTelemetryPublisher {

    private final MqttClient client;

    public MqttTelemetryPublisher(MqttClient client) {
        this.client = client;
    }

    public void publish(String topic, EnergyTelemetry telemetry) throws Exception {
        String json = TelemetryJsonSerializer.toJson(telemetry);
        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
        message.setQos(0);
        client.publish(topic, message);
    }
}
