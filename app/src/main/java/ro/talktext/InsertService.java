package ro.talktext;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import java.util.function.Consumer;

/**
 * Shows the bubble next to editable fields, remembers which field you were in when you started,
 * and writes the finished text there at the cursor. Never presses Send. Never touches password fields.
 */
public class InsertService extends AccessibilityService implements Session.Listener {
    static InsertService instance;

    private static final long START_TIMEOUT_MS = 6000, UNDO_MS = 2000, SETTLE_MS = 300;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private Bubble bubble;
    private WindowManager.LayoutParams params;
    private boolean shown;
    private Session session;
    private AccessibilityNodeInfo target;
    private AccessibilityNodeInfo undoNode;
    private String undoBefore, undoAfter;
    private int undoCursor;
    private String app; // package of the app you dictated into
    private final Runnable undoExpired = () -> {
        if (bubble.mode() == Bubble.Mode.UNDO) {
            clearUndo();
            bubble.setMode(Bubble.Mode.IDLE);
            refresh();
        }
    };
    private final Runnable startWatchdog = () -> {
        if (bubble.mode() == Bubble.Mode.STARTING && session != null) {
            RecorderService.send(this, RecorderService.CANCEL);
            session.cancel();
            toast("Microfonul nu a pornit. Încearcă din nou.");
        }
    };

    @Override protected void onServiceConnected() {
        if (shown) {
            try { wm.removeView(bubble); } catch (RuntimeException ignored) { }
            shown = false;
        }
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        bubble = new Bubble(this);
        SharedPreferences p = Prefs.get(this);
        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int width = getResources().getDisplayMetrics().widthPixels, height = getResources().getDisplayMetrics().heightPixels;
        params.x = p.getInt("bubble_x", width - (int) (64 * getResources().getDisplayMetrics().density));
        params.y = p.getInt("bubble_y", height * 2 / 5);
        bubble.setOnTouchListener(new Touch());
    }

    // ---- when to show the bubble ----

    static boolean safe(AccessibilityNodeInfo n) {
        if (n == null || !n.isEditable() || n.isPassword() || !n.isEnabled()) return false;
        int variation = n.getInputType() & 0xfff;
        return variation != 0x81 && variation != 0x91 && variation != 0xe1 && variation != 0x12;
    }

