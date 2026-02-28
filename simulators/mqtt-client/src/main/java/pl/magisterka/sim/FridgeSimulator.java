package pl.magisterka.sim;

import pl.magisterka.model.DeviceMode;
import pl.magisterka.model.DeviceState;
import pl.magisterka.model.EnergyTelemetry;

import java.time.Instant;
import java.util.Random;

public class FridgeSimulator implements DeviceSimulator {

    private final String deviceId;
    private final String deviceType = "fridge";
    private final double voltageV;

    private final Random random = new Random();

    private DeviceState state = DeviceState.OFF;

    // czasy cyklu (symulacyjne)
    private long stateUntilSimMs = 0;

    /** Reset state for isolated 1B (simulator control/reset). */
    public synchronized void reset() {
        state = DeviceState.OFF;
        stateUntilSimMs = 0;
    }

    private static final long IDLE_MIN = 30_000;
    private static final long IDLE_MAX = 90_000;

    private static final long COOL_MIN = 60_000;
    private static final long COOL_MAX = 150_000;

    public FridgeSimulator(String deviceId, double voltageV) {
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

    @Override
    public synchronized EnergyTelemetry nextTelemetry(long simTimeMs, Instant ts) {

        // pierwsze ustawienie timera
        if (stateUntilSimMs == 0) {
            scheduleNext(simTimeMs);
        }

        // zmiana stanu gdy minął czas
        if (simTimeMs >= stateUntilSimMs) {
            state = (state == DeviceState.ON) ? DeviceState.OFF : DeviceState.ON;
            scheduleNext(simTimeMs);
        }

        double powerW;

        if (state == DeviceState.ON) {
            // COOLING 100–150W + lekki szum
            powerW = 100 + random.nextDouble() * 50;
        } else {
            // IDLE ~2W
            powerW = 2 + random.nextDouble();
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

    private void scheduleNext(long simTimeMs) {
        long duration;

        if (state == DeviceState.ON) {
            duration = COOL_MIN + random.nextLong(COOL_MAX - COOL_MIN);
        } else {
            duration = IDLE_MIN + random.nextLong(IDLE_MAX - IDLE_MIN);
        }

        stateUntilSimMs = simTimeMs + duration;
    }
}
