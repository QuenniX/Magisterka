package pl.magisterka.backend.service;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pl.magisterka.backend.db.ExperimentEntity;
import pl.magisterka.backend.mqtt.MqttCommandPublisher;
import pl.magisterka.backend.model.CommandType;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ManualPolicyRunner {
    private static final Logger log = LoggerFactory.getLogger(ManualPolicyRunner.class);

    private final MqttCommandPublisher cmdPublisher;

    private Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ManualPolicyRunner(MqttCommandPublisher cmdPublisher) {
        this.cmdPublisher = cmdPublisher;
    }

    /** Startuje "manual A" dla konkretnego eksperymentu. Jeśli już działa – restartuje. */
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
        // Manual A: stały plan + jitter, brak koordynacji
        // Doba symulacyjna: 24h. Operujemy na "czasie dnia" w ms.
        final long DAY_MS = 24L * 60 * 60 * 1000;

        // Żeby było deterministycznie: generujemy plan na "dzisiejszą dobę" i wykonujemy go w pętli.
        // Ponieważ backend nie ma SimClock, robimy plan wg CZASU RZECZYWISTEGO, ale w symulacji i tak liczy się sim_time_ms
        // (Twoja telemetria ma sim_time_ms). To wystarczy na start.
        //
        // Uwaga: w kolejnym kroku podepniemy to pod SimClock / odczyt sim_time_ms z DB, żeby było 1:1 symulacyjnie.

        // Prosty scheduler: co 1s real sprawdzamy czy "coś trzeba wykonać".
        // Żeby uniknąć wielokrotnego wysyłania tej samej komendy w tej samej minucie, trzymamy flagi per "dzień".
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
                long nowReal = System.currentTimeMillis();

                // Sztucznie mapujemy "czas dnia" na realny czas: to tylko żeby mieć rytm dobowy.
                // W kolejnym kroku zastąpimy to sim_time_ms.
                long dayIndex = nowReal / DAY_MS;
                long timeOfDayMs = nowReal % DAY_MS;

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

                    log.info("ManualPolicyRunner new day: jitters(ms) washer={} heaterStart={} heaterStop={}",
                            washerJitter, heaterStartJitter, heaterStopJitter);
                }

                // PLAN BAZOWY (czas dnia):
                // Washer: 18:00 start raz dziennie
                // Heater: 17:30 start, 20:30 stop
                // Bulb-01: 18:00 start, 23:30 stop
                // Bulb-02: 19:00 start, 23:00 stop
                // Plug-01: 16:00 start, 22:00 stop
                //
                // Manual = brak koordynacji => wszystko może się na siebie nałożyć.

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

                Thread.sleep(1000); // co 1s real
            } catch (InterruptedException ie) {
                // stop requested
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("ManualPolicyRunner error: {}", e.getMessage());
                // żeby nie zabić wątku na chwilowym błędzie mqtt:
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
            }
        }
    }

    private void publish(String deviceType, String deviceId, CommandType cmd, long experimentId) throws MqttException {
        cmdPublisher.publishCommand(deviceType, deviceId, cmd);
        log.info("ManualPolicyRunner experimentId={} sent {} to {}/{}", experimentId, cmd, deviceType, deviceId);
    }

    /** Jitter w ms: ±minutes */
    private long jitterMs(Random rng, int minutesPlusMinus) {
        int span = minutesPlusMinus * 2 + 1; // np. 41
        int m = rng.nextInt(span) - minutesPlusMinus; // [-20..+20]
        return m * 60_000L;
    }

    /** Godzina/minuta -> ms od północy */
    private long at(int hour, int minute) {
        return (hour * 60L + minute) * 60_000L;
    }
}