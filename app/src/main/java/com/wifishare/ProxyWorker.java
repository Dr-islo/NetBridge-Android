package com.wifishare;

import android.util.Log;

import java.io.*;
import java.net.*;

/**
 * Handles a single client connection through the proxy.
 * Supports:
 *   - Plain HTTP (GET, POST, etc.)  → forwards request to target server
 *   - HTTPS via CONNECT tunnel      → creates raw TCP tunnel, no MITM
 */
public class ProxyWorker implements Runnable {

    private static final String TAG        = "ProxyWorker";
    private static final int    TIMEOUT_MS = 30_000;
    private static final int    BUF_SIZE   = 8192;

    private final Socket clientSocket;

    public ProxyWorker(Socket clientSocket) {
        this.clientSocket = clientSocket;
        try {
            clientSocket.setSoTimeout(TIMEOUT_MS);
            clientSocket.setTcpNoDelay(true);
        } catch (Exception ignored) {}
    }

    @Override
    public void run() {
        try (Socket cs = clientSocket) {
            InputStream  clientIn  = cs.getInputStream();
            OutputStream clientOut = cs.getOutputStream();

            String firstLine = readLine(clientIn);
            if (firstLine == null || firstLine.isEmpty()) return;

            Log.d(TAG, "Request: " + firstLine);

            if (firstLine.startsWith("CONNECT ")) {
                handleConnect(firstLine, clientIn, clientOut);
            } else {
                handleHttp(firstLine, clientIn, clientOut);
            }
        } catch (Exception e) {
            Log.w(TAG, "Worker error: " + e.getMessage());
        }
    }

    // ── HTTPS CONNECT tunnel ──────────────────────────────────────
    private void handleConnect(String firstLine, InputStream clientIn, OutputStream clientOut)
            throws IOException {
        // "CONNECT host:port HTTP/1.1"
        String[] parts = firstLine.split(" ");
        if (parts.length < 2) return;
        String[] hostPort = parts[1].split(":");
        String host = hostPort[0];
        int    port = hostPort.length > 1 ? safeParseInt(hostPort[1], 443) : 443;

        drainHeaders(clientIn);

        try (Socket remote = new Socket()) {
            remote.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            remote.setSoTimeout(TIMEOUT_MS);

            // Tell client tunnel is ready
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
            clientOut.flush();

            pipe(clientIn, clientOut, remote.getInputStream(), remote.getOutputStream());
        } catch (Exception e) {
            try { clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes()); }
            catch (IOException ignored) {}
        }
    }

    // ── Plain HTTP ────────────────────────────────────────────────
    private void handleHttp(String firstLine, InputStream clientIn, OutputStream clientOut)
            throws IOException {
        // "GET http://example.com/path HTTP/1.1"
        String[] tokens = firstLine.split(" ");
        if (tokens.length < 3) return;

        String method  = tokens[0];
        String urlStr  = tokens[1];
        String version = tokens[2];

        // Read headers, tracking Host and Content-Length
        StringBuilder headerBuf = new StringBuilder();
        String line;
        String host          = null;
        int    port          = 80;
        long   contentLength = -1;

        while (!(line = readLine(clientIn)).isEmpty()) {
            String lower = line.toLowerCase();
            if (lower.startsWith("host:")) {
                String hostVal = line.substring(5).trim();
                if (hostVal.contains(":")) {
                    String[] hp = hostVal.split(":", 2);
                    host = hp[0];
                    port = safeParseInt(hp[1], 80);
                } else {
                    host = hostVal;
                }
            } else if (lower.startsWith("content-length:")) {
                try { contentLength = Long.parseLong(line.substring(15).trim()); }
                catch (NumberFormatException ignored) {}
            }
            // Strip proxy-specific headers
            if (!lower.startsWith("proxy-connection:") &&
                !lower.startsWith("proxy-authorization:")) {
                headerBuf.append(line).append("\r\n");
            }
        }

        if (host == null) return;

        // Build path
        String path = urlStr;
        try {
            URL u = new URL(urlStr);
            path = u.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (u.getQuery() != null) path += "?" + u.getQuery();
        } catch (Exception ignored) {}

        try (Socket remote = new Socket()) {
            remote.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            remote.setSoTimeout(TIMEOUT_MS);

            OutputStream remoteOut = remote.getOutputStream();
            InputStream  remoteIn  = remote.getInputStream();

            // Forward request line + headers
            remoteOut.write((method + " " + path + " " + version + "\r\n").getBytes());
            remoteOut.write(headerBuf.toString().getBytes());
            remoteOut.write("Connection: close\r\n\r\n".getBytes());

            // Forward request body using Content-Length for accuracy
            if (contentLength > 0) {
                byte[] buf = new byte[BUF_SIZE];
                long remaining = contentLength;
                while (remaining > 0) {
                    int n = clientIn.read(buf, 0, (int) Math.min(remaining, BUF_SIZE));
                    if (n < 0) break;
                    remoteOut.write(buf, 0, n);
                    remaining -= n;
                }
            } else if (contentLength < 0) {
                // Unknown body size: forward whatever is immediately available
                int avail = clientIn.available();
                if (avail > 0) {
                    byte[] buf = new byte[BUF_SIZE];
                    int n = clientIn.read(buf, 0, Math.min(avail, BUF_SIZE));
                    if (n > 0) remoteOut.write(buf, 0, n);
                }
            }
            remoteOut.flush();

            // Stream response back to client
            byte[] buf = new byte[BUF_SIZE];
            int n;
            while ((n = remoteIn.read(buf)) > 0) {
                clientOut.write(buf, 0, n);
            }
            clientOut.flush();
        }
    }

    // ── Bidirectional pipe for CONNECT tunnels ─────────────────────
    private void pipe(InputStream c2s, OutputStream c2sOut,
                      InputStream s2c, OutputStream s2cOut) {
        Thread serverToClient = new Thread(() -> {
            try {
                byte[] buf = new byte[BUF_SIZE];
                int n;
                while ((n = s2c.read(buf)) > 0) {
                    c2sOut.write(buf, 0, n);
                    c2sOut.flush();
                }
            } catch (Exception ignored) {}
        });
        serverToClient.setDaemon(true);
        serverToClient.start();

        try {
            byte[] buf = new byte[BUF_SIZE];
            int n;
            while ((n = c2s.read(buf)) > 0) {
                s2cOut.write(buf, 0, n);
                s2cOut.flush();
            }
        } catch (Exception ignored) {}

        try { serverToClient.join(5000); } catch (Exception ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────
    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    private void drainHeaders(InputStream in) throws IOException {
        while (!readLine(in).isEmpty()) { /* discard */ }
    }

    private static int safeParseInt(String s, int defaultVal) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}
