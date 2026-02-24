import { useEffect, useMemo, useState } from "react";
import { timeWindowsApi } from "../api/timeWindowsApi";
import { getDevices, deviceLabel, type DeviceItem } from "../api/devicesApi";
import type { CreateTimeWindowRequest, TimeWindow } from "../types/TimeWindow";

const DEFAULT_TZ = "Europe/Warsaw";

export default function SchedulesPage() {
  const [items, setItems] = useState<TimeWindow[]>([]);
  const [devices, setDevices] = useState<DeviceItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [selectedDevice, setSelectedDevice] = useState<DeviceItem | null>(null);
  const [from, setFrom] = useState("18:00");
  const [to, setTo] = useState("22:00");
  const [timezone, setTimezone] = useState(DEFAULT_TZ);

  const sorted = useMemo(() => {
    return [...items].sort((a, b) => a.deviceId.localeCompare(b.deviceId));
  }, [items]);

  async function refresh() {
    setLoading(true);
    setError(null);
    try {
      const data = await timeWindowsApi.list();
      setItems(data);
    } catch (e: any) {
      setError(e?.message ?? "Failed to load schedules");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refresh();
  }, []);

  useEffect(() => {
    getDevices().then((list) => {
      setDevices(list);
      if (list.length > 0 && !selectedDevice) setSelectedDevice(list[0]);
    });
  }, []);

  async function onCreate(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!selectedDevice) {
      setError("Wybierz urządzenie z listy (uruchom symulator, jeśli lista jest pusta).");
      return;
    }
    const req: CreateTimeWindowRequest = {
      deviceId: selectedDevice.deviceId,
      deviceType: selectedDevice.deviceType,
      from,
      to,
      timezone: timezone.trim() || DEFAULT_TZ,
    };

    try {
      await timeWindowsApi.create(req);
      await refresh();
    } catch (e: any) {
      setError(e?.message ?? "Create failed");
    }
  }

  async function toggleEnabled(it: TimeWindow) {
    setError(null);
    try {
      await timeWindowsApi.setEnabled(it.windowId, !it.enabled);
      await refresh();
    } catch (e: any) {
      setError(e?.message ?? "Toggle failed");
    }
  }

  async function remove(it: TimeWindow) {
    const ok = confirm(`Delete schedule window for ${it.deviceId} (${it.from}–${it.to})?`);
    if (!ok) return;

    setError(null);
    try {
      await timeWindowsApi.remove(it.windowId);
      await refresh();
    } catch (e: any) {
      setError(e?.message ?? "Delete failed");
    }
  }

  return (
    <div>
      <h1 style={{ margin: "0 0 8px", fontSize: "1.5rem" }}>Harmonogramy</h1>
      <p style={{ color: "var(--text-muted)", marginBottom: 24 }}>
        Okna czasowe (From–To) zapisane jako harmonogramy START/STOP w backendzie.
      </p>

      <div style={{ display: "flex", gap: 24, alignItems: "flex-start", flexWrap: "wrap" }}>
        <div className="app-card" style={{ padding: 20, minWidth: 340 }}>
          <h3 style={{ margin: "0 0 16px" }}>Nowe okno czasowe</h3>

          <form onSubmit={onCreate} style={{ display: "grid", gap: 14 }}>
            <label style={{ display: "flex", flexDirection: "column", gap: 6 }}>
              <span style={{ fontSize: "0.9rem", color: "var(--text-muted)" }}>Urządzenie</span>
              <select
                value={selectedDevice ? `${selectedDevice.deviceId}|${selectedDevice.deviceType}` : ""}
                onChange={(e) => {
                  const v = e.target.value;
                  if (!v) {
                    setSelectedDevice(null);
                    return;
                  }
                  const [deviceId, deviceType] = v.split("|");
                  setSelectedDevice({ deviceId, deviceType });
                }}
              >
                <option value="">-- wybierz z listy --</option>
                {devices.map((d) => (
                  <option key={d.deviceId} value={`${d.deviceId}|${d.deviceType}`}>
                    {deviceLabel(d)}
                  </option>
                ))}
              </select>
              {devices.length === 0 && (
                <span style={{ fontSize: "0.8rem", color: "var(--text-muted)" }}>Uruchom backend i symulator MQTT, potem odśwież stronę.</span>
              )}
            </label>
            <div style={{ display: "flex", gap: 12 }}>
              <label style={{ flex: 1, display: "flex", flexDirection: "column", gap: 6 }}>
                <span style={{ fontSize: "0.9rem", color: "var(--text-muted)" }}>From</span>
                <input type="time" value={from} onChange={(e) => setFrom(e.target.value)} />
              </label>
              <label style={{ flex: 1, display: "flex", flexDirection: "column", gap: 6 }}>
                <span style={{ fontSize: "0.9rem", color: "var(--text-muted)" }}>To</span>
                <input type="time" value={to} onChange={(e) => setTo(e.target.value)} />
              </label>
            </div>
            <label style={{ display: "flex", flexDirection: "column", gap: 6 }}>
              <span style={{ fontSize: "0.9rem", color: "var(--text-muted)" }}>Timezone</span>
              <input value={timezone} onChange={(e) => setTimezone(e.target.value)} />
            </label>
            <button type="submit" className="primary">Utwórz</button>
          </form>
        </div>

        <div style={{ flex: 1, minWidth: 520 }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
            <h3 style={{ margin: 0 }}>Istniejące okna</h3>
            <button onClick={refresh} disabled={loading}>{loading ? "Ładowanie..." : "Odśwież"}</button>
          </div>
          {error && (
            <div className="app-card" style={{ padding: 12, marginBottom: 12, borderColor: "var(--danger)", background: "rgba(248,81,73,0.08)" }}>
              {error}
            </div>
          )}
          {!loading && (
            <div className="app-card" style={{ overflow: "hidden" }}>
              <table style={{ width: "100%", borderCollapse: "collapse" }}>
                <thead>
                  <tr style={{ background: "var(--bg-input)" }}>
                    <th style={{ textAlign: "left", padding: 12 }}>Urządzenie</th>
                    <th style={{ textAlign: "left", padding: 12 }}>Okno</th>
                    <th style={{ textAlign: "left", padding: 12 }}>Strefa</th>
                    <th style={{ textAlign: "left", padding: 12 }}>Wł.</th>
                    <th style={{ textAlign: "left", padding: 12 }}></th>
                  </tr>
                </thead>
                <tbody>
                  {sorted.map((it) => (
                    <tr key={it.windowId} style={{ borderTop: "1px solid var(--border)" }}>
                      <td style={{ padding: 12 }}>
                        <div style={{ fontWeight: 600 }}>{it.deviceId}</div>
                        <div style={{ fontSize: "0.9rem", color: "var(--text-muted)" }}>{it.deviceType}</div>
                      </td>
                      <td style={{ padding: 12 }}>{it.from} – {it.to}</td>
                      <td style={{ padding: 12 }}>{it.timezone}</td>
                      <td style={{ padding: 12 }}>
                        <button onClick={() => toggleEnabled(it)} style={{ padding: "6px 12px" }}>
                          {it.enabled ? "ON" : "OFF"}
                        </button>
                      </td>
                      <td style={{ padding: 12 }}>
                        <button onClick={() => remove(it)}>Usuń</button>
                      </td>
                    </tr>
                  ))}
                  {sorted.length === 0 && (
                    <tr>
                      <td colSpan={5} style={{ padding: 20, color: "var(--text-muted)" }}>Brak harmonogramów.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}
          <p style={{ marginTop: 10, fontSize: "0.85rem", color: "var(--text-muted)" }}>
            windowId grupuje harmonogramy START/STOP w backendzie.
          </p>
        </div>
      </div>
    </div>
  );
}
