import { useEffect, useMemo, useState } from "react";
import { timeWindowsApi } from "../api/timeWindowsApi";
import type { CreateTimeWindowRequest, TimeWindow } from "../types/TimeWindow";

const DEFAULT_TZ = "Europe/Warsaw";

export default function SchedulesPage() {
  const [items, setItems] = useState<TimeWindow[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // form
  const [deviceId, setDeviceId] = useState("heater-01");
  const [deviceType, setDeviceType] = useState("heater");
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

  async function onCreate(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    const req: CreateTimeWindowRequest = {
      deviceId: deviceId.trim(),
      deviceType: deviceType.trim(),
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
    <div style={{ padding: 24, maxWidth: 1100 }}>
      <h1 style={{ marginBottom: 8 }}>Schedules</h1>
      <div style={{ opacity: 0.8, marginBottom: 16 }}>
        Time windows (From–To) stored as START/STOP cron schedules in backend.
      </div>

      <div style={{ display: "flex", gap: 24, alignItems: "flex-start", flexWrap: "wrap" }}>
        {/* Creator */}
        <div style={{ border: "1px solid #333", borderRadius: 12, padding: 16, minWidth: 340 }}>
          <h3 style={{ marginTop: 0 }}>Create time window</h3>

          <form onSubmit={onCreate} style={{ display: "grid", gap: 10 }}>
            <label>
              Device ID
              <input
                value={deviceId}
                onChange={(e) => setDeviceId(e.target.value)}
                style={{ width: "100%", padding: 8, marginTop: 4 }}
              />
            </label>

            <label>
              Device Type
              <input
                value={deviceType}
                onChange={(e) => setDeviceType(e.target.value)}
                style={{ width: "100%", padding: 8, marginTop: 4 }}
              />
            </label>

            <div style={{ display: "flex", gap: 12 }}>
              <label style={{ flex: 1 }}>
                From
                <input
                  type="time"
                  value={from}
                  onChange={(e) => setFrom(e.target.value)}
                  style={{ width: "100%", padding: 8, marginTop: 4 }}
                />
              </label>

              <label style={{ flex: 1 }}>
                To
                <input
                  type="time"
                  value={to}
                  onChange={(e) => setTo(e.target.value)}
                  style={{ width: "100%", padding: 8, marginTop: 4 }}
                />
              </label>
            </div>

            <label>
              Timezone
              <input
                value={timezone}
                onChange={(e) => setTimezone(e.target.value)}
                style={{ width: "100%", padding: 8, marginTop: 4 }}
              />
            </label>

            <button type="submit" style={{ padding: "10px 12px", cursor: "pointer" }}>
              Create
            </button>
          </form>
        </div>

        {/* List */}
        <div style={{ flex: 1, minWidth: 520 }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <h3 style={{ marginTop: 0 }}>Existing windows</h3>
            <button onClick={refresh} style={{ padding: "8px 10px", cursor: "pointer" }}>
              Refresh
            </button>
          </div>

          {error && (
            <div style={{ border: "1px solid #a33", padding: 10, borderRadius: 8, marginBottom: 12 }}>
              {error}
            </div>
          )}

          {loading ? (
            <div>Loading...</div>
          ) : (
            <div style={{ border: "1px solid #333", borderRadius: 12, overflow: "hidden" }}>
              <table style={{ width: "100%", borderCollapse: "collapse" }}>
                <thead>
                  <tr style={{ background: "#151515" }}>
                    <th style={{ textAlign: "left", padding: 10 }}>Device</th>
                    <th style={{ textAlign: "left", padding: 10 }}>Window</th>
                    <th style={{ textAlign: "left", padding: 10 }}>Timezone</th>
                    <th style={{ textAlign: "left", padding: 10 }}>Enabled</th>
                    <th style={{ textAlign: "left", padding: 10 }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {sorted.map((it) => (
                    <tr key={it.windowId} style={{ borderTop: "1px solid #2a2a2a" }}>
                      <td style={{ padding: 10 }}>
                        <div style={{ fontWeight: 700 }}>{it.deviceId}</div>
                        <div style={{ opacity: 0.75 }}>{it.deviceType}</div>
                      </td>
                      <td style={{ padding: 10 }}>{it.from} – {it.to}</td>
                      <td style={{ padding: 10 }}>{it.timezone}</td>
                      <td style={{ padding: 10 }}>
                        <button onClick={() => toggleEnabled(it)} style={{ padding: "6px 10px", cursor: "pointer" }}>
                          {it.enabled ? "ON" : "OFF"}
                        </button>
                      </td>
                      <td style={{ padding: 10 }}>
                        <button onClick={() => remove(it)} style={{ padding: "6px 10px", cursor: "pointer" }}>
                          Delete
                        </button>
                      </td>
                    </tr>
                  ))}
                  {sorted.length === 0 && (
                    <tr>
                      <td colSpan={5} style={{ padding: 12, opacity: 0.7 }}>
                        No schedules yet.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}

          <div style={{ marginTop: 10, opacity: 0.7, fontSize: 12 }}>
            windowId is used internally to group START/STOP schedules.
          </div>
        </div>
      </div>
    </div>
  );
}
