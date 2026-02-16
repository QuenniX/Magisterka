package pl.magisterka;

import org.eclipse.paho.client.mqttv3.*;

public class MqttPublisher {

    public static void main(String[] args) {

        String broker = "tcp://localhost:1883";
        String clientId = "MagisterkaClient";
        String topic = "magisterka/test";
        String content = "Hello z Javy 🔥";

        try {
            MqttClient client = new MqttClient(broker, clientId);
            client.connect();

            MqttMessage message = new MqttMessage(content.getBytes());
            message.setQos(0);

            client.publish(topic, message);
            System.out.println("Wysłano wiadomość!");

            client.disconnect();
            client.close();

        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}
