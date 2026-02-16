package pl.magisterka;

import org.eclipse.paho.client.mqttv3.MqttClient;
import pl.magisterka.mqtt.MqttTelemetryPublisher;
import pl.magisterka.sim.BulbSimulator;
import pl.magisterka.sim.PlugSimulator;
import pl.magisterka.sim.DeviceSimulator;

public class MqttPublisher {

    public static void main(String[] args) throws Exception {
        long simStart = System.currentTimeMillis();

        String broker = "tcp://localhost:1883";
        String clientId = "MagisterkaClient";

        MqttClient client = new MqttClient(broker, clientId);
        client.connect();

        MqttTelemetryPublisher publisher = new MqttTelemetryPublisher(client);

        BulbSimulator bulb = new BulbSimulator("bulb-01", 230.0, 8.0);
        BulbSimulator bulb2 = new BulbSimulator("bulb-02", 230.0, 12.0);
        PlugSimulator plug = new PlugSimulator("plug-01", 230.0);

        startLoop("bulb-01", publisher, telemetryTopic(bulb), bulb, 2000, simStart);
        startLoop("bulb-02", publisher, telemetryTopic(bulb2), bulb2, 1500, simStart);
        startLoop("plug-01", publisher, telemetryTopic(plug), plug, 3000, simStart);

        Thread.currentThread().join();
    }


    private static Thread startLoop(
            String name,
            MqttTelemetryPublisher publisher,
            String topic,
            DeviceSimulator sim,
            long intervalMs,
            long simStart
    ) {

        Thread t = new Thread(() -> {
            long counter = 0;
            while (true) {
                try {
                    long simTime = System.currentTimeMillis() - simStart;
                    publisher.publish(topic, sim.nextTelemetry(simTime));
                    counter++;

                    if (counter % 10 == 0) {
                        System.out.println(name + " published: " + counter);
                    }

                    Thread.sleep(intervalMs);
                } catch (Exception e) {
                    e.printStackTrace();
                    break;
                }
            }
        });

        t.setName(name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String telemetryTopic(DeviceSimulator sim) {
        return "smarthome/v1/devices/" + sim.deviceType() + "/" + sim.deviceId() + "/telemetry";
    }


}
