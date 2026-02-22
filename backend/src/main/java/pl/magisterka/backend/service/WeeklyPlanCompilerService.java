package pl.magisterka.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.magisterka.backend.db.DeviceScheduleEntity;
import pl.magisterka.backend.db.DeviceScheduleRepository;
import pl.magisterka.backend.db.WeeklyPlanRuleEntity;
import pl.magisterka.backend.db.WeeklyPlanRuleRepository;
import pl.magisterka.backend.model.CommandType;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class WeeklyPlanCompilerService {

    private final WeeklyPlanRuleRepository planRepo;
    private final DeviceScheduleRepository scheduleRepo;

    public WeeklyPlanCompilerService(WeeklyPlanRuleRepository planRepo, DeviceScheduleRepository scheduleRepo) {
        this.planRepo = planRepo;
        this.scheduleRepo = scheduleRepo;
    }

    @Transactional
    public void compileScenario(String scenarioId) {
        // usuń stare schedule dla scenario
        List<DeviceScheduleEntity> old = scheduleRepo.findByScenarioId(scenarioId);
        scheduleRepo.deleteAll(old);

        List<WeeklyPlanRuleEntity> rules = planRepo.findByScenarioId(scenarioId)
                .stream()
                .filter(WeeklyPlanRuleEntity::isEnabled)
                .collect(Collectors.toList());

        for (WeeklyPlanRuleEntity r : rules) {
            String tz = r.getTimezone() != null ? r.getTimezone() : "Europe/Warsaw";
            Set<String> dows = parseDows(r.getDaysOfWeek());

            if (r.getKind() == WeeklyPlanRuleEntity.Kind.EVENT) {
                // washer START o from
                LocalTime t = LocalTime.parse(r.getFromTime());
                saveSchedule(scenarioId, "planRule:" + r.getId(),
                        r.getDeviceId(), r.getDeviceType(), CommandType.START,
                        cronAt(t, dows), tz);
                continue;
            }

            // WINDOW: ON at from, OFF at to (może przez północ)
            LocalTime from = LocalTime.parse(r.getFromTime());
            LocalTime to = LocalTime.parse(Objects.requireNonNull(r.getToTime(), "toTime required for WINDOW"));

            if (!crossesMidnight(from, to)) {
                saveSchedule(scenarioId, "planRule:" + r.getId(),
                        r.getDeviceId(), r.getDeviceType(), CommandType.START,
                        cronAt(from, dows), tz);

                saveSchedule(scenarioId, "planRule:" + r.getId(),
                        r.getDeviceId(), r.getDeviceType(), CommandType.STOP,
                        cronAt(to, dows), tz);
            } else {
                // start w dniu D o "from"
                saveSchedule(scenarioId, "planRule:" + r.getId(),
                        r.getDeviceId(), r.getDeviceType(), CommandType.START,
                        cronAt(from, dows), tz);

                // stop w dniu D+1 o "to" => przesuwamy listę dni
                Set<String> nextDows = shiftDowsToNextDay(dows);
                saveSchedule(scenarioId, "planRule:" + r.getId(),
                        r.getDeviceId(), r.getDeviceType(), CommandType.STOP,
                        cronAt(to, nextDows), tz);
            }
        }
    }

    private void saveSchedule(String scenarioId, String windowId,
                              String deviceId, String deviceType,
                              CommandType cmd, String cron, String timezone) {

        DeviceScheduleEntity e = new DeviceScheduleEntity();
        e.setScenarioId(scenarioId);
        e.setWindowId(windowId);
        e.setDeviceId(deviceId);
        e.setDeviceType(deviceType);
        e.setCmd(cmd);
        e.setCron(cron);
        e.setTimezone(timezone);
        e.setEnabled(true);

        scheduleRepo.save(e);
    }

    private static boolean crossesMidnight(LocalTime from, LocalTime to) {
        return to.isBefore(from) || to.equals(from); // equals => traktuj jako "przez północ / 24h"
    }

    // cron Spring: "sec min hour day-of-month month day-of-week"
    // np. "0 0 18 ? * MON,TUE"
    private static String cronAt(LocalTime t, Set<String> dows) {
        String dow = String.join(",", dows);
        return String.format("0 %d %d ? * %s", t.getMinute(), t.getHour(), dow);
    }

    private static Set<String> parseDows(String csv) {
        if (csv == null || csv.isBlank()) throw new IllegalArgumentException("daysOfWeek required");
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static final List<String> ORDER = List.of("MON","TUE","WED","THU","FRI","SAT","SUN");

    private static Set<String> shiftDowsToNextDay(Set<String> dows) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String d : dows) {
            int idx = ORDER.indexOf(d);
            if (idx < 0) throw new IllegalArgumentException("Invalid DOW: " + d);
            out.add(ORDER.get((idx + 1) % 7));
        }
        return out;
    }
}