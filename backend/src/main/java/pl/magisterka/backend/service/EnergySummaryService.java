package pl.magisterka.backend.service;

import org.springframework.stereotype.Service;
import pl.magisterka.backend.db.EnergyTelemetryEntity;
import pl.magisterka.backend.db.EnergyTelemetryRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class EnergySummaryService {

    private final EnergyTelemetryRepository repo;

    public EnergySummaryService(EnergyTelemetryRepository repo) {
        this.repo = repo;
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

        // dzielimy energię na dni (UTC)
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

            // iterujemy po “kawałkach” ograniczonych granicami dni
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
    // EXPERIMENT (SIM TIME sim_time_ms)
    // ---------------------------------------

    /**
     * Energia per device dla eksperymentu liczona po sim_time_ms (SimClock).
     * Zwraca kWh per deviceId.
     */
    public Map<String, Double> computeKwhPerDeviceForExperiment(long experimentId) {
        List<EnergyTelemetryEntity> rows =
                repo.findByExperimentIdOrderByDeviceIdAscSimTimeAsc(experimentId);

        return computeKwhPerDeviceFromRows(rows);
    }

    /**
     * ✅ NOWE: Energia per device liczona tylko w zakresie sim_time_ms [fromSimMs..toSimMs]
     * (to będzie Twoje "energy-to-achieve").
     */
    public Map<String, Double> computeKwhPerDeviceForExperimentBetweenSim(long experimentId, long fromSimMs, long toSimMs) {
        if (toSimMs <= fromSimMs) return new LinkedHashMap<>();

        List<EnergyTelemetryEntity> rows =
                repo.findByExperimentIdAndSimRangeOrderByDeviceIdAscSimTimeAsc(experimentId, fromSimMs, toSimMs);

        return computeKwhPerDeviceFromRows(rows);
    }

    private Map<String, Double> computeKwhPerDeviceFromRows(List<EnergyTelemetryEntity> rows) {
        Map<String, List<EnergyTelemetryEntity>> byDevice = new LinkedHashMap<>();
        for (EnergyTelemetryEntity e : rows) {
            if (e.getDeviceId() == null || e.getSimTimeMs() == null || e.getPowerW() == null) continue;
            byDevice.computeIfAbsent(e.getDeviceId(), k -> new ArrayList<>()).add(e);
        }

        Map<String, Double> result = new LinkedHashMap<>();
        for (var entry : byDevice.entrySet()) {
            double wh = integrateWhSim(entry.getValue());
            result.put(entry.getKey(), round(wh / 1000.0, 4));
        }
        return result;
    }

    /**
     * Metadane eksperymentu: start/end/duration + peak + samples
     * (dla CAŁEGO eksperymentu)
     */
    public Map<String, Object> computeExperimentMeta(long experimentId) {
        var stats = repo.findExperimentStats(experimentId);

        Map<String, Object> out = new LinkedHashMap<>();
        if (stats == null || stats.getSamples() == null || stats.getSamples() == 0) {
            out.put("experimentId", experimentId);
            out.put("startSimTimeMs", null);
            out.put("endSimTimeMs", null);
            out.put("durationMs", null);
            out.put("peakPowerW", 0.0);
            out.put("samples", 0L);
            return out;
        }

        Long start = stats.getMinSim();
        Long end = stats.getMaxSim();
        Long duration = (start != null && end != null) ? (end - start) : null;

        Double peakDevicePower = stats.getPeakDevicePowerW();
        Double peakTotalPower = stats.getPeakTotalPowerW();
        Long samples = stats.getSamples();

        double avgPowerW = 0.0;
        double peakToAvgRatio = 0.0;

        if (duration != null && duration > 0) {
            double durationHours = duration / 1000.0 / 3600.0;

            Map<String, Double> perDevice = computeKwhPerDeviceForExperiment(experimentId);
            double totalKwh = perDevice.values().stream().mapToDouble(Double::doubleValue).sum();

            avgPowerW = durationHours > 0 ? (totalKwh * 1000.0 / durationHours) : 0.0;
            peakToAvgRatio = avgPowerW > 0 ? (peakTotalPower / avgPowerW) : 0.0;

            out.put("totalKwh", round(totalKwh, 4));
        }

        out.put("experimentId", experimentId);
        out.put("startSimTimeMs", start);
        out.put("endSimTimeMs", end);
        out.put("durationMs", duration);
        out.put("peakDevicePowerW", peakDevicePower);
        out.put("peakTotalPowerW", peakTotalPower);
        out.put("samples", samples);
        out.put("avgPowerW", round(avgPowerW, 2));
        out.put("peakToAvgRatio", round(peakToAvgRatio, 2));
        return out;
    }

    /**
     * ✅ NOWE: meta liczone tylko w oknie sim_time_ms [fromSimMs..toSimMs]
     * To jest "summary at achieve".
     */
    public Map<String, Object> computeExperimentMetaBetweenSim(long experimentId, long fromSimMs, long toSimMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experimentId", experimentId);
        out.put("startSimTimeMs", fromSimMs);
        out.put("endSimTimeMs", toSimMs);

        long duration = Math.max(0, toSimMs - fromSimMs);
        out.put("durationMs", duration);

        List<EnergyTelemetryEntity> rows =
                repo.findByExperimentIdAndSimRangeOrderByDeviceIdAscSimTimeAsc(experimentId, fromSimMs, toSimMs);

        if (rows.isEmpty()) {
            out.put("samples", 0L);
            out.put("totalKwh", 0.0);
            out.put("peakDevicePowerW", 0.0);
            out.put("peakTotalPowerW", 0.0);
            out.put("avgPowerW", 0.0);
            out.put("peakToAvgRatio", 0.0);
            return out;
        }

        // samples
        out.put("samples", (long) rows.size());

        // peak device power (max pojedynczego rekordu)
        double peakDevice = rows.stream()
                .map(EnergyTelemetryEntity::getPowerW)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);
        out.put("peakDevicePowerW", peakDevice);

        // peak total power: sum(powerW) per bucket (1s)
        Map<Long, Double> sumPerBucket = new HashMap<>();
        for (EnergyTelemetryEntity e : rows) {
            if (e.getSimTimeMs() == null || e.getPowerW() == null) continue;
            long bucket = e.getSimTimeMs() / 1000L;
            sumPerBucket.merge(bucket, e.getPowerW(), Double::sum);
        }
        double peakTotal = sumPerBucket.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        out.put("peakTotalPowerW", peakTotal);

        // total energy in window
        Map<String, Double> perDevice = computeKwhPerDeviceFromRows(rows);
        double totalKwh = perDevice.values().stream().mapToDouble(Double::doubleValue).sum();
        out.put("totalKwh", round(totalKwh, 4));

        // avgPower in window
        double avgPowerW = 0.0;
        if (duration > 0) {
            double hours = duration / 1000.0 / 3600.0;
            avgPowerW = hours > 0 ? (totalKwh * 1000.0 / hours) : 0.0;
        }
        out.put("avgPowerW", round(avgPowerW, 2));

        // ratio
        double ratio = avgPowerW > 0 ? (peakTotal / avgPowerW) : 0.0;
        out.put("peakToAvgRatio", round(ratio, 2));

        return out;
    }

    private double integrateWhSim(List<EnergyTelemetryEntity> points) {
        if (points.size() < 2) return 0.0;

        double wh = 0.0;

        for (int i = 1; i < points.size(); i++) {
            EnergyTelemetryEntity a = points.get(i - 1);
            EnergyTelemetryEntity b = points.get(i);

            Long t1 = a.getSimTimeMs();
            Long t2 = b.getSimTimeMs();
            Double p1 = a.getPowerW();
            Double p2 = b.getPowerW();

            if (t1 == null || t2 == null || p1 == null || p2 == null) continue;
            if (t2 <= t1) continue;

            double hours = (t2 - t1) / 3600000.0;
            double avgW = (p1 + p2) / 2.0;

            wh += avgW * hours;
        }

        return wh;
    }
}