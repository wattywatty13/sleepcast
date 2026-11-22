package com.sleepcast.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/** Night-sky background: a deep gradient, a soft moon glow and gently twinkling stars. */
public class StarfieldView extends View {

    private static final int STAR_COUNT = 110;
    private static final long FRAME_MS = 60; // ~16 fps is plenty for a slow twinkle

    private final float[] sx = new float[STAR_COUNT];
    private final float[] sy = new float[STAR_COUNT];
    private final float[] sr = new float[STAR_COUNT];
    private final float[] sAlpha = new float[STAR_COUNT];
    private final float[] sPhase = new float[STAR_COUNT];
    private final float[] sSpeed = new float[STAR_COUNT];

    private final Paint skyPaint = new Paint();
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean animating = false;

    public StarfieldView(Context context) {
        this(context, null);
    }

    public StarfieldView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        Random rnd = new Random(452); // fixed seed: same sky every night
        for (int i = 0; i < STAR_COUNT; i++) {
            sx[i] = rnd.nextFloat();
            sy[i] = (float) Math.pow(rnd.nextFloat(), 1.7); // denser near the top
            sr[i] = (0.5f + rnd.nextFloat() * rnd.nextFloat() * 1.6f) * density;
            sAlpha[i] = 0.25f + rnd.nextFloat() * 0.65f;
            sPhase[i] = rnd.nextFloat() * 6.283f;
            sSpeed[i] = 0.4f + rnd.nextFloat() * 1.4f;
        }
        starPaint.setColor(0xFFF4EEDF);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        skyPaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF04060D, 0xFF0B1129, 0xFF1A1736, 0xFF2A1B3A},
                new float[]{0f, 0.45f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        float cx = w * 0.84f, cy = h * 0.09f, r = 220 * density;
        glowPaint.setShader(new RadialGradient(cx, cy, r,
                new int[]{0x40E8C887, 0x14E8C887, 0x00E8C887},
                new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, skyPaint);
        c.drawRect(0, 0, w, h, glowPaint);

        float t = (System.nanoTime() / 1_000_000_000f);
        for (int i = 0; i < STAR_COUNT; i++) {
            float tw = 0.65f + 0.35f * (float) Math.sin(t * sSpeed[i] + sPhase[i]);
            starPaint.setAlpha((int) (255 * sAlpha[i] * tw));
            c.drawCircle(sx[i] * w, sy[i] * h * 0.85f, sr[i], starPaint);
        }
        if (animating) postInvalidateDelayed(FRAME_MS);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        boolean was = animating;
        animating = isVisible;
        if (animating && !was) invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        animating = false;
        super.onDetachedFromWindow();
    }
}
