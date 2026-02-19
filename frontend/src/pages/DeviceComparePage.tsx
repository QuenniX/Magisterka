import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { computeRangeMetrics, sortByTsAsc } from "../metrics/energyMetrics";
import type { TelemetryPoint } from "../metrics/energyMetrics";
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, CartesianGrid, ResponsiveContainer, Legend
} from "recharts";
import { computeEnergyPerHour } from "../metrics/energyMetrics";


const API_BASE = "http://localhost:8080";

function fmt(n: number | null, digits = 2) {
  if (n === null || !Number.isFinite(n)) return "-";
  return n.toFixed(digits);
}
function fmtKwh(n: number | null) {
  if (n === null || !Number.isFinite(n)) return "-";
  return n.toFixed(4);
}
function fmtPct(n: number | null) {
  if (n === null || !Number.isFinite(n)) return "-";
  return `${n.toFixed(1)}%`;
}

type Row = {
  key: "totalEnergyKwh" | "averagePowerW" | "peakPower" | "activeSeconds" | "activePct";
  label: string;
  render: (v: number | null) => string;
};

export default function DeviceComparePage() {
  const navigate = useNavigate();
  const { deviceType, deviceId } = useParams();
  const [hourly, setHourly] = useState<
    { hourStartMs: number; label: string; kwhA: number; kwhB: number }[]
  >([]);
  const [peakHourA, setPeakHourA] = useState<number>(0);
  const [peakHourB, setPeakHourB] = useState<number>(0);
  const [aFrom, setAFrom] = useState("");
  const [aTo, setATo] = useState("");
  const [bFrom, setBFrom] = useState("");
  const [bTo, setBTo] = useState("");

  const [aMetrics, setAMetrics] = useState<any>(null);
  const [bMetrics, setBMetrics] = useState<any>(null);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const rows: Row[] = useMemo(
    () => [
      { key: "totalEnergyKwh", label: "Total Energy (kWh)", render: fmtKwh },
      { key: "averagePowerW", label: "Average Power (W)", render: (v) => fmt(v, 2) },
      { key: "peakPower", label: "Peak Power (W)", render: (v) => fmt(v, 2) },
      { key: "activeSeconds", label: "Active Time (s)", render: (v) => (v === null ? "-" : String(Math.round(v))) },
      { key: "activePct", label: "Active %", render: fmtPct },
    ],
    []
  );

  async function fetchTelemetry(fromLocal: string, toLocal: string) {
    const url = new URL(`${API_BASE}/api/telemetry/range`);
    url.searchParams.set("deviceId", deviceId!);
    url.searchParams.set("from", new Date(fromLocal).toISOString());
    url.searchParams.set("to", new Date(toLocal).toISOString());

    const res = await fetch(url.toString());
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`Telemetry failed: ${res.status} ${res.statusText} ${text}`);
    }

    const pts: TelemetryPoint[] = await res.json();
    return sortByTsAsc(pts);
  }
