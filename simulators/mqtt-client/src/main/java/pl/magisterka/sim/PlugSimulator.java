package pl.magisterka.sim;

import pl.magisterka.model.DeviceMode;
import pl.magisterka.model.DeviceState;
import pl.magisterka.model.EnergyTelemetry;

import java.time.Instant;
import java.util.Random;

public class PlugSimulator implements DeviceSimulator {

    private final String deviceId;
    private final String deviceType = "plug";
    private final double voltageV;
    private final Random random = new Random();

    private volatile DeviceState state = DeviceState.OFF;

    public PlugSimulator(String deviceId, double voltageV) {
        this.deviceId = deviceId;
        this.voltageV = voltageV;
    }

    @Override
    public String deviceId() {
        return deviceId;
    }

    @Override
    public String deviceType() {
        return deviceType;
    }

    /** Reset state for isolated 1B (simulator control/reset). */
    public synchronized void reset() {
        state = DeviceState.OFF;
    }

    @Override
    public EnergyTelemetry nextTelemetry(long simTimeMs, Instant ts) {

        // co jakiś czas zmiana stanu
        if (random.nextDouble() < 0.1) {
            state = (state == DeviceState.ON) ? DeviceState.OFF : DeviceState.ON;
        }

        double powerW = 0.0;

        if (state == DeviceState.ON) {
            powerW = 500 + random.nextDouble() * 1500; // 500–2000W
        }

        return new EnergyTelemetry(
                deviceId,
                deviceType,
                ts,
                simTimeMs,
                powerW,
                voltageV,
                state,
                (state == DeviceState.ON ? DeviceMode.NORMAL : DeviceMode.STANDBY)
        );

    }
}
