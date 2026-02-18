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
    } catch (e: any) {
      setBackendUp(false);
      setError(e?.message ?? "Failed to fetch");
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

      const res = await fetch(
        `${API_BASE}/api/devices/${deviceType}/${deviceId}/cmd`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ cmd }),
        }
      );

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        throw new Error(`CMD failed: ${res.status} ${res.statusText} ${text}`);
      }

      await fetchDevices();
    } catch (e: any) {
      setError(e?.message ?? "Unknown error");
    } finally {
      setSending(null);
    }
  };

  return (
    <div style={{ padding: "20px", fontFamily: "system-ui, sans-serif" }}>
      <div style={{ display: "flex", alignItems: "baseline", gap: "12px" }}>
        <h1 style={{ margin: 0 }}>Devices</h1>
        <span
          style={{
            fontSize: "14px",
            padding: "4px 8px",
            borderRadius: "999px",
            border: "1px solid gray",
            opacity: 0.9,
          }}
        >
          Backend:{" "}
          <b style={{ color: backendUp ? "#5be37a" : "#ff6b6b" }}>
            {backendUp ? "OK" : "DOWN"}
          </b>
        </span>
      </div>

      {error && (
        <div
          style={{
            border: "1px solid #a00",
            padding: "10px",
            marginTop: "12px",
            marginBottom: "12px",
            borderRadius: "8px",
          }}
        >
          <b>Error:</b> {error}
        </div>
      )}

      <div style={{ display: "grid", gap: "12px", maxWidth: "900px", marginTop: "12px" }}>
        {devices.map((device) => {
          const on = isOn(device.state);
          const busy = sending === device.deviceId;

          return (
            <div
              key={device.deviceId}
              style={{
                border: "1px solid gray",
                padding: "14px",
                borderRadius: "10px",
              }}
            >
              <div style={{ display: "flex", justifyContent: "space-between", gap: "12px" }}>
                <div
                  style={{ cursor: "pointer" }}
                  onClick={() =>
                    navigate(`/devices/${device.deviceType}/${device.deviceId}`)
                  }
                  title="Open details"
                >
                  <h3 style={{ margin: 0 }}>{device.deviceId}</h3>
                  <div style={{ opacity: 0.8, marginTop: "4px" }}>
                    Type: <b>{device.deviceType}</b>
                  </div>
                </div>

                <div style={{ textAlign: "right" }}>
                  <span
                    style={{
                      display: "inline-block",
                      padding: "6px 10px",
                      borderRadius: "999px",
                      border: "1px solid gray",
                      fontWeight: 700,
                      color: on ? "#5be37a" : "#ff6b6b",
                    }}
                  >
                    {on ? "ON" : "OFF"}
                  </span>
                  <div style={{ opacity: 0.7, marginTop: "6px", fontSize: "12px" }}>
                    Last update: {fmtTs(device.ts)}
                  </div>
                </div>
              </div>

              <div style={{ marginTop: "12px", display: "grid", gap: "6px" }}>
                <div>
                  Power: <b>{fmt(device.powerW)}</b> W
                </div>
                <div>
                  Voltage: <b>{fmt(device.voltageV)}</b> V
                </div>
              </div>

              <div style={{ display: "flex", gap: "10px", marginTop: "12px", alignItems: "center" }}>
                <button
                  onClick={() => sendCmd(device.deviceType, device.deviceId, "START")}
                  disabled={busy || on}
                  style={{
                    padding: "8px 12px",
                    borderRadius: "8px",
                    border: "1px solid gray",
                    cursor: busy || on ? "not-allowed" : "pointer",
                    opacity: busy || on ? 0.6 : 1,
                  }}
                >
                  START
                </button>

                <button
                  onClick={() => sendCmd(device.deviceType, device.deviceId, "STOP")}
                  disabled={busy || !on}
                  style={{
                    padding: "8px 12px",
                    borderRadius: "8px",
                    border: "1px solid gray",
                    cursor: busy || !on ? "not-allowed" : "pointer",
                    opacity: busy || !on ? 0.6 : 1,
                  }}
                >
                  STOP
                </button>

                {busy && <span style={{ opacity: 0.8 }}>Sending...</span>}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
