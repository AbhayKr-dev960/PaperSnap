package com.tomato.tApp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Draws the detected page outline on top of the camera preview. */
public class ScanOverlayView extends View {

    private float[] quad;
    private final Path path = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public ScanOverlayView(Context c) {
        this(c, null);
    }

    public ScanOverlayView(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x3340C080);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3f * density);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xFFFFFFFF);
        dot.setColor(0xFFFFFFFF);
    }

    /** Corners as fractions of this view (tl, tr, br, bl), or null to hide the outline. */
    void setQuad(float[] q) {
        quad = q == null ? null : q.clone();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (quad == null) return;
        float w = getWidth();
        float h = getHeight();
        path.reset();
        path.moveTo(quad[0] * w, quad[1] * h);
        for (int i = 1; i < 4; i++) path.lineTo(quad[2 * i] * w, quad[2 * i + 1] * h);
        path.close();
        canvas.drawPath(path, fill);
        canvas.drawPath(path, line);
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(quad[2 * i] * w, quad[2 * i + 1] * h, 6f * density, dot);
        }
    }
}