function setPreset(ms: number) {
  const now = new Date();
  const bTo = now;
  const bFrom = new Date(now.getTime() - ms);

  const aTo = new Date(bFrom.getTime());
  const aFrom = new Date(aTo.getTime() - ms);

  const toLocal = (d: Date) => {
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };

  setBFrom(toLocal(bFrom));
  setBTo(toLocal(bTo));
  setAFrom(toLocal(aFrom));
  setATo(toLocal(aTo));
}

 async function doCompare() {
   if (!deviceId) return;

   if (!aFrom || !aTo || !bFrom || !bTo) {
     setErr("Uzupełnij wszystkie daty (A i B: from/to).");
     return;
   }

   const aFromMs = new Date(aFrom).getTime();
   const aToMs = new Date(aTo).getTime();
   const bFromMs = new Date(bFrom).getTime();
   const bToMs = new Date(bTo).getTime();

   if (!Number.isFinite(aFromMs) || !Number.isFinite(aToMs) || !Number.isFinite(bFromMs) || !Number.isFinite(bToMs)) {
     setErr("Nieprawidłowy format daty (datetime-local).");
     return;
   }
   if (aToMs <= aFromMs || bToMs <= bFromMs) {
     setErr("Zakres musi mieć: To > From (dla A i B).");
     return;
   }

   const aHours = (aToMs - aFromMs) / 3600000;
   const bHours = (bToMs - bFromMs) / 3600000;

   setLoading(true);
   setErr(null);

   // wyczyść wykresy/peak na start, żeby UI nie pokazywało starych danych w trakcie load
   setHourly([]);
   setPeakHourA(0);
   setPeakHourB(0);

   try {
     const [aPts, bPts] = await Promise.all([
       fetchTelemetry(aFrom, aTo),
       fetchTelemetry(bFrom, bTo),
     ]);

     // metryki podstawowe (trapezy, avg W, peak, active)
     const mA = computeRangeMetrics(aPts);
     const mB = computeRangeMetrics(bPts);

     // + normalizacja do kWh/h (fajne do raportu, szczególnie jak zakresy różne)
     const avgKwhPerHourA = aHours > 0 ? mA.totalEnergyKwh / aHours : 0;
     const avgKwhPerHourB = bHours > 0 ? mB.totalEnergyKwh / bHours : 0;

     // wrzuć do obiektu (żebyś mógł to łatwo dodać jako dodatkowy wiersz w tabeli)
     setAMetrics({ ...mA, durationHours: aHours, avgKwhPerHour: avgKwhPerHourA });
     setBMetrics({ ...mB, durationHours: bHours, avgKwhPerHour: avgKwhPerHourB });

     // hourly A/B
     const ha = computeEnergyPerHour(aPts);
     const hb = computeEnergyPerHour(bPts);

     // peak hour (kWh/h)
     setPeakHourA(ha.reduce((m, x) => Math.max(m, x.kwh), 0));
     setPeakHourB(hb.reduce((m, x) => Math.max(m, x.kwh), 0));

     // merge do datasetu wykresu A vs B
     const map = new Map<number, { hourStartMs: number; label: string; kwhA: number; kwhB: number }>();

     const mkLabel = (hourStartMs: number) =>
       new Date(hourStartMs).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

     for (const x of ha) {
       map.set(x.hourStartMs, {
         hourStartMs: x.hourStartMs,
         label: mkLabel(x.hourStartMs),
         kwhA: x.kwh,
         kwhB: 0,
       });
     }

     for (const x of hb) {
       const prev = map.get(x.hourStartMs);
       if (prev) {
         prev.kwhB = x.kwh;
       } else {
         map.set(x.hourStartMs, {
           hourStartMs: x.hourStartMs,
           label: mkLabel(x.hourStartMs),
           kwhA: 0,
           kwhB: x.kwh,
         });
       }
     }

     const merged = [...map.values()].sort((x, y) => x.hourStartMs - y.hourStartMs);
     setHourly(merged);
   } catch (e: any) {
     const msg =
       e?.message ||
       (typeof e === "string" ? e : "Compare failed (unknown error)");
     setErr(msg);

     setAMetrics(null);
     setBMetrics(null);
     setHourly([]);
     setPeakHourA(0);
     setPeakHourB(0);
   } finally {
     setLoading(false);
   }
 }



  return (
    <div style={{ padding: 20, fontFamily: "system-ui, sans-serif" }}>
      <button
        onClick={() => navigate(`/devices/${deviceType}/${deviceId}`)}
        style={{ padding: "8px 12px", borderRadius: 8, border: "1px solid gray", cursor: "pointer" }}
      >
        ← Back
      </button>

      <h2 style={{ marginBottom: 4 }}>Compare ranges</h2>
      <div style={{ opacity: 0.8, marginBottom: 12 }}>
        Device: <b>{deviceId}</b> • Type: <b>{deviceType}</b>
      </div>

      <div style={{ display: "flex", gap: 16, flexWrap: "wrap" }}>
        <div style={{ border: "1px solid gray", borderRadius: 10, padding: 12 }}>
          <b>Range A</b>
          <div style={{ marginTop: 8, display: "grid", gap: 8 }}>
            <label>
              From:
              <input
                style={{ marginLeft: 8 }}
                type="datetime-local"
                value={aFrom}
                onChange={(e) => setAFrom(e.target.value)}
              />
            </label>
            <label>
              To:
              <input
                style={{ marginLeft: 26 }}
                type="datetime-local"
                value={aTo}
                onChange={(e) => setATo(e.target.value)}
              />
            </label>
          </div>
        </div>

        <div style={{ border: "1px solid gray", borderRadius: 10, padding: 12 }}>
          <b>Range B</b>
          <div style={{ marginTop: 8, display: "grid", gap: 8 }}>
            <label>
              From:
              <input
                style={{ marginLeft: 8 }}
                type="datetime-local"
                value={bFrom}
                onChange={(e) => setBFrom(e.target.value)}
              />
            </label>
            <label>
              To:
              <input
                style={{ marginLeft: 26 }}
                type="datetime-local"
                value={bTo}
                onChange={(e) => setBTo(e.target.value)}
              />
            </label>
          </div>
        </div>
      </div>

      <div style={{ marginTop: 12, display: "flex", gap: 10, alignItems: "center" }}>
        <button
          onClick={doCompare}
          disabled={loading}
          style={{
            padding: "8px 12px",
            borderRadius: 8,
            border: "1px solid gray",
            cursor: loading ? "not-allowed" : "pointer",
          }}
        >
          {loading ? "Comparing..." : "Compare"}
        </button>
        {err && <span style={{ color: "#b00020" }}>{err}</span>}
      </div>
        <div style={{ marginTop: 12, display: "flex", gap: 10, flexWrap: "wrap", alignItems: "center" }}>
          <span style={{ opacity: 0.8 }}>Presets:</span>

          <button
            onClick={() => setPreset(60 * 60 * 1000)}
            style={{ padding: "6px 10px", borderRadius: 999, border: "1px solid gray", cursor: "pointer" }}
          >
            Last 1h vs Prev 1h
          </button>

          <button
            onClick={() => setPreset(24 * 60 * 60 * 1000)}
            style={{ padding: "6px 10px", borderRadius: 999, border: "1px solid gray", cursor: "pointer" }}
          >
            Last 24h vs Prev 24h
          </button>
        </div>

      {aMetrics && bMetrics ? (
        <div style={{ marginTop: 16, maxWidth: 900 }}>
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr>
                <th style={{ textAlign: "left", borderBottom: "2px solid gray", padding: "8px 6px" }}>Metric</th>
                <th style={{ textAlign: "left", borderBottom: "2px solid gray", padding: "8px 6px" }}>A</th>
                <th style={{ textAlign: "left", borderBottom: "2px solid gray", padding: "8px 6px" }}>B</th>
                <th style={{ textAlign: "left", borderBottom: "2px solid gray", padding: "8px 6px" }}>Δ (B-A)</th>
                <th style={{ textAlign: "left", borderBottom: "2px solid gray", padding: "8px 6px" }}>Δ% vs A</th>
              </tr>
            </thead>



            <tbody>
              {rows.map((r) => {
                const a = aMetrics[r.key] as number | null;
                const b = bMetrics[r.key] as number | null;

                const diff = a === null || b === null ? null : b - a;
                const pct = a === null || b === null || a === 0 ? null : (diff! / a) * 100;

                return (
                  <tr key={r.key}>
                    <td style={{ padding: "8px 6px", borderBottom: "1px solid #ddd" }}>{r.label}</td>
                    <td style={{ padding: "8px 6px", borderBottom: "1px solid #ddd" }}>{r.render(a)}</td>
                    <td style={{ padding: "8px 6px", borderBottom: "1px solid #ddd" }}>{r.render(b)}</td>
                    <td style={{ padding: "8px 6px", borderBottom: "1px solid #ddd" }}>{r.render(diff)}</td>
                    <td style={{ padding: "8px 6px", borderBottom: "1px solid #ddd" }}>{fmtPct(pct)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>

          <div style={{ marginTop: 10, opacity: 0.75, fontSize: 13 }}>
            Total energy liczone trapezami z telemetry. To jest spójne A vs B.
          </div>

          {hourly.length > 0 && (
            <div style={{ marginTop: 18, maxWidth: 900 }}>
              <h3 style={{ margin: "0 0 6px 0" }}>Energy per hour (kWh/h)</h3>
              <div style={{ opacity: 0.85, marginBottom: 8 }}>
                Peak hour A: <b>{peakHourA.toFixed(4)}</b> kWh • Peak hour B: <b>{peakHourB.toFixed(4)}</b> kWh
              </div>

              <div style={{ height: 280, border: "1px solid #333", borderRadius: 10, padding: 10 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={hourly}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="label" />
                    <YAxis tickFormatter={(v) => Number(v).toFixed(3)} />
                    <Tooltip formatter={(v: any) => `${Number(v).toFixed(4)} kWh`} />
                    <Legend />
                    <Bar dataKey="kwhA" name="A" />
                    <Bar dataKey="kwhB" name="B" />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </div>
          )}

        </div>
      ) : null}
    </div>
  );
}
