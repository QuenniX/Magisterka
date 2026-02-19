export type TelemetryPoint = {
  deviceId: string;
  deviceType: string;
  powerW: number;
  voltageV: number;
  state: string;
  mode: string | null;
  ts: string;
};

export const ACTIVE_THRESHOLD_W = 1;

export type RangeMetrics = {
  peakPower: number;
  averagePowerW: number | null;
  activeSeconds: number;
  activePct: number | null;
  totalEnergyKwh: number;
};

export function computeRangeMetrics(
  points: TelemetryPoint[],
  thresholdW = ACTIVE_THRESHOLD_W
): RangeMetrics {
  if (!points || points.length < 2) {
    return {
      peakPower: 0,
      averagePowerW: null,
      activeSeconds: 0,
      activePct: null,
      totalEnergyKwh: 0,
    };
  }

  let totalDtMs = 0;
  let weightedPowerSum = 0; // W*ms
  let activeDtMs = 0;
  let energyWh = 0;
  let peak = 0;

  for (let i = 0; i < points.length - 1; i++) {
    const a = points[i];
    const b = points[i + 1];

    const t1 = new Date(a.ts).getTime();
    const t2 = new Date(b.ts).getTime();
    const dt = t2 - t1;
    if (!Number.isFinite(dt) || dt <= 0) continue;

    const p1 = Number(a.powerW) || 0;
    const p2 = Number(b.powerW) || 0;
    const pAvg = (p1 + p2) / 2;

    peak = Math.max(peak, p1, p2);

    totalDtMs += dt;
    weightedPowerSum += pAvg * dt;

    if (pAvg > thresholdW) activeDtMs += dt;

    const hours = dt / 3600000;
    energyWh += pAvg * hours;
  }

  return {
    peakPower: peak,
    averagePowerW: totalDtMs > 0 ? weightedPowerSum / totalDtMs : null,
    activeSeconds: Math.round(activeDtMs / 1000),
    activePct: totalDtMs > 0 ? (activeDtMs / totalDtMs) * 100 : null,
    totalEnergyKwh: energyWh / 1000,
  };
}

export function sortByTsAsc(points: TelemetryPoint[]) {
  return [...points].sort(
    (x, y) => new Date(x.ts).getTime() - new Date(y.ts).getTime()
  );
}
export type HourBucket = {
  hourStartMs: number; // epoch ms startu godziny (lokalnie)
  label: string;       // "13:00"
  kwh: number;
};

function startOfHourMs(ms: number) {
  const d = new Date(ms);
  d.setMinutes(0, 0, 0);
  return d.getTime();
}

export function computeEnergyPerHour(points: TelemetryPoint[]): HourBucket[] {
  if (!points || points.length < 2) return [];

  const buckets = new Map<number, number>();

  for (let i = 0; i < points.length - 1; i++) {
    const a = points[i];
    const b = points[i + 1];

    const t0 = new Date(a.ts).getTime();
    const t1 = new Date(b.ts).getTime();
    const dt = t1 - t0;
    if (!Number.isFinite(dt) || dt <= 0) continue;

    const p0 = Number(a.powerW) || 0;
    const p1 = Number(b.powerW) || 0;
    const pAvg = (p0 + p1) / 2;

    let segStart = t0;
    const segEnd = t1;

    while (segStart < segEnd) {
      const hourStart = startOfHourMs(segStart);
      const nextHour = hourStart + 3600000;

      const chunkEnd = Math.min(segEnd, nextHour);
      const chunkMs = chunkEnd - segStart;
      if (chunkMs <= 0) break;

      const chunkHours = chunkMs / 3600000;
      const chunkKwh = (pAvg * chunkHours) / 1000;

      buckets.set(hourStart, (buckets.get(hourStart) ?? 0) + chunkKwh);
      segStart = chunkEnd;
    }
  }

  return [...buckets.entries()]
    .sort((x, y) => x[0] - y[0])
    .map(([hourStartMs, kwh]) => ({
      hourStartMs,
      label: new Date(hourStartMs).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
      kwh,
    }));
}
