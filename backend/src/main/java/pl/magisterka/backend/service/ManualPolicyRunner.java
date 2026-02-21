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
public class ManualPolicyRunner {
    private static final Logger log = LoggerFactory.getLogger(ManualPolicyRunner.class);

    private final SimTimeService simTimeService;
    private final MqttCommandPublisher cmdPublisher;

    private volatile WorkloadDto workload;

    public void setWorkload(WorkloadDto workload) {
        this.workload = workload;
    }

    private Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ManualPolicyRunner(MqttCommandPublisher cmdPublisher,
                              SimTimeService simTimeService) {
        this.cmdPublisher = cmdPublisher;
        this.simTimeService = simTimeService;
    }

    /** Startuje "manual" dla konkretnego eksperymentu. Jeśli już działa – restartuje. */
    public synchronized void start(ExperimentEntity experiment) {
        stop(); // bezpieczeństwo: tylko 1 runner naraz
        running.set(true);

        long seed = (experiment.getSeed() != null) ? experiment.getSeed() : 1L;
        Random rng = new Random(seed);

        worker = new Thread(() -> runLoop(experiment.getId(), rng), "manual-policy-runner");
        worker.setDaemon(true);
        worker.start();

        log.info("ManualPolicyRunner started for experimentId={} seed={}", experiment.getId(), seed);
    }

    public synchronized void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        log.info("ManualPolicyRunner stopped");
    }

    private void runLoop(long experimentId, Random rng) {
        long currentDayIndex = -1;

        boolean washerDone = false;
        boolean heaterStarted = false;
        boolean heaterStopped = false;
        boolean bulb1Started = false;
        boolean bulb1Stopped = false;
        boolean bulb2Started = false;
        boolean bulb2Stopped = false;
        boolean plugStarted = false;
        boolean plugStopped = false;

        // Losujemy jitter raz na dobę (deterministycznie przez seed)
        long washerJitter = 0;
        long heaterStartJitter = 0;
        long heaterStopJitter = 0;
        long bulb1StartJitter = 0;
        long bulb1StopJitter = 0;
        long bulb2StartJitter = 0;
        long bulb2StopJitter = 0;
        long plugStartJitter = 0;
        long plugStopJitter = 0;

        while (running.get()) {
            try {
                long simTime = simTimeService.getCurrentSimTimeMs(experimentId);
                long dayIndex = simTimeService.dayIndex(simTime);
                int minuteOfDay = simTimeService.minuteOfDay(simTime);
                long timeOfDayMs = minuteOfDay * 60_000L;

                if (dayIndex != currentDayIndex) {
                    currentDayIndex = dayIndex;

                    washerDone = false;
                    heaterStarted = false;
                    heaterStopped = false;
                    bulb1Started = false;
                    bulb1Stopped = false;
                    bulb2Started = false;
                    bulb2Stopped = false;
                    plugStarted = false;
                    plugStopped = false;

                    washerJitter = jitterMs(rng, 20);        // ±20 min
                    heaterStartJitter = jitterMs(rng, 15);   // ±15 min
                    heaterStopJitter = jitterMs(rng, 15);
                    bulb1StartJitter = jitterMs(rng, 10);    // ±10 min
                    bulb1StopJitter = jitterMs(rng, 10);
                    bulb2StartJitter = jitterMs(rng, 10);
                    bulb2StopJitter = jitterMs(rng, 10);
                    plugStartJitter = jitterMs(rng, 30);     // ±30 min
                    plugStopJitter = jitterMs(rng, 30);

                    log.info("ManualPolicyRunner new day(sim): dayIndex={} jitters(ms) washer={} heaterStart={} heaterStop={}",
                            currentDayIndex, washerJitter, heaterStartJitter, heaterStopJitter);
                }

                // WSPÓLNY PLAN (ten sam co Schedule):
                // Washer: 18:00 start raz dziennie
                // Heater: 17:30 start, 20:30 stop
                // Plug-01: 16:00 start, 22:00 stop
                // Bulb-01: 18:00 start, 23:30 stop
                // Bulb-02: 19:00 start, 23:00 stop
                //
                // Manual = jitter + brak koordynacji.

                // plug-01 start 16:00
                if (!plugStarted && timeOfDayMs >= at(16, 0) + plugStartJitter) {
                    publish("plug", "plug-01", CommandType.START, experimentId);
                    plugStarted = true;
                }
                // plug-01 stop 22:00
                if (!plugStopped && timeOfDayMs >= at(22, 0) + plugStopJitter) {
                    publish("plug", "plug-01", CommandType.STOP, experimentId);
                    plugStopped = true;
                }

                // heater start 17:30
                if (!heaterStarted && timeOfDayMs >= at(17, 30) + heaterStartJitter) {
                    publish("heater", "heater-01", CommandType.START, experimentId);
                    heaterStarted = true;
                }
                // heater stop 20:30
                if (!heaterStopped && timeOfDayMs >= at(20, 30) + heaterStopJitter) {
                    publish("heater", "heater-01", CommandType.STOP, experimentId);
                    heaterStopped = true;
                }

                // bulb-01 start 18:00
                if (!bulb1Started && timeOfDayMs >= at(18, 0) + bulb1StartJitter) {
                    publish("bulb", "bulb-01", CommandType.START, experimentId);
                    bulb1Started = true;
                }
                // bulb-01 stop 23:30
                if (!bulb1Stopped && timeOfDayMs >= at(23, 30) + bulb1StopJitter) {
                    publish("bulb", "bulb-01", CommandType.STOP, experimentId);
                    bulb1Stopped = true;
                }

                // bulb-02 start 19:00
                if (!bulb2Started && timeOfDayMs >= at(19, 0) + bulb2StartJitter) {
                    publish("bulb", "bulb-02", CommandType.START, experimentId);
                    bulb2Started = true;
                }
                // bulb-02 stop 23:00
                if (!bulb2Stopped && timeOfDayMs >= at(23, 0) + bulb2StopJitter) {
                    publish("bulb", "bulb-02", CommandType.STOP, experimentId);
                    bulb2Stopped = true;
                }

                // washer start 18:00 (raz dziennie)
                if (!washerDone && timeOfDayMs >= at(18, 0) + washerJitter) {
                    publish("washer", "washer-01", CommandType.START, experimentId);
                    washerDone = true;
                }

                Thread.sleep(500); // częściej tickujemy, bo sim_time może skakać szybciej

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("ManualPolicyRunner error: {}", e.getMessage());
                try { Thread.sleep(500); } catch (InterruptedException ignored) { return; }
            }
        }
    }

    private void publish(String deviceType, String deviceId, CommandType cmd, long experimentId) throws MqttException {
        cmdPublisher.publishCommand(deviceType, deviceId, cmd);
        log.info("ManualPolicyRunner experimentId={} sent {} to {}/{}", experimentId, cmd, deviceType, deviceId);
    }

    /** Jitter w ms: ±minutes */
    private long jitterMs(Random rng, int minutesPlusMinus) {
        int span = minutesPlusMinus * 2 + 1;
        int m = rng.nextInt(span) - minutesPlusMinus;
        return m * 60_000L;
    }

    /** Godzina/minuta -> ms od północy */
    private long at(int hour, int minute) {
        return (hour * 60L + minute) * 60_000L;
    }
}