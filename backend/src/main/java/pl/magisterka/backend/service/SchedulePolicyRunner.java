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
    private volatile WorkloadDto workload;

    public void setWorkload(WorkloadDto workload) {
        this.workload = workload;
    }
    private final MqttCommandPublisher cmdPublisher;

    private Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private static final int POWER_LIMIT_W = 2000;

    public SchedulePolicyRunner(MqttCommandPublisher cmdPublisher) {
        this.cmdPublisher = cmdPublisher;
    }

    public synchronized void start(ExperimentEntity experiment) {
        stop();
        running.set(true);

        long seed = (experiment.getSeed() != null) ? experiment.getSeed() : 1L;
        Random rng = new Random(seed);

        worker = new Thread(() -> runLoop(experiment.getId(), rng), "schedule-policy-runner");
        worker.setDaemon(true);
        worker.start();

        log.info("SchedulePolicyRunner started for experimentId={}", experiment.getId());
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

        boolean washerStarted = false;
        boolean heaterStarted = false;
        boolean plugStarted = false;

        while (running.get()) {
            try {

                // 🔹 Prosta strategia: rozkładamy starty w czasie
                if (!plugStarted) {
                    publish("plug", "plug-01", CommandType.START, experimentId);
                    plugStarted = true;
                    Thread.sleep(3000);
                }

                if (!heaterStarted) {
                    publish("heater", "heater-01", CommandType.START, experimentId);
                    heaterStarted = true;
                    Thread.sleep(3000);
                }

                if (!washerStarted) {
                    publish("washer", "washer-01", CommandType.START, experimentId);
                    washerStarted = true;
                }

                Thread.sleep(1000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("SchedulePolicyRunner error: {}", e.getMessage());
            }
        }
    }

    private void publish(String deviceType, String deviceId, CommandType cmd, long experimentId) throws MqttException {
        cmdPublisher.publishCommand(deviceType, deviceId, cmd);
        log.info("Schedule experimentId={} sent {} to {}/{}", experimentId, cmd, deviceType, deviceId);
    }
}