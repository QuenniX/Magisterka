package pl.magisterka.backend.service;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pl.magisterka.backend.api.dto.WorkloadDto;
import pl.magisterka.backend.db.ExperimentEntity;
import pl.magisterka.backend.mqtt.MqttCommandPublisher;
import pl.magisterka.backend.model.CommandType;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SchedulePolicyRunner {

    private static final Logger log = LoggerFactory.getLogger(SchedulePolicyRunner.class);

    private final MqttCommandPublisher cmdPublisher;
    private final SimTimeService simTimeService;

    private volatile WorkloadDto workload;

    public void setWorkload(WorkloadDto workload) {
        this.workload = workload;
    }

    private Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Na razie nie używamy (power-limit dojdzie w kolejnym kroku)
    @SuppressWarnings("unused")
    private static final int POWER_LIMIT_W = 2000;

    public SchedulePolicyRunner(MqttCommandPublisher cmdPublisher,
                                SimTimeService simTimeService) {
        this.cmdPublisher = cmdPublisher;
        this.simTimeService = simTimeService;
    }

    public synchronized void start(ExperimentEntity experiment) {
        stop();
        running.set(true);

        long seed = (experiment.getSeed() != null) ? experiment.getSeed() : 1L;
        Random rng = new Random(seed);

        worker = new Thread(() -> runLoop(experiment.getId(), rng), "schedule-policy-runner");
        worker.setDaemon(true);
        worker.start();

        log.info("SchedulePolicyRunner started for experimentId={} seed={}", experiment.getId(), seed);
    }

    public synchronized void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        log.info("SchedulePolicyRunner stopped");
    }

    private void runLoop(long experimentId, Random rng) {
        long currentDayIndex = -1;

        boolean washerDone = false;
        boolean heaterOn = false;
        boolean plugOn = false;
        boolean bulb1On = false;
        boolean bulb2On = false;

        while (running.get()) {
            try {
                long simTime = simTimeService.getCurrentSimTimeMs(experimentId);
                long dayIndex = simTimeService.dayIndex(simTime);
                int minute = simTimeService.minuteOfDay(simTime);

                if (dayIndex != currentDayIndex) {
                    currentDayIndex = dayIndex;

                    washerDone = false;
                    heaterOn = false;
                    plugOn = false;
                    bulb1On = false;
                    bulb2On = false;

                    log.info("SchedulePolicyRunner new day(sim): dayIndex={}", currentDayIndex);
                }

                // WSPÓLNY PLAN (ten sam co Manual), ale:
                // Schedule = bez jitter, deterministycznie, "idealne wykonanie"

                // plug 16:00–22:00
                if (!plugOn && minute >= 16 * 60) {
                    publish("plug", "plug-01", CommandType.START, experimentId);
                    plugOn = true;
                }
                if (plugOn && minute >= 22 * 60) {
                    publish("plug", "plug-01", CommandType.STOP, experimentId);
                    plugOn = false;
                }

                // heater 17:30–20:30
                if (!heaterOn && minute >= 17 * 60 + 30) {
                    publish("heater", "heater-01", CommandType.START, experimentId);
                    heaterOn = true;
                }
                if (heaterOn && minute >= 20 * 60 + 30) {
                    publish("heater", "heater-01", CommandType.STOP, experimentId);
                    heaterOn = false;
                }

                // bulb-01 18:00–23:30
                if (!bulb1On && minute >= 18 * 60) {
                    publish("bulb", "bulb-01", CommandType.START, experimentId);
                    bulb1On = true;
                }
                if (bulb1On && minute >= 23 * 60 + 30) {
                    publish("bulb", "bulb-01", CommandType.STOP, experimentId);
                    bulb1On = false;
                }

                // bulb-02 19:00–23:00
                if (!bulb2On && minute >= 19 * 60) {
                    publish("bulb", "bulb-02", CommandType.START, experimentId);
                    bulb2On = true;
                }
                if (bulb2On && minute >= 23 * 60) {
                    publish("bulb", "bulb-02", CommandType.STOP, experimentId);
                    bulb2On = false;
                }

                // washer start 18:00 raz dziennie
                if (!washerDone && minute >= 18 * 60) {
                    publish("washer", "washer-01", CommandType.START, experimentId);
                    washerDone = true;
                }

                Thread.sleep(500);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("SchedulePolicyRunner error: {}", e.getMessage());
                try { Thread.sleep(500); } catch (InterruptedException ignored) { return; }
            }
        }
    }

    private void publish(String deviceType, String deviceId, CommandType cmd, long experimentId) throws MqttException {
        cmdPublisher.publishCommand(deviceType, deviceId, cmd);
        log.info("Schedule experimentId={} sent {} to {}/{}", experimentId, cmd, deviceType, deviceId);
    }
}