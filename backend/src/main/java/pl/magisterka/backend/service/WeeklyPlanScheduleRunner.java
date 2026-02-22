package pl.magisterka.backend.service;

import org.springframework.stereotype.Service;
import pl.magisterka.backend.db.ExperimentEntity;
import pl.magisterka.backend.db.WeeklyPlanRuleEntity;
import pl.magisterka.backend.db.WeeklyPlanRuleRepository;
import pl.magisterka.backend.model.CommandType;
import pl.magisterka.backend.mqtt.MqttCommandPublisher;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WeeklyPlanScheduleRunner {

    private final WeeklyPlanRuleRepository weeklyPlanRuleRepository;
    private final SimTimeService simTimeService;
    private final MqttCommandPublisher mqtt;

    private Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Map<String, Boolean> lastOn = new ConcurrentHashMap<>();
    private final Set<String> firedEventKeys = ConcurrentHashMap.newKeySet();

    public WeeklyPlanScheduleRunner(
            WeeklyPlanRuleRepository weeklyPlanRuleRepository,
            SimTimeService simTimeService,
            MqttCommandPublisher mqtt
    ) {
        this.weeklyPlanRuleRepository = weeklyPlanRuleRepository;
        this.simTimeService = simTimeService;
        this.mqtt = mqtt;
    }

    public void start(ExperimentEntity experiment, String scenarioId) {
        stop();

        List<WeeklyPlanRuleEntity> rules = weeklyPlanRuleRepository.findByScenarioIdAndEnabledTrue(scenarioId);

        // RESET: STOP all devices from plan (avoid carryover)
        Map<String, String> deviceTypes = new LinkedHashMap<>();
        for (var r : rules) deviceTypes.put(r.getDeviceId(), r.getDeviceType());

        for (var e : deviceTypes.entrySet()) {
            try {
                mqtt.publishCommand(e.getValue(), e.getKey(), CommandType.STOP);
            } catch (Exception ignored) {}
            lastOn.put(e.getKey(), false);
        }
        firedEventKeys.clear();

        running.set(true);
        long experimentId = experiment.getId();
        thread = new Thread(() -> loop(experimentId, rules), "WeeklyPlanScheduleRunner");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ignored) {}
            thread = null;
        }
        lastOn.clear();
        firedEventKeys.clear();
    }

    private void loop(long experimentId, List<WeeklyPlanRuleEntity> rules) {

        // group rules per device
        Map<String, List<WeeklyPlanRuleEntity>> byDevice = new LinkedHashMap<>();
        for (var r : rules) byDevice.computeIfAbsent(r.getDeviceId(), k -> new ArrayList<>()).add(r);

        while (running.get()) {
            try {
                long simMs = simTimeService.getCurrentSimTimeMs(experimentId);

                long dayMs = 24L * 60L * 60L * 1000L;
                long weekMs = 7L * dayMs;

                long weekPos = simMs % weekMs;
                if (weekPos < 0) weekPos += weekMs;

                int dayIndex = (int) (weekPos / dayMs);          // 0..6
                int minuteOfDay = (int) ((weekPos % dayMs) / 60000L); // 0..1439

                // WINDOW devices: ideal state
                for (var entry : byDevice.entrySet()) {
                    String deviceId = entry.getKey();
                    List<WeeklyPlanRuleEntity> devRules = entry.getValue();
                    String deviceType = devRules.get(0).getDeviceType();

                    boolean shouldBeOn = false;

                    for (var r : devRules) {
                        if (r.getKind() != WeeklyPlanRuleEntity.Kind.WINDOW) continue;
                        if (!dowMatches(r.getDaysOfWeek(), dayIndex)) continue;

                        int fromMin = parseHmToMinute(r.getFromTime());
                        int toMin = parseHmToMinute(r.getToTime());

                        if (isInsideWindow(minuteOfDay, fromMin, toMin)) {
                            shouldBeOn = true;
                            break;
                        }
                    }

                    boolean isOn = lastOn.getOrDefault(deviceId, false);

                    if (shouldBeOn && !isOn) {
                        mqtt.publishCommand(deviceType, deviceId, CommandType.START);
                        lastOn.put(deviceId, true);
                    } else if (!shouldBeOn && isOn) {
                        mqtt.publishCommand(deviceType, deviceId, CommandType.STOP);
                        lastOn.put(deviceId, false);
                    }
                }

                // EVENT devices: fire once per (week/day/min)
                long weekIndex = simMs / (7L * 24 * 60 * 60 * 1000);
                for (var entry : byDevice.entrySet()) {
                    String deviceId = entry.getKey();
                    for (var r : entry.getValue()) {
                        if (r.getKind() != WeeklyPlanRuleEntity.Kind.EVENT) continue;
                        if (!dowMatches(r.getDaysOfWeek(), dayIndex)) continue;

                        int atMin = parseHmToMinute(r.getFromTime());
                        if (minuteOfDay != atMin) continue;

                        String key = deviceId + "|" + weekIndex + "|" + dayIndex + "|" + atMin;
                        if (firedEventKeys.add(key)) {
                            mqtt.publishCommand(r.getDeviceType(), deviceId, CommandType.START);
                        }
                    }
                }

                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.set(false);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isInsideWindow(int nowMin, int fromMin, int toMin) {
        if (fromMin <= toMin) return nowMin >= fromMin && nowMin < toMin;
        return nowMin >= fromMin || nowMin < toMin; // crosses midnight
    }

    private static int parseHmToMinute(String hm) {
        if (hm == null || hm.isBlank()) return 0;
        String[] p = hm.trim().split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private static boolean dowMatches(String csv, int dayIndex) {
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
        for (String s : csv.split(",")) {
            if (dow.equalsIgnoreCase(s.trim())) return true;
        }
        return false;
    }
}