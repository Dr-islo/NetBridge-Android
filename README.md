# WiFi Share v2.0 — WiFi Tethering via WiFi Direct + Proxy

A specialized Android utility designed to share an active WiFi connection with other devices using WiFi Direct (P2P) and a custom-built HTTP/HTTPS proxy server. Ideal for legacy devices (Android 6.0+) or situations where traditional hotspotting is restricted.

---

## 🛠 How It Works (The Logic)

Traditionally, Android disables WiFi when you turn on a Hotspot. This app bypasses that limitation:

1.  **WiFi Direct (P2P):** Creates a virtual network interface that runs alongside the primary WiFi radio.
2.  **Socket Proxying:** Since Android does not natively route traffic between WiFi and WiFi Direct, a built-in Proxy Server (Port 8282) acts as the bridge.
3.  **HTTPS Tunneling:** Implements the `CONNECT` method to allow encrypted traffic (SSL/TLS) to pass through without SSL-stripping, ensuring user privacy.

---

## 🚀 Key Features

- **Dual-Radio Support:** Stay connected to a router while simultaneously sharing the connection.
- **Multithreaded Proxy:** Uses `ProxyWorker` to handle concurrent connections from multiple devices.
- **Foreground Service:** Prevents the Android OS from killing the process during heavy data transfers.
- **Battery Optimized:** Minimal CPU overhead when idle.

---

## 💻 Technical Deep Dive

### Concurrency Model
The app utilizes a multithreaded architecture to ensure the UI remains responsive while the proxy handles data:
- **`ProxyService`**: A Foreground Service that manages the WiFi Direct Group lifecycle and listens for incoming socket connections.
- **`ProxyWorker`**: A dedicated thread spawned for every new client connection, handling the request/response cycle between the client and the internet.

### Network Configuration
- **Host:** `192.168.49.1` (Default Android WiFi Direct Gateway)
- **Port:** `8282`
- **Protocol:** HTTP/1.1 with support for `CONNECT` tunneling.

---

---

## 🔒 Security & Privacy Architecture

In network-heavy applications, security is paramount. This project implements several layers of protection:

### 1. Zero-Decryption HTTPS Tunneling
The proxy utilizes the **HTTP CONNECT** method. Unlike a Man-In-The-Middle (MITM) proxy, this app **never decrypts** HTTPS traffic. It simply establishes a TCP tunnel. 
- **Benefit:** User credentials, bank details, and private messages remain encrypted end-to-end between the client and the destination server.

### 2. Sandbox Isolation
By leveraging Android’s **UID-based permission system**, the app runs in a restricted sandbox. It only requests the minimum necessary permissions (`INTERNET`, `ACCESS_WIFI_STATE`) and does not require **Root Access**, maintaining the integrity of the Android system.

### 3. Loopback Protection
The proxy is bound specifically to the WiFi Direct interface (`192.168.49.1`). This prevents unauthorized access from external public networks and ensures that only devices physically connected to your P2P group can utilize the gateway.

### 4. Data Privacy
- **No Logs Policy:** The application does not store, log, or transmit any traffic metadata.
- **Ephemeral Processing:** Data is processed in-memory (RAM) and is never written to disk, preventing forensic recovery of session data.

## 📦 Build & Installation

### Requirements
- **Android Studio:** Hedgehog 2023.1+
- **Minimum SDK:** 23 (Android 6.0)
- **Target SDK:** 34

### Quick Start
1. Clone the repository: `git clone https://github.com/Dr-islo/WifiShare.git`
2. Open in Android Studio and perform a **Gradle Sync**.
3. Build the APK: `Build > Build APK(s)`.
4. Install on an Android 6.0+ device and grant **Location** & **System Settings** permissions.

---

## 🔧 Troubleshooting

| Issue | Resolution |
| :--- | :--- |
| **Error Code 2** | Grant Location Permissions (required for WiFi scanning). |
| **No Internet on Client** | Ensure the Client device has Proxy set to `192.168.49.1:8282`. |
| **HTTPS Failed** | Check if the firewall on the host phone is blocking Port 8282. |

---

## 📂 Project Structure
```text
WifiShare/
├── app/src/main/java/com/wifishare/
│   ├── MainActivity.java    # UI Logic & Permission Handlers
│   ├── ProxyService.java    # WiFi Direct Management & Socket Listener
│   └── ProxyWorker.java     # Per-client Data Routing & HTTPS Tunneling
└── AndroidManifest.xml      # Network & Foreground Service Declarations
