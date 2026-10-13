package com.example.wifigauge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/**
 * Analog WiFi-strength gauge.
 *
 * Two needles:
 *  - a smoothed needle whose response time is set by the sensitivity slider
 *  - a thin, instant "live" needle that snaps directly to the latest RSSI
 *
 * The dial spans MIN..MAX dBm over a 240 degree sweep (classic speedometer look).
 */
public class GaugeView extends View {

    public static final float MIN_DBM = -100f;
    public static final float MAX_DBM = -40f;

    private static final float START_ANGLE = 150f; // degrees, clockwise from 3 o'clock
    private static final float SWEEP = 240f;

    private static final float[] ZONE_BOUNDS = {-100f, -90f, -80f, -70f, -60f, -50f, -40f};
    private static final int[] ZONE_COLORS = {
            0xFFE5484D, 0xFFF76B15, 0xFFF5A623, 0xFF9BC432, 0xFF33D17A, 0xFF00E08F
    };

    private float target = MIN_DBM;   // latest instant reading
    private float shown = MIN_DBM;    // smoothed needle position
    private long lastFrameNs = -1;
    private float tauMs = 60f;        // smoothing time constant
    private boolean active = false;   // false = not connected to anything

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickMinorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickMajorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint livePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pctPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dbmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path needlePath = new Path();

    private SweepGradient arcGradient;

    public GaugeView(Context c) { super(c); init(); }
    public GaugeView(Context c, AttributeSet a) { super(c, a); init(); }
    public GaugeView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(24 * d);
        trackPaint.setStrokeCap(Paint.Cap.BUTT);
        trackPaint.setColor(0xFF18243C);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(19 * d);
        arcPaint.setStrokeCap(Paint.Cap.BUTT);

        tickMinorPaint.setStrokeWidth(1.2f * d);
        tickMinorPaint.setColor(0xFF33415C);
        tickMajorPaint.setStrokeWidth(2f * d);
        tickMajorPaint.setColor(0xFF5A6B8C);

        labelPaint.setColor(0xFF8494AB);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(11 * d);

        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeWidth(5 * d);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        needlePaint.setColor(0xFFE8EFF9);
        needlePaint.setShadowLayer(8 * d, 0, 0, 0x8033D17A);

        livePaint.setStyle(Paint.Style.STROKE);
        livePaint.setStrokeWidth(1.6f * d);
        livePaint.setStrokeCap(Paint.Cap.ROUND);
        livePaint.setColor(0xCCE8EFF9);

        hubPaint.setColor(0xFF111A2E);
        hubRingPaint.setStyle(Paint.Style.STROKE);
        hubRingPaint.setStrokeWidth(2.5f * d);
        hubRingPaint.setColor(0xFF33D17A);
        hubCenterPaint.setColor(0xFF0B1220);

        pctPaint.setTextAlign(Paint.Align.CENTER);
        pctPaint.setFakeBoldText(true);
        dbmPaint.setTextAlign(Paint.Align.CENTER);
        capPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** Feed a new instantaneous reading. active=false means "no signal". */
    public void setValue(float rssiDbm, boolean active) {
        this.target = clamp(rssiDbm, MIN_DBM, MAX_DBM);
        this.active = active;
        if (!active) {
            this.target = MIN_DBM;
            this.shown = MIN_DBM;
        }
        invalidate();
    }

    /** s in 0..1, higher = snappier needle. */
    public void setSensitivity01(float s) {
        tauMs = lerp(180f, 18f, clamp(s, 0f, 1f));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // rebuild sweep gradient anchored at the center
        float cx = w / 2f, cy = h / 2f;
        float[] pos = new float[7];
        for (int i = 0; i < pos.length; i++) {
            float frac = (ZONE_BOUNDS[i] - MIN_DBM) / (MAX_DBM - MIN_DBM);
            pos[i] = (frac * SWEEP) / 360f; // angles relative to rotated frame (0..240deg)
        }
        arcGradient = new SweepGradient(cx, cy,
                new int[]{ZONE_COLORS[0], ZONE_COLORS[1], ZONE_COLORS[2],
                           ZONE_COLORS[3], ZONE_COLORS[4], ZONE_COLORS[5], ZONE_COLORS[5]},
                pos);
        arcPaint.setShader(arcGradient);
    }

    private static float angleFor(float v) {
        float frac = (v - MIN_DBM) / (MAX_DBM - MIN_DBM);
        return START_ANGLE + frac * SWEEP; // degrees, clockwise (y-down)
    }

    private static double rad(float deg) { return Math.toRadians(deg); }
    private static float radf(float deg) { return (float) Math.toRadians(deg); }

