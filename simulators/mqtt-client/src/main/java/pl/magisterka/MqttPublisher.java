package pl.magisterka;

import org.eclipse.paho.client.mqttv3.MqttClient;
import pl.magisterka.json.DeviceStateJsonSerializer;
import pl.magisterka.model.DeviceCommand;
import pl.magisterka.model.DeviceStateMessage;
import pl.magisterka.mqtt.MqttCommandSubscriber;
import pl.magisterka.mqtt.MqttTelemetryPublisher;
import pl.magisterka.sim.BulbSimulator;
import pl.magisterka.sim.DeviceSimulator;
import pl.magisterka.sim.PlugSimulator;
import pl.magisterka.sim.WasherSimulator;

import java.time.Instant;

public class MqttPublisher {

    public static void main(String[] args) throws Exception {
        long simStart = System.currentTimeMillis();

        String broker = "tcp://localhost:1883";
        // mały trik: unikasz konfliktu clientId jak odpalisz 2 razy
        String clientId = "MagisterkaClient-" + System.currentTimeMillis();

        MqttClient client = new MqttClient(broker, clientId);
        client.connect();

        MqttTelemetryPublisher publisher = new MqttTelemetryPublisher(client);
        DeviceStateJsonSerializer stateSerializer = new DeviceStateJsonSerializer();

        BulbSimulator bulb = new BulbSimulator("bulb-01", 230.0, 8.0);
        BulbSimulator bulb2 = new BulbSimulator("bulb-02", 230.0, 12.0);
        PlugSimulator plug = new PlugSimulator("plug-01", 230.0);
        WasherSimulator washer = new WasherSimulator("washer-01", 230.0);

        // --- KOMENDY MQTT dla pralki ---
        MqttCommandSubscriber cmdSub = new MqttCommandSubscriber(client);
        String washerCmdTopic = cmdTopic(washer);

        cmdSub.subscribe(washerCmdTopic, cmd -> {
            long nowSim = System.currentTimeMillis() - simStart;

            try {
                if (cmd == DeviceCommand.START) {
                    washer.startCycle(nowSim);
                    System.out.println("Washer START (simTimeMs=" + nowSim + ")");
                    publishWasherState(publisher, stateSerializer, washer, nowSim);
                } else if (cmd == DeviceCommand.STOP) {
                    washer.stopCycle();
                    System.out.println("Washer STOP (simTimeMs=" + nowSim + ")");
                    publishWasherState(publisher, stateSerializer, washer, nowSim);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });


        // --- TELEMETRIA ---
        startWasherLoop("washer-01", publisher, stateSerializer, washer, 2000, simStart);
        startLoop("bulb-01", publisher, telemetryTopic(bulb), bulb, 2000, simStart);
        startLoop("bulb-02", publisher, telemetryTopic(bulb2), bulb2, 1500, simStart);
        startLoop("plug-01", publisher, telemetryTopic(plug), plug, 3000, simStart);

        Thread.currentThread().join();
    }

    private static void publishWasherState(
            MqttTelemetryPublisher publisher,
            DeviceStateJsonSerializer stateSerializer,
            WasherSimulator washer,
            long simTimeMs
    ) throws Exception {
        DeviceStateMessage st = new DeviceStateMessage(
                "smarthome.device.state.v1",
                washer.deviceId(),
                washer.deviceType(),
                Instant.now(),
                simTimeMs,
                washer.getWasherState().name(),
                washer.getCurrentPhaseName() // IDLE -> null (brak pola), RUNNING -> np. "FILL"
        );

        // retained state, QoS 1
        publisher.publish(stateTopic(washer), stateSerializer.toJson(st), 1, true);
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

    private static String cmdTopic(DeviceSimulator sim) {
        return "smarthome/v1/devices/" + sim.deviceType() + "/" + sim.deviceId() + "/cmd";
    }

    private static String stateTopic(DeviceSimulator sim) {
        return "smarthome/v1/devices/" + sim.deviceType() + "/" + sim.deviceId() + "/state";
    }

    private static Thread startWasherLoop(
            String name,
            MqttTelemetryPublisher publisher,
            DeviceStateJsonSerializer stateSerializer,
            WasherSimulator washer,
            long intervalMs,
            long simStart
    ) {
        Thread t = new Thread(() -> {
            long counter = 0;
            while (true) {
                try {
                    long simTime = System.currentTimeMillis() - simStart;

                    String before = washer.getCurrentPhaseName();
                    publisher.publish(telemetryTopic(washer), washer.nextTelemetry(simTime));
                    String after = washer.getCurrentPhaseName();

                    if (before == null && after != null || before != null && !before.equals(after)) {
                        publishWasherState(publisher, stateSerializer, washer, simTime);
                        System.out.println("Washer phase changed: " + before + " -> " + after);
                    }

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

}
