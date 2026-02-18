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

}