    private AccessibilityNodeInfo focused() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo n = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (!safe(n) || Prefs.excluded(this, n.getPackageName())) return null;
        return n;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (bubble == null) return;
        Bubble.Mode m = bubble.mode();
        // Leaving the app you dictated into ends the undo / error display at once, so the bubble goes with the app.
        // While listening or working it stays, so the dictation can still be stopped.
        if ((m == Bubble.Mode.UNDO || m == Bubble.Mode.ERROR) && left()) {
            main.removeCallbacks(undoExpired);
            clearUndo();
            bubble.setMode(Bubble.Mode.IDLE);
        }
        m = bubble.mode();
        boolean busy = m == Bubble.Mode.STARTING || m == Bubble.Mode.RECORDING || m == Bubble.Mode.PROCESSING;
        show(busy || (focused() != null && !ownKeyboard()));
    }

    /** With the TalkText keyboard active its microphone key dictates, so the bubble stays out of the way. */
    private boolean ownKeyboard() {
        String ime = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        return ime != null && ime.startsWith(getPackageName() + "/");
    }

    /** True when another app (launcher, recents, another app) is now in front of the one you dictated into. */
    private boolean left() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        CharSequence pkg = root == null ? null : root.getPackageName();
        return pkg != null && app != null && !app.contentEquals(pkg); // null = window still changing: decide on the next event
    }

    private void show(boolean visible) {
        if (visible == shown) return;
        try {
            if (visible) wm.addView(bubble, params);
            else wm.removeView(bubble);
            shown = visible;
        } catch (RuntimeException e) {
            shown = false; // a failed add or remove both mean the bubble is not attached
        }
    }

    // ---- bubble actions ----

    private void tap() {
        switch (bubble.mode()) {
            case IDLE: case ERROR: begin(); break;
            case STARTING: case RECORDING: stopOrCancel(RecorderService.STOP); break;
            case PROCESSING: toast("Procesez textul…"); break;
            case UNDO: undo(); break;
        }
    }

    private void longPress() {
        Bubble.Mode m = bubble.mode();
        if (m == Bubble.Mode.STARTING || m == Bubble.Mode.RECORDING) {
            stopOrCancel(RecorderService.CANCEL);
        } else if (m == Bubble.Mode.PROCESSING && session != null) {
            session.cancel();
        } else if (m == Bubble.Mode.IDLE) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void stopOrCancel(String action) {
        if (!RecorderService.send(this, action) && session != null) session.cancel();
    }

    private void begin() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Permite microfonul în TalkText.");
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (!Vault.has(this, Vault.OPENAI)) {
            toast("Introdu cheia OpenAI în TalkText.");
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        AccessibilityNodeInfo f = focused();
        if (f == null) { toast("Atinge întâi câmpul în care scrii."); return; }
        Session s = Session.start(this, this);
        if (s == null) { toast("O dictare este deja în curs."); return; }
        session = s;
        target = f;
        app = f.getPackageName() == null ? null : f.getPackageName().toString();
        clearUndo();
        main.removeCallbacks(undoExpired);
        bubble.setMode(Bubble.Mode.STARTING);
        main.removeCallbacks(startWatchdog);
        main.postDelayed(startWatchdog, START_TIMEOUT_MS);
        if (!RecorderService.send(this, RecorderService.START)) {
            s.trampolineTried = true;
            startViaActivity();
        }
    }

    boolean owns(Session s) { return s != null && s == session; }

    /** Android sometimes allows the microphone only after an app window was visible; a 1-frame transparent window does it. */
    void startViaActivity() {
        main.post(() -> {
            try {
                startActivity(new Intent(this, StartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } catch (RuntimeException e) {
                if (session != null) session.micFailed("Android nu permite pornirea microfonului. Deschide TalkText și încearcă din nou.");
            }
        });
    }

    // ---- session callbacks ----

    @Override public void onLevel(float level) {
        if (instance != this) return;
        if (bubble.mode() == Bubble.Mode.STARTING || bubble.mode() == Bubble.Mode.RECORDING) bubble.setLevel(level);
    }

    @Override public void onPhase(Session s, Session.Phase phase, String message) {
        if (s != session || instance != this) return;
        switch (phase) {
            case PROCESSING:
                bubble.setMode(Bubble.Mode.PROCESSING);
                break;
            case FAILED:
                target = null;
                bubble.setMode(Bubble.Mode.ERROR);
                if (message != null) toast(message);
                main.postDelayed(this::idleIfError, 3000);
                break;
            case CANCELLED:
                target = null;
                bubble.setMode(Bubble.Mode.IDLE);
                toast("Dictare anulată");
                refresh();
                break;
            default:
        }
    }

    @Override public void onResult(Session s, String text, String warning) {
        if (s != session || instance != this) return;
        AccessibilityNodeInfo node = target;
        target = null;
        if (warning != null) toast(warning);
        Consumer<Boolean> done = ok -> {
            if (!ok) {
                copy(text);
                toast("Nu am putut scrie în câmp. Textul e copiat: ține apăsat → Lipește.");
            }
            if (undoNode != null) {
                SharedPreferences p = Prefs.get(this);
                if (!p.getBoolean("undo_hint_shown", false)) {
                    p.edit().putBoolean("undo_hint_shown", true).apply();
                    toast("Text inserat. Dacă nu e bun, atinge bula în 2 secunde ca să-l anulezi.");
                }
                bubble.setMode(Bubble.Mode.UNDO);
                main.removeCallbacks(undoExpired);
                main.postDelayed(undoExpired, UNDO_MS);
            } else {
                bubble.setMode(Bubble.Mode.IDLE);
                refresh();
            }
        };
        if (node != null && node.refresh() && safe(node)) insert(node, text, done);
        else done.accept(false);
    }

    private void idleIfError() {
        if (bubble.mode() == Bubble.Mode.ERROR) { bubble.setMode(Bubble.Mode.IDLE); refresh(); }
    }

    private void refresh() { show(focused() != null && !ownKeyboard()); }

    // ---- insertion ----

    // Placeholders that messengers report as if they were the field's text.
    private static final java.util.Set<String> PLACEHOLDERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "message", "type a message", "write a message", "text message", "mesaj", "scrie un mesaj",
            "сообщение", "написать сообщение", "введите сообщение", "повідомлення", "написати повідомлення"));

    private static String current(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || n.isShowingHintText()) return "";
        CharSequence hint = n.getHintText();
        if (hint != null && hint.length() > 0 && hint.toString().contentEquals(t)) return "";
        String s = t.toString();
        if (PLACEHOLDERS.contains(s.trim().replaceAll("[.…]+$", "").toLowerCase(java.util.Locale.ROOT))) return "";
        return s;
    }

    /**
     * At the cursor (or replacing the selection). SET_TEXT only when the field's text is known,
     * otherwise paste, so existing text we cannot see is never overwritten. Checked after the field
     * had time to update; paste is tried only if SET_TEXT visibly changed nothing (no double text).
     */
    private void insert(AccessibilityNodeInfo n, String text, Consumer<Boolean> done) {
        String before = current(n);
        int a = n.getTextSelectionStart(), b = n.getTextSelectionEnd();
        // Some apps (Telegram) report an empty field as its placeholder ("Message") with no cursor.
        // Then the visible text is not trustworthy: paste at the app's own cursor instead of rewriting the field.
        boolean noCursor = a < 0 || b < 0 || a > before.length() || b > before.length();
        boolean known = n.getText() != null || n.isShowingHintText();
        if (!known || (noCursor && !before.isEmpty())) {
            paste(n, text, -1, -1, before, done);
            return;
        }
        if (noCursor) a = b = before.length();
        int s = Math.min(a, b), e = Math.max(a, b);
        String piece = Spacing.fit(before.substring(0, s), text, before.substring(e));
        String after = before.substring(0, s) + piece + before.substring(e);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, after);
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            paste(n, piece, s, e, before, done);
            return;
        }
        main.postDelayed(() -> {
            boolean alive = n.refresh();
            String now = alive ? current(n) : null;
            if (now != null && now.contains(piece.trim())) {
                int cursor = s + piece.length();
                Bundle sel = new Bundle();
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor);
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor);
                n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
                undoNode = n;
                undoBefore = before;
                undoAfter = now;
                undoCursor = e;
                done.accept(true);
            } else if (now != null && now.equals(before)) {
                paste(n, piece, s, e, before, done); // SET_TEXT was ignored
            } else {
                done.accept(true); // the field changed (reformatted / rebuilt); do not risk a second copy
            }
        }, SETTLE_MS);
    }

    private void paste(AccessibilityNodeInfo n, String piece, int s, int e, String before, Consumer<Boolean> done) {
        copy(piece);
        if (s >= 0) { // s < 0: leave the app's own cursor where it is
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s);
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, e);
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            done.accept(false);
            return;
        }
        main.postDelayed(() -> {
            boolean changed = !n.refresh() || n.getText() == null || !current(n).equals(before);
            done.accept(changed);
        }, SETTLE_MS);
    }

    private void undo() {
        main.removeCallbacks(undoExpired);
        AccessibilityNodeInfo n = undoNode;
        if (n != null && n.refresh() && current(n).equals(undoAfter)) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, undoBefore);
            n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            Bundle sel = new Bundle();
            int c = Math.min(undoCursor, undoBefore.length());
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, c);
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, c);
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
            toast("Anulat. Textul rămâne în TalkText → Acasă → Recente.");
        } else {
            toast("Textul s-a schimbat între timp; nu anulez.");
        }
        clearUndo();
        bubble.setMode(Bubble.Mode.IDLE);
        refresh();
    }

    private void clearUndo() {
        undoNode = null;
        undoBefore = undoAfter = null;
    }

    private void copy(String text) {
        ClipData clip = ClipData.newPlainText("TalkText", text);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
    }

    private void toast(String s) {
        main.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    // ---- drag, tap, long press ----

    private final class Touch implements android.view.View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean dragging, longDone;
        private long downAt;
        private final Runnable longRun = () -> { if (!dragging) { longDone = true; longPress(); } };

        @Override public boolean onTouch(android.view.View v, MotionEvent ev) {
            float slop = 10 * getResources().getDisplayMetrics().density;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX(); downY = ev.getRawY();
                    startX = params.x; startY = params.y;
                    dragging = false; longDone = false;
                    downAt = SystemClock.elapsedRealtime();
                    main.postDelayed(longRun, 650);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.hypot(ev.getRawX() - downX, ev.getRawY() - downY) > slop) {
                        dragging = true;
                        main.removeCallbacks(longRun);
                    }
                    if (dragging) {
                        params.x = startX + (int) (ev.getRawX() - downX);
                        params.y = startY + (int) (ev.getRawY() - downY);
                        if (shown) wm.updateViewLayout(bubble, params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longRun);
                    if (dragging) Prefs.get(InsertService.this).edit().putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply();
                    else if (!longDone && SystemClock.elapsedRealtime() - downAt < 650) tap();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longRun);
                    return true;
                default:
                    return false;
            }
        }
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (shown) {
            try { wm.removeView(bubble); } catch (RuntimeException ignored) { }
        }
        shown = false;
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
