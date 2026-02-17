# Smart Home Simulator – Architecture Overview

## 1. Project Overview

This project is a research-oriented Smart Home simulation platform developed for a Master's thesis.

The system simulates IoT energy devices, processes MQTT messages, stores historical telemetry data, and provides a foundation for optimization and AI-based energy management.

---

## 2. High-Level Architecture

System layers:

1. IoT Device Simulation (MQTT Client module)
2. MQTT Broker (Mosquitto)
3. Backend (Spring Boot)
4. PostgreSQL Database (Dockerized)
5. Future: REST API + Dashboard + AI module

The system is event-driven and modular.

---

## 3. Modules

### 3.1 simulators/mqtt-client

Responsible for:
- Device simulation
- Generating EnergyTelemetry
- State machines (washer, fridge, etc.)
- Publishing MQTT telemetry and state messages

Devices implemented:
- Washer (sequential state machine)
- Fridge (cyclical compressor)
- Heater (controllable high-power device)
- Bulb (controllable low-power device)
- Plug (random behavior)

Important:
- Simulators do NOT depend on backend.
- Simulators do NOT know about database.

---

### 3.2 backend

Spring Boot application responsible for:
- Subscribing to MQTT topics
- Persisting telemetry to PostgreSQL
- Persisting device state changes
- Providing REST API (planned)

Database:
- PostgreSQL (Docker container)
- JPA/Hibernate auto schema generation

Current tables:
- energy_telemetry

---

## 4. MQTT Topic Convention

Telemetry:
