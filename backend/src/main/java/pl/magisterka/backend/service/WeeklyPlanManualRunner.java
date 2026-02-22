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
public class WeeklyPlanManualRunner {

    private final WeeklyPlanRuleRepository weeklyPlanRuleRepository;
    private final SimTimeService simTimeService;
    private final MqttCommandPublisher mqtt;

    private Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Map<String, Boolean> lastOn = new ConcurrentHashMap<>();
    private final Set<String> firedEventKeys = ConcurrentHashMap.newKeySet();

    public WeeklyPlanManualRunner(
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

        long experimentId = experiment.getId();
        long seed = experiment.getSeed() != null ? experiment.getSeed() : 1L;

        List<WeeklyPlanRuleEntity> rules = weeklyPlanRuleRepository.findByScenarioIdAndEnabledTrue(scenarioId);

        // RESET
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
        thread = new Thread(() -> loop(experimentId, rules, seed), "WeeklyPlanManualRunner");
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

    private void loop(long experimentId, List<WeeklyPlanRuleEntity> rules, long seed) {

        Map<String, List<WeeklyPlanRuleEntity>> byDevice = new LinkedHashMap<>();
        for (var r : rules) byDevice.computeIfAbsent(r.getDeviceId(), k -> new ArrayList<>()).add(r);

        while (running.get()) {
            try {
                long simMs = simTimeService.getCurrentSimTimeMs(experimentId);

                long dayMs = 24L * 60L * 60L * 1000L;
                long weekMs = 7L * dayMs;

                long weekPos = simMs % weekMs;
                if (weekPos < 0) weekPos += weekMs;

                int dayIndex = (int) (weekPos / dayMs);               // 0..6
                int minuteOfDay = (int) ((weekPos % dayMs) / 60000L); // 0..1439

                long weekIndex = simMs / weekMs; // do seed/key

                // WINDOW devices with jitter
                for (var entry : byDevice.entrySet()) {
                    String deviceId = entry.getKey();
                    List<WeeklyPlanRuleEntity> devRules = entry.getValue();
                    String deviceType = devRules.get(0).getDeviceType();

                    boolean shouldBeOn = false;

                    for (var r : devRules) {
                        if (r.getKind() != WeeklyPlanRuleEntity.Kind.WINDOW) continue;
                        if (!dowMatches(r.getDaysOfWeek(), dayIndex)) continue;

                        int from = parseHmToMinute(r.getFromTime());
                        int to = parseHmToMinute(r.getToTime());

                        Random rnd = new Random(mix(seed, deviceId, weekIndex, dayIndex, from, to));

                        int jitterOn = randBetween(rnd, -5, 10);
                        int jitterOff = randBetween(rnd, 0, 15);

                        boolean forgetOff = rnd.nextDouble() < 0.02;
                        if (forgetOff) jitterOff += 60;

                        int fromJ = clampMinute(from + jitterOn);
                        int toJ = clampMinute(to + jitterOff);

                        if (isInsideWindow(minuteOfDay, fromJ, toJ)) {
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

                // EVENT devices (washer) with jitter/skip
                for (var entry : byDevice.entrySet()) {
                    String deviceId = entry.getKey();
                    for (var r : entry.getValue()) {
                        if (r.getKind() != WeeklyPlanRuleEntity.Kind.EVENT) continue;
                        if (!dowMatches(r.getDaysOfWeek(), dayIndex)) continue;

                        int at = parseHmToMinute(r.getFromTime());

                        Random rnd = new Random(mix(seed, deviceId, weekIndex, dayIndex, at, 0));
                        int jitterStart = randBetween(rnd, -10, 20);
                        boolean skip = rnd.nextDouble() < 0.05;

                        int atJ = clampMinute(at + jitterStart);

                        if (minuteOfDay != atJ) continue;
                        if (skip) continue;

                        String key = deviceId + "|" + weekIndex + "|" + dayIndex + "|" + atJ;
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

    private static long mix(long seed, String deviceId, long weekIndex, int dayIndex, int a, int b) {
        long h = seed;
        h = 31 * h + deviceId.hashCode();
        h = 31 * h + weekIndex;
        h = 31 * h + dayIndex;
        h = 31 * h + a;
        h = 31 * h + b;
        return h;
    }

    private static int randBetween(Random rnd, int min, int max) {
        if (max < min) return min;
        return min + rnd.nextInt((max - min) + 1);
    }

    private static int clampMinute(int m) {
        if (m < 0) return 0;
        if (m > 1439) return 1439;
        return m;
    }

    private static boolean isInsideWindow(int nowMin, int fromMin, int toMin) {
        if (fromMin <= toMin) return nowMin >= fromMin && nowMin < toMin;
        return nowMin >= fromMin || nowMin < toMin;
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