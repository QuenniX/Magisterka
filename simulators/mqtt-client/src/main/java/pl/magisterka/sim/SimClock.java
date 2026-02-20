package pl.magisterka.sim;

public class SimClock {
    private final long speedFactor; // ile ms symulacji na 1 ms real
    private final long startRealMs;
    private final long startSimMs;

    private SimClock(long speedFactor, long startRealMs, long startSimMs) {
        this.speedFactor = speedFactor;
        this.startRealMs = startRealMs;
        this.startSimMs = startSimMs;
    }

    /** start od simTime=0 */
    public static SimClock start(long speedFactor) {
        return new SimClock(speedFactor, System.currentTimeMillis(), 0L);
    }

    /** start od dowolnego simTime (przydatne później) */
    public static SimClock startAt(long speedFactor, long startSimMs) {
        return new SimClock(speedFactor, System.currentTimeMillis(), startSimMs);
    }

    /** aktualny czas symulacji w ms */
    public long nowMs() {
        long realDelta = System.currentTimeMillis() - startRealMs;
        return startSimMs + realDelta * speedFactor;
    }

    public long getSpeedFactor() {
        return speedFactor;
    }

    /** tworzy nowy zegar z simTime=0 (reset) */
    public SimClock reset() {
        return SimClock.start(speedFactor);
    }

    /** tworzy nowy zegar z nowym speedFactor, ale ten sam aktualny simTime jako punkt startu */
    public SimClock withSpeedFactor(long newSpeedFactor) {
        long currentSim = nowMs();
        return SimClock.startAt(newSpeedFactor, currentSim);
    }
}