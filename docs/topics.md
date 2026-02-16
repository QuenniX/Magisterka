# MQTT topics i format wiadomości (v1)

## Broker
- mqtt://localhost:1883 (Mosquitto lokalnie)

## Konwencja topiców
- Telemetria energii:
  smarthome/v1/devices/{deviceType}/{deviceId}/telemetry
- Stan urządzenia (opcjonalnie retained):
  smarthome/v1/devices/{deviceType}/{deviceId}/state
- Zdarzenia (opcjonalnie):
  smarthome/v1/devices/{deviceType}/{deviceId}/events

Przykład:
smarthome/v1/devices/bulb/bulb-01/telemetry

## QoS / retained (zalecenie)
- telemetry: QoS 0, retained=false
- state: QoS 1, retained=true

## Payload: Telemetry (JSON)
Wiadomość telemetryczna zawiera m.in.: identyfikator urządzenia, czas pomiaru,
pobór mocy (W), napięcie (V), stan, tryb pracy.

Przykład:
{
"schema": "smarthome.energy.telemetry.v1",
"deviceId": "bulb-01",
"deviceType": "bulb",
"ts": "2026-02-16T12:34:56.789Z",
"powerW": 8.4,
"voltageV": 230.1,
"state": "ON",
"mode": "NORMAL"
}
