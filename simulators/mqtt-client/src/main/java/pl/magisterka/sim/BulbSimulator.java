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

    public BulbSimulator(String deviceId, double voltageV, double basePowerW) {
        this.deviceId = deviceId;
        this.voltageV = voltageV;
        this.basePowerW = basePowerW;
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
    public EnergyTelemetry nextTelemetry(long simTimeMs) {
        double fluctuation = (Math.random() - 0.5) * 1.0; // +/- 0.5W
        double powerW = basePowerW + fluctuation;

        return new EnergyTelemetry(
                deviceId,
                deviceType,
                Instant.now(),
                simTimeMs,
                powerW,
                voltageV,
                DeviceState.ON,
                DeviceMode.NORMAL
        );
    }
}
