import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  LineChart,
  Line,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  CartesianGrid,
  ResponsiveContainer,

} from "recharts";

type TelemetryPoint = {
  deviceId: string;
  deviceType: string;
  powerW: number;
  voltageV: number;
  state: string;
  mode: string | null;
  ts: string;
};

// days może być mapą albo listą — robimy "any" i parsujemy bezpiecznie
type EnergyDailyDto = {
  deviceId: string;
  from: string;
  to: string;
  days: any;
};

import { apiFetch } from "../api/http";
import { formatPowerW, formatEnergyKWh, formatDuration, formatTsAgo } from "../utils/formatPower";
import { deviceTypeLabel, stateLabel } from "../utils/labels";

type RangeKey = "15m" | "1h" | "24h";

function rangeToMs(r: RangeKey) {
  switch (r) {
    case "15m":
      return 15 * 60 * 1000;
    case "1h":
      return 60 * 60 * 1000;
    case "24h":
      return 24 * 60 * 60 * 1000;
  }
}

function safeSumEnergy(days: any): number {
  let total = 0;

  if (!days) return 0;

  // Case A: lista
  if (Array.isArray(days)) {
    for (const item of days) {
      if (typeof item === "number") {
        total += item;
        continue;
      }
      // próbujemy typowych pól
      const v =
        item?.kwh ??
        item?.value ??
        item?.energyKwh ??
        item?.kWh ??
        item?.kwhValue ??
        0;
      total += Number(v) || 0;
    }
    return total;
  }

  // Case B: mapa/obiekt
  if (typeof days === "object") {
    for (const v of Object.values(days)) {
      total += Number(v) || 0;
    }
    return total;
  }

  // fallback
  return Number(days) || 0;
}

const ACTIVE_THRESHOLD_W = 1;

function calculateRangeMetrics(points: TelemetryPoint[], thresholdW: number) {
  if (!points || points.length < 2) {
    return {
      activeSeconds: 0,
      totalSeconds: 0,
      activePct: null as number | null,
      averagePowerW: null as number | null,
    };
  }

  let totalDt = 0; // ms
  let weightedPowerSum = 0; // W*ms
  let activeDt = 0; // ms

  for (let i = 0; i < points.length - 1; i++) {
    const a = points[i];
    const b = points[i + 1];

    const ta = new Date(a.ts).getTime();
    const tb = new Date(b.ts).getTime();
    const dt = tb - ta;

    if (!Number.isFinite(dt) || dt <= 0) continue;

    const pa = Number(a.powerW) || 0;
    const pb = Number(b.powerW) || 0;

    const pAvgSegment = (pa + pb) / 2;

    totalDt += dt;
    weightedPowerSum += pAvgSegment * dt;

    if (pAvgSegment > thresholdW) {
      activeDt += dt;
    }
  }

  const averagePowerW = totalDt > 0 ? weightedPowerSum / totalDt : null;

  const totalSeconds = Math.round(totalDt / 1000);
  const activeSeconds = Math.round(activeDt / 1000);
  const activePct =
    totalDt > 0 ? (activeDt / totalDt) * 100 : null;

  return {
    activeSeconds,
    totalSeconds,
    activePct,
    averagePowerW,
  };
}
type HourBucket = {
  hourStart: Date;   // początek godziny (lokalny)
  label: string;     // np. "17:00"
  kwh: number;       // energia w tej godzinie
};

function startOfHour(d: Date) {
  const x = new Date(d);
  x.setMinutes(0, 0, 0);
  return x;
}

function addHours(d: Date, h: number) {
  const x = new Date(d);
  x.setHours(x.getHours() + h);
  return x;
}

/**
 * Agreguje energię (kWh) do kubełków godzinowych.
 * Liczymy trapezami między kolejnymi próbkami i rozdzielamy segmenty na godziny, jeśli przecinają granice.
 */
