package com.example.wifigauge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Scrolling history strip of RSSI samples (~30 s at 100 ms).
 * Newest sample is drawn at the right edge.
 */
public class SparklineView extends View {

    private static final int CAP = 300;
    private static final float PAD = 6f;

    private final float[] data = new float[CAP];
    private int size = 0;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private LinearGradient fillGrad;

    public SparklineView(Context c) { super(c); init(); }
    public SparklineView(Context c, AttributeSet a) { super(c, a); init(); }
    public SparklineView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        gridPaint.setStrokeWidth(d);
        gridPaint.setColor(0x222E3E5E);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2 * d);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(0xFF33D17A);
        fillPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(0xFFE8EFF9);
    }

    public void add(float v) {
        float clamped = v < GaugeView.MIN_DBM ? GaugeView.MIN_DBM :
                (v > GaugeView.MAX_DBM ? GaugeView.MAX_DBM : v);
        if (size == CAP) {
            System.arraycopy(data, 1, data, 0, CAP - 1);
            data[CAP - 1] = clamped;
        } else {
            data[size++] = clamped;
        }
        invalidate();
    }

    public void reset() { size = 0; invalidate(); }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            fillGrad = new LinearGradient(0, 0, 0, h,
                    0x5533D17A, 0x0033D17A, Shader.TileMode.CLAMP);
            fillPaint.setShader(fillGrad);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float pad = PAD * getResources().getDisplayMetrics().density;
        float left = pad, right = w - pad, top = pad, bottom = h - pad;

        // gridlines at -90, -70, -50 dBm (nice round values)
        for (int g = -90; g <= -50; g += 20) {
            float gy = yFor(g, top, bottom);
            canvas.drawLine(left, gy, right, gy, gridPaint);
        }

        if (size < 2) {
            // draw a small idle dot at the far right
            float gy = yFor(GaugeView.MIN_DBM, top, bottom);
            canvas.drawCircle(right, gy, 1.5f * getResources().getDisplayMetrics().density, dotPaint);
            return;
        }

        path.rewind();
        float xStep = (right - left) / (CAP - 1);
        boolean first = true;
        for (int i = 0; i < size; i++) {
            float x = left + i * xStep;
            float y = yFor(data[i], top, bottom);
            if (first) {
                path.moveTo(x, y);
                first = false;
            } else {
                path.lineTo(x, y);
            }
        }

        // filled area under the line
        Path fillPath = new Path(path);
        float lastX = left + (size - 1) * xStep;
        fillPath.lineTo(lastX, bottom);
        fillPath.lineTo(left, bottom);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);

        canvas.drawPath(path, linePaint);

        float lastY = yFor(data[size - 1], top, bottom);
        canvas.drawCircle(lastX, lastY, 3 * getResources().getDisplayMetrics().density, dotPaint);
    }

    private static float yFor(float dbm, float top, float bottom) {
        float frac = (dbm - GaugeView.MIN_DBM) / (GaugeView.MAX_DBM - GaugeView.MIN_DBM);
        return top + (1f - frac) * (bottom - top);
    }
}