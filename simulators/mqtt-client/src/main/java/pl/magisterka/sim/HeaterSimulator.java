package pl.magisterka.sim;

import pl.magisterka.model.DeviceMode;
import pl.magisterka.model.DeviceState;
import pl.magisterka.model.EnergyTelemetry;

import java.time.Instant;
import java.util.Random;

public class HeaterSimulator implements DeviceSimulator {

    private final String deviceId;
    private final String deviceType = "heater";
    private final double voltageV;
    private final Random random = new Random();

    private DeviceState state = DeviceState.OFF;

    public HeaterSimulator(String deviceId, double voltageV) {
        this.deviceId = deviceId;
        this.voltageV = voltageV;
    }

    // sterowanie z MQTT
    public synchronized void startHeating() {
        state = DeviceState.ON;
    }

    public synchronized void stopHeating() {
        state = DeviceState.OFF;
    }

    /** Reset state for isolated 1B (simulator control/reset). */
    public synchronized void reset() {
        state = DeviceState.OFF;
    }

    public synchronized DeviceState getState() {
        return state;
    }

    @Override
    public String deviceId() {
        return deviceId;
    }

    @Override
    public String deviceType() {
        return deviceType;
    }

    @Override
    public synchronized EnergyTelemetry nextTelemetry(long simTimeMs, Instant ts) {

        double powerW;

        if (state == DeviceState.ON) {
            // 1500–2000W + lekki szum
            powerW = 1500 + random.nextDouble() * 500;
            return new EnergyTelemetry(
                    deviceId,
                    deviceType,
                    ts,
                    simTimeMs,
                    powerW,
                    voltageV,
                    DeviceState.ON,
                    DeviceMode.NORMAL
            );
        }

        // OFF / standby
        powerW = 1.0 + random.nextDouble(); // ~1–2W
        return new EnergyTelemetry(
                deviceId,
                deviceType,
                ts,
                simTimeMs,
                powerW,
                voltageV,
                DeviceState.OFF,
                DeviceMode.STANDBY
        );
    }
}
