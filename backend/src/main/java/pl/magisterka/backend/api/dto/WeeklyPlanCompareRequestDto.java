package pl.magisterka.backend.api.dto;

import jakarta.validation.constraints.NotBlank;

public class WeeklyPlanCompareRequestDto {
    @NotBlank(message = "scenarioId required")
    public String scenarioId;
    public int weeks = 4;          // default 28 dni
    public long seed = 1;
    public long durationSeconds = 120; // real-time safety timeout
}