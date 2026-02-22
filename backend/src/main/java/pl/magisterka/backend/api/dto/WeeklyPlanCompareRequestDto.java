package pl.magisterka.backend.api.dto;

public class WeeklyPlanCompareRequestDto {
    public String scenarioId;
    public int weeks = 4;          // default 28 dni
    public long seed = 1;
    public long durationSeconds = 120; // real-time safety timeout
}