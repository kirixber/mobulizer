package com.mobulizer.visualizer;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {

    private VisualizerGLView visualizerView;
    private WifiManager.WifiLock wifiLock;

    private TextView debugOverlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastPackets = 0L;
    private long lastFrames = 0L;
    private long lastSampleMs = 0L;

    private final Runnable debugTick = new Runnable() {
        @Override
        public void run() {
            if (visualizerView != null) {
                VisualizerRenderer r = visualizerView.getRenderer();
                long now = SystemClock.elapsedRealtime();
                double dt = (now - lastSampleMs) / 1000.0;
                if (dt >= 0.25 && lastSampleMs != 0L) {
                    int pps = (int) ((r.getStatPackets() - lastPackets) / dt);
                    int fps = (int) ((r.getStatFrames() - lastFrames) / dt);
                    lastPackets = r.getStatPackets();
                    lastFrames = r.getStatFrames();
                    lastSampleMs = now;

                    if (debugOverlay != null
                            && debugOverlay.getVisibility() == View.VISIBLE) {
                        long ageMs = r.getStatLastPacketNs() == 0L ? -1L
                                : (System.nanoTime() - r.getStatLastPacketNs()) / 1_000_000L;
                        long intervalMs = r.getStatIntervalNs() / 1_000_000L;

                        String extra = r.isStatReceiverAlive() ? "" : "\nRX DEAD";
                        if (r.getStatError() != null) {
                            extra += "\n" + r.getStatError();
                        }

                        debugOverlay.setText(String.format(Locale.US,
                                "pkt/s %d   fps %d\ninterval %dms   age %dms%s",
                                pps, fps, intervalMs, ageMs, extra));
                    }
                } else if (lastSampleMs == 0L) {
                    lastSampleMs = now;
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        hideSystemUI();
        acquireWifiLock();

        visualizerView = new VisualizerGLView(this);
        visualizerView.setOnTouchListener(this::onGlTouch);

        debugOverlay = new TextView(this);
        debugOverlay.setTextColor(0xFF30FF60);
        debugOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        debugOverlay.setBackgroundColor(0x66000000);
        debugOverlay.setVisibility(View.GONE);

        FrameLayout root = new FrameLayout(this);
        root.addView(visualizerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        root.addView(debugOverlay, lp);

        setContentView(root);
    }

    private boolean onGlTouch(View v, MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            if (debugOverlay.getVisibility() == View.VISIBLE) {
                debugOverlay.setVisibility(View.GONE);
            } else {
                VisualizerRenderer r = visualizerView.getRenderer();
                lastPackets = r.getStatPackets();
                lastFrames = r.getStatFrames();
                lastSampleMs = SystemClock.elapsedRealtime();
                debugOverlay.setVisibility(View.VISIBLE);
            }
        }
        return true;
    }

    private void acquireWifiLock() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;

            int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = WifiManager.WIFI_MODE_FULL_LOW_LATENCY;
            }

            wifiLock = wifi.createWifiLock(mode, "Mobulizer:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wifiLock != null && !wifiLock.isHeld()) {
            try { wifiLock.acquire(); } catch (Exception ignored) { }
        }
        handler.removeCallbacks(debugTick);
        handler.post(debugTick);
        if (visualizerView != null) visualizerView.onResume();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(debugTick);
        if (visualizerView != null) visualizerView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (Exception ignored) { }
        }
        wifiLock = null;
        super.onDestroy();
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }
}