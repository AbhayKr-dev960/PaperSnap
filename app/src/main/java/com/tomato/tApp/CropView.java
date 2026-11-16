package com.tomato.tApp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Shows a photo with four draggable corner handles. */
public class CropView extends View {

    private Bitmap bmp;
    private final float[] q = {0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f};
    private final RectF img = new RectF();
    private final Path path = new Path();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final float density;
    private int active = -1;
    private float offX, offY;

    public CropView(Context c) {
        this(c, null);
    }

    public CropView(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        int accent = c.getResources().getColor(R.color.accent);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f * density);
        linePaint.setColor(accent);
        handleFill.setColor(0xFFFAF6EC);
        handleRing.setStyle(Paint.Style.STROKE);
        handleRing.setStrokeWidth(2.5f * density);
        handleRing.setColor(accent);
        dimPaint.setColor(0x99000000);
    }

    void setImage(Bitmap b, float[] quad) {
        bmp = b;
        setQuad(quad);
    }

    void setQuad(float[] quad) {
        System.arraycopy(quad, 0, q, 0, 8);
        invalidate();
    }

    float[] getQuad() {
        return q.clone();
    }

    private void computeRect() {
        float s = Math.min(getWidth() / (float) bmp.getWidth(), getHeight() / (float) bmp.getHeight());
        float w = bmp.getWidth() * s;
        float h = bmp.getHeight() * s;
        img.set((getWidth() - w) / 2f, (getHeight() - h) / 2f,
                (getWidth() + w) / 2f, (getHeight() + h) / 2f);
    }

    private float px(int i) {
        return img.left + q[2 * i] * img.width();
    }

    private float py(int i) {
        return img.top + q[2 * i + 1] * img.height();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bmp == null) return;
        computeRect();
        canvas.drawBitmap(bmp, null, img, null);

        path.reset();
        path.moveTo(px(0), py(0));
        for (int i = 1; i < 4; i++) path.lineTo(px(i), py(i));
        path.close();

        canvas.save();
        canvas.clipPath(path, Region.Op.DIFFERENCE);
        canvas.drawRect(img, dimPaint);
        canvas.restore();

        canvas.drawPath(path, linePaint);
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(px(i), py(i), 13f * density, handleFill);
            canvas.drawCircle(px(i), py(i), 13f * density, handleRing);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float best = 56f * density;
                active = -1;
                for (int i = 0; i < 4; i++) {
                    float d = (float) Math.hypot(e.getX() - px(i), e.getY() - py(i));
                    if (d < best) {
                        best = d;
                        active = i;
                    }
                }
                if (active >= 0) {
                    offX = px(active) - e.getX();
                    offY = py(active) - e.getY();
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return active >= 0;
            }
            case MotionEvent.ACTION_MOVE:
                if (active >= 0) {
                    float x = (e.getX() + offX - img.left) / img.width();
                    float y = (e.getY() + offY - img.top) / img.height();
                    q[2 * active] = Math.max(0f, Math.min(1f, x));
                    q[2 * active + 1] = Math.max(0f, Math.min(1f, y));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = -1;
                return true;
            default:
                return true;
        }
    }
}
