import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { computeRangeMetrics, sortByTsAsc } from "../metrics/energyMetrics";
import type { TelemetryPoint } from "../metrics/energyMetrics";
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, CartesianGrid, ResponsiveContainer, Legend
} from "recharts";
import { computeEnergyPerHour } from "../metrics/energyMetrics";
import { apiFetch } from "../api/http";
import { formatPowerW, formatEnergyKWh } from "../utils/formatPower";
import { deviceTypeLabel } from "../utils/labels";

function fmtPct(n: number | null) {
  if (n === null || !Number.isFinite(n)) return "—";
  return `${n.toFixed(1)}%`;
}

type Row = {
  key: "totalEnergyKwh" | "averagePowerW" | "peakPower" | "activeSeconds" | "activePct";
  label: string;
  render: (v: number | null) => string;
  bold?: boolean;
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
      { key: "totalEnergyKwh", label: "Energia całkowita (kWh)", render: (v) => formatEnergyKWh(v), bold: true },
      { key: "averagePowerW", label: "Średnia moc (W)", render: (v) => (v == null ? "—" : formatPowerW(v)), bold: true },
      { key: "peakPower", label: "Szczyt mocy (W)", render: (v) => (v == null ? "—" : formatPowerW(v)), bold: true },
      { key: "activeSeconds", label: "Czas aktywny (s)", render: (v) => (v === null ? "—" : String(Math.round(v))) },
      { key: "activePct", label: "Aktywność (%)", render: fmtPct },
    ],
    []
  );

  async function fetchTelemetry(fromLocal: string, toLocal: string) {
    const params = new URLSearchParams();
    params.set("deviceId", deviceId!);
    params.set("from", new Date(fromLocal).toISOString());
    params.set("to", new Date(toLocal).toISOString());

    const res = await apiFetch(`/api/telemetry/range?${params.toString()}`, {
      cache: "no-store",
    });
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
    <div style={{ padding: 20, fontFamily: "system-ui, sans-serif", maxWidth: 960, margin: "0 auto" }}>
      <button
        onClick={() => navigate(`/devices/${deviceType}/${deviceId}`)}
        style={{ padding: "8px 12px", borderRadius: "var(--radius-sm)", border: "1px solid var(--border)", cursor: "pointer", marginBottom: 16 }}
      >
        ← Wstecz
      </button>

      <h2 style={{ margin: "0 0 4px", fontSize: "1.25rem" }}>Porównaj zakresy</h2>
      <div style={{ fontSize: "0.9rem", color: "var(--text-muted)", marginBottom: 16 }}>
        Urządzenie: <b>{deviceId}</b> ({deviceTypeLabel(deviceType)})
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: 16, marginBottom: 16 }}>
        <div className="app-card" style={{ padding: 16 }}>
          <b style={{ display: "block", marginBottom: 10 }}>Zakres A</b>
          <label style={{ display: "block", marginBottom: 8, fontSize: "0.9rem" }}>
            Od: <input type="datetime-local" value={aFrom} onChange={(e) => setAFrom(e.target.value)} style={{ marginLeft: 8, padding: "6px 8px" }} />
          </label>
          <label style={{ display: "block", fontSize: "0.9rem" }}>
            Do: <input type="datetime-local" value={aTo} onChange={(e) => setATo(e.target.value)} style={{ marginLeft: 10, padding: "6px 8px" }} />
          </label>
        </div>
        <div className="app-card" style={{ padding: 16 }}>
          <b style={{ display: "block", marginBottom: 10 }}>Zakres B</b>
          <label style={{ display: "block", marginBottom: 8, fontSize: "0.9rem" }}>
            Od: <input type="datetime-local" value={bFrom} onChange={(e) => setBFrom(e.target.value)} style={{ marginLeft: 8, padding: "6px 8px" }} />
          </label>
          <label style={{ display: "block", fontSize: "0.9rem" }}>
            Do: <input type="datetime-local" value={bTo} onChange={(e) => setBTo(e.target.value)} style={{ marginLeft: 10, padding: "6px 8px" }} />
          </label>
        </div>
      </div>

      <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 12, marginBottom: 16 }}>
        <button onClick={doCompare} disabled={loading} className="primary" style={{ padding: "10px 20px" }}>
          {loading ? "Porównuję…" : "Porównaj"}
        </button>
        <span style={{ fontSize: "0.9rem", color: "var(--text-muted)" }}>Presety:</span>
        <button onClick={() => setPreset(60 * 60 * 1000)} style={{ padding: "8px 12px", borderRadius: "var(--radius-sm)", border: "1px solid var(--border)", cursor: "pointer" }}>
          Ostatnia 1h vs poprzednia 1h
        </button>
        <button onClick={() => setPreset(24 * 60 * 60 * 1000)} style={{ padding: "8px 12px", borderRadius: "var(--radius-sm)", border: "1px solid var(--border)", cursor: "pointer" }}>
          Ostatnie 24h vs poprzednie 24h
        </button>
        {err && <span style={{ color: "var(--danger)" }}>{err}</span>}
      </div>

      {aMetrics && bMetrics ? (
        <>
          <div className="app-card" style={{ padding: 16, marginBottom: 20 }}>
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead>
                <tr>
                  <th style={{ textAlign: "left", borderBottom: "2px solid var(--border)", padding: "10px 8px" }}>Metryka</th>
                  <th style={{ textAlign: "left", borderBottom: "2px solid var(--border)", padding: "10px 8px" }}>A</th>
                  <th style={{ textAlign: "left", borderBottom: "2px solid var(--border)", padding: "10px 8px" }}>B</th>
                  <th style={{ textAlign: "left", borderBottom: "2px solid var(--border)", padding: "10px 8px" }}>Różnica (B−A)</th>
                  <th style={{ textAlign: "left", borderBottom: "2px solid var(--border)", padding: "10px 8px" }}>Zmiana % vs A</th>
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
                      <td style={{ padding: "10px 8px", borderBottom: "1px solid var(--border)", fontWeight: r.bold ? 600 : 400 }}>{r.label}</td>
                      <td style={{ padding: "10px 8px", borderBottom: "1px solid var(--border)" }}>{r.render(a)}</td>
                      <td style={{ padding: "10px 8px", borderBottom: "1px solid var(--border)" }}>{r.render(b)}</td>
                      <td style={{ padding: "10px 8px", borderBottom: "1px solid var(--border)" }}>{r.render(diff)}</td>
                      <td style={{ padding: "10px 8px", borderBottom: "1px solid var(--border)" }}>{fmtPct(pct)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <div style={{ marginTop: 12, fontSize: "0.85rem", color: "var(--text-muted)" }}>
              Energia liczona trapezami z telemetrii. Spójne dla A i B.
            </div>
          </div>

          {hourly.length > 0 && (
            <div className="app-card" style={{ padding: 16 }}>
              <h3 style={{ margin: "0 0 8px", fontSize: "1rem" }}>Energia na godzinę (kWh/h)</h3>
              <div style={{ marginBottom: 12, fontSize: "0.9rem", color: "var(--text-muted)" }}>
                Szczyt godziny A: <b>{formatEnergyKWh(peakHourA)}</b> · B: <b>{formatEnergyKWh(peakHourB)}</b>
              </div>
              <div style={{ height: 280 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={hourly}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="label" />
                    <YAxis tickFormatter={(v) => Number(v).toFixed(3)} />
                    <Tooltip formatter={(v: unknown) => `${Number(v).toFixed(4)} kWh`} />
                    <Legend />
                    <Bar dataKey="kwhA" name="Zakres A" fill="var(--accent)" />
                    <Bar dataKey="kwhB" name="Zakres B" fill="var(--success)" />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </div>
          )}
        </>
      ) : null}
    </div>
  );
}