function energyPerHour(points: TelemetryPoint[]): HourBucket[] {
  if (!points || points.length < 2) return [];

  // map: epochMs(hourStart) -> kWh
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
    const pAvg = (p0 + p1) / 2; // W

    // segment [t0, t1] może przeciąć granice godzin — dzielimy
    let segStart = new Date(t0);
    const segEnd = new Date(t1);

    while (segStart < segEnd) {
      const hourStart = startOfHour(segStart);
      const nextHour = addHours(hourStart, 1);

      const chunkEnd = segEnd < nextHour ? segEnd : nextHour;
      const chunkMs = chunkEnd.getTime() - segStart.getTime();
      if (chunkMs <= 0) break;

      // energia = Pavg[W] * time[h] -> Wh -> kWh
      const chunkHours = chunkMs / 3600000;
      const chunkKwh = (pAvg * chunkHours) / 1000;

      const key = hourStart.getTime();
      buckets.set(key, (buckets.get(key) ?? 0) + chunkKwh);

      segStart = chunkEnd;
    }
  }

  const result = [...buckets.entries()]
    .sort((x, y) => x[0] - y[0])
    .map(([ms, kwh]) => {
      const d = new Date(ms);
      const label = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
      return { hourStart: d, label, kwh };
    });

  return result;
}



