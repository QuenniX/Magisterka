package pl.magisterka.mqtt;

import org.eclipse.paho.client.mqttv3.*;
import pl.magisterka.json.CommandJsonParser;
import pl.magisterka.model.DeviceCommand;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class MqttCommandSubscriber {

    private final MqttClient client;

    public MqttCommandSubscriber(MqttClient client) {
        this.client = client;
    }

    public void subscribe(String topic, Consumer<DeviceCommand> handler) throws MqttException {

        client.subscribe(topic, (t, msg) -> {
            String payload = new String(msg.getPayload(), StandardCharsets.UTF_8);

            try {
                DeviceCommand cmd = CommandJsonParser.parseCommand(payload);
                System.out.println("Command received on " + t + ": " + cmd);
                handler.accept(cmd);
            } catch (Exception e) {
                System.err.println("Invalid command payload on topic " + t + ": " + payload);
                e.printStackTrace();
            }
        });
    }
}
