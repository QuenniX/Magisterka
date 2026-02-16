package pl.magisterka.sim;

import pl.magisterka.model.EnergyTelemetry;

public interface DeviceSimulator {

    String deviceId();

    String deviceType();

    EnergyTelemetry nextTelemetry(long simTimeMs);
}