export default function DeviceDetailsPage() {
  const navigate = useNavigate();
  const { deviceType, deviceId } = useParams();

  const [data, setData] = useState<TelemetryPoint[]>([]);
  const [error, setError] = useState<string | null>(null);

  const [range, setRange] = useState<RangeKey>("1h");
  const [live, setLive] = useState<boolean>(true);

  const [energyKwh, setEnergyKwh] = useState<number | null>(null);
  const [energyError, setEnergyError] = useState<string | null>(null);
  const getWindow = () => {
    const now = new Date();
    const from = new Date(now.getTime() - rangeToMs(range));
    return { from, to: now };
  };

  const fetchTelemetryRange = async () => {
    if (!deviceId) return;

    try {
      setError(null);

      const { from, to } = getWindow();
      const params = new URLSearchParams();
      params.set("deviceId", deviceId);
      params.set("from", from.toISOString());
      params.set("to", to.toISOString());

      const res = await apiFetch(`/api/telemetry/range?${params.toString()}`, {
        cache: "no-store",
      });
      if (!res.ok) {
        const text = await res.text().catch(() => "");
        throw new Error(`Telemetry failed: ${res.status} ${res.statusText} ${text}`);
      }

      const json: TelemetryPoint[] = await res.json();
      setData(json);
    } catch (e: any) {
      setError(e?.message ?? "Failed to fetch telemetry");
    }
  };

  const fetchEnergy = async () => {
    if (!deviceId) return;

    try {
      setEnergyError(null);

      const { from, to } = getWindow();
      const params = new URLSearchParams();
      params.set("deviceId", deviceId);
      params.set("from", from.toISOString());
      params.set("to", to.toISOString());

      const res = await apiFetch(`/api/energy/daily?${params.toString()}`, {
        cache: "no-store",
      });
      if (!res.ok) {
        const text = await res.text().catch(() => "");
        throw new Error(`Energy failed: ${res.status} ${res.statusText} ${text}`);
      }

      const dto: EnergyDailyDto = await res.json();
      const total = safeSumEnergy(dto.days);
      setEnergyKwh(total);
    } catch (e: any) {
      setEnergyError(e?.message ?? "Failed to fetch energy");
      setEnergyKwh(null);
    }
  };

  // fetch przy wejściu / zmianie range / zmianie device
  useEffect(() => {
    fetchTelemetryRange();
    fetchEnergy();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deviceId, range]);

  // live refresh
  useEffect(() => {
    if (!live) return;
    const interval = setInterval(() => {
      fetchTelemetryRange();
      fetchEnergy();
    }, 5000);
    return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live, deviceId, range]);

    const sortedData = useMemo(() => {
      return [...data].sort(
        (x, y) => new Date(x.ts).getTime() - new Date(y.ts).getTime()
      );
    }, [data]);

    const metrics = useMemo(
      () => calculateRangeMetrics(sortedData, ACTIVE_THRESHOLD_W),
      [sortedData]
    );

    const chartData = useMemo(
      () =>
        sortedData.map((p) => ({
          ts: new Date(p.ts).toLocaleTimeString(),
          powerW: p.powerW,
        })),
      [sortedData]
    );

    const peakPower = useMemo(
      () => sortedData.reduce((max, p) => Math.max(max, p.powerW ?? 0), 0),
      [sortedData]
    );

    const last = sortedData.length > 0 ? sortedData[sortedData.length - 1] : null;

    const hourly = useMemo(() => energyPerHour(sortedData), [sortedData]);

    const hourlyTotal = useMemo(
      () => hourly.reduce((sum, b) => sum + (b.kwh || 0), 0),
      [hourly]
    );

    const hourlyPeak = useMemo(
      () => hourly.reduce((max, b) => Math.max(max, b.kwh || 0), 0),
      [hourly]
    );


  const lastTsAgo = last ? formatTsAgo(last.ts).ago : "—";
  const rangeLabels: Record<RangeKey, string> = { "15m": "15 min", "1h": "1 h", "24h": "24 h" };

  return (
    <div style={{ padding: 20, fontFamily: "system-ui, sans-serif", maxWidth: 1200, margin: "0 auto" }}>
      <button
        onClick={() => navigate("/devices")}
        style={{ padding: "8px 12px", borderRadius: "var(--radius-sm)", border: "1px solid var(--border)", cursor: "pointer", marginBottom: 16 }}
      >
        ← Wstecz
      </button>

      <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap", marginBottom: 8 }}>
        <h1 style={{ margin: 0, fontSize: "1.5rem" }}>{deviceId}</h1>
        <span
          style={{
            padding: "6px 12px",
            borderRadius: 999,
            fontSize: "0.85rem",
            fontWeight: 600,
            background: last?.state?.toUpperCase() === "ON" ? "rgba(63,185,80,0.2)" : "rgba(248,81,73,0.2)",
            color: last?.state?.toUpperCase() === "ON" ? "var(--success)" : "var(--danger)",
          }}
        >
          {stateLabel(last?.state)}
        </span>
      </div>
      <div style={{ fontSize: "0.9rem", color: "var(--text-muted)", marginBottom: 4 }}>
        Typ: {deviceTypeLabel(deviceType)}
      </div>
      <div style={{ fontSize: "0.85rem", color: "var(--text-muted)" }}>
        Ostatnia telemetria: {lastTsAgo}
      </div>
      <button
        onClick={() => navigate(`/devices/${deviceType}/${deviceId}/compare`)}
        style={{ marginTop: 12, padding: "8px 12px", borderRadius: "var(--radius-sm)", border: "1px solid var(--border)", cursor: "pointer" }}
      >
        Porównaj zakresy
      </button>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(140px, 1fr))", gap: 12, marginTop: 24, marginBottom: 20 }}>
        <div className="app-card" style={{ padding: 12 }}>
          <div style={{ fontSize: "0.75rem", color: "var(--text-muted)", marginBottom: 4 }}>Moc teraz</div>
          <div style={{ fontWeight: 700 }}>{formatPowerW(last?.powerW)}</div>
        </div>
        <div className="app-card" style={{ padding: 12 }}>
          <div style={{ fontSize: "0.75rem", color: "var(--text-muted)", marginBottom: 4 }}>Średnia (zakres)</div>
          <div style={{ fontWeight: 700 }}>{formatPowerW(metrics.averagePowerW)}</div>
        </div>
        <div className="app-card" style={{ padding: 12 }}>
          <div style={{ fontSize: "0.75rem", color: "var(--text-muted)", marginBottom: 4 }}>Szczyt (zakres)</div>
          <div style={{ fontWeight: 700 }}>{formatPowerW(peakPower)}</div>
        </div>
        <div className="app-card" style={{ padding: 12 }}>
          <div style={{ fontSize: "0.75rem", color: "var(--text-muted)", marginBottom: 4 }}>Energia (zakres)</div>
          <div style={{ fontWeight: 700 }}>{formatEnergyKWh(energyKwh)}</div>
        </div>
        <div className="app-card" style={{ padding: 12 }}>
          <div style={{ fontSize: "0.75rem", color: "var(--text-muted)", marginBottom: 4 }}>Czas aktywny</div>
          <div style={{ fontWeight: 700 }}>{formatDuration(metrics.activeSeconds)}</div>
        </div>
      </div>

      <div className="app-card" style={{ padding: "12px 16px", marginBottom: 16, display: "flex", flexWrap: "wrap", alignItems: "center", gap: 12 }}>
        <span style={{ fontSize: "0.9rem", color: "var(--text-muted)" }}>Zakres:</span>

        {(["15m", "1h", "24h"] as RangeKey[]).map((r) => (
          <button
            key={r}
            onClick={() => setRange(r)}
            style={{
              padding: "6px 10px",
              borderRadius: "999px",
              border: "1px solid gray",
              cursor: "pointer",
              opacity: range === r ? 1 : 0.7,
              fontWeight: range === r ? 700 : 400,
            }}
          >
            {r === "15m" ? "15 min" : r === "1h" ? "1 h" : "24 h"}
          </button>
        ))}

        <span style={{ marginLeft: 8, fontSize: "0.9rem", color: "var(--text-muted)" }}>Na żywo:</span>
        <button onClick={() => setLive((v) => !v)} style={{ padding: "6px 12px", borderRadius: 999, border: "1px solid var(--border)", cursor: "pointer", fontWeight: 600 }}>
          {live ? "Pauza" : "Włącz"}
        </button>
        <button onClick={() => { fetchTelemetryRange(); fetchEnergy(); }} style={{ padding: "6px 12px", borderRadius: 999, border: "1px solid var(--border)", cursor: "pointer" }}>
          Odśwież
        </button>
      </div>

      {error && (
        <div className="app-card" style={{ padding: 12, marginBottom: 16, borderColor: "var(--danger)", background: "rgba(248,81,73,0.08)" }}>
          <b>Błąd:</b> {error}
        </div>
      )}

      <div className="app-card" style={{ padding: 16, marginBottom: 20, height: 360 }}>
        <h3 style={{ margin: "0 0 12px", fontSize: "1rem" }}>Moc</h3>
        {chartData.length === 0 ? (
          <div style={{ color: "var(--text-muted)" }}>Brak danych w zakresie. Zmień zakres albo uruchom symulator.</div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="ts" minTickGap={30} />
              <YAxis />
              <Tooltip />
              <Line type="monotone" dataKey="powerW" dot={false} />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
      <div className="app-card" style={{ padding: 16 }}>
        <h3 style={{ margin: "0 0 12px", fontSize: "1rem" }}>Energia na godzinę</h3>

        {hourly.length === 0 ? (
          <div style={{ opacity: 0.8 }}>
            Brak danych do agregacji godzinowej.
          </div>
        ) : (
          <>
            <div style={{ marginBottom: 12, fontSize: "0.9rem", color: "var(--text-muted)" }}>
              Suma: <b>{formatEnergyKWh(hourlyTotal)}</b> · Szczyt godziny: <b>{formatEnergyKWh(hourlyPeak)}</b>
            </div>
            <div style={{ height: 260 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={hourly}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="label" />
                  <YAxis tickFormatter={(v) => Number(v).toFixed(4)} />
                  <Tooltip
                    formatter={(value: number) =>
                      `${Number(value).toFixed(4)} kWh`
                    }
                  />
                  <Bar dataKey="kwh" fill="var(--accent)" radius={[6, 6, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </>
        )}

      </div>

      <div style={{ marginTop: 12, fontSize: "0.85rem", color: "var(--text-muted)" }}>
        15 min / 1 h do testów na żywo, 24 h do “live”, porównań i raportów.
      </div>
    </div>
  );
}
