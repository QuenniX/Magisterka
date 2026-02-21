package pl.magisterka.backend.service;

public class RunUntilResult {
    private final boolean achieved;
    private final long startSimTimeMs;
    private final long endSimTimeMs;

    public RunUntilResult(boolean achieved, long startSimTimeMs, long endSimTimeMs) {
        this.achieved = achieved;
        this.startSimTimeMs = startSimTimeMs;
        this.endSimTimeMs = endSimTimeMs;
    }

    public boolean isAchieved() {
        return achieved;
    }

    public long getStartSimTimeMs() {
        return startSimTimeMs;
    }

    public long getEndSimTimeMs() {
        return endSimTimeMs;
    }

    public long getSimDurationMs() {
        return Math.max(0, endSimTimeMs - startSimTimeMs);
    }
}