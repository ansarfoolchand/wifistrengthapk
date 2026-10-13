package com.example.wifigauge;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live WiFi signal-strength gauge.
 *
 * Sensitivity strategy:
 *  - reacts instantly to WifiManager.RSSI_CHANGED broadcasts (the OS fires this
 *    the moment the driver reports a signal change)
 *  - a 100 ms poller catches anything the asynchronous event misses
 *  - the needle uses an exponential smoother whose time constant is user
 *    adjustable (default "fast"); an additional thin needle always sits exactly
 *    on the latest reading
 *  - a 30-second scrolling sparkline shows fine-grained fluctuations
 */
public class MainActivity extends Activity {

    private static final long POLL_MS = 100;
    private static final int REQ_PERMS = 1;
    private static final int MIN_RSSI = -100;
    private static final int MAX_RSSI = -40;

    private WifiManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private GaugeView gauge;
    private SparklineView spark;
    private TextView statusView, ssidView, deltaView, barsView, pctView, sensLabel;
    private SeekBar sensSeek;

    private int lastRssi = Integer.MIN_VALUE;
    private int delta = 0;
    private boolean permissionVisible = false;

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            updateRssi();
            maybeScan();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable scanKick = new Runnable() {
        @Override public void run() {
            maybeScan();
            handler.postDelayed(this, 15000);
        }
    };

    private final BroadcastReceiver rssiReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateRssi(); // instantly reflects driver-reported changes
        }
    };

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateRssi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        gauge = findViewById(R.id.gauge);
        spark = findViewById(R.id.spark);
        statusView = findViewById(R.id.statusView);
        ssidView = findViewById(R.id.ssidView);
        deltaView = findViewById(R.id.deltaView);
        barsView = findViewById(R.id.barsView);
        pctView = findViewById(R.id.pctView);
        sensLabel = findViewById(R.id.sensLabel);
        sensSeek = findViewById(R.id.sensSeek);

        // sensitivity: higher = snappier needle
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        int saved = prefs.getInt("sensitivity", 80);
        sensSeek.setProgress(saved);
        applySensitivity(saved);

        sensSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                applySensitivity(progress);
                if (fromUser) {
                    getPreferences(MODE_PRIVATE).edit().putInt("sensitivity", progress).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        View statusClick = findViewById(R.id.statusView);
        statusClick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                requestMissingPermissions();
            }
        });
    }

    private void applySensitivity(int progress) {
        float s = progress / 100f;
        gauge.setSensitivity01(s);
        String label;
        if (s >= 0.85f) label = "Instant";
        else if (s >= 0.6f) label = "Fast";
        else if (s >= 0.35f) label = "Balanced";
        else label = "Smooth";
        sensLabel.setText(label);
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean allPerms() {
        return has(Manifest.permission.ACCESS_FINE_LOCATION) &&
                (Build.VERSION.SDK_INT < 33 ||
                 has(Manifest.permission.NEARBY_WIFI_DEVICES));
    }

    private void requestMissingPermissions() {
        ArrayList<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.NEARBY_WIFI_DEVICES)) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            // also useful on 13+ so scan results reflect the user's location choice
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        String[] arr = needed.toArray(new String[0]);
        if (arr.length > 0) {
            requestPermissions(arr, REQ_PERMS);
        } else {
            updateRssi();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        updateRssi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f1 = new IntentFilter(WifiManager.RSSI_CHANGED_ACTION);
        IntentFilter f2 = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rssiReceiver, f1, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(scanReceiver, f2, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(rssiReceiver, f1);
            registerReceiver(scanReceiver, f2);
        }
        handler.post(poller);
        handler.post(scanKick);
        updateRssi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poller);
        handler.removeCallbacks(scanKick);
        try {
            unregisterReceiver(rssiReceiver);
        } catch (Exception ignored) { }
        try {
            unregisterReceiver(scanReceiver);
        } catch (Exception ignored) { }
    }

    private void maybeScan() {
        if (!allPerms()) return;
        WifiInfo info = wm == null ? null : wm.getConnectionInfo();
        if (info != null && info.getRssi() > -127) return; // already connected to something
        try {
            wm.startScan(); // throttled by the system; used when not connected
        } catch (SecurityException ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void updateRssi() {
        WifiInfo info = wm == null ? null : wm.getConnectionInfo();
        int rssi = info == null ? Integer.MIN_VALUE : info.getRssi();
        String ssid = info == null ? null : info.getSSID();
        if (ssid == null || ssid.equals("unknown ssid") || ssid.equals("<unknown ssid>")) {
            ssid = null;
        }
        boolean connected = rssi != Integer.MIN_VALUE && rssi > -127;

        String source = connected ? ssid : null;

        // Not connected: fall back to the strongest nearby network from scan results.
        if (!connected && allPerms()) {
            try {
                List<ScanResult> results = wm.getScanResults();
                if (results != null) {
                    ScanResult best = null;
                    for (ScanResult r : results) {
                        int l = r.level;
                        if (l == Integer.MIN_VALUE) continue;
                        if (best == null || l > best.level) best = r;
                    }
                    if (best != null) {
                        rssi = best.level;
                        connected = true;
                        source = (best.SSID != null && !best.SSID.isEmpty())
                                ? best.SSID : "(" + (best.BSSID == null ? "hidden" : best.BSSID) + ")";
                    }
                }
            } catch (SecurityException ignored) { }
        }

        // delta vs. previous sample
        if (connected && lastRssi != Integer.MIN_VALUE) {
            delta = rssi - lastRssi;
        } else {
            delta = 0;
        }
        if (connected) lastRssi = rssi; else lastRssi = Integer.MIN_VALUE;

        int clamped = clampRssi(connected ? rssi : MIN_RSSI);
        gauge.setValue(clamped, connected);
        spark.add(clamped);

        int pct = connected ? Math.round(((clamped - MIN_RSSI) * 100f) / (MAX_RSSI - MIN_RSSI)) : 0;
        pctView.setText(pct + "%");

        ssidView.setText(connected && source != null ? source : "");

        if (delta > 0) {
            deltaView.setText(String.format(Locale.US, "▲ +%d dBm", delta));
            deltaView.setTextColor(0xFF33D17A);
        } else if (delta < 0) {
            deltaView.setText(String.format(Locale.US, "▼ %d dBm", delta));
            deltaView.setTextColor(0xFFE5484D);
        } else {
            deltaView.setText("±0 dBm");
            deltaView.setTextColor(0xFF8494AB);
        }

        int level = connected ? WifiManager.calculateSignalLevel(clamped, 5) : 0;
        barsView.setText(barsText(level));
        barsView.setTextColor(level >= 3 ? 0xFF33D17A : (level >= 2 ? 0xFFF5A623 : 0xFFE5484D));

        // status line
        boolean perms = allPerms();
        if (permissionVisible && perms) {
            statusView.setTextColor(0xFF8494AB);
        }
        if (!perms) {
            statusView.setTextColor(0xFFF5A623);
            statusView.setText("Tap to enable live reading (location / nearby WiFi permission)");
        } else if (!connected) {
            statusView.setTextColor(0xFF8494AB);
            statusView.setText("Not connected — scanning for strongest nearby network…");
        } else {
            statusView.setTextColor(0xFF33D17A);
            statusView.setText("Live · RSSI fires on change + 100 ms poll");
        }
    }

    private static int clampRssi(int rssi) {
        return rssi < MIN_RSSI ? MIN_RSSI : (rssi > MAX_RSSI ? MAX_RSSI : rssi);
    }

    private static String barsText(int level) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(i < level ? '\u25AE' : '\u25AF');
        return sb.toString();
    }
}