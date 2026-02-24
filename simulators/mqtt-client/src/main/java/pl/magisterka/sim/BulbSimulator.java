package pl.magisterka.sim;

import pl.magisterka.model.DeviceMode;
import pl.magisterka.model.DeviceState;
import pl.magisterka.model.EnergyTelemetry;

import java.time.Instant;

public class BulbSimulator implements DeviceSimulator {

    private final String deviceId;
    private final String deviceType = "bulb";
    private final double voltageV;
    private final double basePowerW;

    private DeviceState state = DeviceState.OFF; // domyślnie OFF

    public BulbSimulator(String deviceId, double voltageV, double basePowerW) {
        this.deviceId = deviceId;
        this.voltageV = voltageV;
        this.basePowerW = basePowerW;
    }

    public synchronized void turnOn() {
        state = DeviceState.ON;
    }

    public synchronized void turnOff() {
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
        DeviceMode mode;

        if (state == DeviceState.ON) {
            double fluctuation = (Math.random() - 0.5) * 1.0; // +/- 0.5W
            powerW = basePowerW + fluctuation;
            mode = DeviceMode.NORMAL;
        } else {
            powerW = 0.0; // zgaszona żarówka
            mode = DeviceMode.STANDBY;
        }

        return new EnergyTelemetry(
                deviceId,
                deviceType,
                ts,
                simTimeMs,
                powerW,
                voltageV,
                state,
                mode
        );
    }
}
