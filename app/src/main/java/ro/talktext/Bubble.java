package ro.talktext;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * The dictation button, used twice: the floating bubble (a lavender tile) and, compact, the round
 * microphone of the keyboard toolbar (Gboard style). Ready = still bars or a microphone;
 * listening = bars that follow your voice; working = three dots; done = ↶ for 2 s; error = !.
 * Colours cross-fade between states and everything moves at the display's frame rate.
 */
final class Bubble extends View {
    enum Mode { IDLE, STARTING, RECORDING, PROCESSING, UNDO, ERROR }

    private static final float[] BARS = {0.34f, 0.78f, 0.56f, 1f};
    private static final long FADE_MS = 220;
    private static final ArgbEvaluator ARGB = new ArgbEvaluator();

    private Mode mode = Mode.IDLE;
    private float level, shown;
    private final float[] bars = new float[4];
    private long since, lastFrame;
    private int fromBg, fromFg, idleBg = Ui.LAVENDER, idleFg = Ui.INK;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF(), bar = new RectF();
    private final Path arrow = new Path();
    private final float dp;        // one "dp" of the 64 dp floating bubble, scaled to this button's size
    private final boolean compact; // keyboard microphone: round, no timer or caption, no shadow
    private final Drawable micGlyph;

    Bubble(Context c) { this(c, 64, false); }

