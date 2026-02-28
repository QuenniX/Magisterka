package pl.magisterka;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import pl.magisterka.json.DeviceStateJsonSerializer;
import pl.magisterka.model.DeviceCommand;
import pl.magisterka.model.DeviceStateMessage;
import pl.magisterka.mqtt.MqttCommandSubscriber;
import pl.magisterka.mqtt.MqttTelemetryPublisher;
import pl.magisterka.mqtt.SimControlResetSubscriber;
import pl.magisterka.sim.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class MqttPublisher {

    /**
     * Punkt startu czasu symulacji (ts w telemetrii = simStart + simTimeMs).
     * Dzięki temu po długim runie (np. 30 dni sym.) w bazie są daty w zakresie Od–Do w Badaniu 2/3.
     * Zmienne środowiskowe:
     * - SIM_START_DAYS_AGO=N  → start = now - N dni (np. 30 = dane „ostatnie 30 dni”)
     * - SIM_START_INSTANT=ISO → start = podana data (np. 2026-01-22T00:00:00Z)
     * Gdy brak: simStart = now() (zachowanie jak wcześniej, ts ≈ czas rzeczywisty).
     */
    private static Instant computeSimStart() {
        String daysAgo = System.getenv("SIM_START_DAYS_AGO");
        if (daysAgo != null && !daysAgo.isBlank()) {
            try {
                int days = Integer.parseInt(daysAgo.trim());
                return Instant.now().minus(Math.max(0, days), ChronoUnit.DAYS);
            } catch (NumberFormatException ignored) { }
        }
        String instantStr = System.getenv("SIM_START_INSTANT");
        if (instantStr != null && !instantStr.isBlank()) {
            try {
                return Instant.parse(instantStr.trim());
            } catch (Exception ignored) { }
        }
        return Instant.now();
    }

    public static void main(String[] args) throws Exception {
        long speedFactor = 2000; // 1s real = 2000s sim (~33 min). Testy krótkie, cele kWh osiągalne w 1-3 min.
        AtomicReference<SimClock> clockRef = new AtomicReference<>(SimClock.start(speedFactor));
        Instant simStart = computeSimStart();
        System.out.println("Sim start (ts base): " + simStart + " (use SIM_START_DAYS_AGO=30 for 30-day reference data)");

        String broker = System.getenv().getOrDefault("MQTT_BROKER", "tcp://localhost:1883");
        // mały trik: unikasz konfliktu clientId jak odpalisz 2 razy
        String clientId = "MagisterkaClient-" + System.currentTimeMillis();

        MqttClient client = new MqttClient(broker, clientId);
        try {
            client.connect();
        } catch (MqttException e) {
            System.err.println("Błąd połączenia z brokerem MQTT (" + broker + "): " + e.getMessage());
            System.err.println("Upewnij się, że broker MQTT działa (np. Mosquitto na porcie 1883).");
            System.err.println("Windows: uruchom mosquitto.exe lub 'net start mosquitto'. Docker: docker run -p 1883:1883 eclipse-mosquitto.");
            throw e;
        }

        MqttTelemetryPublisher publisher = new MqttTelemetryPublisher(client);
        DeviceStateJsonSerializer stateSerializer = new DeviceStateJsonSerializer();

        FridgeSimulator fridge = new FridgeSimulator("fridge-01", 230.0);
        BulbSimulator bulb = new BulbSimulator("bulb-01", 230.0, 8.0);
        BulbSimulator bulb2 = new BulbSimulator("bulb-02", 230.0, 12.0);
        PlugSimulator plug = new PlugSimulator("plug-01", 230.0);
        WasherSimulator washer = new WasherSimulator("washer-01", 230.0);
        HeaterSimulator heater = new HeaterSimulator("heater-01", 230.0);
        BulbSimulator bulb3 = new BulbSimulator("bulb-03", 230.0, 10.0);
        HeaterSimulator heater2 = new HeaterSimulator("heater-02", 230.0);

        // --- KOMENDY MQTT ---
        MqttCommandSubscriber cmdSub = new MqttCommandSubscriber(client);

        try {
            // --- KOMENDY MQTT dla pralki ---
            String washerCmdTopic = cmdTopic(washer);
            cmdSub.subscribe(washerCmdTopic, cmd -> {
                long nowSim = clockRef.get().nowMs();
                Instant ts = simStart.plusMillis(nowSim);
                try {
                    if (cmd == DeviceCommand.START) {
                        washer.startCycle(nowSim);
                        System.out.println("Washer START (simTimeMs=" + nowSim + ")");
                        publishWasherState(publisher, stateSerializer, washer, ts, nowSim);
                    } else if (cmd == DeviceCommand.STOP) {
                        washer.stopCycle();
                        System.out.println("Washer STOP (simTimeMs=" + nowSim + ")");
                        publishWasherState(publisher, stateSerializer, washer, ts, nowSim);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            // --- KOMENDY MQTT dla heatera ---
            String heaterCmdTopic = cmdTopic(heater);
            cmdSub.subscribe(heaterCmdTopic, cmd -> {
                long nowSim = clockRef.get().nowMs();
                Instant ts = simStart.plusMillis(nowSim);
                try {
                    if (cmd == DeviceCommand.START) {
                        heater.startHeating();
                        System.out.println("Heater START (simTimeMs=" + nowSim + ")");
                        publishDeviceState(publisher, stateSerializer, heater, ts, nowSim, "HEATING", null);
                    } else if (cmd == DeviceCommand.STOP) {
                        heater.stopHeating();
                        System.out.println("Heater STOP (simTimeMs=" + nowSim + ")");
                        publishDeviceState(publisher, stateSerializer, heater, ts, nowSim, "OFF", null);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            // --- KOMENDY MQTT dla bulb-01 ---
            cmdSub.subscribe(cmdTopic(bulb), cmd -> {
                long nowSim = clockRef.get().nowMs();
                Instant ts = simStart.plusMillis(nowSim);
                try {
                    if (cmd == DeviceCommand.START) {
                        bulb.turnOn();
                        System.out.println("Bulb-01 ON (simTimeMs=" + nowSim + ")");
                        publishDeviceState(publisher, stateSerializer, bulb, ts, nowSim, "ON", null);
                    } else if (cmd == DeviceCommand.STOP) {
                        bulb.turnOff();
                        System.out.println("Bulb-01 OFF (simTimeMs=" + nowSim + ")");
                        publishDeviceState(publisher, stateSerializer, bulb, ts, nowSim, "OFF", null);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            // --- KOMENDY MQTT dla bulb-02 ---
            cmdSub.subscribe(cmdTopic(bulb2), cmd -> {
                long nowSim = clockRef.get().nowMs();
                Instant ts = simStart.plusMillis(nowSim);
                try {
                    if (cmd == DeviceCommand.START) {
                        bulb2.turnOn();
                        System.out.println("Bulb-02 ON (simTimeMs=" + nowSim + ")");
                        publishDeviceState(publisher, stateSerializer, bulb2, ts, nowSim, "ON", null);
                    } else if (cmd == DeviceCommand.STOP) {
                        bulb2.turnOff();
                        System.out.println("Bulb-02 OFF (simTimeMs=" + nowSim + ")");
                        publishDeviceState(publisher, stateSerializer, bulb2, ts, nowSim, "OFF", null);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            // --- KOMENDY MQTT dla bulb-03 ---
            cmdSub.subscribe(cmdTopic(bulb3), cmd -> {
                long nowSim = clockRef.get().nowMs();
                Instant ts = simStart.plusMillis(nowSim);
                try {
                    if (cmd == DeviceCommand.START) {
                        bulb3.turnOn();
                        System.out.println("Bulb-03 ON (simTimeMs=" + nowSim + ")");
                        publishDeviceState(publisher, stateSerializer, bulb3, ts, nowSim, "ON", null);
                    } else if (cmd == DeviceCommand.STOP) {
                        bulb3.turnOff();
                        System.out.println("Bulb-03 OFF (simTimeMs=" + nowSim + ")");
                        publishDeviceState(publisher, stateSerializer, bulb3, ts, nowSim, "OFF", null);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            // --- KOMENDY MQTT dla heater-02 ---
            String heater2CmdTopic = cmdTopic(heater2);
            cmdSub.subscribe(heater2CmdTopic, cmd -> {
                long nowSim = clockRef.get().nowMs();
                Instant ts = simStart.plusMillis(nowSim);
                try {
                    if (cmd == DeviceCommand.START) {
                        heater2.startHeating();
                        System.out.println("Heater-02 START (simTimeMs=" + nowSim + ")");
                        publishDeviceState(publisher, stateSerializer, heater2, ts, nowSim, "HEATING", null);
                    } else if (cmd == DeviceCommand.STOP) {
                        heater2.stopHeating();
                        System.out.println("Heater-02 STOP (simTimeMs=" + nowSim + ")");
                        publishDeviceState(publisher, stateSerializer, heater2, ts, nowSim, "OFF", null);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

        } catch (MqttException e) {
            throw new RuntimeException("Failed to subscribe to MQTT cmd topics", e);
        }

        // --- SIM CONTROL: reset (isolated 1B) ---
        Runnable resetAction = () -> {
            heater.reset();
            heater2.reset();
            washer.reset();
            bulb.reset();
            bulb2.reset();
            bulb3.reset();
            plug.reset();
            fridge.reset();
            clockRef.set(clockRef.get().reset());
        };
        SimControlResetSubscriber resetSub = new SimControlResetSubscriber(client, publisher, resetAction);
        resetSub.subscribe();
        System.out.println("Subscribed to " + SimControlResetSubscriber.TOPIC_RESET + " (isolated 1B)");

        // publish initial retained states (ts = simStart + 0)
        Instant ts0 = simStart;
        publishDeviceState(publisher, stateSerializer, bulb, ts0, 0, bulb.getState().name(), null);
        publishDeviceState(publisher, stateSerializer, bulb2, ts0, 0, bulb2.getState().name(), null);
        publishDeviceState(publisher, stateSerializer, bulb3, ts0, 0, bulb3.getState().name(), null);
        publishDeviceState(publisher, stateSerializer, heater, ts0, 0, "OFF", null);
        publishDeviceState(publisher, stateSerializer, heater2, ts0, 0, "OFF", null);
        publishWasherState(publisher, stateSerializer, washer, ts0, 0);

        // --- TELEMETRIA ---
        startWasherLoop("washer-01", publisher, stateSerializer, washer, 2000, clockRef, simStart);
        startLoop("bulb-01", publisher, telemetryTopic(bulb), bulb, 2000, clockRef, simStart);
        startLoop("bulb-02", publisher, telemetryTopic(bulb2), bulb2, 1500, clockRef, simStart);
        startLoop("bulb-03", publisher, telemetryTopic(bulb3), bulb3, 1800, clockRef, simStart);

        startStateLoop("plug-01", publisher, stateSerializer, plug, 3000, clockRef, simStart, s -> s);
        startStateLoop("fridge-01", publisher, stateSerializer, fridge, 2000, clockRef, simStart,
                raw -> raw.equals("ON") ? "COOLING" : "IDLE");
        startStateLoop("heater-01", publisher, stateSerializer, heater, 2000, clockRef, simStart,
                raw -> raw.equals("ON") ? "HEATING" : "OFF");
        startStateLoop("heater-02", publisher, stateSerializer, heater2, 2000, clockRef, simStart,
                raw -> raw.equals("ON") ? "HEATING" : "OFF");

        Thread.currentThread().join();
    }

    private static void publishWasherState(
            MqttTelemetryPublisher publisher,
            DeviceStateJsonSerializer stateSerializer,
            WasherSimulator washer,
            Instant ts,
            long simTimeMs
    ) throws Exception {
        DeviceStateMessage st = new DeviceStateMessage(
                "smarthome.device.state.v1",
                washer.deviceId(),
                washer.deviceType(),
                ts,
                simTimeMs,
                washer.getWasherState().name(),
                washer.getCurrentPhaseName()
        );

        // retained state, QoS 1
        publisher.publish(stateTopic(washer), stateSerializer.toJson(st), 1, true);
    }

    private static void publishDeviceState(
            MqttTelemetryPublisher publisher,
            DeviceStateJsonSerializer stateSerializer,
            DeviceSimulator sim,
            Instant ts,
            long simTimeMs,
            String state,
            String phase
    ) throws Exception {

        DeviceStateMessage st = new DeviceStateMessage(
                "smarthome.device.state.v1",
                sim.deviceId(),
                sim.deviceType(),
                ts,
                simTimeMs,
                state,
                phase
        );

        // retained state, QoS 1
        publisher.publish(stateTopic(sim), stateSerializer.toJson(st), 1, true);
    }

    private static Thread startLoop(
            String name,
            MqttTelemetryPublisher publisher,
            String topic,
            DeviceSimulator sim,
            long intervalMs,
            AtomicReference<SimClock> clockRef,
            Instant simStart
    ) {
        Thread t = new Thread(() -> {
            long counter = 0;
            while (true) {
                try {
                    long simTime = clockRef.get().nowMs();
                    Instant ts = simStart.plusMillis(simTime);
                    publisher.publish(topic, sim.nextTelemetry(simTime, ts));
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

    private static Thread startWasherLoop(
            String name,
            MqttTelemetryPublisher publisher,
            DeviceStateJsonSerializer stateSerializer,
            WasherSimulator washer,
            long intervalMs,
            AtomicReference<SimClock> clockRef,
            Instant simStart
    ) {
        Thread t = new Thread(() -> {
            long counter = 0;
            while (true) {
                try {
                    long simTime = clockRef.get().nowMs();
                    Instant ts = simStart.plusMillis(simTime);

                    String before = washer.getCurrentPhaseName();
                    publisher.publish(telemetryTopic(washer), washer.nextTelemetry(simTime, ts));
                    String after = washer.getCurrentPhaseName();

                    boolean changed =
                            (before == null && after != null) ||
                                    (before != null && !before.equals(after));

                    if (changed) {
                        publishWasherState(publisher, stateSerializer, washer, ts, simTime);
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

    private static Thread startStateLoop(
            String name,
            MqttTelemetryPublisher publisher,
            DeviceStateJsonSerializer stateSerializer,
            DeviceSimulator sim,
            long intervalMs,
            AtomicReference<SimClock> clockRef,
            Instant simStart,
            Function<String, String> stateMapper
    ) {
        Thread t = new Thread(() -> {
            long counter = 0;
            String prevState = null;

            while (true) {
                try {
                    long simTime = clockRef.get().nowMs();
                    Instant ts = simStart.plusMillis(simTime);

                    var tel = sim.nextTelemetry(simTime, ts);
                    publisher.publish(telemetryTopic(sim), tel);

                    String raw = tel.getState().name();
                    String currState = stateMapper.apply(raw);

                    if (prevState == null || !prevState.equals(currState)) {
                        publishDeviceState(publisher, stateSerializer, sim, ts, simTime, currState, null);
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

    private static String telemetryTopic(DeviceSimulator sim) {
        return "smarthome/v1/devices/" + sim.deviceType() + "/" + sim.deviceId() + "/telemetry";
    }

    private static String cmdTopic(DeviceSimulator sim) {
        return "smarthome/v1/devices/" + sim.deviceType() + "/" + sim.deviceId() + "/cmd";
    }

    private static String stateTopic(DeviceSimulator sim) {
        return "smarthome/v1/devices/" + sim.deviceType() + "/" + sim.deviceId() + "/state";
    }
}