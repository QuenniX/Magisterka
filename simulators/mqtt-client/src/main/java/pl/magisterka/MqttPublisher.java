package pl.magisterka;

import org.eclipse.paho.client.mqttv3.MqttClient;
import pl.magisterka.json.DeviceStateJsonSerializer;
import pl.magisterka.model.DeviceCommand;
import pl.magisterka.model.DeviceStateMessage;
import pl.magisterka.mqtt.MqttCommandSubscriber;
import pl.magisterka.mqtt.MqttTelemetryPublisher;
import pl.magisterka.sim.*;
import java.util.function.Function;
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

        FridgeSimulator fridge = new FridgeSimulator("fridge-01", 230.0);
        BulbSimulator bulb = new BulbSimulator("bulb-01", 230.0, 8.0);
        BulbSimulator bulb2 = new BulbSimulator("bulb-02", 230.0, 12.0);
        PlugSimulator plug = new PlugSimulator("plug-01", 230.0);
        WasherSimulator washer = new WasherSimulator("washer-01", 230.0);
        HeaterSimulator heater = new HeaterSimulator("heater-01", 230.0);


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
        // --- KOMENDY MQTT dla heatera ---
        String heaterCmdTopic = cmdTopic(heater);

        cmdSub.subscribe(heaterCmdTopic, cmd -> {
            long nowSim = System.currentTimeMillis() - simStart;

            try {
                if (cmd == DeviceCommand.START) {
                    heater.startHeating();
                    System.out.println("Heater START (simTimeMs=" + nowSim + ")");
                    publishDeviceState(publisher, stateSerializer, heater, nowSim, "HEATING", null);
                } else if (cmd == DeviceCommand.STOP) {
                    heater.stopHeating();
                    System.out.println("Heater STOP (simTimeMs=" + nowSim + ")");
                    publishDeviceState(publisher, stateSerializer, heater, nowSim, "OFF", null);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // --- KOMENDY MQTT dla bulb-01 ---
        cmdSub.subscribe(cmdTopic(bulb), cmd -> {
            long nowSim = System.currentTimeMillis() - simStart;

            try {
                if (cmd == DeviceCommand.START) {
                    bulb.turnOn();
                    System.out.println("Bulb-01 ON (simTimeMs=" + nowSim + ")");
                    publishDeviceState(publisher, stateSerializer, bulb, nowSim, "ON", null);
                } else if (cmd == DeviceCommand.STOP) {
                    bulb.turnOff();
                    System.out.println("Bulb-01 OFF (simTimeMs=" + nowSim + ")");
                    publishDeviceState(publisher, stateSerializer, bulb, nowSim, "OFF", null);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

// --- KOMENDY MQTT dla bulb-02 ---
        cmdSub.subscribe(cmdTopic(bulb2), cmd -> {
            long nowSim = System.currentTimeMillis() - simStart;

            try {
                if (cmd == DeviceCommand.START) {
                    bulb2.turnOn();
                    System.out.println("Bulb-02 ON (simTimeMs=" + nowSim + ")");
                    publishDeviceState(publisher, stateSerializer, bulb2, nowSim, "ON", null);
                } else if (cmd == DeviceCommand.STOP) {
                    bulb2.turnOff();
                    System.out.println("Bulb-02 OFF (simTimeMs=" + nowSim + ")");
                    publishDeviceState(publisher, stateSerializer, bulb2, nowSim, "OFF", null);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        publishDeviceState(publisher, stateSerializer, bulb, 0, bulb.getState().name(), null);
        publishDeviceState(publisher, stateSerializer, bulb2, 0, bulb2.getState().name(), null);
        publishDeviceState(publisher, stateSerializer, heater, 0, "OFF", null);



        // --- TELEMETRIA ---
        startWasherLoop("washer-01", publisher, stateSerializer, washer, 2000, simStart);
        startLoop("bulb-01", publisher, telemetryTopic(bulb), bulb, 2000, simStart);
        startLoop("bulb-02", publisher, telemetryTopic(bulb2), bulb2, 1500, simStart);
        startStateLoop("plug-01", publisher, stateSerializer, plug, 3000, simStart);
        startStateLoop("fridge-01", publisher, stateSerializer, fridge, 2000, simStart, raw -> raw.equals("ON") ? "COOLING" : "IDLE");
        startStateLoop("heater-01",publisher, stateSerializer, heater, 2000, simStart, raw -> raw.equals("ON") ? "HEATING" : "OFF");



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

    private static void publishDeviceState(
            MqttTelemetryPublisher publisher,
            DeviceStateJsonSerializer stateSerializer,
            DeviceSimulator sim,
            long simTimeMs,
            String state,
            String phase // dla fridge zawsze null
    ) throws Exception {

        DeviceStateMessage st = new DeviceStateMessage(
                "smarthome.device.state.v1",
                sim.deviceId(),
                sim.deviceType(),
                Instant.now(),
                simTimeMs,
                state,
                phase
        );

        publisher.publish(stateTopic(sim), stateSerializer.toJson(st), 1, true);
    }


    private static Thread startStateLoop(
            String name,
            MqttTelemetryPublisher publisher,
            DeviceStateJsonSerializer stateSerializer,
            DeviceSimulator sim,
            long intervalMs,
            long simStart,
            Function<String, String> stateMapper
    ) {
        Thread t = new Thread(() -> {
            long counter = 0;
            String prevState = null;

            while (true) {
                try {
                    long simTime = System.currentTimeMillis() - simStart;

                    var tel = sim.nextTelemetry(simTime);
                    publisher.publish(telemetryTopic(sim), tel);

                    String raw = tel.getState().name();   // ON/OFF
                    String currState = stateMapper.apply(raw);

                    if (prevState == null || !prevState.equals(currState)) {
                        publishDeviceState(publisher, stateSerializer, sim, simTime, currState, null);
                        System.out.println(name + " state changed: " + prevState + " -> " + currState);
                        prevState = currState;
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
    private static Thread startStateLoop(
            String name,
            MqttTelemetryPublisher publisher,
            DeviceStateJsonSerializer stateSerializer,
            DeviceSimulator sim,
            long intervalMs,
            long simStart
    ) {
        return startStateLoop(name, publisher, stateSerializer, sim, intervalMs, simStart, s -> s);
    }





}
