import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

type Device = {
  deviceId: string;
  deviceType: string;
  state: string;
  powerW: number;
  voltageV: number;
  ts: string;
};

const API_BASE = "http://localhost:8080";

export default function DevicesPage() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState<string | null>(null);
  const [backendUp, setBackendUp] = useState<boolean>(true);

  const navigate = useNavigate();

  const isOn = (state: string) => state?.toUpperCase() === "ON";
  const fmt = (n: number) => (Number.isFinite(n) ? n.toFixed(2) : "-");
  const fmtTs = (ts: string) => {
    const d = new Date(ts);
    return isNaN(d.getTime()) ? ts : d.toLocaleString();
  };

  const fetchDevices = async () => {
    try {
      setError(null);
      const res = await fetch(`${API_BASE}/api/devices/latest`);
      if (!res.ok) {
        setBackendUp(false);
        throw new Error(`Fetch failed: ${res.status} ${res.statusText}`);
      }
      const data: Device[] = await res.json();
      setDevices(data);
      setBackendUp(true);
    } catch (e: unknown) {
      setBackendUp(false);
      setError(e instanceof Error ? e.message : "Failed to fetch");
    }
  };

  useEffect(() => {
    fetchDevices();
    const interval = setInterval(fetchDevices, 5000);
    return () => clearInterval(interval);
  }, []);

  const sendCmd = async (
    deviceType: string,
    deviceId: string,
    cmd: "START" | "STOP"
  ) => {
    try {
      setError(null);
      setSending(deviceId);

      const token = localStorage.getItem("magisterka_jwt");
      const headers: Record<string, string> = { "Content-Type": "application/json" };
      if (token) headers["Authorization"] = `Bearer ${token}`;

      const res = await fetch(
        `${API_BASE}/api/devices/${deviceType}/${deviceId}/cmd`,
        { method: "POST", headers, body: JSON.stringify({ cmd }) }
      );

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        if (res.status === 403) {
          throw new Error("Brak dostępu. Wyloguj i zaloguj ponownie.");
        }
        throw new Error(`CMD failed: ${res.status} ${res.statusText} ${text}`);
      }
      await fetchDevices();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Unknown error");
    } finally {
      setSending(null);
    }
  };

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 12, marginBottom: 20 }}>
        <h1 style={{ margin: 0, fontSize: "1.5rem" }}>Urządzenia</h1>
        <span
          className="app-card"
          style={{
            padding: "6px 12px",
            fontSize: "0.9rem",
          }}
        >
          Backend: <b style={{ color: backendUp ? "var(--success)" : "var(--danger)" }}>{backendUp ? "OK" : "DOWN"}</b>
        </span>
      </div>

      {error && (
        <div
          className="app-card"
          style={{
            padding: 12,
            marginBottom: 20,
            borderColor: "var(--danger)",
            background: "rgba(248, 81, 73, 0.08)",
          }}
        >
          <b>Błąd:</b> {error}
        </div>
      )}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(320px, 1fr))", gap: 16 }}>
        {devices.map((device) => {
          const on = isOn(device.state);
          const busy = sending === device.deviceId;
          return (
            <div
              key={device.deviceId}
              className="app-card"
              style={{ padding: 18 }}
            >
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 12 }}>
                <div
                  style={{ cursor: "pointer", flex: 1 }}
                  onClick={() => navigate(`/devices/${device.deviceType}/${device.deviceId}`)}
                  title="Szczegóły"
                >
                  <h3 style={{ margin: 0, fontSize: "1.1rem" }}>{device.deviceId}</h3>
                  <div style={{ color: "var(--text-muted)", fontSize: "0.9rem", marginTop: 4 }}>
                    {device.deviceType}
                  </div>
                </div>
                <span
                  style={{
                    padding: "6px 12px",
                    borderRadius: 999,
                    fontSize: "0.85rem",
                    fontWeight: 600,
                    background: on ? "rgba(63, 185, 80, 0.2)" : "rgba(248, 81, 73, 0.2)",
                    color: on ? "var(--success)" : "var(--danger)",
                  }}
                >
                  {on ? "ON" : "OFF"}
                </span>
              </div>
              <div style={{ marginTop: 14, display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, fontSize: "0.9rem" }}>
                <div>Moc: <b>{fmt(device.powerW)}</b> W</div>
                <div>Napięcie: <b>{fmt(device.voltageV)}</b> V</div>
              </div>
              <div style={{ marginTop: 12, fontSize: "0.8rem", color: "var(--text-muted)" }}>
                Ostatnia aktualizacja: {fmtTs(device.ts)}
              </div>
              <div style={{ display: "flex", gap: 10, marginTop: 16 }}>
                <button
                  onClick={() => sendCmd(device.deviceType, device.deviceId, "START")}
                  disabled={busy || on}
                  className="primary"
                  style={{ flex: 1 }}
                >
                  START
                </button>
                <button
                  onClick={() => sendCmd(device.deviceType, device.deviceId, "STOP")}
                  disabled={busy || !on}
                  style={{ flex: 1 }}
                >
                  STOP
                </button>
              </div>
              {busy && <div style={{ marginTop: 8, fontSize: "0.85rem", color: "var(--text-muted)" }}>Wysyłanie...</div>}
            </div>
          );
        })}
      </div>
    </div>
  );
}
