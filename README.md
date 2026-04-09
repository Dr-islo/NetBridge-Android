# WiFi Share v2.0 — WiFi Tethering via WiFi Direct + Proxy

Share your WiFi internet with other devices on Android 6 (no SIM card needed).

## How It Works
```
[Router] ──WiFi──► [Your Android 6 Phone]
                          │
                    WiFi Direct (P2P)
                          │
                   [Other Device] ──proxy──► internet ✓
```

Your phone stays connected to your router via normal WiFi.
It simultaneously creates a **WiFi Direct group** (a second virtual hotspot).
A built-in **HTTP/HTTPS proxy server** (port 8282) routes traffic from
connected devices through your phone to the internet.

---

## Build Instructions (Android Studio)

### Requirements
- Android Studio Hedgehog 2023.1 or newer
- Android SDK 34
- JDK 8+

### Steps
1. Extract the ZIP
2. Open Android Studio → **File → Open** → select the `WifiShare` folder
3. Wait for Gradle sync
4. **Build → Build Bundle(s)/APK(s) → Build APK(s)**
5. APK is at: `app/build/outputs/apk/debug/app-debug.apk`
6. Copy APK to your Android 6 phone and install it
   (Settings → Security → enable "Unknown sources" first)

---

## First-Time Setup on Your Phone

1. Make sure **WiFi is ON** and connected to your router
2. Open **WiFi Share** app
3. When prompted, tap **"Open Settings"** and allow **Modify system settings**
4. Grant **Location** permission when asked (Android requires this for WiFi Direct)
5. That's it — you're ready to share

---

## Using the App

### On your Android 6 phone:
1. Open **WiFi Share**
2. Set a hotspot name (auto-filled from your WiFi name)
3. Set a password (optional, leave blank for open network)
4. Tap **▶ Start Sharing**
5. A green card appears showing:  `192.168.49.1:8282`

### On the device you want to connect:
1. Go to **WiFi settings** → find and connect to the hotspot (e.g. `MyWifi_Share`)
2. Once connected, go to **WiFi Advanced settings** for that network
3. Set **Proxy → Manual**
4. **Host:** `192.168.49.1`
5. **Port:** `8282`
6. Save — internet now works ✓

### Android proxy path (varies by version):
- **Android 10+:** Settings → WiFi → long press network → Modify → Advanced → Proxy
- **Android 7-9:** Settings → WiFi → long press → Manage → Advanced → Proxy
- **iOS:** Settings → WiFi → tap ℹ → Configure Proxy → Manual
- **Windows:** Settings → Network → WiFi → Proxy → Manual

---

## Features
| Feature | Detail |
|---|---|
| WiFi Direct group | Phone stays on WiFi + creates hotspot |
| HTTP proxy | Port 8282, handles all HTTP traffic |
| HTTPS tunnel | CONNECT method, no MITM, fully secure |
| Foreground service | Runs in background when app minimised |
| Notification | Shows status + quick Stop button |
| Copy button | One-tap copy of proxy address |
| Step-by-step guide | Shown in app after starting |

---

## 🔒 Security & Technical Specs

| Spec | Detail |
|---|---|
| **HTTPS Privacy** | Uses `CONNECT` method; **Zero-Decryption** tunnel (No MITM). |
| **Data Integrity** | Pass-through architecture; app cannot see or log encrypted traffic. |
| **Network Binding** | Proxy locked to `192.168.49.1` for local P2P isolation. |
| **Concurrency** | Multithreaded `ProxyWorker` handles simultaneous client sockets. |
| **Persistence** | Foreground Service with sticky notification to prevent OS kill. |

> **Note:** This app acts as a transparent bridge. It does not perform SSL stripping or store any user metadata, making it compliant with standard privacy practices.
## Troubleshooting

| Problem | Fix |
|---|---|
| "WiFi Direct failed (code 2)" | Location permission not granted |
| "WiFi Direct failed (code 3)" | WiFi is OFF — turn it on first |
| Hotspot appears but no internet | Proxy not set on client device |
| App crashes on start | Allow "Modify system settings" permission |
| HTTPS sites don't load | Make sure proxy port is 8282 (not 8080) |

---

## File Structure
```
WifiShare/
├── app/src/main/
│   ├── java/com/wifishare/
│   │   ├── MainActivity.java    ← UI, permissions, status display
│   │   ├── ProxyService.java    ← WiFi Direct group + proxy server (foreground)
│   │   └── ProxyWorker.java     ← Per-connection HTTP/HTTPS proxy handler
│   ├── res/layout/activity_main.xml
│   ├── res/values/ (colors, strings, styles)
│   ├── res/drawable/ (cards, buttons)
│   └── AndroidManifest.xml
├── build.gradle
├── settings.gradle
└── gradle.properties
```
