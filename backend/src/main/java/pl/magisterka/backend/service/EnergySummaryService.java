package pl.magisterka.backend.service;

import org.springframework.stereotype.Service;
import pl.magisterka.backend.batch.TelemetryPoint;
import pl.magisterka.backend.db.EnergyTelemetryEntity;
import pl.magisterka.backend.db.EnergyTelemetryRepository;
import pl.magisterka.backend.db.WeeklyPlanRuleEntity;
import pl.magisterka.backend.db.WeeklyPlanRuleRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class EnergySummaryService {

    private final EnergyTelemetryRepository repo;
    private final WeeklyPlanRuleRepository weeklyPlanRuleRepository;
    private final SimulationAnalyticsEngine analyticsEngine;

    public EnergySummaryService(EnergyTelemetryRepository repo,
                                WeeklyPlanRuleRepository weeklyPlanRuleRepository,
                                SimulationAnalyticsEngine analyticsEngine) {
        this.repo = repo;
        this.weeklyPlanRuleRepository = weeklyPlanRuleRepository;
        this.analyticsEngine = analyticsEngine;
    }

    // ---------------------------------------
    // EXISTING (REAL TIME ts) - zostawiamy
    // ---------------------------------------

    public Map<String, Double> computeKwhPerDevice(Instant from, Instant to) {

        List<EnergyTelemetryEntity> rows =
                repo.findByTsBetweenOrderByDeviceIdAscTsAsc(from, to);

        Map<String, List<EnergyTelemetryEntity>> byDevice = new LinkedHashMap<>();
        for (EnergyTelemetryEntity e : rows) {
            if (e.getDeviceId() == null || e.getTs() == null || e.getPowerW() == null) continue;
            byDevice.computeIfAbsent(e.getDeviceId(), k -> new ArrayList<>()).add(e);
        }

        Map<String, Double> result = new LinkedHashMap<>();
        for (var entry : byDevice.entrySet()) {
            double wh = integrateWh(entry.getValue());
            result.put(entry.getKey(), round(wh / 1000.0, 4));
        }

        return result;
    }

    private double integrateWh(List<EnergyTelemetryEntity> points) {
        if (points.size() < 2) return 0.0;

        double wh = 0.0;

        for (int i = 1; i < points.size(); i++) {
            EnergyTelemetryEntity a = points.get(i - 1);
            EnergyTelemetryEntity b = points.get(i);

            Instant t1 = a.getTs();
            Instant t2 = b.getTs();
            Double p1 = a.getPowerW();
            Double p2 = b.getPowerW();

            if (t1 == null || t2 == null || p1 == null || p2 == null) continue;
            if (!t2.isAfter(t1)) continue;

            double hours = Duration.between(t1, t2).toMillis() / 3600000.0;
            double avgW = (p1 + p2) / 2.0;

            wh += avgW * hours;
        }

        return wh;
    }

    private static double round(double v, int places) {
        double m = Math.pow(10, places);
        return Math.round(v * m) / m;
    }

    public List<pl.magisterka.backend.api.dto.EnergyDailyDto.DayKwhDto> computeDailyKwh(String deviceId, Instant from, Instant to) {

        var points = repo.findByDeviceIdAndTsBetweenOrderByTsAsc(deviceId, from, to);

        if (points.size() < 2) return List.of();

        Map<LocalDate, Double> whPerDay = new LinkedHashMap<>();

        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1);
            var b = points.get(i);

            if (a.getTs() == null || b.getTs() == null || a.getPowerW() == null || b.getPowerW() == null) continue;
            if (!b.getTs().isAfter(a.getTs())) continue;

            Instant t1 = a.getTs();
            Instant t2 = b.getTs();
            double p1 = a.getPowerW();
            double p2 = b.getPowerW();

            Instant cursor = t1;

            while (cursor.isBefore(t2)) {
                LocalDate day = cursor.atZone(ZoneOffset.UTC).toLocalDate();
                Instant dayEnd = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

                Instant segmentEnd = dayEnd.isBefore(t2) ? dayEnd : t2;

                double hours = java.time.Duration.between(cursor, segmentEnd).toMillis() / 3600000.0;
                double avgW = (p1 + p2) / 2.0;

                whPerDay.merge(day, avgW * hours, Double::sum);

                cursor = segmentEnd;
            }
        }

        return whPerDay.entrySet().stream()
                .map(e -> new pl.magisterka.backend.api.dto.EnergyDailyDto.DayKwhDto(
                        e.getKey().toString(),
                        round((e.getValue() / 1000.0), 4)
                ))
                .toList();
    }

    // ---------------------------------------
    // EXPERIMENT (SIM TIME) – Study 1A: fixed grid + sample-and-hold (deterministic)
    // ---------------------------------------

    public Map<String, Double> computeKwhPerDeviceForExperiment(long experimentId) {
        var stats = repo.findExperimentStats(experimentId);
        if (stats == null || stats.getMinSim() == null || stats.getMaxSim() == null) {
            return new LinkedHashMap<>();
        }
        long from = stats.getMinSim();
        long to = stats.getMaxSim();
        if (to <= from) return new LinkedHashMap<>();
        ExperimentMetricsResult res = analyticsEngine.computeExperimentMetrics(experimentId, from, to, null);
        return res.getKwhPerDevice();
    }

    public Map<String, Double> computeKwhPerDeviceForExperimentBetweenSim(long experimentId, long fromSimMs, long toSimMs) {
        return computeKwhPerDeviceForExperimentBetweenSim(experimentId, fromSimMs, toSimMs, null);
    }

    public Map<String, Double> computeKwhPerDeviceForExperimentBetweenSim(long experimentId, long fromSimMs, long toSimMs, Integer stepSimMsOverride) {
        return computeKwhPerDeviceForExperimentBetweenSim(experimentId, fromSimMs, toSimMs, stepSimMsOverride, null);
    }

    /** When allowedDeviceIds is non-null and non-empty, only those devices are included (1B isolated). */
    public Map<String, Double> computeKwhPerDeviceForExperimentBetweenSim(long experimentId, long fromSimMs, long toSimMs, Integer stepSimMsOverride, Set<String> allowedDeviceIds) {
        if (toSimMs <= fromSimMs) return new LinkedHashMap<>();
        ExperimentMetricsResult res = analyticsEngine.computeExperimentMetrics(experimentId, fromSimMs, toSimMs, null, stepSimMsOverride, allowedDeviceIds);
        return res.getKwhPerDevice();
    }

    public Map<String, Object> computeExperimentMeta(long experimentId) {
        var stats = repo.findExperimentStats(experimentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experimentId", experimentId);
        if (stats == null || stats.getSamples() == null || stats.getSamples() == 0) {
            out.put("startSimTimeMs", null);
            out.put("endSimTimeMs", null);
            out.put("durationMs", null);
            out.put("peakDevicePowerW", 0.0);
            out.put("peakTotalPowerW", 0.0);
            out.put("samples", 0L);
            out.put("totalKwh", 0.0);
            out.put("avgPowerW", 0.0);
            out.put("peakToAvgRatio", 0.0);
            return out;
        }
        long fromSimMs = stats.getMinSim();
        long toSimMs = stats.getMaxSim();
        ExperimentMetricsResult res = analyticsEngine.computeExperimentMetrics(experimentId, fromSimMs, toSimMs, null);
        out.put("startSimTimeMs", fromSimMs);
        out.put("endSimTimeMs", toSimMs);
        putMetaFromResult(out, res, null);
        return out;
    }

    public Map<String, Object> computeExperimentMetaBetweenSim(long experimentId, long fromSimMs, long toSimMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experimentId", experimentId);
        out.put("startSimTimeMs", fromSimMs);
        out.put("endSimTimeMs", toSimMs);
        if (toSimMs <= fromSimMs) {
            out.put("durationMs", 0L);
            out.put("samples", 0L);
            out.put("totalKwh", 0.0);
            out.put("peakDevicePowerW", 0.0);
            out.put("peakTotalPowerW", 0.0);
            out.put("avgPowerW", 0.0);
            out.put("peakToAvgRatio", 0.0);
            out.put("firstSimMsInData", fromSimMs);
            out.put("lastSimMsInData", toSimMs);
            out.put("dataRangeWarning", false);
            return out;
        }
        ExperimentMetricsResult res = analyticsEngine.computeExperimentMetrics(experimentId, fromSimMs, toSimMs, null, null);
        putMetaFromResult(out, res, null);
        return out;
    }

    /** Same as above with optional step override and targets (for missingKwhPerDevice in 1A). */
    public Map<String, Object> computeExperimentMetaBetweenSim(long experimentId, long fromSimMs, long toSimMs, Integer stepSimMsOverride, Integer durationSecondsReal, Map<String, Double> targetKwhPerDevice) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experimentId", experimentId);
        out.put("startSimTimeMs", fromSimMs);
        out.put("endSimTimeMs", toSimMs);
        if (toSimMs <= fromSimMs) {
            out.put("durationMs", 0L);
            out.put("samples", 0L);
            out.put("totalKwh", 0.0);
            out.put("peakDevicePowerW", 0.0);
            out.put("peakTotalPowerW", 0.0);
            out.put("avgPowerW", 0.0);
            out.put("peakToAvgRatio", 0.0);
            out.put("firstSimMsInData", fromSimMs);
            out.put("lastSimMsInData", toSimMs);
            out.put("dataRangeWarning", false);
            out.put("noDataInRange", false);
            out.put("requestedFromSimMs", fromSimMs);
            out.put("requestedToSimMs", toSimMs);
            out.put("effectiveFromSimMs", fromSimMs);
            out.put("effectiveToSimMs", toSimMs);
            out.put("analysisWindowSimMs", 0L);
            if (durationSecondsReal != null) out.put("analysisWindowRealSeconds", durationSecondsReal);
            return out;
        }
        ExperimentMetricsResult res = analyticsEngine.computeExperimentMetrics(experimentId, fromSimMs, toSimMs, targetKwhPerDevice, stepSimMsOverride, null);
        putMetaFromResult(out, res, durationSecondsReal);
        return out;
    }

    /**
     * Same as above; when allowedDeviceIds is non-null and non-empty, metrics are computed only for those devices (1B isolated: planned devices only).
     */
    public Map<String, Object> computeExperimentMetaBetweenSim(long experimentId, long fromSimMs, long toSimMs, Integer stepSimMsOverride, Integer durationSecondsReal, Map<String, Double> targetKwhPerDevice, Set<String> allowedDeviceIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experimentId", experimentId);
        out.put("startSimTimeMs", fromSimMs);
        out.put("endSimTimeMs", toSimMs);
        if (toSimMs <= fromSimMs) {
            out.put("durationMs", 0L);
            out.put("samples", 0L);
            out.put("totalKwh", 0.0);
            out.put("peakDevicePowerW", 0.0);
            out.put("peakTotalPowerW", 0.0);
            out.put("avgPowerW", 0.0);
            out.put("peakToAvgRatio", 0.0);
            out.put("firstSimMsInData", fromSimMs);
            out.put("lastSimMsInData", toSimMs);
            out.put("dataRangeWarning", false);
            out.put("noDataInRange", false);
            out.put("requestedFromSimMs", fromSimMs);
            out.put("requestedToSimMs", toSimMs);
            out.put("effectiveFromSimMs", fromSimMs);
            out.put("effectiveToSimMs", toSimMs);
            out.put("analysisWindowSimMs", 0L);
            if (durationSecondsReal != null) out.put("analysisWindowRealSeconds", durationSecondsReal);
            return out;
        }
        ExperimentMetricsResult res = analyticsEngine.computeExperimentMetrics(experimentId, fromSimMs, toSimMs, targetKwhPerDevice, stepSimMsOverride, allowedDeviceIds);
        putMetaFromResult(out, res, durationSecondsReal);
        return out;
    }

    public Map<String, Object> computeExperimentMetaBetweenSim(long experimentId, long fromSimMs, long toSimMs, Integer stepSimMsOverride, Integer durationSecondsReal) {
        return computeExperimentMetaBetweenSim(experimentId, fromSimMs, toSimMs, stepSimMsOverride, durationSecondsReal, null);
    }

    public Map<String, Object> computeExperimentMetaBetweenSim(long experimentId, long fromSimMs, long toSimMs, Integer stepSimMsOverride) {
        return computeExperimentMetaBetweenSim(experimentId, fromSimMs, toSimMs, stepSimMsOverride, null, null);
    }

    /** Distinct device_id present in telemetry for experiment (for 1B debug transparency). */
    public List<String> findDistinctDeviceIdsByExperimentId(long experimentId) {
        return repo.findDistinctDeviceIdsByExperimentId(experimentId);
    }

    /** Build meta map from analytics result (e.g. for batch mode). */
    public Map<String, Object> buildMetaFromResult(ExperimentMetricsResult res) {
        Map<String, Object> out = new LinkedHashMap<>();
        putMetaFromResult(out, res, null);
        return out;
    }

    private void putMetaFromResult(Map<String, Object> out, ExperimentMetricsResult res, Integer durationSecondsReal) {
        out.put("durationMs", res.getDurationMs());
        out.put("samples", res.getSamples());
        double peakDevice = res.getPeakDevicePowerW().values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        out.put("peakDevicePowerW", round(peakDevice, 1));
        out.put("peakTotalPowerW", round(res.getPeakTotalPowerW(), 1));
        out.put("totalWh", res.getTotalWh());
        out.put("totalKwh", round(res.getTotalKwh(), 4));
        out.put("avgPowerW", round(res.getAvgPowerW(), 1));
        double peakToAvg = res.getAvgPowerW() > 0 ? res.getPeakTotalPowerW() / res.getAvgPowerW() : 0.0;
        out.put("peakToAvgRatio", round(peakToAvg, 2));
        out.put("peakToAvgRatioRaw", peakToAvg);
        out.put("durationHours", res.getDurationMs() / 3600000.0);
        out.put("firstSimMsInData", res.getFirstSimMsInData());
        out.put("lastSimMsInData", res.getLastSimMsInData());
        out.put("dataRangeWarning", res.isDataRangeWarning());
        out.put("requestedFromSimMs", res.getRequestedFromSimMs());
        out.put("requestedToSimMs", res.getRequestedToSimMs());
        out.put("effectiveFromSimMs", res.getEffectiveFromSimMs());
        out.put("effectiveToSimMs", res.getEffectiveToSimMs());
        long startSimMs = res.getEffectiveFromSimMs();
        out.put("startSimMs", startSimMs);
        Long achieved = res.getExperimentTimeToAchieveSimMs();
        out.put("experimentTimeToAchieveSimMs", achieved);
        out.put("timeToAchieveDeltaSimMs", achieved != null ? achieved - startSimMs : null);
        out.put("windowDeltaSimMs", res.getAnalysisWindowSimMs());
        out.put("noDataInRange", res.isNoDataInRange());
        out.put("diagnosticMessage", res.getDiagnosticMessage());
        out.put("analysisWindowSimMs", res.getAnalysisWindowSimMs());
        out.put("missingKwhPerDevice", res.getMissingKwhPerDevice());
        out.put("gridSteps", res.getGridSteps());
        out.put("devicesSeen", res.getDevicesSeen());
        out.put("rowsUsed", res.getRowsUsed());
        long analysisGridSteps = res.getGridSteps();
        out.put("analysisGridSteps", analysisGridSteps);
        if (analysisGridSteps > 500_000) {
            out.put("analysisWarning", "Duża liczba kroków siatki (" + analysisGridSteps + "). Rozważ zwiększenie kroku analizy (np. 5s), aby przyspieszyć obliczenia.");
        } else {
            out.put("analysisWarning", null);
        }
        out.put("chartStepSimMs", res.getChartStepSimMs());
        out.put("chartPoints", res.getChartPoints() != null ? res.getChartPoints() : List.of());
        if (durationSecondsReal != null) out.put("analysisWindowRealSeconds", durationSecondsReal);
    }

    /** Exposes the resampling engine for run-until-target (deterministic time-to-achieve). */
    public ExperimentMetricsResult computeExperimentMetricsWithTargets(
            long experimentId, long fromSimMs, long toSimMs, Map<String, Double> targetKwhPerDevice, Integer stepSimMsOverride) {
        return analyticsEngine.computeExperimentMetrics(experimentId, fromSimMs, toSimMs, targetKwhPerDevice, stepSimMsOverride);
    }

    /**
     * Recompute analytics on existing telemetry (same experiment, no new simulation).
     * Returns meta + chartPoints for UI (downsampled, max 5000 points).
     */
    public Map<String, Object> recompute(long experimentId, long fromSimMs, long toSimMs, int stepSimMs) {
        ExperimentMetricsResult res = analyticsEngine.computeExperimentMetrics(
                experimentId, fromSimMs, toSimMs, null, stepSimMs);
        Map<String, Object> meta = new LinkedHashMap<>();
        putMetaFromResult(meta, res, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", meta);
        out.put("chartPoints", res.getChartPoints() != null ? res.getChartPoints() : List.of());
        out.put("chartStepSimMs", res.getChartStepSimMs());
        return out;
    }

    // ---------------------------------------
    // ✅ NEW: Waste energy (outside weekly plan)
    // ---------------------------------------

    public double computeEnergyOutsidePlanKwh(long experimentId, String scenarioId, long fromSim, long toSim) {
        var rules = weeklyPlanRuleRepository.findByScenarioIdAndEnabledTrue(scenarioId);
        return computeEnergyOutsidePlanKwh(experimentId, fromSim, toSim, rules);
    }

    /**
     * Waste (energy outside plan) for devices that are in the plan only.
     * Uses provided rules (e.g. snapshot for reproducibility).
     */
    public double computeEnergyOutsidePlanKwh(long experimentId, long fromSim, long toSim, List<WeeklyPlanRuleEntity> rules) {
        var rows = repo.findByExperimentIdAndSimRangeOrderByDeviceIdAscSimTimeAsc(experimentId, fromSim, toSim);
        if (rows.isEmpty()) return 0.0;

        Set<String> plannedDeviceIds = rules.stream()
                .map(WeeklyPlanRuleEntity::getDeviceId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        double wasteWh = 0.0;

        for (int i = 1; i < rows.size(); i++) {
            var prev = rows.get(i - 1);
            var curr = rows.get(i);

            if (!Objects.equals(prev.getDeviceId(), curr.getDeviceId())) continue;
            if (!plannedDeviceIds.contains(curr.getDeviceId())) continue;

            long t1 = nz(prev.getSimTimeMs());
            long t2 = nz(curr.getSimTimeMs());
            if (t2 <= t1) continue;

            double p1 = nz(prev.getPowerW());
            double p2 = nz(curr.getPowerW());

            double dtHours = (t2 - t1) / 3600000.0;
            double energyWh = ((p1 + p2) / 2.0) * dtHours;

            long mid = (t1 + t2) / 2;
            boolean inside = isInsideWeeklyPlan(curr.getDeviceId(), mid, rules);

            if (!inside) wasteWh += energyWh;
        }

        return wasteWh / 1000.0;
    }

    /**
     * Waste (energy outside plan) from in-memory batch telemetry. Planned devices only; same logic as DB version.
     */
    public double computeWasteFromTelemetry(List<TelemetryPoint> telemetry, List<WeeklyPlanRuleEntity> rules) {
        if (telemetry == null || telemetry.isEmpty() || rules == null) return 0.0;

        Set<String> plannedDeviceIds = rules.stream()
                .map(WeeklyPlanRuleEntity::getDeviceId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        Map<String, List<TelemetryPoint>> byDevice = new LinkedHashMap<>();
        for (TelemetryPoint p : telemetry) {
            if (p.deviceId() == null) continue;
            byDevice.computeIfAbsent(p.deviceId(), k -> new ArrayList<>()).add(p);
        }
        for (List<TelemetryPoint> list : byDevice.values()) {
            list.sort(Comparator.comparingLong(TelemetryPoint::simTimeMs));
        }

        double wasteWh = 0.0;
        for (String deviceId : plannedDeviceIds) {
            List<TelemetryPoint> pts = byDevice.get(deviceId);
            if (pts == null || pts.size() < 2) continue;
            for (int i = 1; i < pts.size(); i++) {
                TelemetryPoint prev = pts.get(i - 1);
                TelemetryPoint curr = pts.get(i);
                long t1 = prev.simTimeMs();
                long t2 = curr.simTimeMs();
                if (t2 <= t1) continue;
                double p1 = prev.powerW();
                double p2 = curr.powerW();
                double dtHours = (t2 - t1) / 3600000.0;
                double energyWh = ((p1 + p2) / 2.0) * dtHours;
                long mid = (t1 + t2) / 2;
                if (!isInsideWeeklyPlan(deviceId, mid, rules)) wasteWh += energyWh;
            }
        }
        return wasteWh / 1000.0;
    }

    private boolean isInsideWeeklyPlan(String deviceId, long simTimeMs, List<WeeklyPlanRuleEntity> rules) {
        long dayMs = 24L * 60L * 60L * 1000L;
        long weekMs = 7L * dayMs;

        long weekPos = mod(simTimeMs, weekMs);
        int dayIndex = (int) (weekPos / dayMs); // 0..6
        long dayPosMs = weekPos % dayMs;

        for (var r : rules) {
            if (!Objects.equals(deviceId, r.getDeviceId())) continue;
            if (!dowMatches(r.getDaysOfWeek(), dayIndex)) continue;

            // EVENT (washer) ignorujemy w waste (na start)
            if (r.getKind() == WeeklyPlanRuleEntity.Kind.EVENT) continue;

            long fromMs = parseHmToMs(r.getFromTime());
            long toMs = parseHmToMs(r.getToTime());

            if (fromMs <= toMs) {
                if (dayPosMs >= fromMs && dayPosMs < toMs) return true;
            } else {
                // przez północ
                if (dayPosMs >= fromMs || dayPosMs < toMs) return true;
            }
        }

        return false;
    }

    private boolean dowMatches(String csv, int dayIndex) {
        if (csv == null) return false;
        String dow = switch (dayIndex) {
            case 0 -> "MON";
            case 1 -> "TUE";
            case 2 -> "WED";
            case 3 -> "THU";
            case 4 -> "FRI";
            case 5 -> "SAT";
            case 6 -> "SUN";
            default -> "";
        };
        for (String p : csv.split(",")) {
            if (dow.equalsIgnoreCase(p.trim())) return true;
        }
        return false;
    }

    private long parseHmToMs(String hm) {
        if (hm == null || hm.isBlank()) return 0L;
        String[] parts = hm.trim().split(":");
        int h = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);
        return (h * 60L + m) * 60L * 1000L;
    }

    private long mod(long a, long m) {
        long r = a % m;
        return r < 0 ? r + m : r;
    }

    private long nz(Long v) { return v == null ? 0L : v; }
    private double nz(Double v) { return v == null ? 0.0 : v; }
}