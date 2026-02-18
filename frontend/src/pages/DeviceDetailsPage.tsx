import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  LineChart,
  Line,
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

const API_BASE = "http://localhost:8080";

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

const ACTIVE_THRESHOLD_W = 1; // startowo: 1W (możesz potem wystawić w UI)

function formatDuration(totalSeconds: number) {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;

  if (h > 0) return `${h}h ${m}m ${sec}s`;
  if (m > 0) return `${m}m ${sec}s`;
  return `${sec}s`;
}

function calculateRangeMetrics(points: TelemetryPoint[], thresholdW: number) {
  if (!points || points.length < 2) {
    return { activeSeconds: 0, averagePowerW: null as number | null };
  }

  let totalDt = 0; // ms
  let weightedPowerSum = 0; // W*ms (trapezy)
  let activeDt = 0; // ms

  for (let i = 0; i < points.length - 1; i++) {
    const a = points[i];
    const b = points[i + 1];

    const ta = new Date(a.ts).getTime();
    const tb = new Date(b.ts).getTime();
    const dt = tb - ta;

    // zabezpieczenia (np. duplikaty / nieposortowane)
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

  return {
    activeSeconds: Math.round(activeDt / 1000),
    averagePowerW: averagePowerW === null ? null : averagePowerW,
  };
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

  const fmt = (n: number) => (Number.isFinite(n) ? n.toFixed(2) : "-");
  const fmtKwh = (n: number | null) =>
    n === null ? "-" : Number.isFinite(n) ? n.toFixed(4) : "-";

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
      const url = new URL(`${API_BASE}/api/telemetry/range`);
      url.searchParams.set("deviceId", deviceId);
      url.searchParams.set("from", from.toISOString());
      url.searchParams.set("to", to.toISOString());

      const res = await fetch(url.toString());
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
      const url = new URL(`${API_BASE}/api/energy/daily`);
      url.searchParams.set("deviceId", deviceId);
      url.searchParams.set("from", from.toISOString());
      url.searchParams.set("to", to.toISOString());

      const res = await fetch(url.toString());
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


  return (
    <div style={{ padding: "20px", fontFamily: "system-ui, sans-serif" }}>
      <button
        onClick={() => navigate("/devices")}
        style={{
          padding: "8px 12px",
          borderRadius: "8px",
          border: "1px solid gray",
          cursor: "pointer",
        }}
      >
        ← Back
      </button>

      <div style={{ marginTop: "12px" }}>
        <h1 style={{ margin: 0 }}>{deviceId}</h1>
        <div style={{ opacity: 0.8 }}>
          Type: <b>{deviceType}</b>
        </div>

        <div style={{ opacity: 0.85, marginTop: "6px" }}>
          Range: <b>{range}</b> • Peak: <b>{fmt(peakPower)}</b> W
          {last && (
            <>
              {" "}
              • Current: <b>{fmt(last.powerW)}</b> W • State: <b>{last.state}</b>
            </>
          )}
          {" "}
          • Avg: <b>{metrics.averagePowerW === null ? "-" : fmt(metrics.averagePowerW)}</b> W
          {" "}
          • Active: <b>{formatDuration(metrics.activeSeconds)}</b>
          {" "}
          • Energy: <b>{fmtKwh(energyKwh)}</b> kWh
        </div>
          </div> {/*
      {/* Controls */}
      <div
        style={{
          marginTop: "12px",
          display: "flex",
          gap: "10px",
          alignItems: "center",
          flexWrap: "wrap",
        }}
      >
        <span style={{ opacity: 0.8 }}>Range:</span>

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
            {r}
          </button>
        ))}

        <span style={{ marginLeft: "10px", opacity: 0.8 }}>Live:</span>
        <button
          onClick={() => setLive((v) => !v)}
          style={{
            padding: "6px 10px",
            borderRadius: "999px",
            border: "1px solid gray",
            cursor: "pointer",
            fontWeight: 700,
          }}
        >
          {live ? "Pause" : "Resume"}
        </button>

        <button
          onClick={() => {
            fetchTelemetryRange();
            fetchEnergy();
          }}
          style={{
            padding: "6px 10px",
            borderRadius: "999px",
            border: "1px solid gray",
            cursor: "pointer",
          }}
        >
          Refresh now
        </button>
      </div>

      {error && (
        <div
          style={{
            border: "1px solid #a00",
            padding: "10px",
            marginTop: "12px",
            borderRadius: "8px",
          }}
        >
          <b>Error:</b> {error}
        </div>
      )}

      <div
        style={{
          marginTop: "16px",
          border: "1px solid gray",
          borderRadius: "10px",
          padding: "12px",
          height: "360px",
          maxWidth: "1000px",
        }}
      >
        <h3 style={{ marginTop: 0 }}>Power (W)</h3>

        {chartData.length === 0 ? (
          <div style={{ opacity: 0.8 }}>
            Brak danych w zakresie. Zmień range albo uruchom symulator.
          </div>
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

      <div style={{ marginTop: "10px", opacity: 0.8 }}>
        Tip: 15m/1h do testów “live”, 24h do porównań i raportów.
      </div>
    </div>
  );
}
