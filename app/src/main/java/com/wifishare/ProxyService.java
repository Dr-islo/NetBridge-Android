package com.wifishare;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.p2p.*;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/**
 * Foreground service that:
 *  1. Creates a WiFi Direct Autonomous Group Owner (phone becomes hotspot
 *     while staying connected to the router).
 *  2. Runs an HTTP/HTTPS proxy server so connected devices can reach the internet.
 *  3. Runs a setup/PAC server on port 8080 for automatic proxy configuration.
 */
public class ProxyService extends Service {

    // ── Public constants ──────────────────────────────────────────
    public static final String ACTION_START     = "com.wifishare.START";
    public static final String ACTION_STOP      = "com.wifishare.STOP";
    public static final String BROADCAST_ACTION = "com.wifishare.STATUS";
    public static final int    PROXY_PORT       = 8282;
    public static final int    CONFIG_PORT      = 8080;

    public static volatile boolean running         = false;
    public static volatile String  currentIp       = "192.168.49.1";
    public static volatile String  activeGroupSsid = "";
    public static volatile String  activeGroupPass = "";
    /** True when transparent NAT via iptables is active (root mode). */
    public static volatile boolean natMode         = false;

    // ── Private ───────────────────────────────────────────────────
    private static final String TAG        = "WifiShare";
    private static final String CHANNEL_ID = "WifiShareCh";
    private static final int    NOTIF_ID   = 42;

    private WifiP2pManager        p2pManager;
    private WifiP2pManager.Channel p2pChannel;
    private ServerSocket          proxySocket;
    private ServerSocket          configSocket;
    private ExecutorService       threadPool;
    private boolean               groupCreated = false;
    private String                pendingSsid  = "";
    private String                pendingPass  = "";
    // Stored so teardown can remove the exact same iptables rules
    private String                activeP2pIface = null;
    private String                activeWanIface = null;

