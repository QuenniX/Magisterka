package pl.magisterka.backend.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pl.magisterka.backend.db.DeviceStateEventEntity;
import pl.magisterka.backend.db.DeviceStateEventRepository;
import pl.magisterka.backend.model.CommandType;
import pl.magisterka.backend.mqtt.MqttCommandPublisher;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ScenarioPolicyRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioPolicyRunner.class);

    private final DeviceStateEventRepository stateRepo;
    private final MqttCommandPublisher mqtt;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;

    public ScenarioPolicyRunner(DeviceStateEventRepository stateRepo, MqttCommandPublisher mqtt) {
        this.stateRepo = stateRepo;
        this.mqtt = mqtt;
    }

    public void start(long experimentId) {
        if (running) return;
        running = true;

        executor.submit(() -> {
            log.info("ScenarioPolicyRunner started for experimentId={}", experimentId);

            while (running) {
                try {
                    enforcePeakShavingV1();
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("ScenarioPolicyRunner error: {}", e.getMessage());
                }
            }

            log.info("ScenarioPolicyRunner stopped");
        });
    }

    public void stop() {
        running = false;
    }

    /**
     * Peak-shaving v1:
     * Jeśli washer ON i heater ON -> wyłącz heater.
     */
    private void enforcePeakShavingV1() throws Exception {
        String washer = stateRepo.findTopByDeviceIdOrderByTsDesc("washer-01")
                .map(DeviceStateEventEntity::getState)
                .orElse("OFF");

        String heater = stateRepo.findTopByDeviceIdOrderByTsDesc("heater-01")
                .map(DeviceStateEventEntity::getState)
                .orElse("OFF");

        boolean washerOn = "ON".equalsIgnoreCase(washer);
        boolean heaterOn = "ON".equalsIgnoreCase(heater);

        if (washerOn && heaterOn) {
            mqtt.publishCommand("heater", "heater-01", CommandType.STOP);
            log.info("Scenario peak-shaving: heater STOP because washer ON");
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executor.shutdownNow();
    }
}
