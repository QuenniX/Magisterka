import { useEffect, useState } from "react";
import DeviceCard from "../components/DeviceCard";
import { getLatestDevices, sendCmd, type LatestDeviceState } from "../api/devicesApi";

export default function DevicesPage() {
  const [devices, setDevices] = useState<LatestDeviceState[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState<string | null>(null);
  const [backendUp, setBackendUp] = useState<boolean>(true);

  const fetchDevices = async () => {
    try {
      setError(null);
      const data = await getLatestDevices();
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

  const handleSendCmd = async (
    deviceType: string,
    deviceId: string,
    cmd: "START" | "STOP"
  ) => {
    try {
      setError(null);
      setSending(deviceId);
      await sendCmd(deviceType, deviceId, cmd);
      await fetchDevices();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Unknown error");
    } finally {
      setSending(null);
    }
  };

  return (
    <div>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          flexWrap: "wrap",
          gap: 12,
          marginBottom: 20,
        }}
      >
        <h1 style={{ margin: 0, fontSize: "1.5rem" }}>Urządzenia</h1>
        <span
          className="app-card"
          style={{
            padding: "6px 12px",
            fontSize: "0.9rem",
          }}
        >
          Backend:{" "}
          <b style={{ color: backendUp ? "var(--success)" : "var(--danger)" }}>
            {backendUp ? "OK" : "DOWN"}
          </b>
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

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fill, minmax(320px, 1fr))",
          gap: 16,
        }}
      >
        {devices.map((device) => (
          <DeviceCard
            key={device.deviceId}
            device={device}
            sending={sending === device.deviceId}
            onSendCmd={handleSendCmd}
            showNav
            compact={false}
          />
        ))}
      </div>
    </div>
  );
}