    private int zoneColor(float v) {
        int idx = 0;
        for (int i = 0; i < ZONE_BOUNDS.length - 1; i++) {
            if (v >= ZONE_BOUNDS[i]) idx = i;
        }
        return ZONE_COLORS[Math.min(idx, ZONE_COLORS.length - 1)];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float d = getResources().getDisplayMetrics().density;
        float radius = Math.min(w, h) / 2f - 20 * d;
        if (radius <= 0) return;

        long now = System.nanoTime();
        if (lastFrameNs < 0) lastFrameNs = now;
        float dtMs = (now - lastFrameNs) / 1_000_000f;
        lastFrameNs = now;
        float k = 1f - (float) Math.exp(-dtMs / Math.max(1f, tauMs));
        shown += (target - shown) * Math.min(k, 1f);

        // ---- base track ----
        trackPaint.setStrokeWidth(24 * d);
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                0f, 360f, false, trackPaint);
        // gap under the dial so it reads like a gauge
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                START_ANGLE + SWEEP, 360f - SWEEP, false, trackPaint);

        // ---- colored sweep (canvas rotated so arc angle 0 == START_ANGLE) ----
        int save = canvas.save();
        canvas.rotate(START_ANGLE, cx, cy);
        arcPaint.setStrokeWidth(19 * d);
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                0f, SWEEP, false, arcPaint);
        canvas.restoreToCount(save);

        // ---- ticks & labels ----
        float tickR = radius - 10 * d;
        float tickR2 = radius;
        float tickR3 = radius - 22 * d;
        for (int v = -100; v <= -40; v += 5) {
            float a = angleFor(v);
            double r = rad(a);
            float x1 = (float) (cx + tickR * Math.cos(r));
            float y1 = (float) (cy + tickR * Math.sin(r));
            float x2 = (float) (cx + tickR2 * Math.cos(r));
            float y2 = (float) (cy + tickR2 * Math.sin(r));
            boolean major = (v % 10 == 0);
            canvas.drawLine(x1, y1, x2, y2, major ? tickMajorPaint : tickMinorPaint);
            if (major) {
                float x3 = (float) (cx + tickR3 * Math.cos(r));
                float y3 = (float) (cy + tickR3 * Math.sin(r));
                float fy = labelPaint.getFontMetrics().ascent;
                canvas.drawText(Integer.toString(v), x3, y3 - fy / 2f, labelPaint);
            }
        }

        // ---- live (instant) needle ----
        float liveA = radf(angleFor(target));
        float lx = (float) (cx + radius * 0.82 * Math.cos(liveA));
        float ly = (float) (cy + radius * 0.82 * Math.sin(liveA));
        canvas.drawLine(cx, cy, lx, ly, livePaint);

        // ---- smoothed needle ----
        float a = radf(angleFor(shown));
        needlePaint.setColor(active ? 0xFFE8EFF9 : 0xFF5A6B8C);
        float tipR = radius - 26 * d;
        float tx = (float) (cx + tipR * Math.cos(a));
        float ty = (float) (cy + tipR * Math.sin(a));
        canvas.drawLine(cx, cy, tx, ty, needlePaint);
        // small tail
        float tailR = radius * 0.18f;
        float tx2 = (float) (cx - tailR * Math.cos(a));
        float ty2 = (float) (cy - tailR * Math.sin(a));
        needlePaint.setStrokeWidth(3.5f * d);
        canvas.drawLine(cx, cy, tx2, ty2, needlePaint);

        // ---- center readout ----
        int color = active ? zoneColor(shown) : 0xFF5A6B8C;
        pctPaint.setColor(color);
        pctPaint.setTextSize(radius * 0.62f);
        String pct = active ? Math.round(percentFor(shown)) + "%" : "--";
        float cyPct = cy + radius * 0.02f;
        canvas.drawText(pct, cx, cyPct - pctPaint.getFontMetrics().ascent * 0.85f, pctPaint);

        dbmPaint.setColor(active ? 0xFFE8EFF9 : 0xFF8494AB);
        dbmPaint.setTextSize(radius * 0.18f);
        String dbm = active ? Math.round(shown) + " dBm" : "no signal";
        canvas.drawText(dbm, cx, cyPct + radius * 0.26f - dbmPaint.getFontMetrics().ascent, dbmPaint);

        capPaint.setColor(0xFF8494AB);
        capPaint.setTextSize(radius * 0.09f);
        canvas.drawText("WIFI · RSSI", cx, cyPct + radius * 0.40f - capPaint.getFontMetrics().ascent, capPaint);

        // mark exact current spot on the arc
        float ma = radf(angleFor(target));
        float mx = (float) (cx + radius * Math.cos(ma));
        float my = (float) (cy + radius * Math.sin(ma));
        spotPaint.setColor(active ? zoneColor(target) : 0xFF5A6B8C);
        canvas.drawCircle(mx, my, 5 * d, spotPaint);

        // ---- hub ----
        float hubR = 11 * d;
        canvas.drawCircle(cx, cy, hubR, hubPaint);
        hubRingPaint.setColor(active ? zoneColor(shown) : 0xFF5A6B8C);
        canvas.drawCircle(cx, cy, hubR + 2 * d, hubRingPaint);
        canvas.drawCircle(cx, cy, hubR * 0.45f, hubCenterPaint);
    }

    private static float percentFor(float v) {
        float f = (v - MIN_DBM) / (MAX_DBM - MIN_DBM);
        return clamp(f * 100f, 0f, 100f);
    }
}