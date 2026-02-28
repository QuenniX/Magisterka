package pl.magisterka.sim;

import pl.magisterka.model.DeviceMode;
import pl.magisterka.model.DeviceState;
import pl.magisterka.model.EnergyTelemetry;

import java.time.Instant;
import java.util.List;

public class WasherSimulator implements DeviceSimulator {

    public enum WasherState {
        IDLE,
        RUNNING
    }

    private enum Phase {
        FILL,
        HEAT,
        WASH,
        SPIN,
        END
    }

    private static class Step {
        final Phase phase;
        final long durationMs; // czas w symulacji

        Step(Phase phase, long durationMs) {
            this.phase = phase;
            this.durationMs = durationMs;
        }
    }

    private final String deviceId;
    private final String deviceType = "washer";
    private final double voltageV;

    // program w czasie symulacji (łatwo potem zmienić / dodać programy)
    private final List<Step> program = List.of(
            new Step(Phase.FILL, 10_000),
            new Step(Phase.HEAT, 25_000),
            new Step(Phase.WASH, 35_000),
            new Step(Phase.SPIN, 15_000),
            new Step(Phase.END, 5_000)
    );

    private WasherState washerState = WasherState.IDLE;

    private int stepIndex = 0;
    private long stepStartSimMs = 0;

    public WasherSimulator(String deviceId, double voltageV) {
        this.deviceId = deviceId;
        this.voltageV = voltageV;
    }

    // sterowanie (pod MQTT cmd)
    public synchronized void startCycle(long simTimeMs) {
        if (washerState == WasherState.RUNNING) return;
        washerState = WasherState.RUNNING;
        stepIndex = 0;
        stepStartSimMs = simTimeMs;
    }

    public synchronized void stopCycle() {
        washerState = WasherState.IDLE;
        stepIndex = 0;
        stepStartSimMs = 0;
    }

    /** Reset state for isolated 1B (simulator control/reset). */
    public synchronized void reset() {
        stopCycle();
    }

    public synchronized WasherState getWasherState() {
        return washerState;
    }

    public synchronized String getCurrentPhaseName() {
        if (washerState == WasherState.IDLE) {
            return null; // wtedy pole "phase" zniknie z JSON (NON_NULL)
        }
        return program.get(stepIndex).phase.name();
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

        // IDLE = standby
        if (washerState == WasherState.IDLE) {
            return new EnergyTelemetry(
                    deviceId,
                    deviceType,
                    ts,
                    simTimeMs,
                    1.5, // standby ~1–2W
                    voltageV,
                    DeviceState.OFF,
                    DeviceMode.STANDBY
            );
        }

        // RUNNING
        Step step = program.get(stepIndex);
        long elapsed = simTimeMs - stepStartSimMs;

        // przejście do kolejnej fazy
        if (elapsed >= step.durationMs) {
            stepIndex++;
            stepStartSimMs = simTimeMs;

            if (stepIndex >= program.size()) {
                // koniec cyklu -> wróć do IDLE
                stopCycle();
                return new EnergyTelemetry(
                        deviceId,
                        deviceType,
                        ts,
                        simTimeMs,
                        1.5,
                        voltageV,
                        DeviceState.OFF,
                        DeviceMode.STANDBY
                );
            }

            step = program.get(stepIndex);
            elapsed = 0;
        }

        double powerW = powerFor(step.phase, elapsed);

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

    private double powerFor(Phase phase, long elapsedMs) {
        return switch (phase) {
            case FILL -> 20.0 + noise(5.0);                    // elektrozawór/pompa
            case HEAT -> 1800.0 + noise(200.0);                // grzałka (piki)
            case WASH -> 250.0 + oscillation(elapsedMs, 350.0, 80.0); // pranie
            case SPIN -> 700.0 + oscillation(elapsedMs, 900.0, 120.0); // wirowanie
            case END -> 5.0 + noise(2.0);                      // końcówka
        };
    }

    private double noise(double amplitude) {
        return (Math.random() - 0.5) * 2.0 * amplitude;
    }

    private double oscillation(long elapsedMs, double base, double amplitude) {
        // prosta fala trójkątna 0..1..0 co 2 sekundy symulacji
        double t = (elapsedMs % 2000) / 2000.0; // 0..1
        double wave = (t < 0.5) ? (t * 2.0) : (2.0 - t * 2.0); // 0..1..0
        return base + (wave - 0.5) * 2.0 * amplitude;
    }
}