    // ── Lifecycle ─────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        p2pManager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        p2pChannel  = p2pManager.initialize(this, getMainLooper(), null);
        threadPool  = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            pendingSsid = intent.getStringExtra("ssid");
            pendingPass = intent.getStringExtra("pass");
            if (pendingSsid == null) pendingSsid = "";
            if (pendingPass == null) pendingPass = "";
            startForeground(NOTIF_ID, buildNotif("Starting…", ""));
            createP2pGroup();
        } else if (ACTION_STOP.equals(action)) {
            stopEverything();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        stopEverything();
        super.onDestroy();
    }

    // ── WiFi Direct group ─────────────────────────────────────────
    private void createP2pGroup() {
        p2pManager.removeGroup(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { createGroupNow(); }
            @Override public void onFailure(int r)  { createGroupNow(); }
        });
    }

    private void createGroupNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: custom SSID and passphrase
            String pass = pendingPass.isEmpty() ? "12345678" : pendingPass;
            WifiP2pConfig config = new WifiP2pConfig.Builder()
                    .setNetworkName("DIRECT-" + pendingSsid)
                    .setPassphrase(pass)
                    .enablePersistentMode(false)
                    .build();
            p2pManager.createGroup(p2pChannel, config, groupListener);
        } else {
            // Android 6-9: system assigns SSID/pass (no API to override)
            p2pManager.createGroup(p2pChannel, groupListener);
        }
    }

    private final WifiP2pManager.ActionListener groupListener = new WifiP2pManager.ActionListener() {
        @Override
        public void onSuccess() {
            Log.d(TAG, "P2P group created, querying info…");
            new Handler(Looper.getMainLooper()).postDelayed(ProxyService.this::requestGroupInfo, 1500);
        }

        @Override
        public void onFailure(int reason) {
            String msg = "WiFi Direct failed (code " + reason + "). "
                    + "Make sure WiFi is ON and Location permission is granted.";
            Log.e(TAG, msg);
            broadcast("ERROR", null, 0, msg);
            stopSelf();
        }
    };

    private void requestGroupInfo() {
        p2pManager.requestGroupInfo(p2pChannel, group -> {
            if (group == null) {
                broadcast("ERROR", null, 0, "Could not get group info. Try again.");
                stopSelf();
                return;
            }

            String ssid = group.getNetworkName();
            if (ssid == null) ssid = "DIRECT-" + pendingSsid;
            activeGroupSsid = ssid;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String pp = group.getPassphrase();
                activeGroupPass = (pp != null && !pp.isEmpty()) ? pp : "12345678";
            } else {
                // getPassphrase() is hidden API pre-Q but exists in the class — use reflection
                // to read the real system-assigned password instead of guessing
                String reflected = null;
                try {
                    java.lang.reflect.Method m = group.getClass().getMethod("getPassphrase");
                    reflected = (String) m.invoke(group);
                } catch (Exception ignored) {}
                activeGroupPass = (reflected != null && !reflected.isEmpty()) ? reflected
                        : (pendingPass.isEmpty() ? "12345678" : pendingPass);
            }

            currentIp    = "192.168.49.1";
            groupCreated = true;
            running      = true;

            Log.d(TAG, "Group: " + activeGroupSsid + " pass=" + activeGroupPass);
            updateNotif("Starting — " + activeGroupSsid, "");

            // Attempt transparent NAT (root) + proxy fallback in background
            threadPool.execute(this::startNetworking);
        });
    }

    /**
     * Runs in a background thread.
     * Tries iptables NAT first; falls back to HTTP proxy if no root.
     * Broadcasts STARTED when ready (with natMode flag).
     */
    private void startNetworking() {
        // ── Try transparent NAT (requires root) ───────────────────
        String p2pIface = RootHelper.findP2pInterface();
        String wanIface = (p2pIface != null) ? RootHelper.findWanInterface(p2pIface) : null;

        if (p2pIface != null && RootHelper.isRootAvailable()) {
            boolean ok = RootHelper.setupNat(p2pIface, wanIface);
            if (ok) {
                activeP2pIface = p2pIface;
                activeWanIface = wanIface;
                natMode = true;
                Log.d(TAG, "NAT active: " + p2pIface + " → " + wanIface);
                updateNotif("Router Mode — " + activeGroupSsid, "transparent NAT");
            } else {
                Log.w(TAG, "NAT setup failed, falling back to proxy");
            }
        } else {
            Log.d(TAG, "Root not available — proxy mode");
        }

        // ── Always start proxy + config servers ───────────────────
        // In NAT mode the proxy is a transparent fallback; in non-root
        // mode it is the primary path.
        startProxyServerInternal();
        startConfigServerInternal();
    }

    // ── Proxy server (port 8282) ──────────────────────────────────
    // Called from startNetworking() which already runs in a thread pool thread.
    private void startProxyServerInternal() {
        try {
            proxySocket = new ServerSocket();
            proxySocket.setReuseAddress(true);
            proxySocket.bind(new InetSocketAddress(currentIp, PROXY_PORT), 50);
            Log.d(TAG, "Proxy listening on " + currentIp + ":" + PROXY_PORT);

            // Notify UI — by this point natMode is already set
            broadcastStarted();
            updateNotif(natMode ? "Router Mode — " + activeGroupSsid
                                : "Proxy Mode — " + activeGroupSsid,
                        natMode ? "transparent NAT" : currentIp + ":" + PROXY_PORT);

            while (!proxySocket.isClosed()) {
                try {
                    Socket client = proxySocket.accept();
                    threadPool.execute(new ProxyWorker(client));
                } catch (IOException e) {
                    if (!proxySocket.isClosed()) Log.w(TAG, "Accept err: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Proxy start error: " + e.getMessage());
            broadcast("ERROR", null, 0, "Proxy failed: " + e.getMessage());
            stopSelf();
        }
    }

    // ── Config / PAC server (port 8080) ──────────────────────────
    private void startConfigServerInternal() {
        threadPool.execute(() -> {
            try {
                configSocket = new ServerSocket();
                configSocket.setReuseAddress(true);
                configSocket.bind(new InetSocketAddress(currentIp, CONFIG_PORT), 20);
                Log.d(TAG, "Config server listening on " + currentIp + ":" + CONFIG_PORT);

                while (!configSocket.isClosed()) {
                    try {
                        final Socket client = configSocket.accept();
                        threadPool.execute(() -> handleConfigRequest(client));
                    } catch (IOException e) {
                        if (!configSocket.isClosed()) Log.w(TAG, "Config accept err: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Config server failed (non-critical): " + e.getMessage());
            }
        });
    }

    private void handleConfigRequest(Socket client) {
        try {
            client.setSoTimeout(10_000);
            InputStream  in  = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String requestLine = readHttpLine(in);
            if (requestLine.isEmpty()) return;

            // Drain request headers
            while (!readHttpLine(in).isEmpty()) { /* discard */ }

            // Parse path from "GET /path HTTP/1.1"
            String path = "/";
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) {
                path = parts[1];
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
            }

            String body;
            String contentType;

            if (path.equals("/proxy.pac") || path.equals("/wpad.dat") || path.equals("/wpad")) {
                contentType = "application/x-ns-proxy-autoconfig";
                body = buildPacFile();
            } else if (path.equals("/generate_204") || path.equals("/connecttest.txt")) {
                // Respond to Android/Windows captive portal checks so WiFi shows "connected"
                byte[] resp = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII);
                out.write(resp);
                out.flush();
                return;
            } else {
                contentType = "text/html; charset=utf-8";
                body = buildSetupPage();
            }

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            byte[] header = ("HTTP/1.1 200 OK\r\n" +
                             "Content-Type: " + contentType + "\r\n" +
                             "Content-Length: " + bodyBytes.length + "\r\n" +
                             "Cache-Control: no-cache\r\n" +
                             "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            out.write(header);
            out.write(bodyBytes);
            out.flush();

        } catch (Exception e) {
            Log.w(TAG, "Config request error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private String buildPacFile() {
        return "function FindProxyForURL(url, host) {\n" +
               "  if (shExpMatch(host, '192.168.49.*')) return 'DIRECT';\n" +
               "  if (host === 'localhost' || host === '127.0.0.1') return 'DIRECT';\n" +
               "  return 'PROXY " + currentIp + ":" + PROXY_PORT + "';\n" +
               "}\n";
    }

    private String buildSetupPage() {
        String pacUrl  = "http://" + currentIp + ":" + CONFIG_PORT + "/proxy.pac";
        String modeBar = natMode
                ? "<div style='background:#2e7d32;color:#fff;border-radius:8px;padding:14px;margin-bottom:14px'>"
                  + "<b>ROUTER MODE (transparent NAT)</b><br>"
                  + "<span style='font-size:13px'>Root detected — internet works automatically on all apps. No proxy setup needed.</span>"
                  + "</div>"
                : "<div style='background:#1565c0;color:#fff;border-radius:8px;padding:14px;margin-bottom:14px'>"
                  + "<b>PROXY MODE</b><br>"
                  + "<span style='font-size:13px'>No root — follow the steps below to configure internet on connected devices.</span>"
                  + "</div>";

        String proxySection = natMode ? "" :
               "<div class='card'>" +
               "<h2>Android — Auto Proxy (easiest)</h2>" +
               "<div class='step'><div class='num'>1</div><p>WiFi Settings → long-press this network → <b>Modify</b></p></div>" +
               "<div class='step'><div class='num'>2</div><p>Advanced → Proxy: <b>Auto-config</b></p></div>" +
               "<div class='step'><div class='num'>3</div><p>URL: <code>" + pacUrl + "</code> → Save</p></div>" +
               "</div>" +
               "<div class='card'>" +
               "<h2>iOS — Auto Proxy</h2>" +
               "<div class='step'><div class='num'>1</div><p>Settings → WiFi → tap (i) → Configure Proxy → <b>Auto</b></p></div>" +
               "<div class='step'><div class='num'>2</div><p>URL: <code>" + pacUrl + "</code></p></div>" +
               "</div>" +
               "<div class='card'>" +
               "<h2>Manual Proxy</h2>" +
               "<p>Host: <code>" + currentIp + "</code>&nbsp;&nbsp;Port: <code>" + PROXY_PORT + "</code></p>" +
               "</div>" +
               "<div class='card'>" +
               "<h2>WhatsApp / Signal</h2>" +
               "<p>WhatsApp → Settings → Privacy → Advanced → Proxy → Enable</p>" +
               "<p>Server: <code>" + currentIp + "</code>&nbsp;&nbsp;Port: <code>" + PROXY_PORT + "</code></p>" +
               "</div>";

        return "<!DOCTYPE html><html><head>" +
               "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
               "<title>WiFi Share Setup</title>" +
               "<style>" +
               "*{box-sizing:border-box;margin:0;padding:0}" +
               "body{font-family:sans-serif;padding:16px;background:#f0f4f8;max-width:520px;margin:auto}" +
               "h1{color:#1565C0;font-size:20px;margin-bottom:16px}" +
               ".card{background:#fff;border-radius:10px;padding:16px;margin-bottom:14px;box-shadow:0 1px 4px rgba(0,0,0,.1)}" +
               "h2{color:#1565C0;font-size:14px;text-transform:uppercase;letter-spacing:.08em;margin-bottom:10px}" +
               "p{font-size:14px;margin-bottom:6px;color:#333;line-height:1.5}" +
               "code{background:#e3f2fd;color:#0d47a1;padding:2px 6px;border-radius:4px;font-size:13px}" +
               ".step{display:flex;align-items:flex-start;margin-bottom:8px}" +
               ".num{background:#1565C0;color:#fff;border-radius:50%;width:22px;height:22px;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:bold;flex-shrink:0;margin-right:8px;margin-top:1px}" +
               "</style></head><body>" +
               "<h1>WiFi Share</h1>" +
               modeBar +
               "<div class='card'><h2>Hotspot</h2>" +
               "<p>Network: <code>" + activeGroupSsid + "</code></p>" +
               "<p>Password: <code>" + (activeGroupPass.isEmpty() ? "(open)" : activeGroupPass) + "</code></p>" +
               "</div>" +
               proxySection +
               "</body></html>";
    }

    // ── Cleanup ───────────────────────────────────────────────────
    private void stopEverything() {
        running = false;

        // Remove NAT rules before shutting down thread pool
        if (natMode) {
            RootHelper.teardownNat(activeP2pIface, activeWanIface);
            natMode        = false;
            activeP2pIface = null;
            activeWanIface = null;
        }

        try { if (proxySocket  != null && !proxySocket.isClosed())  proxySocket.close();  }
        catch (IOException ignored) {}
        try { if (configSocket != null && !configSocket.isClosed()) configSocket.close(); }
        catch (IOException ignored) {}
        if (groupCreated && p2pManager != null) {
            p2pManager.removeGroup(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { Log.d(TAG, "Group removed"); }
                @Override public void onFailure(int r) {}
            });
            groupCreated = false;
        }
        if (threadPool != null) threadPool.shutdownNow();
        broadcast("STOPPED", null, 0, null);
        stopForeground(true);
        stopSelf();
    }

    // ── Helpers ───────────────────────────────────────────────────
    public static boolean isRunning() { return running; }

    private void broadcastStarted() {
        Intent i = new Intent(BROADCAST_ACTION);
        i.putExtra("action", "STARTED");
        i.putExtra("ip",   currentIp);
        i.putExtra("port", PROXY_PORT);
        i.putExtra("ssid", activeGroupSsid);
        i.putExtra("pass", activeGroupPass);
        i.putExtra("nat",  natMode);
        sendBroadcast(i);
    }

    private void broadcast(String action, String ip, int port, String msg) {
        Intent i = new Intent(BROADCAST_ACTION);
        i.putExtra("action", action);
        if (ip  != null) i.putExtra("ip",   ip);
        if (port > 0)    i.putExtra("port", port);
        if (msg != null) i.putExtra("msg",  msg);
        sendBroadcast(i);
    }

    private static String readHttpLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    // ── Notification ─────────────────────────────────────────────
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "WiFi Share", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent stopI = new Intent(this, ProxyService.class);
        stopI.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopI,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WiFi Share — " + title)
                .setContentText(text.isEmpty() ? "Running in background" : "Proxy: " + text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
                .setOngoing(true)
                .build();
    }

    private void updateNotif(String title, String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, buildNotif(title, text));
    }
}
