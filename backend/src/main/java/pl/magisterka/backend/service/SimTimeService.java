package pl.magisterka.backend.service;

import org.springframework.stereotype.Service;
import pl.magisterka.backend.db.EnergyTelemetryRepository;

@Service
public class SimTimeService {

    private final EnergyTelemetryRepository telemetryRepository;

    public SimTimeService(EnergyTelemetryRepository telemetryRepository) {
        this.telemetryRepository = telemetryRepository;
    }

    public long getCurrentSimTimeMs(long experimentId) {
        return telemetryRepository.findMaxSimTimeMs(experimentId);
    }

    public int minuteOfDay(long simTimeMs) {
        return (int) ((simTimeMs / 60_000L) % 1440L);
    }

    public long dayIndex(long simTimeMs) {
        return (simTimeMs / 60_000L) / 1440L;
    }
}