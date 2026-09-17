package com.mobulizer.visualizer;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Process;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class VisualizerRenderer implements GLSurfaceView.Renderer {

    // =========================================================
    // NETWORK
    // =========================================================

    private static final int DATA_PORT = 49321;
    private static final int CONTROL_PORT = 49322;
    private static final int MAX_BARS = 96;
    private static final int MAX_PACKET_SIZE = 14 + (MAX_BARS * 2);

    // Packet-interval bookkeeping (nanoseconds).
    private static final long MIN_INTERVAL_NS = 12_000_000L;     // 12 ms
    private static final long MAX_INTERVAL_NS = 100_000_000L;    // 100 ms
    private static final long DEFAULT_INTERVAL_NS = 33_000_000L;

    // If packets stop arriving, smoothly bring the spectrum to zero.
    private static final long DATA_TIMEOUT_NS = 400_000_000L;   // 400 ms

    // =========================================================
    // SMOOTHING
    // =========================================================

    // CAVA updates arrive at roughly 33 FPS in the tested Windows setup.
    // The old packet-to-packet easing therefore launched every new frame too
    // aggressively and made the bars look like they were flickering.
    //
    // The GL thread now continuously chases the newest target at display
    // refresh rate. Every bar uses the SAME time constants, keeping the whole
    // spectrum coherent instead of making each bar behave like its own spring.
    private static final float ATTACK_TIME_MS = 105.0f;
    private static final float RELEASE_TIME_MS = 230.0f;
    private static final float SILENCE_RELEASE_TIME_MS = 260.0f;

    // CAVA values often occupy only a fraction of 0..1 during normal playback.
    // Boost the display-only amplitude so ordinary music reaches higher on screen
    // without changing the incoming data or the stereo mapping.
    private static final float VISUAL_HEIGHT_GAIN = 1.55f;

    // Bass lives at the center in the mirrored CAVA layout. Give the center
    // a gentle extra height boost so bass hits punch higher than mids/treble.
    // The boost fades smoothly toward the edges instead of creating a hard
    // boundary between boosted and normal bars.
    private static final float BASS_CENTER_BOOST = 1.35f;
    private static final float BASS_BOOST_RADIUS = 0.42f;

    // The Windows/CAVA stream used by this project is treated as a single
    // low-to-high spectrum and mirrored here. This guarantees the visual
    // CAVA pattern: bass at the center, mids beside it, treble at the edges.
    // Set false only when the incoming packet is already CAVA stereo ordered
    // as high->low | low->high.
    private static final boolean FORCE_MIRRORED_SPECTRUM = true;

    // =========================================================
    // VISUAL SETTINGS
    // =========================================================

    private static final float TARGET_BAR_WIDTH = 30.0f;
    private static final float GAP_RATIO = 0.28f;
    private static final float BASELINE_RATIO = 0.95f;
    private static final float MAX_HEIGHT_RATIO = 1.0f;

    // IMPORTANT:
    // CAVA normal stereo raw output is already ordered as:
    //
    //   left half : high frequencies -> bass toward the center
    //   right half: bass -> high frequencies toward the edge
    //
    // So the renderer should use UNIFORM horizontal positions. The previous
    // logarithmic X warp distorted this already-correct stereo arrangement.

    // =========================================================
    // SHARED STATE (receiver writes, GL thread reads)
    // =========================================================

    private final Object dataLock = new Object();

    private int barCount = 32;

    // Newest network target for each bar, normalized 0..1.
    private final float[] targetBars = new float[MAX_BARS];

    // Actual visual state drawn by the GL thread.
    private final float[] renderedBars = new float[MAX_BARS];

    private long lastArrivalNs = 0L;
    private boolean decaying = false;
    private long lastFrameNs = 0L;

    // =========================================================
    // DEBUG STATS (read by the MainActivity overlay)
    // =========================================================

    private volatile long statPackets = 0L;
    private volatile long statFrames = 0L;
    private volatile long statLastPacketNs = 0L;
    private volatile long statIntervalNs = DEFAULT_INTERVAL_NS;
    private volatile boolean statReceiverAlive = false;
    private volatile String statError = null;

    // =========================================================
    // RENDER-THREAD-ONLY STATE
    // =========================================================

    private final float[] drawTargets = new float[MAX_BARS];

    // Cached geometry (NDC). Rebuilt only when size / bar count changes.
    private final float[] geomX1 = new float[MAX_BARS];
    private final float[] geomX2 = new float[MAX_BARS];
    private float geomBaselineY = -0.94f;
    private float geomMaxHeight = 2.0f;
    private float geomMinBar = 0.004f;
    private int geomCount = -1;
    private int geomW = -1;
    private int geomH = -1;

    private int screenWidth = 0;
    private int screenHeight = 0;
    private int lastWidth = -1;
    private int lastRequestedWidth = -1;

    // =========================================================
    // OPENGL
    // =========================================================

    private int program = 0;
    private int positionHandle = 0;
    private int colorHandle = 0;

    private final FloatBuffer vertexBuffer;
    private static final int MAX_FLOATS = MAX_BARS * 6 * 2;

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;" +
                    "void main() {" +
                    "    gl_Position = vec4(aPosition, 0.0, 1.0);" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
                    "uniform vec4 uColor;" +
                    "void main() {" +
                    "    gl_FragColor = uColor;" +
                    "}";

    // =========================================================
    // RECEIVER
    // =========================================================

    private volatile boolean receiverRunning = true;
    private DatagramSocket receiverSocket;
    private Thread receiverThread;

    // Receiver-thread-only parse staging.
    private final float[] parseTarget = new float[MAX_BARS];
    private final float[] mappedTarget = new float[MAX_BARS];
    private int parsedCount = 0;

    // =========================================================
    // CONSTRUCTOR
    // =========================================================

    public VisualizerRenderer(Context context) {
        vertexBuffer = ByteBuffer
                .allocateDirect(MAX_FLOATS * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        startReceiver();
        startHeartbeat();
    }

    // =========================================================
    // OPENGL INIT
    // =========================================================

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Runs ON the GL thread: raise its priority so frames don't get
        // preempted by MIUI background services on weak SoCs.
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Throwable ignored) { }

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DITHER);

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        colorHandle = GLES20.glGetUniformLocation(program, "uColor");
    }

    // =========================================================
    // RESIZE
    // =========================================================

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        screenWidth = width;
        screenHeight = height;

        GLES20.glViewport(0, 0, width, height);

        if (width != lastWidth) {
            lastWidth = width;
            calculateBarCount(width);
            sendBarRequest(width);
        }
    }

    private void calculateBarCount(int width) {
        int count = Math.round(width / TARGET_BAR_WIDTH);
        if ((count & 1) != 0) count++;
        count = Math.max(24, count);
        count = Math.min(MAX_BARS, count);

        synchronized (dataLock) {
            barCount = count;
            for (int i = 0; i < MAX_BARS; i++) {
                targetBars[i] = 0f;
                renderedBars[i] = 0f;
            }
            lastArrivalNs = 0L;
            decaying = false;
            lastFrameNs = 0L;
        }
    }

    // =========================================================
    // RENDER (runs at display vsync, 60 Hz)
    // =========================================================

    @Override
    public void onDrawFrame(GL10 gl) {
        statFrames++;

        final long nowNs = System.nanoTime();

        // Frame-rate-independent time step.
        float dtMs;
        if (lastFrameNs == 0L) {
            dtMs = 16.667f;
        } else {
            dtMs = (nowNs - lastFrameNs) / 1_000_000.0f;
            if (dtMs < 1f) dtMs = 1f;
            else if (dtMs > 50f) dtMs = 50f;
        }
        lastFrameNs = nowNs;

        int count;

        synchronized (dataLock) {
            // If the network stream disappears, smoothly target zero.
            if (lastArrivalNs != 0L
                    && !decaying
                    && nowNs - lastArrivalNs > DATA_TIMEOUT_NS) {
                for (int i = 0; i < barCount; i++) {
                    targetBars[i] = 0f;
                }
                decaying = true;
            }

            count = barCount;
            System.arraycopy(targetBars, 0, drawTargets, 0, count);
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glUniform4f(colorHandle, 1f, 1f, 1f, 1f);

        if (count <= 0) return;

        if (count != geomCount
                || screenWidth != geomW
                || screenHeight != geomH) {
            rebuildGeometry(count);
        }

        // Continuous low-pass chase. All bars use the same timing, so the
        // spectrum moves as one coherent animation.
        final boolean silence = decaying;

        for (int i = 0; i < count; i++) {
            final float current = renderedBars[i];
            final float target = clamp01(drawTargets[i]);

            final float tauMs;
            if (silence) {
                tauMs = SILENCE_RELEASE_TIME_MS;
            } else if (target > current) {
                tauMs = ATTACK_TIME_MS;
            } else {
                tauMs = RELEASE_TIME_MS;
            }

            // Exponential approach is smooth and packet-rate independent.
            // It does not restart an easing curve every time a UDP packet
            // arrives, which removes the previous visible flicker.
            final float alpha =
                    1.0f - (float) Math.exp(-dtMs / tauMs);

            renderedBars[i] =
                    current + (target - current) * alpha;
        }

        for (int i = count; i < MAX_BARS; i++) {
            renderedBars[i] = 0f;
        }

        vertexBuffer.clear();

        final float baseline = geomBaselineY;
        final float maxH = geomMaxHeight;
        final float minBar = geomMinBar;

        for (int i = 0; i < count; i++) {
            // The mirrored layout puts the lowest frequencies at the exact
            // center. Apply a smooth center-weighted boost there so bass hits
            // visibly punch higher than the surrounding frequencies.
            final float center = (count - 1) * 0.5f;
            final float distance = Math.abs(i - center);
            final float distanceNorm = center <= 0f
                    ? 0f
                    : distance / center;

            float bassProfile = 0f;
            if (distanceNorm < BASS_BOOST_RADIUS) {
                float t = distanceNorm / BASS_BOOST_RADIUS;
                // Smooth fade: 1 at center -> 0 at the boost radius.
                bassProfile = 1f - (t * t * (3f - 2f * t));
            }

            final float bassBoost =
                    1f + (BASS_CENTER_BOOST - 1f) * bassProfile;

            float v = clamp01(
                    renderedBars[i] * VISUAL_HEIGHT_GAIN * bassBoost);
            float barH = v * maxH;
            if (barH < minBar) barH = minBar;

            final float x1 = geomX1[i];
            final float x2 = geomX2[i];
            final float y2 = baseline;
            final float y1 = baseline + barH;

            vertexBuffer.put(x1);
            vertexBuffer.put(y1);
            vertexBuffer.put(x2);
            vertexBuffer.put(y1);
            vertexBuffer.put(x1);
            vertexBuffer.put(y2);

            vertexBuffer.put(x1);
            vertexBuffer.put(y2);
            vertexBuffer.put(x2);
            vertexBuffer.put(y1);
            vertexBuffer.put(x2);
            vertexBuffer.put(y2);
        }

        vertexBuffer.flip();

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(
                positionHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                vertexBuffer
        );
        GLES20.glDrawArrays(
                GLES20.GL_TRIANGLES,
                0,
                count * 6
        );
        GLES20.glDisableVertexAttribArray(positionHandle);
    }

    private static float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }

    private void rebuildGeometry(int count) {
        final float w = screenWidth;
        final float h = screenHeight;

        if (w <= 0f || h <= 0f || count <= 0) return;

        final float baselinePx = h * BASELINE_RATIO;

        geomBaselineY =
                1f - (baselinePx / h) * 2f;

        geomMaxHeight =
                MAX_HEIGHT_RATIO * 2f;

        geomMinBar =
                (3f / h) * 2f;

        // =====================================================
        // CAVA SYMMETRIC HORIZONTAL LAYOUT
        // =====================================================
        // Keep bar positions uniform. Frequency mapping is handled in the
        // packet-to-target transform above, where the low frequencies are
        // explicitly placed around the center and higher frequencies move
        // outward toward both edges.
        //
        // Visual order:
        //
        //   TREBLE ... MID ... BASS | BASS ... MID ... TREBLE
        //
        // This is the spatial pattern the user is expecting from CAVA.
        // =====================================================

        final float barWidth =
                w / (count * (1f + GAP_RATIO));

        final float gap =
                barWidth * GAP_RATIO;

        final float totalWidth =
                count * barWidth
                        + (count - 1) * gap;

        final float startX =
                (w - totalWidth) * 0.5f;

        for (int i = 0; i < count; i++) {
            final float left =
                    startX + i * (barWidth + gap);

            final float right =
                    left + barWidth;

            geomX1[i] =
                    (left / w) * 2f - 1f;

            geomX2[i] =
                    (right / w) * 2f - 1f;
        }

        geomCount = count;
        geomW = screenWidth;
        geomH = screenHeight;
    }

    // =========================================================
    // UDP RECEIVER
    // =========================================================

    private void startReceiver() {
        receiverThread = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            } catch (Throwable ignored) { }

            byte[] buffer = new byte[MAX_PACKET_SIZE];

            DatagramSocket socket = null;

            // Bind with retry: if a zombie process (old app version still
            // alive in the background, MIUI resurrecting something) holds
            // port 49321, we retry every second instead of dying silently.
            while (socket == null && receiverRunning) {
                try {
                    socket = new DatagramSocket(null);
                    socket.setReuseAddress(true);
                    socket.bind(new InetSocketAddress(DATA_PORT));
                } catch (Exception e) {
                    statError = "bind: " + e;
                    android.util.Log.e("CAVA", "bind failed, retrying", e);
                    if (socket != null) {
                        try { socket.close(); } catch (Exception ignored) { }
                        socket = null;
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
            if (socket == null) return;

            statReceiverAlive = true;
            statError = null;

            try {
                receiverSocket = socket;
                socket.setBroadcast(true);
                socket.setReceiveBufferSize(64 * 1024);

                DatagramPacket packet =
                        new DatagramPacket(buffer, buffer.length);

                while (receiverRunning) {
                    // Block for a new frame.
                    socket.setSoTimeout(1000);
                    packet.setLength(buffer.length);

                    try {
                        socket.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        continue;
                    }

                    // Parse the first packet.
                    boolean gotValid =
                            parsePacket(buffer, packet.getLength());

                    // Drain all packets that are already queued and keep
                    // only the newest valid one. This avoids visual lag when
                    // Windows/CAVA produces bursts.
                    socket.setSoTimeout(1);

                    while (true) {
                        packet.setLength(buffer.length);

                        try {
                            socket.receive(packet);
                        } catch (SocketTimeoutException ignored) {
                            break;
                        }

                        if (parsePacket(buffer, packet.getLength())) {
                            gotValid = true;
                        }
                    }

                    socket.setSoTimeout(1000);

                    if (gotValid) {
                        commitPacket();
                    }
                }
            } catch (Exception e) {
                // Surface instead of swallowing: if the receiver ever dies,
                // the overlay says so instead of just showing flat bars.
                statError = "recv: " + e;
                android.util.Log.e("CAVA", "receiver died", e);
            } finally {
                statReceiverAlive = false;
                closeReceiverSocket();
            }
        }, "CAVA-Receiver");

        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    private boolean parsePacket(byte[] buffer, int length) {
        if (length < 14) return false;
        if (buffer[0] != 'C' || buffer[1] != 'A'
                || buffer[2] != 'V' || buffer[3] != 'A') return false;

        int count = (buffer[12] & 0xFF) | ((buffer[13] & 0xFF) << 8);
        if (count < 16 || count > MAX_BARS) return false;
        if (length != 14 + count * 2) return false;

        for (int i = 0; i < count; i++) {
            int offset = 14 + i * 2;
            int value = (buffer[offset] & 0xFF)
                    | ((buffer[offset + 1] & 0xFF) << 8);
            parseTarget[i] = value / 65535f;
        }
        parsedCount = count;
        return true;
    }

    private void commitPacket() {
        final long nowNs = System.nanoTime();
        final int count = parsedCount;

        if (count <= 0) return;

        long durationSnapshot = DEFAULT_INTERVAL_NS;

        synchronized (dataLock) {
            if (lastArrivalNs != 0L) {
                long delta = nowNs - lastArrivalNs;

                if (delta < MIN_INTERVAL_NS) {
                    delta = MIN_INTERVAL_NS;
                } else if (delta > MAX_INTERVAL_NS) {
                    delta = MAX_INTERVAL_NS;
                }

                // Keep the stat as an EMA only. Animation itself is no longer
                // tied to this duration.
                durationSnapshot =
                        (statIntervalNs * 3L + delta) / 4L;

                if (durationSnapshot < MIN_INTERVAL_NS) {
                    durationSnapshot = MIN_INTERVAL_NS;
                } else if (durationSnapshot > MAX_INTERVAL_NS) {
                    durationSnapshot = MAX_INTERVAL_NS;
                }
            }

            // Publish ONLY the newest packet.
            //
            // IMPORTANT: the visual layout is deliberately reconstructed here.
            // We want the exact CAVA-style spatial relationship:
            //
            //     TREBLE ... MID ... BASS | BASS ... MID ... TREBLE
            //                                  ^ center ^
            //
            // The incoming project stream is treated as a normal low->high
            // spectrum. We resample that full spectrum onto one half and mirror
            // it. This puts the lowest frequencies at the center instead of at
            // the left edge.
            if (FORCE_MIRRORED_SPECTRUM && count >= 2) {
                final int half = count / 2;
                final int sourceMax = count - 1;

                for (int out = 0; out < count; out++) {
                    final int distanceFromCenter;

                    if (out < half) {
                        distanceFromCenter = half - 1 - out;
                    } else {
                        distanceFromCenter = out - half;
                    }

                    // 0 = bass/center, half-1 = treble/edge.
                    float normalized = half <= 1
                            ? 0f
                            : (float) distanceFromCenter / (float) (half - 1);

                    int sourceIndex = Math.round(normalized * sourceMax);
                    if (sourceIndex < 0) sourceIndex = 0;
                    if (sourceIndex > sourceMax) sourceIndex = sourceMax;

                    mappedTarget[out] = parseTarget[sourceIndex];
                }

                System.arraycopy(mappedTarget, 0, targetBars, 0, count);
            } else {
                System.arraycopy(
                        parseTarget,
                        0,
                        targetBars,
                        0,
                        count
                );
            }

            for (int i = count; i < MAX_BARS; i++) {
                targetBars[i] = 0f;
            }

            barCount = count;
            lastArrivalNs = nowNs;
            decaying = false;
        }

        statPackets++;
        statLastPacketNs = nowNs;
        statIntervalNs = durationSnapshot;
    }

    // =========================================================
    // CONTROL REQUEST
    // =========================================================

    private void sendBarRequest(int width) {
        lastRequestedWidth = width;
        Thread thread = new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);

                byte[] data = ("BARS " + width).getBytes("US-ASCII");

                DatagramPacket packet = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName("255.255.255.255"),
                        CONTROL_PORT);

                socket.send(packet);
                socket.close();
            } catch (Exception ignored) { }
        }, "Mobulizer-Control");
        thread.setDaemon(true);
        thread.start();
    }

    // =========================================================
    // LIFECYCLE
    // =========================================================

    public void shutdown() {
        receiverRunning = false;
        closeReceiverSocket();
    }

    private void closeReceiverSocket() {
        DatagramSocket s = receiverSocket;
        receiverSocket = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    // =========================================================
    // HEARTBEAT
    // =========================================================
    private void startHeartbeat() {
        Thread t = new Thread(() -> {
            while (receiverRunning) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                int w = lastRequestedWidth;
                if (w > 0) sendBarRequest(w);
            }
        }, "Mobulizer-Heartbeat");
        t.setDaemon(true);
        t.start();
    }

    // =========================================================
    // DEBUG STAT GETTERS (used by the MainActivity overlay)
    // =========================================================

    public long getStatPackets()      { return statPackets; }
    public long getStatFrames()       { return statFrames; }
    public long getStatLastPacketNs() { return statLastPacketNs; }
    public long getStatIntervalNs()   { return statIntervalNs; }
    public boolean isStatReceiverAlive() { return statReceiverAlive; }
    public String getStatError() { return statError; }

    // =========================================================
    // SHADER HELPERS
    // =========================================================

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        return program;
    }
}
