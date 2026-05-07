package com.wifishare;

import android.util.Log;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Utilities for running shell commands as root and setting up
 * transparent NAT via iptables so connected WiFi Direct clients
 * get internet without any proxy configuration.
 *
 * All methods are safe to call on non-rooted devices — they return false/null
 * and the caller falls back to proxy mode.
 */
public class RootHelper {

    private static final String TAG         = "RootHelper";
    private static final int    SU_TIMEOUT  = 5_000; // ms

    // ── Root detection ────────────────────────────────────────────

    /** Returns true if su is present and grants uid 0. */
    public static boolean isRootAvailable() {
        try {
            Process p = exec("su", "-c", "id");
            String out = drain(p.getInputStream());
            boolean ok = waitFor(p) == 0 && out.contains("uid=0");
            Log.d(TAG, "Root check: " + (ok ? "GRANTED" : "denied") + " — " + out.trim());
            return ok;
        } catch (Exception e) {
            Log.d(TAG, "Root not available: " + e.getMessage());
            return false;
        }
    }

    // ── Interface detection ───────────────────────────────────────

    /**
     * Finds the WiFi Direct Group Owner interface by locating the
     * network interface that holds 192.168.49.1.
     */
    public static String findP2pInterface() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    String host = addrs.nextElement().getHostAddress();
                    if ("192.168.49.1".equals(host)) {
                        Log.d(TAG, "P2P interface: " + iface.getName());
                        return iface.getName();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findP2pInterface: " + e.getMessage());
        }
        return null;
    }

    /**
     * Finds the uplink interface (the one carrying internet traffic).
     * Reads the kernel routing table; falls back to the first wlan/eth
     * interface that is NOT the P2P interface.
     */
    public static String findWanInterface(String p2pIface) {
        // Primary: kernel default route
        try {
            Process p = exec("ip", "route", "show", "default");
            String out = drain(p.getInputStream());
            for (String line : out.split("\n")) {
                if (!line.startsWith("default")) continue;
                String[] tok = line.split("\\s+");
                for (int i = 0; i + 1 < tok.length; i++) {
                    if ("dev".equals(tok[i])) {
                        String iface = tok[i + 1];
                        if (!iface.equals(p2pIface)) {
                            Log.d(TAG, "WAN interface (route): " + iface);
                            return iface;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ip route: " + e.getMessage());
        }

        // Fallback: first wlan/eth interface that isn't the P2P one
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                while (ifaces.hasMoreElements()) {
                    NetworkInterface iface = ifaces.nextElement();
                    String name = iface.getName();
                    if (name.equals(p2pIface)) continue;
                    if (name.startsWith("wlan") || name.startsWith("eth")) {
                        // Must have an address that's not in 192.168.49.x
                        Enumeration<InetAddress> addrs = iface.getInetAddresses();
                        while (addrs.hasMoreElements()) {
                            String host = addrs.nextElement().getHostAddress();
                            if (!host.startsWith("192.168.49.") && !host.contains(":")) {
                                Log.d(TAG, "WAN interface (enum): " + name);
                                return name;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Interface enum: " + e.getMessage());
        }

        Log.w(TAG, "WAN interface not found, defaulting to wlan0");
        return "wlan0";
    }

    // ── NAT setup / teardown ──────────────────────────────────────

    /**
     * Installs iptables rules for transparent NAT.
     * Traffic from p2pIface is masqueraded out through wanIface,
     * making the phone act like a router — no proxy needed on clients.
     *
     * @return true if all rules were applied successfully
     */
    public static boolean setupNat(String p2pIface, String wanIface) {
        Log.d(TAG, "setupNat  p2p=" + p2pIface + "  wan=" + wanIface);
        String[] cmds = {
            // Enable kernel IP forwarding
            "sysctl -w net.ipv4.ip_forward=1",

            // NAT: masquerade packets leaving through the internet interface
            "iptables -t nat -A POSTROUTING -o " + wanIface + " -j MASQUERADE",

            // FORWARD: allow packets routed from P2P → internet
            "iptables -A FORWARD -i " + p2pIface + " -o " + wanIface + " -j ACCEPT",

            // FORWARD: allow established/related return packets internet → P2P
            "iptables -A FORWARD -i " + wanIface + " -o " + p2pIface
                    + " -m state --state RELATED,ESTABLISHED -j ACCEPT",
        };
        boolean ok = runAsRoot(cmds);
        Log.d(TAG, "setupNat result: " + ok);
        return ok;
    }

    /**
     * Removes the iptables rules created by setupNat().
     * Safe to call even if setup partially failed (-D silently fails on
     * rules that don't exist).
     */
    public static void teardownNat(String p2pIface, String wanIface) {
        if (p2pIface == null || wanIface == null) return;
        Log.d(TAG, "teardownNat  p2p=" + p2pIface + "  wan=" + wanIface);
        runAsRoot(new String[]{
            "sysctl -w net.ipv4.ip_forward=0",
            "iptables -t nat -D POSTROUTING -o " + wanIface + " -j MASQUERADE",
            "iptables -D FORWARD -i " + p2pIface + " -o " + wanIface + " -j ACCEPT",
            "iptables -D FORWARD -i " + wanIface + " -o " + p2pIface
                    + " -m state --state RELATED,ESTABLISHED -j ACCEPT",
        });
    }

    // ── Shell helpers ─────────────────────────────────────────────

    /**
     * Runs an array of shell commands in a single su session.
     * Returns true only if su exits with code 0.
     */
    public static boolean runAsRoot(String[] commands) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream dos = new DataOutputStream(p.getOutputStream());
            for (String cmd : commands) {
                Log.v(TAG, "  $ " + cmd);
                dos.writeBytes(cmd + "\n");
            }
            dos.writeBytes("exit\n");
            dos.flush();
            int exit = waitFor(p);
            // Drain stderr so the process doesn't block
            drain(p.getErrorStream());
            return exit == 0;
        } catch (Exception e) {
            Log.w(TAG, "runAsRoot failed: " + e.getMessage());
            return false;
        }
    }

    // ── Private utilities ─────────────────────────────────────────

    private static Process exec(String... cmd) throws IOException {
        return Runtime.getRuntime().exec(cmd);
    }

    private static String drain(InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } catch (IOException ignored) {}
        return sb.toString();
    }

    private static int waitFor(Process p) {
        try {
            // Avoid hanging forever
            long deadline = System.currentTimeMillis() + SU_TIMEOUT;
            while (System.currentTimeMillis() < deadline) {
                try {
                    return p.exitValue();
                } catch (IllegalThreadStateException e) {
                    Thread.sleep(50);
                }
            }
            p.destroy();
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
