package pl.magisterka.backend.db;

import jakarta.persistence.*;
import pl.magisterka.backend.model.ExperimentType;

import java.time.Instant;

@Entity
@Table(name = "experiment")
public class ExperimentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExperimentType type;

    @Column(name = "start_sim_time")
    private Long startSimTime;

    @Column(name = "end_sim_time")
    private Long endSimTime;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Long seed = 1L;

    /** JSON snapshot of weekly plan rules at experiment start (reproducibility for 1B). */
    @Column(name = "weekly_plan_snapshot_json", columnDefinition = "TEXT")
    private String weeklyPlanSnapshotJson;

    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }

    public ExperimentEntity() {}

    // --- getters/setters ---
    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ExperimentType getType() { return type; }
    public void setType(ExperimentType type) { this.type = type; }

    public Long getStartSimTime() { return startSimTime; }
    public void setStartSimTime(Long startSimTime) { this.startSimTime = startSimTime; }

    public Long getEndSimTime() { return endSimTime; }
    public void setEndSimTime(Long endSimTime) { this.endSimTime = endSimTime; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getWeeklyPlanSnapshotJson() { return weeklyPlanSnapshotJson; }
    public void setWeeklyPlanSnapshotJson(String weeklyPlanSnapshotJson) { this.weeklyPlanSnapshotJson = weeklyPlanSnapshotJson; }
}