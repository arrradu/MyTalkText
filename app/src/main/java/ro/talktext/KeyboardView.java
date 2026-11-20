package ro.talktext;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import java.util.List;

/**
 * Draws the keys and turns touches into key presses, the way Gboard does:
 * preview above the finger, long press for alternates (ă â î ș ț, numbers), repeating delete,
 * double-tap Shift for Caps Lock, slide on the space bar to move the cursor.
 */
final class KeyboardView extends View {
    interface Listener {
        void onText(String text);
        void onDelete();
        void onEnter();
        void onSpace();
        void onCursor(int step);
        void onGlobe(boolean longPress);
        void onShiftChanged();
        /** Swipe left from ⌫: select the last `words` words (0 = none). */
        void onSwipeDelete(int words);
        /** Finger lifted: delete the selected words (0 = cancelled, put the cursor back). */
        void onSwipeDeleteEnd(int words);
    }

    static final int SHIFT_OFF = 0, SHIFT_ONCE = 1, SHIFT_LOCK = 2;
    private static final long LONG_MS = 380, REPEAT_START_MS = 400, REPEAT_MS = 55, DOUBLE_TAP_MS = 320;

    private Listener listener; // not final: the Runnables below are created before the constructor sets it
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    // Gboard "borderless": letters sit on the background; only ?123 / Enter / space are pills.
    private final int bg, pill, spacePill, previewColor, pressedTint, ink, muted, accent;
    private final android.graphics.drawable.Drawable icShift, icShiftOn, icCaps, icDelete, icReturn, icSend, icSearch, icDone, icNext, icGlobe;

    // Smooth feedback: the key highlight fades out, the preview grows in.
    private static final long FADE_MS = 160, GROW_MS = 90;
    private KeyLayout.Key fading;
    private long fadeStart, pressStart;

    private List<List<KeyLayout.Key>> rows;
    private int page = KeyLayout.PAGE_LETTERS;
    private boolean cyrillic;
    private int shift = SHIFT_OFF;
    private long lastShiftTap;
    private String enterLabel = "↵", spaceLabel = "";
    boolean vibrate = true;

    private KeyLayout.Key pressed;
    private int pointer = -1;
    private float downX, spaceDragX;
    private boolean spaceDragged, longShown;
    // Swipe-to-delete on ⌫, as on Gboard: one more word for every 24 dp to the left.
    private static final float SWIPE_START_DP = 18, SWIPE_WORD_DP = 24;
    private boolean deleteSwiping, deleteRepeated;
    private int swipeWords;
    private String[] popup;
    private int popupSel;
    private final RectF popupRect = new RectF();

