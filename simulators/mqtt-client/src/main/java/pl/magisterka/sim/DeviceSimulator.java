package pl.magisterka.sim;

import pl.magisterka.model.EnergyTelemetry;

import java.time.Instant;

public interface DeviceSimulator {

    String deviceId();

    String deviceType();

    /** @param simTimeMs czas symulacji w ms (od startu) */
    /** @param ts czas symulacji jako Instant – używany w bazie do zakresów Od–Do w Badaniu 2/3 */
    EnergyTelemetry nextTelemetry(long simTimeMs, Instant ts);
}

