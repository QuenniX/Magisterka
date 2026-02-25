package pl.magisterka.backend.service;

public class RunUntilResult {
    private final boolean achieved;
    private final long startSimTimeMs;
    private final long endSimTimeMs;
    /** When achieved: deterministic sim time when last device reached target. Null when timeout. */
    private final Long achievedAtSimTimeMs;
    /** When timeout: effective end of data range (so summary does not include empty tail). */
    private final Long effectiveToSimMsWhenTimeout;

    public RunUntilResult(boolean achieved, long startSimTimeMs, long endSimTimeMs) {
        this(achieved, startSimTimeMs, endSimTimeMs, achieved ? endSimTimeMs : null, null);
    }

    public RunUntilResult(boolean achieved, long startSimTimeMs, long endSimTimeMs, Long achievedAtSimTimeMs) {
        this(achieved, startSimTimeMs, endSimTimeMs, achievedAtSimTimeMs, null);
    }

    public RunUntilResult(boolean achieved, long startSimTimeMs, long endSimTimeMs, Long achievedAtSimTimeMs, Long effectiveToSimMsWhenTimeout) {
        this.achieved = achieved;
        this.startSimTimeMs = startSimTimeMs;
        this.endSimTimeMs = endSimTimeMs;
        this.achievedAtSimTimeMs = achievedAtSimTimeMs;
        this.effectiveToSimMsWhenTimeout = effectiveToSimMsWhenTimeout;
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

    /** When achieved: sim time when target was reached. Null when timeout. */
    public Long getAchievedAtSimTimeMs() {
        return achievedAtSimTimeMs;
    }

    /** End of range for summary: achievement time when achieved, else effective end of data (or endSimTimeMs). */
    public long getSummaryEndSimTimeMs() {
        if (achieved && achievedAtSimTimeMs != null) return achievedAtSimTimeMs;
        if (effectiveToSimMsWhenTimeout != null) return effectiveToSimMsWhenTimeout;
        return endSimTimeMs;
    }

    public long getSimDurationMs() {
        return Math.max(0, endSimTimeMs - startSimTimeMs);
    }
}