    Bubble(Context c, float sizeDp, boolean compact) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density * sizeDp / 64;
        this.compact = compact;
        micGlyph = compact ? c.getDrawable(R.drawable.ic_mic_key).mutate() : null;
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Ui.sans(c));
        label.setFakeBoldText(true);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        if (!compact) setElevation(8 * dp);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                int p = (int) (4 * dp);
                o.setRoundRect(p, p, v.getWidth() - p, v.getHeight() - p, compact ? (v.getWidth() - 2 * p) / 2f : 18 * dp);
            }
        });
        fromBg = background(Mode.IDLE);
        fromFg = foreground(Mode.IDLE);
    }

    /** Ready-state colours; the keyboard uses its own dark/light key colour so the mic looks like Gboard's. */
    void setIdleColors(int bg, int fg) {
        idleBg = bg;
        idleFg = fg;
        fromBg = background(mode);
        fromFg = foreground(mode);
        invalidate();
    }

    Mode mode() { return mode; }

    void setMode(Mode m) {
        if (m != mode) {
            float f = fade();
            fromBg = (int) ARGB.evaluate(f, fromBg, background(mode));
            fromFg = (int) ARGB.evaluate(f, fromFg, foreground(mode));
            since = SystemClock.uptimeMillis();
        }
        mode = m;
        if (m != Mode.RECORDING) level = 0;
        postInvalidateOnAnimation();
    }

    void setLevel(float l) {
        level = l;
        if (mode == Mode.STARTING) setMode(Mode.RECORDING);
        postInvalidateOnAnimation();
    }

    /** A gentle press-in for the keyboard microphone. */
    @Override public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        if (compact) animate().scaleX(pressed ? 0.9f : 1f).scaleY(pressed ? 0.9f : 1f).setDuration(110).start();
    }

    @Override protected void onMeasure(int w, int h) {
        int size = (int) (64 * dp);
        setMeasuredDimension(size, size);
    }

    private int background(Mode m) {
        switch (m) {
            case RECORDING: return compact ? Ui.LAVENDER : Ui.INK;
            case ERROR: return Ui.ERROR_BG;
            case IDLE: return idleBg;
            default: return Ui.LAVENDER;
        }
    }

    private int foreground(Mode m) {
        switch (m) {
            case RECORDING: return compact ? Ui.INK : Ui.LAVENDER;
            case ERROR: return Ui.RED;
            case IDLE: return idleFg;
            default: return Ui.INK;
        }
    }

    private float fade() {
        return Math.min(1f, (SystemClock.uptimeMillis() - since) / (float) FADE_MS);
    }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16 : Math.min(64, now - lastFrame);
        lastFrame = now;
        float p = 4 * dp;
        box.set(p, p, getWidth() - p, getHeight() - p);
        float cx = box.centerX(), cy = box.centerY(), r = compact ? box.width() / 2 : 18 * dp;
        long t = now - since;
        float f = fade();
        float ease = f * f * (3 - 2 * f);
        int bg = (int) ARGB.evaluate(ease, fromBg, background(mode)), fg = (int) ARGB.evaluate(ease, fromFg, foreground(mode));
        fill.setColor(bg);
        c.drawRoundRect(box, r, r, fill);
        ink.setColor(fg);
        label.setColor(fg);

        switch (mode) {
            case RECORDING: {
                // Time-based smoothing: the same softness at 60, 90 or 120 Hz.
                shown += (level - shown) * (float) (1 - Math.exp(-dt / 90.0));
                float loud = Math.min(1, shown * 1.6f);
                for (int i = 0; i < 4; i++) {
                    float wave = 0.55f + 0.45f * (float) Math.sin(t / 210.0 + i * 1.9);
                    float target = 0.24f + 0.76f * loud * wave;
                    bars[i] += (target - bars[i]) * (float) (1 - Math.exp(-dt / 70.0));
                }
                if (compact) {
                    drawBars(c, cx, cy, 26 * dp, bars);
                    break;
                }
                drawBars(c, cx, cy - 6 * dp, 22 * dp, bars);
                long s = t / 1000;
                label.setTextSize(11 * dp);
                c.drawText(s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60), cx, box.bottom - 9 * dp, label);
                break;
            }
            case STARTING: {
                for (int i = 0; i < 4; i++) bars[i] = BARS[i] * (0.65f + 0.35f * (float) Math.sin(t / 300.0 + i * 1.2));
                drawBars(c, cx, cy, 24 * dp, bars);
                break;
            }
            case PROCESSING:
                for (int i = 0; i < 3; i++) {
                    float phase = (float) Math.sin(t / 190.0 - i * 0.8);
                    float lift = Math.max(0, phase) * 5 * dp;
                    c.drawCircle(cx + (i - 1) * 9 * dp, cy - lift, (3f + 0.4f * Math.max(0, phase)) * dp, ink);
                }
                break;
            case UNDO: {
                // Standard "undo" arrow (Material icon, 24-unit grid) + a word, so it never reads as "reload".
                arrow.reset();
                arrow.moveTo(12.5f, 8);
                arrow.rCubicTo(-2.65f, 0, -5.05f, 0.99f, -6.9f, 2.6f);
                arrow.lineTo(2, 7);
                arrow.rLineTo(0, 9);
                arrow.rLineTo(9, 0);
                arrow.rLineTo(-3.62f, -3.62f);
                arrow.rCubicTo(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f);
                arrow.rCubicTo(3.54f, 0, 6.55f, 2.31f, 7.6f, 5.5f);
                arrow.rLineTo(2.37f, -0.78f);
                arrow.cubicTo(21.08f, 11.03f, 17.15f, 8, 12.5f, 8);
                arrow.close();
                float size = 24 * dp, scale = size / 24;
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(scale, scale);
                m.postTranslate(cx - size / 2, cy - (compact ? 0 : 6 * dp) - size / 2);
                arrow.transform(m);
                ink.setStyle(Paint.Style.FILL);
                c.drawPath(arrow, ink);
                label.setTextSize(10 * dp);
                if (!compact) c.drawText("anulează", cx, box.bottom - 8 * dp, label);
                break;
            }
            case ERROR:
                label.setTextSize(24 * dp);
                c.drawText("!", cx, cy + 8 * dp, label);
                break;
            default:
                if (compact) { // Gboard-like microphone at rest
                    int s = (int) (26 * dp);
                    micGlyph.setBounds((int) cx - s / 2, (int) cy - s / 2, (int) cx + s / 2, (int) cy + s / 2);
                    micGlyph.setTint(fg);
                    micGlyph.draw(c);
                } else drawBars(c, cx, cy, 24 * dp, BARS);
        }
        boolean moving = mode == Mode.RECORDING || mode == Mode.STARTING || mode == Mode.PROCESSING || f < 1;
        if (moving) postInvalidateOnAnimation();
        else lastFrame = 0;
    }

    /** Four rounded bars, centred, heights as fractions of maxH. */
    private void drawBars(Canvas c, float cx, float cy, float maxH, float[] heights) {
        float w = 3.6f * dp, gap = 3.4f * dp;
        float left = cx - (4 * w + 3 * gap) / 2;
        ink.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            float h = Math.max(w, maxH * heights[i]);
            float x = left + i * (w + gap);
            bar.set(x, cy - h / 2, x + w, cy + h / 2);
            c.drawRoundRect(bar, w / 2, w / 2, ink);
        }
    }
}
