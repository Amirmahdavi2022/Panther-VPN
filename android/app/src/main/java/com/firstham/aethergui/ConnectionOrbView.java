package com.firstham.aethergui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.DashPathEffect;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.MotionEvent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

public final class ConnectionOrbView extends View {
    private static final int DISCONNECTED = 0;
    private static final int CONNECTING = 1;
    private static final int CONNECTED = 2;
    private static final int DISCONNECTING = 3;
    private static final int ERROR = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final DashPathEffect dashes = new DashPathEffect(new float[]{7f, 12f}, 0f);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] particleAngles = new float[18];
    private final float[] particleRadii = new float[18];
    private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();
    private Shader ringShader;
    private Shader bodyShader;
    private Shader highlightShader;
    private ValueAnimator motion;
    private int state = DISCONNECTED;
    private float phase;
    private String label = "";

    public ConnectionOrbView(Context context) { this(context, null); }
    public ConnectionOrbView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
        iconPaint.setColor(Color.WHITE);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        particlePaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < particleAngles.length; i++) {
            particleAngles[i] = (float) (i * Math.PI * 2 / particleAngles.length);
            particleRadii[i] = 1.05f + (i % 5) * .065f;
        }
    }

    public void setConnectionState(String value, String text) {
        int next;
        if ("connected".equals(value)) next = CONNECTED;
        else if ("disconnecting".equals(value)) next = DISCONNECTING;
        else if ("starting".equals(value) || "smart-testing".equals(value) || "scanning".equals(value) || "securing".equals(value) || "reconnecting".equals(value)) next = CONNECTING;
        else if ("error".equals(value) || "blocked".equals(value)) next = ERROR;
        else next = DISCONNECTED;
        boolean changed = next != state;
        state = next;
        label = text == null ? "" : text;
        if (changed) { updateShaders(); restartMotion(); }
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateShaders();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float cx = width / 2f;
        float cy = height / 2f;
        float radius = Math.min(width, height) * 0.36f;
        float breath = state == CONNECTED ? 1f + 0.014f * (float) Math.sin(phase * Math.PI * 2)
                : state == CONNECTING ? 1f + 0.022f * (float) Math.sin(phase * Math.PI * 2)
                : state == DISCONNECTED ? 1f + 0.008f * (float) Math.sin(phase * Math.PI * 2) : 1f;
        radius *= breath;

        int start = startColor();
        int end = endColor();

        // Outer glow: two soft halos instead of the old wobbling blob.
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(end, state == DISCONNECTED ? 16 : 30));
        canvas.drawCircle(cx, cy, radius * 1.34f, paint);
        paint.setColor(withAlpha(start, state == DISCONNECTED ? 20 : 38));
        canvas.drawCircle(cx, cy, radius * 1.18f, paint);

        // Track ring.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(radius * 0.055f);
        paint.setColor(withAlpha(start, 46));
        canvas.drawCircle(cx, cy, radius * 1.1f, paint);

        // Dashed progress arc sweeping around the track.
        arc.set(cx - radius * 1.1f, cy - radius * 1.1f, cx + radius * 1.1f, cy + radius * 1.1f);
        paint.setShader(null);
        paint.setColor(state == ERROR ? end : start);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setPathEffect(dashes);
        float sweep = state == CONNECTED ? 300f : state == CONNECTING ? 110f : state == ERROR ? 60f : 82f;
        float rotation = state == CONNECTING ? phase * 360f : state == CONNECTED ? phase * 45f : -phase * 20f;
        canvas.drawArc(arc, rotation - 90f, sweep, false, paint);
        paint.setPathEffect(null);

        // Neon rim.
        paint.setShader(ringShader);
        paint.setStrokeWidth(radius * 0.085f);
        canvas.save();
        canvas.rotate((state == CONNECTING ? phase * 360f : state == CONNECTED ? phase * 30f : 0f) - 90f, cx, cy);
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.restore();
        paint.setShader(null);

        // Core.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(bodyShader);
        canvas.drawCircle(cx, cy, radius * 0.93f, paint);
        paint.setShader(highlightShader);
        canvas.drawCircle(cx, cy, radius * 0.9f, paint);
        paint.setShader(null);

        float iconY = cy - radius * .22f;
        iconPaint.setStrokeWidth(Math.max(5f, radius * .04f));
        canvas.drawLine(cx, iconY - radius * .26f, cx, iconY - radius * .02f, iconPaint);
        arc.set(cx - radius * .23f, iconY - radius * .18f, cx + radius * .23f, iconY + radius * .28f);
        canvas.drawArc(arc, -43f, 266f, false, iconPaint);

        textPaint.setTextSize(Math.max(18f, radius * .15f));
        textPaint.getFontMetrics(fontMetrics);
        float baseline = cy + radius * .38f - (fontMetrics.ascent + fontMetrics.descent) / 2f;
        canvas.drawText(label, cx, baseline, textPaint);
        textPaint.setTextSize(Math.max(10f, radius * .072f));
        textPaint.setColor(Color.argb(200, 210, 210, 220));
        canvas.drawText(getContext().getString(R.string.tap_to_secure), cx, baseline + radius * .19f, textPaint);
        textPaint.setColor(Color.WHITE);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            animate().cancel();
            if (ValueAnimator.areAnimatorsEnabled()) animate().scaleX(.97f).scaleY(.97f).setDuration(90).start();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            animate().cancel();
            if (ValueAnimator.areAnimatorsEnabled()) animate().scaleX(1f).scaleY(1f).setDuration(180).start();
            else { setScaleX(1f); setScaleY(1f); }
        }
        return super.onTouchEvent(event);
    }

    private void restartMotion() {
        stopMotion();
        if (!isShown() || !ValueAnimator.areAnimatorsEnabled() || state == ERROR) { phase = 0f; invalidate(); return; }
        motion = ValueAnimator.ofFloat(0f, 1f);
        motion.setDuration(state == CONNECTING ? 1450 : state == DISCONNECTING ? 900 : state == DISCONNECTED ? 4200 : 3200);
        motion.setRepeatCount(state == DISCONNECTING ? 0 : ValueAnimator.INFINITE);
        motion.setInterpolator(state == CONNECTING ? new LinearInterpolator() : new AccelerateDecelerateInterpolator());
        motion.addUpdateListener(animation -> { phase = (Float) animation.getAnimatedValue(); invalidate(); });
        motion.start();
    }

    private void stopMotion() { if (motion != null) { motion.cancel(); motion = null; } }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); restartMotion(); }
    @Override protected void onDetachedFromWindow() { stopMotion(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); if (visibility == VISIBLE) restartMotion(); else stopMotion(); }

    private static int withAlpha(int color, int alpha) { return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)); }
    private static int lighten(int color, float amount) { return Color.rgb((int) (Color.red(color) + (255 - Color.red(color)) * amount), (int) (Color.green(color) + (255 - Color.green(color)) * amount), (int) (Color.blue(color) + (255 - Color.blue(color)) * amount)); }
    // Glossy black and white: the orb is polished silver when idle and takes the single accent
    // blue only once connected, so colour on this screen always means "you are protected".
    private int startColor() {
        switch (state) {
            case CONNECTED:     return Color.rgb(0x4D, 0xA3, 0xFF);
            case CONNECTING:    return Color.rgb(0xE6, 0xE6, 0xEC);
            case DISCONNECTING: return Color.rgb(0x9A, 0x9A, 0xA6);
            case ERROR:         return Color.rgb(0xFF, 0x5A, 0x6E);
            default:            return Color.rgb(0xF2, 0xF2, 0xF5);
        }
    }

    private int endColor() {
        switch (state) {
            case CONNECTED:     return Color.rgb(0x12, 0x4E, 0x8C);
            case CONNECTING:    return Color.rgb(0x6E, 0x6E, 0x7A);
            case DISCONNECTING: return Color.rgb(0x3A, 0x3A, 0x44);
            case ERROR:         return Color.rgb(0x6E, 0x18, 0x28);
            default:            return Color.rgb(0x5A, 0x5A, 0x66);
        }
    }
    private void updateShaders() {
        if (getWidth() == 0 || getHeight() == 0) return;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) * .39f;
        int start = startColor();
        int end = endColor();
        int highlight = Color.argb(state == DISCONNECTED ? 90 : 165, 255, 255, 255);
        ringShader = new SweepGradient(cx, cy, new int[]{start, end, highlight, start}, new float[]{0f, .46f, .72f, 1f});
        bodyShader = new RadialGradient(cx - radius * .26f, cy - radius * .32f, radius * 1.5f, new int[]{lighten(start, .18f), start, end, Color.rgb(6, 6, 8)}, new float[]{0f, .28f, .66f, 1f}, Shader.TileMode.CLAMP);
        highlightShader = new RadialGradient(cx - radius * .32f, cy - radius * .4f, radius * .72f, new int[]{Color.argb(70, 255, 255, 255), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP);
    }
}