    private final Runnable longPress = () -> {
        if (pressed == null) return;
        if (pressed.code == KeyLayout.GLOBE) {
            longShown = true;
            listener.onGlobe(true);
        } else if (pressed.alts != null) {
            longShown = true;
            popup = new String[pressed.alts.length];
            for (int i = 0; i < popup.length; i++) popup[i] = pressed.isLetter() ? KeyLayout.shifted(pressed.alts[i], shift != SHIFT_OFF) : pressed.alts[i];
            popupSel = 0;
            invalidate();
        }
    };
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (pressed == null || pressed.code != KeyLayout.DELETE || deleteSwiping) return;
            deleteRepeated = true;
            listener.onDelete();
            main.postDelayed(this, REPEAT_MS);
        }
    };

    KeyboardView(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        dp = c.getResources().getDisplayMetrics().density;
        boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF181A1F : 0xFFF0F2F5;
        pill = night ? 0xFF5D6779 : 0xFFC9D3E3;
        spacePill = night ? 0xFF2A2F38 : 0xFFDCE2EA;
        previewColor = night ? 0xFF3A404B : 0xFFFFFFFF;
        pressedTint = night ? 0x26FFFFFF : 0x1A000000;
        ink = night ? 0xFFECEEF1 : 0xFF1F1F1F;
        muted = night ? 0xFFA6ABB3 : 0xFF6B6F76;
        accent = Ui.LAVENDER;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        small.setTextAlign(Paint.Align.CENTER);
        small.setColor(muted);
        icShift = icon(c, R.drawable.kb_shift);
        icShiftOn = icon(c, R.drawable.kb_shift_on);
        icCaps = icon(c, R.drawable.kb_caps);
        icDelete = icon(c, R.drawable.kb_backspace);
        icReturn = icon(c, R.drawable.kb_return);
        icSend = icon(c, R.drawable.kb_send);
        icSearch = icon(c, R.drawable.kb_search);
        icDone = icon(c, R.drawable.kb_done);
        icNext = icon(c, R.drawable.kb_next);
        icGlobe = icon(c, R.drawable.kb_globe);
        setHapticFeedbackEnabled(true);
        rows = KeyLayout.rows(page, cyrillic);
    }

    private static android.graphics.drawable.Drawable icon(Context c, int res) {
        return c.getDrawable(res).mutate();
    }

    int background() { return bg; }
    int ink() { return ink; }
    int muted() { return muted; }
    int specialColor() { return spacePill; }

    // ---- state from the service ----

    void setPage(int p) {
        page = p;
        rows = KeyLayout.rows(page, cyrillic);
        requestLayout();
        layoutKeys();
        invalidate();
    }

    int page() { return page; }

    void setCyrillic(boolean c) {
        cyrillic = c;
        setPage(page);
    }

    boolean cyrillic() { return cyrillic; }

    void setShift(int s) {
        if (shift == SHIFT_LOCK && s == SHIFT_ONCE) return; // auto-capitalization never cancels Caps Lock
        shift = s;
        invalidate();
    }

    int shift() { return shift; }

    void setEnterLabel(String l) { enterLabel = l; invalidate(); }

    void setSpaceLabel(String l) { spaceLabel = l; invalidate(); }

    // ---- layout ----

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int height = (int) (rows.size() * 54 * dp + 10 * dp);
        setMeasuredDimension(width, height);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { layoutKeys(); }

    private void layoutKeys() {
        if (getWidth() == 0) return;
        float pad = 3 * dp, top = 5 * dp;
        float rowH = (getHeight() - 10 * dp) / rows.size();
        for (int i = 0; i < rows.size(); i++) {
            List<KeyLayout.Key> row = rows.get(i);
            float unit = (getWidth() - 2 * pad) / KeyLayout.units(row);
            float x = pad;
            for (KeyLayout.Key k : row) {
                k.x = x;
                k.y = top + i * rowH;
                k.w = k.units * unit;
                k.h = rowH;
                x += k.w;
            }
        }
    }

    // ---- drawing ----

    @Override protected void onDraw(Canvas c) {
        // Not drawColor: the parent does not clip (for key previews), so drawColor would paint over the toolbar too.
        fill.setColor(bg);
        c.drawRect(0, 0, getWidth(), getHeight(), fill);
        long now = SystemClock.uptimeMillis();
        boolean animating = false;
        for (List<KeyLayout.Key> row : rows)
            for (KeyLayout.Key k : row) {
                if (k.code == KeyLayout.GAP) continue;
                boolean isPill = k.code == KeyLayout.ENTER || k.code == KeyLayout.SYMBOLS || k.code == KeyLayout.LETTERS || k.code == KeyLayout.MORE;
                if (k.code == KeyLayout.SPACE || isPill) {
                    r.set(k.x + 4 * dp, k.y + 7 * dp, k.x + k.w - 4 * dp, k.y + k.h - 7 * dp);
                    fill.setColor(k.code == KeyLayout.SPACE ? spacePill : k.code == KeyLayout.ENTER ? accent : pill);
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
                }
                // Press highlight: a soft rounded glow that fades out after release.
                float alpha = 0;
                if (k == pressed && !longShown) alpha = 1;
                else if (k == fading) {
                    float f = 1f - (now - fadeStart) / (float) FADE_MS;
                    if (f > 0) {
                        alpha = f;
                        animating = true;
                    } else fading = null;
                }
                if (alpha > 0) {
                    r.set(k.x + 2 * dp, k.y + 4 * dp, k.x + k.w - 2 * dp, k.y + k.h - 4 * dp);
                    fill.setColor(pressedTint);
                    fill.setAlpha((int) (Color.alpha(pressedTint) * alpha));
                    c.drawRoundRect(r, 12 * dp, 12 * dp, fill);
                    fill.setAlpha(255);
                }
                drawLabel(c, k);
                if (k.hint != null && page == KeyLayout.PAGE_LETTERS) {
                    small.setTextSize(11 * dp);
                    c.drawText(k.hint, k.x + k.w - 9 * dp, k.y + 17 * dp, small);
                }
            }
        if (pressed != null && !longShown && pressed.isChar()) animating |= drawPreview(c, pressed, now);
        if (longShown && popup != null) drawPopup(c);
        if (animating) postInvalidateOnAnimation();
    }

    private void drawIcon(Canvas c, android.graphics.drawable.Drawable d, KeyLayout.Key k, float sizeDp, int color) {
        int s = (int) (sizeDp * dp);
        int cx = (int) (k.x + k.w / 2), cy = (int) (k.y + k.h / 2);
        d.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
        d.setTint(color);
        d.draw(c);
    }

    private void drawLabel(Canvas c, KeyLayout.Key k) {
        switch (k.code) {
            case KeyLayout.SHIFT:
                drawIcon(c, shift == SHIFT_LOCK ? icCaps : shift == SHIFT_ONCE ? icShiftOn : icShift, k, 26, ink);
                return;
            case KeyLayout.DELETE:
                drawIcon(c, icDelete, k, 24, ink);
                return;
            case KeyLayout.GLOBE:
                drawIcon(c, icGlobe, k, 22, ink);
                return;
            case KeyLayout.ENTER: {
                android.graphics.drawable.Drawable d;
                switch (enterLabel) {
                    case "➤": d = icSend; break;
                    case "⌕": d = icSearch; break;
                    case "→": d = icNext; break;
                    case "✓": d = icDone; break;
                    default: d = icReturn;
                }
                drawIcon(c, d, k, 24, Ui.INK);
                return;
            }
            default:
        }
        String label;
        float size;
        text.setColor(ink);
        if (k.code == KeyLayout.CHAR) {
            label = k.isLetter() ? KeyLayout.shifted(k.label, shift != SHIFT_OFF) : k.label;
            size = k.isLetter() ? 25 * dp : 23 * dp;
        } else if (k.code == KeyLayout.SPACE) {
            label = spaceLabel;
            size = 14 * dp;
        } else {
            label = k.label; // ?123, ABC, the second symbols page
            size = 17 * dp;
        }
        text.setTextSize(size);
        text.setFakeBoldText(k.code != KeyLayout.CHAR && k.code != KeyLayout.SPACE);
        float cy = k.y + k.h / 2 - (text.descent() + text.ascent()) / 2;
        c.drawText(label, k.x + k.w / 2, cy, text);
        text.setFakeBoldText(false);
    }

    /** Enlarged key above the finger; grows in over 90 ms. Returns true while it is still growing. */
    private boolean drawPreview(Canvas c, KeyLayout.Key k, long now) {
        float grow = Math.min(1f, (now - pressStart) / (float) GROW_MS);
        float ease = 1 - (1 - grow) * (1 - grow);
        float w = Math.max(k.w * 1.1f, 48 * dp), h = k.h * 1.2f;
        float left = Math.max(2 * dp, Math.min(getWidth() - w - 2 * dp, k.x + k.w / 2 - w / 2));
        float bottom = k.y + 4 * dp;
        // The window clips above the toolbar (~50 dp): keep the top-row preview inside it.
        r.set(left, Math.max(bottom - h, -44 * dp), left + w, bottom);
        float sc = 0.85f + 0.15f * ease;
        c.save();
        c.scale(sc, sc, r.centerX(), r.bottom);
        fill.setColor(previewColor);
        fill.setShadowLayer(10 * dp, 0, 3 * dp, 0x44000000);
        c.drawRoundRect(r, 14 * dp, 14 * dp, fill);
        fill.clearShadowLayer();
        text.setColor(ink);
        text.setTextSize(32 * dp);
        String label = k.isLetter() ? KeyLayout.shifted(k.label, shift != SHIFT_OFF) : k.label;
        c.drawText(label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text);
        c.restore();
        return grow < 1;
    }

    private void drawPopup(Canvas c) {
        float cell = Math.max(pressed.w, 36 * dp), h = pressed.h * 1.05f;
        float w = cell * popup.length;
        float left = Math.max(2 * dp, Math.min(getWidth() - w - 2 * dp, pressed.x + pressed.w / 2 - cell / 2));
        float bottom = pressed.y + 4 * dp;
        popupRect.set(left, Math.max(bottom - h, -44 * dp), left + w, bottom);
        fill.setColor(previewColor);
        fill.setShadowLayer(12 * dp, 0, 3 * dp, 0x55000000);
        c.drawRoundRect(popupRect, 16 * dp, 16 * dp, fill);
        fill.clearShadowLayer();
        text.setTextSize(23 * dp);
        for (int i = 0; i < popup.length; i++) {
            float x = left + i * cell;
            if (i == popupSel) {
                fill.setColor(accent);
                c.drawRoundRect(x + 4 * dp, popupRect.top + 4 * dp, x + cell - 4 * dp, popupRect.bottom - 4 * dp, 12 * dp, 12 * dp, fill);
            }
            text.setColor(i == popupSel ? Ui.INK : ink);
            c.drawText(popup[i], x + cell / 2, popupRect.centerY() - (text.descent() + text.ascent()) / 2, text);
        }
    }

    // ---- touch ----

    private KeyLayout.Key keyAt(float x, float y) {
        KeyLayout.Key best = null;
        float bestDist = Float.MAX_VALUE;
        for (List<KeyLayout.Key> row : rows)
            for (KeyLayout.Key k : row) {
                if (k.code == KeyLayout.GAP) continue;
                if (k.contains(x, y)) return k;
                float dx = Math.max(0, Math.max(k.x - x, x - (k.x + k.w))), dy = Math.max(0, Math.max(k.y - y, y - (k.y + k.h)));
                float d = dx * dx + dy * dy;
                if (d < bestDist) { bestDist = d; best = k; }
            }
        return best; // touches on the gaps between keys still hit the nearest key
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pressed != null) release(true); // fast two-thumb typing: finish the first key at once
                pointer = e.getPointerId(index);
                press(keyAt(e.getX(index), e.getY(index)), e.getX(index));
                return true;
            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(pointer);
                if (i < 0 || pressed == null) return true;
                move(e.getX(i), e.getY(i));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerId(index) == pointer) release(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                release(false);
                return true;
            default:
                return true;
        }
    }

    private void press(KeyLayout.Key k, float x) {
        if (k == null) return;
        pressed = k;
        pressStart = SystemClock.uptimeMillis();
        fading = null;
        downX = x;
        spaceDragX = x;
        spaceDragged = false;
        longShown = false;
        popup = null;
        if (vibrate) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (k.code == KeyLayout.DELETE) {
            // No delete yet: a swipe may follow. A tap deletes on release, a hold starts repeating.
            deleteSwiping = false;
            deleteRepeated = false;
            swipeWords = 0;
            main.postDelayed(repeat, REPEAT_START_MS);
        } else if (k.alts != null || k.code == KeyLayout.GLOBE) {
            main.postDelayed(longPress, LONG_MS);
        }
        invalidate();
    }

    private void move(float x, float y) {
        if (pressed.code == KeyLayout.DELETE && !deleteRepeated) {
            float dx = downX - x;
            if (!deleteSwiping && dx > SWIPE_START_DP * dp) {
                deleteSwiping = true;
                main.removeCallbacks(repeat);
            }
            if (deleteSwiping) {
                int n = dx <= SWIPE_START_DP * dp ? 0 : 1 + (int) ((dx - SWIPE_START_DP * dp) / (SWIPE_WORD_DP * dp));
                if (n != swipeWords) {
                    swipeWords = n;
                    if (vibrate) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    listener.onSwipeDelete(n);
                }
            }
            return;
        }
        if (longShown && popup != null) {
            float cell = popupRect.width() / popup.length;
            int sel = (int) ((x - popupRect.left) / cell);
            sel = Math.max(0, Math.min(popup.length - 1, sel));
            if (sel != popupSel) { popupSel = sel; invalidate(); }
            return;
        }
        if (pressed.code == KeyLayout.SPACE) {
            float step = 12 * dp;
            if (!spaceDragged && Math.abs(x - downX) > 3 * step) spaceDragged = true;
            if (spaceDragged) {
                while (x - spaceDragX > step) { listener.onCursor(1); spaceDragX += step; tick(); }
                while (spaceDragX - x > step) { listener.onCursor(-1); spaceDragX -= step; tick(); }
            }
            return;
        }
        if (pressed.isChar()) {
            KeyLayout.Key k = keyAt(x, y);
            if (k != null && k != pressed && k.isChar()) { // finger slid to a neighbour key before lifting
                main.removeCallbacks(longPress);
                pressed = k;
                pressStart = SystemClock.uptimeMillis();
                if (k.alts != null) main.postDelayed(longPress, LONG_MS);
                invalidate();
            }
        }
    }

    private void release(boolean commit) {
        main.removeCallbacks(longPress);
        main.removeCallbacks(repeat);
        KeyLayout.Key k = pressed;
        pressed = null;
        pointer = -1;
        if (k != null && k.code == KeyLayout.DELETE && deleteSwiping) {
            deleteSwiping = false;
            listener.onSwipeDeleteEnd(commit ? swipeWords : 0);
            swipeWords = 0;
            invalidate();
            return;
        }
        if (k != null && !longShown) {
            fading = k;
            fadeStart = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
        if (k != null && commit) {
            if (longShown) {
                if (popup != null) type(popup[popupSel], k);
            } else switch (k.code) {
                case KeyLayout.CHAR: type(k.isLetter() ? KeyLayout.shifted(k.out, shift != SHIFT_OFF) : k.out, k); break;
                case KeyLayout.DELETE: if (!deleteSwiping && !deleteRepeated) listener.onDelete(); break;
                case KeyLayout.SPACE: if (!spaceDragged) listener.onSpace(); break;
                case KeyLayout.ENTER: listener.onEnter(); break;
                case KeyLayout.SHIFT: tapShift(); break;
                case KeyLayout.SYMBOLS: setPage(KeyLayout.PAGE_SYMBOLS); break;
                case KeyLayout.LETTERS: setPage(KeyLayout.PAGE_LETTERS); break;
                case KeyLayout.MORE: setPage(KeyLayout.PAGE_MORE); break;
                case KeyLayout.GLOBE: listener.onGlobe(false); break;
                default:
            }
        }
        longShown = false;
        popup = null;
        invalidate();
    }

    /** A light tick for every step of the cursor on the space bar, as on Gboard. */
    private void tick() {
        if (vibrate) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    @Override protected void onDetachedFromWindow() {
        release(false); // never leave the delete repeat running
        super.onDetachedFromWindow();
    }

    private void type(String s, KeyLayout.Key k) {
        listener.onText(s);
        if (k.isLetter() && shift == SHIFT_ONCE) {
            shift = SHIFT_OFF;
            listener.onShiftChanged();
        }
    }

    private void tapShift() {
        long now = SystemClock.elapsedRealtime();
        if (shift == SHIFT_LOCK) shift = SHIFT_OFF;
        else if (now - lastShiftTap < DOUBLE_TAP_MS) shift = SHIFT_LOCK;
        else shift = shift == SHIFT_OFF ? SHIFT_ONCE : SHIFT_OFF;
        lastShiftTap = now;
        listener.onShiftChanged();
    }
}
