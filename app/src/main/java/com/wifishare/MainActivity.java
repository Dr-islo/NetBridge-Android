package com.wifishare;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView tvWifiName, tvStatus;
    private TextView tvHotspotSsid, tvHotspotPass;
    private TextView tvProxyInfo, tvPacUrl, tvClientsLabel;
    private EditText etGroupName, etGroupPass;
    private Button   btnToggle, btnCopyProxy, btnCopyPacUrl;
    private View     cardProxy;
    private boolean  isRunning = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getStringExtra("action");
            if (action == null) return;
            switch (action) {
                case "STARTED":
                    String ip   = intent.getStringExtra("ip");
                    int    port = intent.getIntExtra("port", ProxyService.PROXY_PORT);
                    String ssid = intent.getStringExtra("ssid");
                    String pass = intent.getStringExtra("pass");
                    boolean nat = intent.getBooleanExtra("nat", false);
                    onStarted(ip, port, ssid, pass, nat);
                    break;
                case "STOPPED":
                    onStopped();
                    break;
                case "ERROR":
                    showError(intent.getStringExtra("msg"));
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvWifiName    = findViewById(R.id.tvWifiName);
        tvStatus      = findViewById(R.id.tvStatus);
        tvHotspotSsid = findViewById(R.id.tvHotspotSsid);
        tvHotspotPass = findViewById(R.id.tvHotspotPass);
        tvProxyInfo   = findViewById(R.id.tvProxyInfo);
        tvPacUrl      = findViewById(R.id.tvPacUrl);
        tvClientsLabel= findViewById(R.id.tvClientsLabel);
        etGroupName   = findViewById(R.id.etGroupName);
        etGroupPass   = findViewById(R.id.etGroupPass);
        btnToggle     = findViewById(R.id.btnToggle);
        btnCopyProxy  = findViewById(R.id.btnCopyProxy);
        btnCopyPacUrl = findViewById(R.id.btnCopyPacUrl);
        cardProxy     = findViewById(R.id.cardProxy);

        requestAllPermissions();
        checkWriteSettings();
        loadCurrentWifi();

        btnToggle.setOnClickListener(v -> {
            if (isRunning) stopSharing();
            else startSharing();
        });

        btnCopyProxy.setOnClickListener(v -> copyToClipboard(
                tvProxyInfo.getText().toString(), "Proxy address copied!"));

        btnCopyPacUrl.setOnClickListener(v -> copyToClipboard(
                tvPacUrl.getText().toString(), "PAC URL copied!"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ProxyService.BROADCAST_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        if (ProxyService.isRunning()) {
            onStarted(ProxyService.currentIp, ProxyService.PROXY_PORT,
                      ProxyService.activeGroupSsid, ProxyService.activeGroupPass,
                      ProxyService.natMode);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }

    private void loadCurrentWifi() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null && wm.isWifiEnabled()) {
            WifiInfo info = wm.getConnectionInfo();
            String ssid = info.getSSID();
            if (ssid != null) ssid = ssid.replace("\"", "");
            if (ssid == null || ssid.equals("<unknown ssid>")) ssid = "Unknown";
            tvWifiName.setText("Connected to: " + ssid);
            etGroupName.setText(ssid + "_Share");
        } else {
            tvWifiName.setText("WiFi: Not connected");
        }
    }

    private void startSharing() {
        String name = etGroupName.getText().toString().trim();
        String pass = etGroupPass.getText().toString().trim();

        if (name.isEmpty()) { etGroupName.setError("Required"); return; }
        if (!pass.isEmpty() && pass.length() < 8) {
            etGroupPass.setError("Min 8 characters (or leave blank)"); return;
        }

        tvStatus.setText("Starting…");
        btnToggle.setEnabled(false);

        Intent i = new Intent(this, ProxyService.class);
        i.setAction(ProxyService.ACTION_START);
        i.putExtra("ssid", name);
        i.putExtra("pass", pass);
        ContextCompat.startForegroundService(this, i);
    }

    private void stopSharing() {
        Intent i = new Intent(this, ProxyService.class);
        i.setAction(ProxyService.ACTION_STOP);
        startService(i);
    }

    private void onStarted(String ip, int port, String ssid, String pass, boolean nat) {
        if (ip == null) ip = "192.168.49.1";
        if (ssid == null) ssid = "";
        if (pass == null) pass = "";

        isRunning = true;
        btnToggle.setEnabled(true);
        btnToggle.setText("Stop Sharing");
        btnToggle.setBackgroundResource(R.drawable.btn_stop);

        if (nat) {
            tvStatus.setText("ROUTER MODE ACTIVE  (transparent NAT)");
            tvStatus.setTextColor(0xFF2E7D32);
        } else {
            tvStatus.setText("PROXY MODE ACTIVE");
            tvStatus.setTextColor(0xFF1565C0);
        }

        // Actual SSID/password from the WiFi Direct group
        tvHotspotSsid.setText("Network: " + (ssid.isEmpty() ? "DIRECT-" + etGroupName.getText() : ssid));
        tvHotspotPass.setText("Password: " + (pass.isEmpty() ? "(open)" : pass));

        // Proxy address (always shown; useful as fallback even in NAT mode)
        tvProxyInfo.setText(ip + ":" + port);

        // PAC auto-config URL
        String pacUrl   = "http://" + ip + ":" + ProxyService.CONFIG_PORT + "/proxy.pac";
        String setupUrl = "http://" + ip + ":" + ProxyService.CONFIG_PORT + "/";
        tvPacUrl.setText(pacUrl);

        if (nat) {
            // Router mode: no proxy setup needed
            tvClientsLabel.setText(
                "Root detected — iptables NAT is active.\n\n" +
                "Just connect to the hotspot above.\n" +
                "Internet works on ALL apps automatically,\n" +
                "including WhatsApp, without any proxy setup.\n\n" +
                "Full status page: " + setupUrl
            );
        } else {
            // Proxy mode
            tvClientsLabel.setText(
                "Android: WiFi settings → long-press network → Modify\n" +
                "  → Advanced → Proxy: Auto-config → paste PAC URL above\n\n" +
                "iOS: Settings → WiFi → (i) → Configure Proxy → Auto\n" +
                "  → paste PAC URL above\n\n" +
                "WhatsApp: Settings → Privacy → Advanced → Proxy → Enable\n" +
                "  Server: " + ip + "   Port: " + port + "\n\n" +
                "Full guide (open in browser on the other phone):\n" +
                setupUrl
            );
        }

        cardProxy.setVisibility(View.VISIBLE);
    }

    private void onStopped() {
        isRunning = false;
        btnToggle.setEnabled(true);
        btnToggle.setText("Start Sharing");
        btnToggle.setBackgroundResource(R.drawable.btn_start);
        tvStatus.setText("Sharing is STOPPED");
        tvStatus.setTextColor(0xFFF44336);
        cardProxy.setVisibility(View.GONE);
    }

    private void showError(String msg) {
        btnToggle.setEnabled(true);
        tvStatus.setText("Error — see dialog");
        tvStatus.setTextColor(0xFFF44336);
        new AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show();
    }

    private void copyToClipboard(String text, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("clip", text));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    // ── Permissions ───────────────────────────────────────────────
    private void requestAllPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_WIFI_STATE);
        perms.add(Manifest.permission.CHANGE_WIFI_STATE);
        perms.add(Manifest.permission.ACCESS_NETWORK_STATE);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), 1);
        }
    }

    private void checkWriteSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Permission Needed")
                .setMessage("Allow 'Modify system settings' so the hotspot can be created.")
                .setPositiveButton("Open Settings", (d, w) -> startActivityForResult(
                    new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName())), 100))
                .setNegativeButton("Cancel", null)
                .show();
        }
    }
}
