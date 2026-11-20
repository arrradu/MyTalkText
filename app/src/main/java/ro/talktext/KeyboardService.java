package ro.talktext;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The TalkText keyboard. Types like Gboard; the microphone (top right) dictates through the same
 * OpenAI + Claude pipeline as the bubble, without the bubble; the translate button (top left) turns
 * what you type in one language into another, live, in the field.
 */
public class KeyboardService extends InputMethodService implements KeyboardView.Listener, Session.Listener {
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT, MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final long TRANSLATE_DELAY_MS = 450, UNDO_MS = 2000, DOUBLE_SPACE_MS = 350;

    private final Handler main = new Handler(Looper.getMainLooper());
    private KeyboardView keys;
    private LinearLayout toolbar, translateBar;
    private Bubble mic;              // the bubble, small, in the toolbar: same states and gestures
    private ImageView translateIcon;
    private TextView status, trSrc, trDst, trText, trStatus;

    // translation
    private boolean translating, commitWhenReady, exitWhenReady;
    private final StringBuilder buffer = new StringBuilder();
    private String src = "ro", dst = "en", translatedFrom = "", lead = null;
    private int trGen;
    private boolean layoutWasCyrillic;
    private final Runnable translateLater = this::translateNow;

    // dictation
    private Session session;
    private String field;
    // Compose apps and web pages give every field the same id; a counter of new inputs tells them apart.
    private int inputGeneration, sessionGeneration;
    private String lastInserted;
    private final Runnable undoGone = () -> {
        lastInserted = null;
        if (mic != null && mic.mode() == Bubble.Mode.UNDO) mic.setMode(Bubble.Mode.IDLE);
        setStatus("");
    };
    private final Runnable errorGone = () -> { if (mic != null && mic.mode() == Bubble.Mode.ERROR) mic.setMode(Bubble.Mode.IDLE); };

    private long lastSpaceAt;
    private boolean autoSpace; // the keyboard itself just added the space after a suggestion
    private int autoSpaceAt = -1; // ...and the cursor is still right after it

    // Numbered lists: "1. " at the start of a line gets one space in front; Enter leaves an empty line and continues with " 2. ";
    // Enter on an empty item ends the list. (Chat apps wrap long lines themselves, so a hanging indent is not possible.)
    static final String INDENT = " ";
    private static final java.util.regex.Pattern ITEM = java.util.regex.Pattern.compile("^" + INDENT + "(\\d{1,3})([.)]) (.*)$");
    private boolean numericField; // phone, amounts, dates: stay on the number page
    private boolean holdShift;    // capital armed after ". " from the number page; auto-caps must not undo it

    // A new screenshot shows as a small thumbnail in the toolbar; one tap puts it in the chat.
    private static final long SHOT_FRESH_S = 180;
    private LinearLayout shotChip;
    private ImageView shotThumb;
    private Uri shotUri;
    private String shotMime;
    private long shotId = -1;
    private boolean suggestionsShown;
    private android.database.ContentObserver shotObserver;
    private final Runnable shotExpired = () -> { shotUri = null; updateMiddle(); };

    // autocorrect and suggestions (word lists load in the background when the keyboard starts)
    private volatile Lexicon lexRo, lexEn, lexRu;
    private volatile boolean ruLoading;
    private final Corrector corrector = new Corrector();
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int suggestGen;
    private LinearLayout suggestRow;
    private String correctedFrom, correctedTo, correctedSep; // the last autocorrect, undone by Backspace
    // Every autocorrect in this field {what it became, what you typed}: put the cursor on such a word later
    // and your own spelling is offered in the toolbar, as on Gboard.
    private final java.util.ArrayList<String[]> corrections = new java.util.ArrayList<>();

    // Clipboard history (📋, next to the microphone) and text improvement (✨, next to translate).
    private ImageView polishIcon, clipIcon;
    private FrameLayout panel; // covers the keys for the clipboard list or the improved text
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private boolean polishing;
    // The text you just copied, offered in the toolbar for 3 minutes: one tap pastes it.
    private static final long CLIP_FRESH_MS = 180_000;
    private TextView clipChip;
    private String clipRecent;
    private final Runnable clipExpired = () -> { clipRecent = null; updateMiddle(); refreshSuggestions(); };

    @Override public void onCreate() {
        super.onCreate();
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipListener = () -> {
            ClipData clip = cm.getPrimaryClip();
            rememberClip(clip);
            main.removeCallbacks(clipExpired);
            clipRecent = null; // whatever was offered is no longer what is on the clipboard
            offerClip(clip, true);
            updateMiddle();
        };
        cm.addPrimaryClipChangedListener(clipListener); // Android lets only the active keyboard hear this
        worker.execute(() -> {
            lexRo = Words.ro(this);
            lexEn = Words.en(this);
            refreshPersonal();
        }); // Russian only when the Russian layout is first used (saves memory)
    }

    // ---- views ----

    @Override public View onCreateInputView() {
        Ui.init(this);
        keys = new KeyboardView(this, this);
        int bg = keys.background(), ink = keys.ink(), muted = keys.muted();

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(bg);
        root.setClipChildren(false); // key previews of the top row draw over the toolbar
        inputRoot = root;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(v, insets);
            return insets;
        });

        // Toolbar: translate on the left, the microphone on the right (where Gboard has it), a status line between.
        toolbar = Ui.row(this);
        toolbar.setPadding(dp(8), dp(4), dp(6), dp(2));
        translateIcon = icon(R.drawable.ic_translate, ink, v -> toggleTranslate());
        toolbar.addView(translateIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        polishIcon = icon(R.drawable.ic_polish, ink, v -> improveText());
        toolbar.addView(polishIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        status = Ui.text(this, "", 13, muted, false, 500);
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // Middle of the toolbar: three suggestions while you type a word, otherwise the status line.
        FrameLayout middle = new FrameLayout(this);
        middle.addView(status, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
        suggestRow = Ui.row(this);
        for (int i = 0; i < 3; i++) {
            TextView t = Ui.text(this, "", 16, ink, false, 500);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(dp(4), dp(10), dp(4), dp(10));
            t.setBackground(Ui.pressable(Ui.round(0x00000000, dp(14), 0, 0)));
            t.setOnClickListener(v -> pickSuggestion(((TextView) v).getText().toString()));
            suggestRow.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1));
        }
        suggestRow.setVisibility(View.GONE);
        middle.addView(suggestRow, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
        shotChip = Ui.row(this);
        shotChip.setPadding(dp(6), dp(5), dp(14), dp(5));
        shotChip.setBackground(Ui.pressable(Ui.round(keys.specialColor(), dp(100), 0, 0)));
        shotThumb = new ImageView(this);
        shotThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shotThumb.setClipToOutline(true);
        shotThumb.setBackground(Ui.round(0xFF000000, dp(8), 0, 0));
        shotChip.addView(shotThumb, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView shotLabel = Ui.text(this, "Inserează captura", 14, ink, false, 600);
        shotLabel.setPadding(dp(10), 0, 0, 0);
        shotChip.addView(shotLabel);
        shotChip.setOnClickListener(v -> insertShot());
        shotChip.setOnLongClickListener(v -> { dismissShot(); return true; });
        shotChip.setVisibility(View.GONE);
        middle.addView(shotChip, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        clipChip = Ui.text(this, "", 14, ink, false, 500);
        clipChip.setSingleLine(true);
        clipChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        clipChip.setMaxWidth(dp(210));
        clipChip.setPadding(dp(12), dp(8), dp(14), dp(8));
        clipChip.setBackground(Ui.pressable(Ui.round(keys.specialColor(), dp(100), 0, 0)));
        clipChip.setCompoundDrawablePadding(dp(6));
        android.graphics.drawable.Drawable clipGlyph = getDrawable(R.drawable.ic_clipboard).mutate();
        clipGlyph.setTint(muted);
        clipGlyph.setBounds(0, 0, dp(16), dp(16));
        clipChip.setCompoundDrawables(clipGlyph, null, null, null);
        clipChip.setOnClickListener(v -> pasteRecentClip());
        clipChip.setOnLongClickListener(v -> { forgetRecentClip(); return true; });
        clipChip.setVisibility(View.GONE);
        middle.addView(clipChip, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        toolbar.addView(middle, new LinearLayout.LayoutParams(0, WRAP, 1));
        mic = new Bubble(this, 50, true);
        mic.setIdleColors(keys.specialColor(), keys.ink()); // round, key-coloured, like Gboard's microphone
        mic.setOnClickListener(v -> micTap());
        mic.setOnLongClickListener(v -> micLongPress());
        clipIcon = icon(R.drawable.ic_clipboard, ink, v -> { if (panel.getVisibility() == View.VISIBLE) closePanel(); else showClipboard(); });
        toolbar.addView(clipIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        toolbar.addView(mic);
        root.addView(toolbar, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Translate bar: languages, the text you type, the state of the translation.
        translateBar = Ui.column(this);
        translateBar.setPadding(dp(10), dp(6), dp(10), dp(4));
        LinearLayout langs = Ui.row(this);
        trSrc = chip(v -> { src = next(src, dst); languagesChanged(); });
        langs.addView(trSrc);
        TextView swap = Ui.text(this, "⇄", 20, ink, false, 500);
        swap.setPadding(dp(14), dp(2), dp(14), dp(2));
        swap.setOnClickListener(v -> { String t = src; src = dst; dst = t; languagesChanged(); });
        langs.addView(swap);
        trDst = chip(v -> { dst = next(dst, src); languagesChanged(); });
        langs.addView(trDst);
        langs.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView close = Ui.text(this, "✕", 18, muted, false, 500);
        close.setPadding(dp(12), dp(4), dp(8), dp(4));
        close.setOnClickListener(v -> closeTranslate());
        langs.addView(close);
        translateBar.addView(langs);
        trText = Ui.text(this, "", 16, ink, false, 400);
        trText.setPadding(dp(12), dp(10), dp(12), dp(10));
        trText.setBackground(Ui.round(keys.specialColor(), dp(12), 0, 0));
        trText.setMaxLines(3);
        translateBar.addView(trText, Ui.lp(MATCH, WRAP, 0, dp(6), 0, 0));
        trStatus = Ui.text(this, "", 12, muted, false, 500);
        trStatus.setPadding(dp(4), dp(3), 0, 0);
        translateBar.addView(trStatus);
        translateBar.setVisibility(View.GONE);
        root.addView(translateBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Keys. No separate dictation panel: the microphone button itself shows listening and working, like the bubble.
        FrameLayout body = new FrameLayout(this);
        body.setClipChildren(false);
        body.addView(keys, new FrameLayout.LayoutParams(MATCH, WRAP));
        panel = new FrameLayout(this);
        panel.setBackgroundColor(bg);
        panel.setVisibility(View.GONE);
        body.addView(panel, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));
        restoreState();
        return root;
    }

    /** Rotation or dark mode rebuilds the views; put back what was going on (dictation, translation). */
    private void restoreState() {
        Session.Phase phase = session == null ? null : session.phase();
        if (phase == Session.Phase.RECORDING) {
            mic.setMode(Bubble.Mode.RECORDING);
            setStatus("Ascult… atinge 🎤 când termini");
        } else if (phase == Session.Phase.PROCESSING) {
            mic.setMode(Bubble.Mode.PROCESSING);
            setStatus("Scriu textul…");
        }
        if (translating) {
            keys.setCyrillic("ru".equals(src));
            translateBar.setVisibility(View.VISIBLE);
            markTranslate(true);
            renderTranslate();
            updateSpaceLabel();
        }
    }

    private void setStatus(String s) {
        if (status == null) return;
        status.setText(s);
        if (!s.isEmpty()) showSuggestions(null, null); // dictation messages take the place of suggestions
        else updateMiddle();
    }

    private void markTranslate(boolean on) {
        translateIcon.setBackground(Ui.pressable(Ui.round(on ? Ui.LAVENDER : 0x00000000, dp(22), 0, 0)));
        translateIcon.setImageTintList(ColorStateList.valueOf(on ? Ui.INK : keys.ink()));
    }

    private LinearLayout inputRoot;

    /**
     * Room for the system bar under the keyboard (the ⌄ and 🌐 buttons), the side navigation bar in landscape
     * and camera cutouts, so no key ends up under them.
     */
    private static void applyInsets(View v, WindowInsets insets) {
        android.graphics.Insets nav = insets.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
        android.graphics.Insets tap = insets.getInsets(WindowInsets.Type.tappableElement());
        int bottom = Math.max(nav.bottom, tap.bottom);
        if (v.getPaddingBottom() != bottom || v.getPaddingLeft() != nav.left || v.getPaddingRight() != nav.right)
            v.setPadding(nav.left, 0, nav.right, bottom);
    }

    /** Android does not always send the insets again when the keyboard reopens: ask for them each time. */
    private void refreshInsets() {
        if (inputRoot == null) return;
        inputRoot.post(() -> {
            WindowInsets now = inputRoot.getRootWindowInsets();
            if (now != null) applyInsets(inputRoot, now);
            inputRoot.requestApplyInsets();
        });
    }

    private ImageView icon(int res, int tint, View.OnClickListener click) {
        ImageView i = new ImageView(this);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(tint));
        i.setPadding(dp(10), dp(8), dp(10), dp(8));
        i.setBackground(Ui.pressable(Ui.round(0x00000000, dp(20), 0, 0)));
        i.setOnClickListener(click);
        return i;
    }

    private TextView chip(View.OnClickListener click) {
        TextView t = Ui.text(this, "", 14, Ui.INK, false, 600);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        t.setBackground(Ui.pressable(Ui.round(Ui.LAVENDER, dp(100), 0, 0)));
        t.setOnClickListener(click);
        return t;
    }

    private int dp(float v) { return Ui.dp(this, v); }

    // ---- input lifecycle ----

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (keys == null) return;
        refreshInsets();
        if (translating) exitTranslate(); // new field, or the app restarted the input: never carry a translation over
        closePanel();
        SharedPreferences p = Prefs.get(this);
        keys.vibrate = p.getBoolean("kb_vibrate", true);
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        boolean numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME;
        numericField = numeric;
        holdShift = false;
        keys.setPage(numeric ? KeyLayout.PAGE_SYMBOLS : KeyLayout.PAGE_LETTERS);
        keys.setEnterLabel(enterLabel(info));
        updateSpaceLabel();
        updateShift();
        corrector.setUserWords(Prefs.lines(Prefs.dictionary(this)), Prefs.lines(p.getString("kb_learned", "")));
        refreshPersonalLater();
        correctedFrom = null;
        offerClip(((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip(), false);
        refreshSuggestions();
        watchScreenshots();
        checkScreenshot();
    }

    @Override public void onFinishInputView(boolean finishing) {
        super.onFinishInputView(finishing);
        PersonalStore.saveNow();
        if (translating) exitTranslate();
        if (session != null && session.phase() == Session.Phase.RECORDING) stopDictation(); // keyboard closed: keep what was said
    }

    @Override public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd, int candStart, int candEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, candStart, candEnd);
        if (keys == null) return; // selection updates arrive even before the keyboard was ever shown
        if (!translating) {
            updateShift();
            refreshSuggestions();
            return;
        }
        // The editor dropped our composing translation (tap elsewhere, web fields): start a new sentence
        // instead of composing a second copy next to the first.
        boolean composing = lead != null && !translatedFrom.trim().isEmpty();
        if (composing && (candStart < 0 || newEnd < candStart || newStart > candEnd)) {
            main.removeCallbacks(translateLater);
            trGen++;
            commitWhenReady = false;
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
            buffer.setLength(0);
            translatedFrom = "";
            lead = null;
            renderTranslate();
            trStatus.setText("");
        }
    }

    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        if (!restarting) {
            inputGeneration++;
            corrections.clear();
        }
    }

    @Override public void onCurrentInputMethodSubtypeChanged(android.view.inputmethod.InputMethodSubtype subtype) {
        super.onCurrentInputMethodSubtypeChanged(subtype);
        if (keys == null || translating) return;
        keys.setCyrillic(subtype != null && subtype.getLanguageTag().startsWith("ru"));
        updateSpaceLabel();
    }

    private static boolean password(EditorInfo info) {
        if (info == null) return false;
        int cls = info.inputType & InputType.TYPE_MASK_CLASS, variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT)
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        return cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private static String enterLabel(EditorInfo info) {
        // Even a multi-line box (Google's search bar) shows its action, as on Gboard.
        if (!enterActs(info)) return "↵";
        switch (info.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_SEND: return "➤";
            case EditorInfo.IME_ACTION_SEARCH: return "⌕";
            case EditorInfo.IME_ACTION_GO: case EditorInfo.IME_ACTION_NEXT: return "→";
            case EditorInfo.IME_ACTION_PREVIOUS: return "←";
            case EditorInfo.IME_ACTION_DONE: return "✓";
            default: return "↵";
        }
    }

    private void updateShift() {
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (keys == null || ic == null || info == null || keys.page() != KeyLayout.PAGE_LETTERS || holdShift) return;
        boolean caps = ic.getCursorCapsMode(info.inputType) != 0;
        if (caps) keys.setShift(KeyboardView.SHIFT_ONCE);
        else if (keys.shift() == KeyboardView.SHIFT_ONCE) keys.setShift(KeyboardView.SHIFT_OFF);
    }

    private void updateSpaceLabel() {
        String a = src.toUpperCase(java.util.Locale.ROOT), b = dst.toUpperCase(java.util.Locale.ROOT);
        keys.setSpaceLabel(translating ? a + " → " + b : keys.cyrillic() ? "RU • RO" : "RO • RU");
    }

    // ---- keys ----

    @Override public void onText(String s) {
        if (translating) {
            buffer.append(s);
            translateChanged();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        holdShift = false;
        CharSequence prevChar = ic.getTextBeforeCursor(1, 0);
        boolean afterDigit = prevChar != null && prevChar.length() == 1 && Character.isDigit(prevChar.charAt(0));
        boolean punctuation = s.length() == 1 && ".,!?;:)".contains(s);
        if (punctuation && autoSpace && cursorAt(ic) == autoSpaceAt && prevChar != null && prevChar.length() == 1 && prevChar.charAt(0) == ' ') {
            // The space the keyboard added after a suggestion goes after the punctuation: "cuvânt, " (and "!!!", "?!").
            ic.beginBatchEdit();
            ic.deleteSurroundingText(1, 0);
            ic.commitText(s + " ", 1);
            ic.endBatchEdit();
            correctedFrom = null;
            autoSpace = true;
            autoSpaceAt = cursorAt(ic);
            return;
        }
        autoSpace = false;
        if (punctuation && !s.equals(")")) {
            autocorrect(s);
            learnFinishedWord();
        }
        else correctedFrom = null;
        ic.commitText(s, 1);
        // "… 25." on the number page: back to letters with a capital ready, like the end of a sentence.
        if (!numericField && keys.page() != KeyLayout.PAGE_LETTERS && s.equals(".") && afterDigit) {
            keys.setPage(KeyLayout.PAGE_LETTERS);
            keys.setShift(KeyboardView.SHIFT_ONCE);
            holdShift = true;
        }
    }

    @Override public void onSpace() {
        if (translating) {
            buffer.append(' ');
            translateChanged();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (autoSpace) { // space right after a suggestion: it is already there, do not double it
            autoSpace = false;
            CharSequence b = ic.getTextBeforeCursor(1, 0);
            if (b != null && b.length() == 1 && b.charAt(0) == ' ' && cursorAt(ic) == autoSpaceAt) return;
        }
        if (startList(ic)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastSpaceAt >= DOUBLE_SPACE_MS) {
            autocorrect(" ");
            learnFinishedWord();
        }
        boolean wasHolding = holdShift;
        holdShift = false;
        if (!numericField && keys.page() != KeyLayout.PAGE_LETTERS) keys.setPage(KeyLayout.PAGE_LETTERS); // space ends the number, as on Gboard
        CharSequence before = ic.getTextBeforeCursor(2, 0);
        if (now - lastSpaceAt < DOUBLE_SPACE_MS && before != null && before.length() == 2
                && before.charAt(1) == ' ' && Character.isLetterOrDigit(before.charAt(0))) {
            ic.deleteSurroundingText(1, 0); // double space = ". " like Gboard
            ic.commitText(". ", 1);
            lastSpaceAt = 0;
        } else {
            ic.commitText(" ", 1);
            lastSpaceAt = now;
        }
        if (wasHolding) keys.setShift(KeyboardView.SHIFT_ONCE);
        updateShift();
    }

    @Override public void onDelete() {
        autoSpace = false;
        if (translating) {
            if (buffer.length() > 0) {
                int cut = buffer.offsetByCodePoints(buffer.length(), -1);
                buffer.setLength(cut);
                translateChanged();
            }
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        holdShift = false;
        if (correctedFrom != null) { // Backspace right after an autocorrect gives your word back, and remembers it
            String done = correctedTo + correctedSep;
            CharSequence b = ic.getTextBeforeCursor(done.length(), 0);
            String original = correctedFrom;
            correctedFrom = null;
            if (b != null && b.toString().equals(done)) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(done.length(), 0);
                ic.commitText(original, 1);
                ic.endBatchEdit();
                learn(original);
                return;
            }
        }
        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) ic.commitText("", 1);
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); // the app deletes whole emoji and surrogate pairs itself
    }

    @Override public void onEnter() {
        if (translating && buffer.length() > 0) {
            commitTranslation();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null || info == null) return;
        if (info.inputType == InputType.TYPE_NULL) { // terminals and similar expect a real Enter key
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
            return;
        }
        autocorrect("");
        learnFinishedWord();
        correctedFrom = null;
        autoSpace = false;
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean multiLine = (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        if (enterActs(info)) ic.performEditorAction(action);
        else if (multiLine && continueList(ic)) return;
        else if (multiLine) ic.commitText("\n", 1);
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER); // one-line box without a declared action (many web search fields)
    }

    private boolean listsOn() {
        EditorInfo info = getCurrentInputEditorInfo();
        return info != null && (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 && !enterActs(info)
                && Prefs.get(this).getBoolean("kb_lists", true);
    }

    /** The text of the current line, up to the cursor. */
    private static String lineBefore(InputConnection ic) {
        CharSequence b = ic.getTextBeforeCursor(300, 0);
        if (b == null) return "";
        String s = b.toString();
        return s.substring(s.lastIndexOf('\n') + 1);
    }

    /** "1." or "1)" typed at the start of a line, then space: indent it and make it a numbered item. */
    private boolean startList(InputConnection ic) {
        if (!listsOn()) return false;
        String line = lineBefore(ic);
        if (!line.equals("1.") && !line.equals("1)")) return false;
        ic.beginBatchEdit();
        ic.deleteSurroundingText(line.length(), 0);
        ic.commitText(INDENT + line + " ", 1);
        ic.endBatchEdit();
        return true;
    }

    /** Enter inside a numbered item: next number on a new line; Enter on an empty item ends the list. */
    private boolean continueList(InputConnection ic) {
        if (!listsOn()) return false;
        String line = lineBefore(ic);
        java.util.regex.Matcher m = ITEM.matcher(line);
        if (!m.matches()) return false;
        if (m.group(3).trim().isEmpty()) { // empty item: remove it, the list is over
            ic.deleteSurroundingText(line.length(), 0);
            return true;
        }
        int n = Integer.parseInt(m.group(1)) + 1;
        ic.commitText("\n\n" + INDENT + n + m.group(2) + " ", 1); // an empty line between items, like between paragraphs
        return true;
    }

    /** Gboard's rule: the app's action (search, send, go, next, done) wins unless the app asks for a plain Enter. */
    private static boolean enterActs(EditorInfo info) {
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        return (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
                && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED;
    }

    @Override public void onCursor(int step) {
        if (!translating) sendDownUpKeyEvents(step > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
    }

    @Override public void onGlobe(boolean longPress) {
        if (longPress) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker(); // e.g. back to Gboard
            return;
        }
        keys.setCyrillic(!keys.cyrillic());
        updateSpaceLabel();
        if (!translating) updateShift();
    }

    @Override public void onShiftChanged() { }

    // ---- swipe left from ⌫: select whole words, delete them on release ----

    private int swipeCursor = -1;
    private String swipeBefore = "";

    @Override public void onSwipeDelete(int words) {
        if (translating) {
            int cut = Spacing.wordsBack(buffer, words);
            trStatus.setText(words == 0 ? "" : "Șterg: " + buffer.substring(buffer.length() - cut).trim());
            trStatus.setTextColor(keys.muted());
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (swipeCursor < 0) { // first step: remember where the cursor is and the text before it
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(new android.view.inputmethod.ExtractedTextRequest(), 0);
            CharSequence before = ic.getTextBeforeCursor(2000, 0);
            if (et == null || before == null || et.selectionStart < 0) return;
            swipeCursor = et.startOffset + Math.max(et.selectionStart, et.selectionEnd);
            swipeBefore = before.toString();
            correctedFrom = null;
            holdShift = false;
        }
        int len = Spacing.wordsBack(swipeBefore, words);
        ic.setSelection(swipeCursor - len, swipeCursor); // the app highlights what will go
    }

    @Override public void onSwipeDeleteEnd(int words) {
        if (translating) {
            trStatus.setText("");
            if (words > 0) {
                buffer.setLength(buffer.length() - Spacing.wordsBack(buffer, words));
                translateChanged();
            }
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        int cursor = swipeCursor;
        swipeCursor = -1;
        if (ic == null || cursor < 0) return;
        if (words > 0) ic.commitText("", 1); // removes the highlighted words
        else ic.setSelection(cursor, cursor);
    }

    // ---- autocorrect and suggestions ----

    /** Fields where changing words would be wrong: passwords, e-mail, links, numbers, fields that ask for no suggestions. */
    private static boolean wordsAllowed(EditorInfo info) {
        if (info == null || password(info)) return false;
        int cls = info.inputType & InputType.TYPE_MASK_CLASS, v = info.inputType & InputType.TYPE_MASK_VARIATION;
        if (cls != InputType.TYPE_CLASS_TEXT) return false;
        if (v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || v == InputType.TYPE_TEXT_VARIATION_URI) return false;
        return (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0;
    }

    /** Learning too: never in incognito tabs or fields that ask the keyboard not to learn. */
    private static boolean learningAllowed(EditorInfo info) {
        return wordsAllowed(info) && (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
    }

    /** The cursor's position in the whole text, or -1 if the app does not say. */
    private static int cursorAt(InputConnection ic) {
        android.view.inputmethod.SurroundingText st = ic.getSurroundingText(0, 0, 0);
        return st == null || st.getSelectionStart() < 0 ? -1 : st.getOffset() + st.getSelectionStart();
    }

    /** The letters right before the cursor (the word being typed). */
    private static String wordBefore(CharSequence before) {
        if (before == null) return "";
        int i = before.length();
        while (i > 0) {
            char ch = before.charAt(i - 1);
            boolean apostrophe = (ch == '\'' || ch == '’') && i > 1 && Character.isLetter(before.charAt(i - 2))
                    && i < before.length() && Character.isLetter(before.charAt(i)); // only between two letters
            if (!Character.isLetter(ch) && !apostrophe) break;
            i--;
        }
        return before.subSequence(i, before.length()).toString();
    }

    /** The letters right after the cursor (the rest of the word the cursor is in). */
    private static String wordAfter(CharSequence after) {
        if (after == null) return "";
        int i = 0;
        while (i < after.length()) {
            char ch = after.charAt(i);
            boolean apostrophe = (ch == '\'' || ch == '’') && i + 1 < after.length() && Character.isLetter(after.charAt(i + 1)) && i > 0;
            if (!Character.isLetter(ch) && !apostrophe) break;
            i++;
        }
        return after.subSequence(0, i).toString();
    }

    private static java.util.List<String> recentWords(CharSequence before, String current) {
        String text = before == null ? "" : before.subSequence(0, before.length() - current.length()).toString();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String w : text.split("[^\\p{L}']+")) if (!w.isEmpty()) out.add(w);
        return out.size() > 6 ? out.subList(out.size() - 6, out.size()) : out;
    }

    /** Russian layout: Russian. Latin layout: Romanian or English, from the words already in the sentence. */
    private Lexicon[] lexicons(boolean cyrillic, java.util.List<String> recent) {
        if (cyrillic) {
            if (lexRu == null && !ruLoading) {
                ruLoading = true;
                worker.execute(() -> lexRu = Words.ru(this));
            }
            return new Lexicon[]{lexRu, null};
        }
        Lexicon primary = Corrector.pick(recent, lexRo, lexEn);
        return new Lexicon[]{primary, primary == lexRo ? lexEn : lexRo};
    }

    private void autocorrect(String separator) {
        correctedFrom = null;
        if (!Prefs.get(this).getBoolean("kb_autocorrect", true) || !wordsAllowed(getCurrentInputEditorInfo())) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        String word = wordBefore(before);
        if (word.length() < 2) return;
        Lexicon[] l = lexicons(keys.cyrillic(), recentWords(before, word));
        if (l[0] == null) return; // word lists still loading
        String fix = corrector.correct(word, l[0], l[1]);
        if (fix == null || fix.equals(word)) return;
        int cursor = cursorAt(ic);
        ic.beginBatchEdit();
        ic.deleteSurroundingText(word.length(), 0);
        ic.commitText(fix, 1);
        ic.endBatchEdit();
        correctedFrom = word;
        correctedTo = fix;
        correctedSep = separator;
        if (cursor >= 0) corrections.add(new String[]{fix, word, String.valueOf(cursor - word.length())}); // {became, typed, where}
        if (corrections.size() > 40) corrections.remove(0);
    }

    // ---- learning how you write ----

    private int learnedSinceRefresh;

    /** The word before `current` in the same sentence (commas allowed), or null at a sentence start. */
    private static String previousWord(CharSequence before, String current) {
        if (before == null) return null;
        String pre = before.subSequence(0, before.length() - current.length()).toString();
        int i = pre.length();
        while (i > 0 && (Character.isWhitespace(pre.charAt(i - 1)) || ",;:".indexOf(pre.charAt(i - 1)) >= 0)) {
            if (pre.charAt(i - 1) == '\n') return null; // a new line starts a new sentence (letters, lists)
            i--;
        }
        if (i == 0 || !Character.isLetter(pre.charAt(i - 1))) return null; // ". ", "! ", digits, start of the text
        String w = wordBefore(pre.substring(0, i));
        return w.isEmpty() ? null : w;
    }

    /** After a word is finished (space, punctuation, Enter): remember it and the word before it. */
    private void learnFinishedWord() {
        if (!PersonalStore.enabled(this) || !learningAllowed(getCurrentInputEditorInfo())) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        String word = wordBefore(before);
        if (word.length() < 2) return;
        PersonalStore.get(this).learn(previousWord(before, word), word);
        PersonalStore.saveSoon();
        if (++learnedSinceRefresh >= 20) refreshPersonalLater();
    }

    private void refreshPersonalLater() {
        learnedSinceRefresh = 0;
        worker.execute(this::refreshPersonal);
    }

    /** Hand what was learned to the corrector (words used twice, names written with a capital). Worker thread. */
    private void refreshPersonal() {
        Personal me = PersonalStore.get(this);
        corrector.setPersonal(PersonalStore.enabled(this) ? me.lexicon(2) : null, PersonalStore.enabled(this) ? me.capitalized(3) : null);
    }

    private void learn(String word) {
        corrector.learn(word);
        SharedPreferences p = Prefs.get(this);
        if (PersonalStore.enabled(this) && learningAllowed(getCurrentInputEditorInfo())) PersonalStore.get(this).learn(null, word);
        // One line per take-back: the corrector counts them (once = diacritics still added, twice = never changed).
        java.util.List<String> learned = new java.util.ArrayList<>(Prefs.lines(p.getString("kb_learned", "")));
        if (java.util.Collections.frequency(learned, word) >= 2) return;
        learned.add(0, word);
        if (learned.size() > 1000) learned = learned.subList(0, 1000);
        p.edit().putString("kb_learned", String.join("\n", learned)).apply();
    }

    private void refreshSuggestions() {
        if (suggestRow == null || keys == null) return;
        InputConnection ic = getCurrentInputConnection();
        boolean on = !translating && mic.mode() == Bubble.Mode.IDLE && Prefs.get(this).getBoolean("kb_suggest", true)
                && wordsAllowed(getCurrentInputEditorInfo()) && ic != null;
        CharSequence before = on ? ic.getTextBeforeCursor(64, 0) : null;
        CharSequence selected = on ? ic.getSelectedText(0) : null;
        String head = wordBefore(before);
        String word = on ? head + wordAfter(ic.getTextAfterCursor(32, 0)) : ""; // the whole word, also with the cursor inside it
        if (on && word.isEmpty() && (selected == null || selected.length() == 0)
                && PersonalStore.enabled(this) && clipRecent == null && shotUri == null) {
            // Nothing typed yet: the words you usually write next (learned from you).
            suggestGen++;
            String prev = previousWord(before, "");
            java.util.List<String> next = prev == null ? java.util.Collections.emptyList() : PersonalStore.get(this).predict(prev, 3);
            showSuggestions(next.isEmpty() ? null : next, null);
            return;
        }
        if (!on || word.isEmpty() || (selected != null && selected.length() > 0)) {
            suggestGen++;
            showSuggestions(null, null);
            return;
        }
        int wordStart = cursorAt(ic) - head.length();
        for (int i = corrections.size() - 1; i >= 0; i--) {
            String[] c = corrections.get(i);
            if (c[0].equals(word) && String.valueOf(wordStart).equals(c[2])) { // the word autocorrect changed, at its place: offer what you typed
                suggestGen++;
                showSuggestions(java.util.Collections.singletonList(c[1]), null);
                return;
            }
        }
        int gen = ++suggestGen;
        boolean cyrillic = keys.cyrillic();
        java.util.List<String> recent = recentWords(before, head);
        worker.execute(() -> {
            Lexicon[] l = lexicons(cyrillic, recent);
            if (l[0] == null) return;
            java.util.List<String> list = corrector.suggest(word, l[0], l[1]);
            String fix = corrector.correct(word, l[0], l[1]);
            main.post(() -> { if (gen == suggestGen) showSuggestions(list, fix); });
        });
    }

    /** Three slots like Gboard: [your word] [best / autocorrect, bold] [another]. */
    private void showSuggestions(java.util.List<String> list, String fix) {
        if (suggestRow == null) return;
        if (list == null || list.isEmpty()) {
            suggestionsShown = false;
            updateMiddle();
            return;
        }
        String[] slots = new String[3];
        if (list.size() == 1) slots[1] = list.get(0);
        else for (int i = 0; i < Math.min(3, list.size()); i++) slots[i] = list.get(i);
        for (int i = 0; i < 3; i++) {
            TextView t = (TextView) suggestRow.getChildAt(i);
            t.setText(slots[i] == null ? "" : slots[i]);
            t.setVisibility(slots[i] == null ? View.INVISIBLE : View.VISIBLE);
            t.setFontVariationSettings("'wght' " + (i == 1 && fix != null ? 700 : 500));
        }
        suggestionsShown = true;
        updateMiddle();
    }

    /** One thing at a time in the middle of the toolbar: suggestions while typing a word, else a new screenshot, else the status. */
    private void updateMiddle() {
        if (suggestRow == null) return;
        boolean busy = mic != null && mic.mode() != Bubble.Mode.IDLE;
        boolean clip = clipRecent != null && !suggestionsShown && !busy && !translating;
        boolean shot = !clip && shotUri != null && !suggestionsShown && !busy && !translating;
        clipChip.setVisibility(clip ? View.VISIBLE : View.GONE);
        suggestRow.setVisibility(suggestionsShown && !busy ? View.VISIBLE : View.GONE);
        int tools = suggestionsShown && !busy ? View.GONE : View.VISIBLE; // room for three suggestions, as on Gboard
        polishIcon.setVisibility(translating ? View.GONE : tools);
        clipIcon.setVisibility(tools);
        shotChip.setVisibility(shot ? View.VISIBLE : View.GONE);
        status.setVisibility(!shot && !clip && !(suggestionsShown && !busy) ? View.VISIBLE : View.GONE);
    }

    // ---- clipboard history ----

    private java.util.List<String> clips() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(Prefs.get(this).getString("kb_clips", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (org.json.JSONException ignored) { }
        return out;
    }

    private void saveClips(java.util.List<String> list) {
        Prefs.get(this).edit().putString("kb_clips", new org.json.JSONArray(list).toString()).apply();
    }

    /** Text copies only; never a clip the copying app marked sensitive (passwords, codes). */
    private void rememberClip(ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) return;
        android.os.PersistableBundle extras = clip.getDescription().getExtras();
        if (extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE", false)) return;
        CharSequence text = clip.getItemAt(0).getText();
        if (text == null) return;
        saveClips(ClipHistory.add(clips(), text.toString()));
    }

    /** Offer a fresh text copy in the toolbar (not the one already pasted or hidden, never a sensitive one). */
    private void offerClip(ClipData clip, boolean justCopied) {
        if (clipChip == null || clip == null || clip.getItemCount() == 0) return;
        android.os.PersistableBundle extras = clip.getDescription().getExtras();
        if (extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE", false)) return;
        CharSequence cs = clip.getItemAt(0).getText();
        if (cs == null || cs.toString().trim().isEmpty()) return;
        String text = cs.toString();
        if (text.equals(Prefs.get(this).getString("kb_clip_done", null))) return;
        long age = 0;
        if (!justCopied) { // copied while the keyboard was closed: trust the clip's own time
            long ts = clip.getDescription().getTimestamp();
            age = Math.min(Math.abs(System.currentTimeMillis() - ts), Math.abs(SystemClock.elapsedRealtime() - ts));
            if (ts <= 0 || age > CLIP_FRESH_MS) return;
        }
        clipRecent = text;
        String preview = text.trim();
        clipChip.setText((preview.length() > 100 ? preview.substring(0, 100) : preview).replaceAll("\\s+", " "));
        main.removeCallbacks(clipExpired);
        main.postDelayed(clipExpired, CLIP_FRESH_MS - age);
        updateMiddle();
    }

    private void pasteRecentClip() {
        String text = clipRecent;
        InputConnection ic = getCurrentInputConnection();
        if (text == null || ic == null) return;
        ic.commitText(text, 1);
        forgetRecentClip();
    }

    private void forgetRecentClip() {
        if (clipRecent != null) Prefs.get(this).edit().putString("kb_clip_done", clipRecent).apply();
        clipRecent = null;
        updateMiddle();
        refreshSuggestions();
    }

    private void showClipboard() {
        rememberClip(((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip()); // in case it was copied while the keyboard slept
        panel.removeAllViews();
        LinearLayout column = Ui.column(this);
        column.setPadding(dp(10), dp(4), dp(10), 0);
        LinearLayout header = Ui.row(this);
        header.addView(Ui.text(this, "Clipboard", 16, keys.ink(), false, 600), new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView clear = Ui.text(this, "Șterge tot", 14, keys.muted(), false, 600);
        clear.setPadding(dp(12), dp(8), dp(12), dp(8));
        clear.setOnClickListener(v -> { saveClips(new java.util.ArrayList<>()); showClipboard(); });
        header.addView(clear);
        header.addView(icon(R.drawable.ic_keyboard, keys.ink(), v -> closePanel()), new LinearLayout.LayoutParams(dp(44), dp(40)));
        column.addView(header);
        java.util.List<String> list = clips();
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout items = Ui.column(this);
        if (list.isEmpty()) {
            TextView empty = Ui.text(this, "Ce copiezi apare aici: ultimele 20 de texte.\nAtinge un text ca să-l lipești, ține apăsat ca să-l ștergi.", 14, keys.muted(), false, 500);
            empty.setPadding(dp(6), dp(16), dp(6), dp(16));
            items.addView(empty);
        }
        for (String c : list) {
            TextView t = Ui.ellipsized(Ui.text(this, c, 15, keys.ink(), false, 400), 3);
            t.setPadding(dp(14), dp(11), dp(14), dp(11));
            t.setBackground(Ui.pressable(Ui.round(keys.specialColor(), dp(14), 0, 0)));
            t.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(c, 1);
                closePanel();
            });
            t.setOnLongClickListener(v -> {
                java.util.List<String> left = clips();
                left.remove(c);
                saveClips(left);
                showClipboard();
                return true;
            });
            items.addView(t, Ui.lp(MATCH, WRAP, 0, 0, 0, dp(8)));
        }
        scroll.addView(items);
        column.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        panel.addView(column, new FrameLayout.LayoutParams(MATCH, MATCH));
        keys.setVisibility(View.INVISIBLE);
        panel.setVisibility(View.VISIBLE);
    }

    private void closePanel() {
        if (panel == null) return;
        panel.setVisibility(View.GONE);
        panel.removeAllViews();
        keys.setVisibility(View.VISIBLE);
    }

    // ---- ✨ improve the text already in the field ----

    private void improveText() {
        if (polishing) return;
        if (translating) exitTranslate();
        EditorInfo info = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || password(info)) { toast("Aici nu pot îmbunătăți textul."); return; }
        if (!Vault.has(this, Vault.ANTHROPIC)) { toast("Introdu cheia Claude în TalkText → Setări."); return; }
        android.view.inputmethod.ExtractedTextRequest req = new android.view.inputmethod.ExtractedTextRequest();
        req.hintMaxChars = 20000;
        android.view.inputmethod.ExtractedText et = ic.getExtractedText(req, 0);
        if (et == null || et.text == null || et.text.toString().trim().isEmpty()) { toast("Scrie întâi un text."); return; }
        String all = et.text.toString();
        int a = Math.min(et.selectionStart, et.selectionEnd), b = Math.max(et.selectionStart, et.selectionEnd);
        boolean selection = a >= 0 && b <= all.length() && b > a;
        int start = selection ? a : 0, end = selection ? b : all.length(); // only the selection, if you selected something
        String original = all.substring(start, end);
        int offset = et.startOffset;
        polishing = true;
        setStatus(selection ? "Îmbunătățesc selecția…" : "Îmbunătățesc textul…");
        new Thread(() -> {
            try {
                String better = ClaudeClient.improve(getApplicationContext(), original);
                main.post(() -> {
                    polishing = false;
                    setStatus("");
                    if (better.equals(original)) toast("Textul e deja bun.");
                    else showImproved(original, better, offset + start, offset + end);
                });
            } catch (Http.Failure | RuntimeException e) {
                main.post(() -> {
                    polishing = false;
                    setStatus("");
                    toast(String.valueOf(e.getMessage()));
                });
            }
        }, "improve").start();
    }

    private void showImproved(String original, String better, int start, int end) {
        panel.removeAllViews();
        LinearLayout column = Ui.column(this);
        column.setPadding(dp(12), dp(6), dp(12), dp(10));
        column.addView(Ui.text(this, "✨ Varianta îmbunătățită", 15, keys.ink(), false, 600));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        TextView text = Ui.text(this, better, 16, keys.ink(), false, 400);
        text.setPadding(dp(14), dp(12), dp(14), dp(12));
        text.setBackground(Ui.round(keys.specialColor(), dp(14), 0, 0));
        text.setTextIsSelectable(false);
        scroll.addView(text);
        column.addView(scroll, Ui.lp(MATCH, 0, 0, dp(8), 0, dp(10)));
        ((LinearLayout.LayoutParams) scroll.getLayoutParams()).weight = 1;
        LinearLayout buttons = Ui.row(this);
        buttons.setGravity(Gravity.END);
        buttons.addView(Ui.pill(this, "Renunță", false, v -> closePanel()), Ui.lp(WRAP, WRAP, 0, 0, dp(10), 0));
        buttons.addView(Ui.pill(this, "Înlocuiește", true, v -> {
            replaceText(original, better, start, end);
            closePanel();
        }));
        column.addView(buttons);
        panel.addView(column, new FrameLayout.LayoutParams(MATCH, MATCH));
        keys.setVisibility(View.INVISIBLE);
        panel.setVisibility(View.VISIBLE);
    }

    /** Replace only if the text there is still exactly what was sent, so nothing typed meanwhile is lost. */
    private void replaceText(String original, String better, int start, int end) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        android.view.inputmethod.ExtractedTextRequest req = new android.view.inputmethod.ExtractedTextRequest();
        req.hintMaxChars = 20000;
        android.view.inputmethod.ExtractedText et = ic.getExtractedText(req, 0);
        String now = et == null || et.text == null ? null : et.text.toString();
        int s0 = start - (et == null ? 0 : et.startOffset), e0 = end - (et == null ? 0 : et.startOffset);
        if (now == null || s0 < 0 || e0 > now.length() || !now.substring(s0, e0).equals(original)) {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("TalkText", better));
            toast("Textul s-a schimbat între timp. Varianta îmbunătățită e copiată.");
            return;
        }
        ic.beginBatchEdit();
        ic.setSelection(start, end);
        ic.commitText(better, 1);
        ic.endBatchEdit();
    }

    // ---- screenshots ----

    private boolean canSeeImages() {
        return checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
    }

    private void watchScreenshots() {
        if (shotObserver != null || !canSeeImages()) return;
        shotObserver = new android.database.ContentObserver(main) {
            @Override public void onChange(boolean self) { checkScreenshot(); }
        };
        try {
            getContentResolver().registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, shotObserver);
        } catch (RuntimeException e) {
            shotObserver = null;
        }
    }

    /** The newest image in a Screenshots folder, if it is from the last 3 minutes and not used or dismissed yet. */
    private void checkScreenshot() {
        if (!canSeeImages()) return;
        long dismissed = Prefs.get(this).getLong("kb_shot_done", -1);
        worker.execute(() -> {
            Uri collection = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL);
            android.os.Bundle q = new android.os.Bundle();
            q.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, android.provider.MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?");
            q.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{"%Screenshots%"});
            q.putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[]{android.provider.MediaStore.Images.Media.DATE_ADDED});
            q.putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
            q.putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1);
            String[] cols = {android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DATE_ADDED, android.provider.MediaStore.Images.Media.MIME_TYPE};
            try (android.database.Cursor c = getContentResolver().query(collection, cols, q, null)) {
                if (c == null || !c.moveToFirst()) return;
                long id = c.getLong(0), added = c.getLong(1);
                String mime = c.getString(2);
                long age = System.currentTimeMillis() / 1000 - added;
                if (id == dismissed || age > SHOT_FRESH_S || age < -60) return;
                Uri uri = android.content.ContentUris.withAppendedId(collection, id);
                android.graphics.Bitmap thumb = getContentResolver().loadThumbnail(uri, new android.util.Size(96, 96), null);
                main.post(() -> {
                    shotId = id;
                    shotUri = uri;
                    shotMime = mime == null ? "image/png" : mime;
                    shotThumb.setImageBitmap(thumb);
                    main.removeCallbacks(shotExpired);
                    main.postDelayed(shotExpired, Math.max(5, SHOT_FRESH_S - age) * 1000);
                    updateMiddle();
                });
            } catch (Exception ignored) {
                // no permission any more, or the image is still being written: the next change will try again
            }
        });
    }

    private void dismissShot() {
        Prefs.get(this).edit().putLong("kb_shot_done", shotId).apply();
        shotUri = null;
        updateMiddle();
    }

    /** Copy the screenshot into the app's cache and give it to the chat; if the app does not take images from keyboards, copy it. */
    private void insertShot() {
        Uri source = shotUri;
        String mime = shotMime;
        long id = shotId;
        EditorInfo info = getCurrentInputEditorInfo();
        if (source == null) return;
        dismissShot();
        worker.execute(() -> {
            String ext = mime.endsWith("jpeg") || mime.endsWith("jpg") ? ".jpg" : mime.endsWith("webp") ? ".webp" : ".png";
            java.io.File dir = ShotProvider.dir(this);
            java.io.File[] old = dir.listFiles();
            if (old != null) for (java.io.File f : old) if (System.currentTimeMillis() - f.lastModified() > 3600_000) f.delete();
            java.io.File copy = new java.io.File(dir, "captura_" + id + ext);
            try (java.io.InputStream in = getContentResolver().openInputStream(source);
                 java.io.OutputStream out = new java.io.FileOutputStream(copy)) {
                if (in == null) throw new java.io.IOException();
                in.transferTo(out);
            } catch (Exception e) {
                main.post(() -> toast("Nu pot citi captura."));
                return;
            }
            Uri mine = ShotProvider.uri(copy.getName());
            main.post(() -> {
                boolean accepted = false;
                InputConnection ic = getCurrentInputConnection();
                if (ic != null && takesImages(info, mime)) {
                    android.view.inputmethod.InputContentInfo content = new android.view.inputmethod.InputContentInfo(
                            mine, new android.content.ClipDescription("Captură", new String[]{mime}), null);
                    accepted = ic.commitContent(content, android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null);
                }
                if (!accepted) {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newUri(getContentResolver(), "Captură", mine));
                    toast("Aplicația nu primește imagini de la tastatură. Captura e copiată: ține apăsat în câmp → Lipește.");
                }
            });
        });
    }

    private static boolean takesImages(EditorInfo info, String mime) {
        if (info == null || info.contentMimeTypes == null) return false;
        for (String t : info.contentMimeTypes)
            if (android.content.ClipDescription.compareMimeTypes(mime, t) || android.content.ClipDescription.compareMimeTypes(t, mime)) return true;
        return false;
    }

    private void pickSuggestion(String chosen) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || chosen.isEmpty()) return;
        CharSequence beforePick = ic.getTextBeforeCursor(64, 0);
        String head = wordBefore(beforePick);
        String pickStart = String.valueOf(cursorAt(ic) - head.length());
        String prevWord = previousWord(beforePick, head);
        CharSequence after = ic.getTextAfterCursor(33, 0);
        String tail = wordAfter(after);
        String word = head + tail;
        // A space only where the word ends the text; inside a sentence the space (or the punctuation) is already there.
        boolean spaceFollows = after != null && after.length() > tail.length();
        ic.beginBatchEdit();
        if (!word.isEmpty()) ic.deleteSurroundingText(head.length(), tail.length());
        ic.commitText(spaceFollows ? chosen : chosen + " ", 1);
        autoSpace = !spaceFollows;
        autoSpaceAt = autoSpace ? cursorAt(ic) : -1;
        ic.endBatchEdit();
        for (int i = corrections.size() - 1; i >= 0; i--) {
            String[] c = corrections.get(i);
            if (c[0].equals(word) && c[1].equals(chosen) && c[2].equals(pickStart)) { // took back an autocorrect: remember your spelling
                corrections.remove(i);
                learn(chosen);
                break;
            }
        }
        if (chosen.equals(word)) learn(word); // you kept your own spelling: never correct it again
        if (PersonalStore.enabled(this) && learningAllowed(getCurrentInputEditorInfo()) && !chosen.contains(" ")) {
            PersonalStore.get(this).learn(prevWord, chosen); // a picked suggestion is a word you wrote
            PersonalStore.saveSoon();
        }
        correctedFrom = null;
        lastSpaceAt = 0;
    }

    // ---- translation ----

    /** ✕ or the translate button: first wait for the last words to be translated, then close. */
    private void closeTranslate() {
        if (buffer.length() > 0 && !translatedFrom.equals(buffer.toString())) {
            exitWhenReady = true;
            commitTranslation();
        } else exitTranslate();
    }

    private void toggleTranslate() {
        if (translating) {
            closeTranslate();
            return;
        }
        if (session != null && session.phase() == Session.Phase.RECORDING) return;
        if (password(getCurrentInputEditorInfo())) { toast("Nu traduc în câmpuri de parolă."); return; }
        SharedPreferences p = Prefs.get(this);
        src = p.getString("tr_src", "ro");
        dst = p.getString("tr_dst", "en");
        translating = true;
        buffer.setLength(0);
        translatedFrom = "";
        lead = null;
        layoutWasCyrillic = keys.cyrillic();
        keys.setPage(KeyLayout.PAGE_LETTERS);
        keys.setCyrillic("ru".equals(src));
        translateBar.setVisibility(View.VISIBLE);
        markTranslate(true);
        renderTranslate();
        updateSpaceLabel();
    }

    private void exitTranslate() {
        main.removeCallbacks(translateLater);
        trGen++;
        boolean stale = buffer.length() > 0 && !translatedFrom.equals(buffer.toString());
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.finishComposingText(); // what is already translated stays in the field
        if (stale) toast("Ultimele cuvinte n-au apucat să fie traduse. Verifică textul.");
        translating = false;
        commitWhenReady = false;
        exitWhenReady = false;
        buffer.setLength(0);
        if (keys != null) {
            keys.setCyrillic(layoutWasCyrillic);
            translateBar.setVisibility(View.GONE);
            markTranslate(false);
            updateSpaceLabel();
        }
    }

    private void languagesChanged() {
        Prefs.get(this).edit().putString("tr_src", src).putString("tr_dst", dst).apply();
        keys.setCyrillic("ru".equals(src));
        renderTranslate();
        updateSpaceLabel();
        if (buffer.length() > 0) translateChanged();
    }

    private static String next(String current, String other) {
        String[] order = {"ro", "en", "ru"};
        int i = java.util.Arrays.asList(order).indexOf(current);
        for (int k = 1; k <= 3; k++) {
            String n = order[(i + k) % 3];
            if (!n.equals(other)) return n;
        }
        return current;
    }

    private static String label(String l) {
        switch (l) {
            case "en": return "English";
            case "ru": return "Русский";
            default: return "Română";
        }
    }

    private void renderTranslate() {
        trSrc.setText(label(src));
        trDst.setText(label(dst));
        if (buffer.length() == 0) {
            trText.setText("ru".equals(src) ? "Пишите по-русски…" : "en".equals(src) ? "Type in English…" : "Scrie în română…");
            trText.setTextColor(keys.muted());
        } else {
            trText.setText(buffer + "▏");
            trText.setTextColor(keys.ink());
        }
    }

    private void translateChanged() {
        commitWhenReady = false; // typing again after Enter: the new words belong to the same sentence
        exitWhenReady = false;
        renderTranslate();
        main.removeCallbacks(translateLater);
        if (buffer.toString().trim().isEmpty()) {
            translateNow();
            return;
        }
        trStatus.setText("Traduc…");
        trStatus.setTextColor(keys.muted());
        main.postDelayed(translateLater, TRANSLATE_DELAY_MS);
    }

    private void translateNow() {
        String text = buffer.toString();
        int gen = ++trGen;
        if (text.trim().isEmpty()) {
            show("", text);
            trStatus.setText("");
            if (commitWhenReady) finishTranslation();
            return;
        }
        String from = src, to = dst;
        new Thread(() -> {
            try {
                String out = ClaudeClient.translate(getApplicationContext(), text, from, to);
                main.post(() -> {
                    if (gen != trGen || !translating) return;
                    show(out, text);
                    trStatus.setText("");
                    if (commitWhenReady) finishTranslation();
                });
            } catch (Http.Failure | RuntimeException e) {
                main.post(() -> {
                    if (gen != trGen || !translating) return;
                    commitWhenReady = false;
                    exitWhenReady = false; // keep the bar open so the error and the text are visible
                    trStatus.setText(e.getMessage());
                    trStatus.setTextColor(Ui.RED);
                });
            }
        }, "translate").start();
    }

    /** The translation lives in the field as composing text, replaced as you type, until Enter or ✕. */
    private void show(String translation, String from) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (lead == null) {
            CharSequence before = ic.getTextBeforeCursor(1, 0);
            lead = before != null && before.length() > 0 && !Character.isWhitespace(before.charAt(0)) ? " " : "";
        }
        ic.setComposingText(translation.isEmpty() ? "" : lead + translation, 1);
        translatedFrom = from;
    }

    private void commitTranslation() {
        if (translatedFrom.equals(buffer.toString())) {
            finishTranslation();
            return;
        }
        commitWhenReady = true; // Enter pressed before the last words were translated: wait for them
        main.removeCallbacks(translateLater);
        translateNow();
    }

    private void finishTranslation() {
        commitWhenReady = false;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.finishComposingText();
        buffer.setLength(0);
        translatedFrom = "";
        lead = null;
        renderTranslate();
        if (exitWhenReady) exitTranslate();
    }

    // ---- dictation (same pipeline as the bubble) ----

    private static String fieldKey(EditorInfo info) {
        return info == null ? "" : info.packageName + "/" + info.fieldId + "/" + info.fieldName;
    }

    /** Same gestures as the bubble: tap = start / stop / undo, long press while listening = cancel. */
    private void micTap() {
        switch (mic.mode()) {
            case STARTING: case RECORDING: stopDictation(); return;
            case PROCESSING: setStatus("Scriu textul… ține apăsat ca să anulezi"); return;
            case UNDO: undoDictation(); return;
            default: startDictation();
        }
    }

    private boolean micLongPress() {
        Bubble.Mode m = mic.mode();
        if (m == Bubble.Mode.STARTING || m == Bubble.Mode.RECORDING || m == Bubble.Mode.PROCESSING) {
            cancelDictation();
            return true;
        }
        return false;
    }

    private void startDictation() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Permite microfonul în TalkText.");
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (!Vault.has(this, Vault.OPENAI)) {
            toast("Introdu cheia OpenAI în TalkText → Setări.");
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (password(getCurrentInputEditorInfo())) { toast("Nu dictez în câmpuri de parolă."); return; }
        Session s = Session.start(this, this);
        if (s == null) { toast("O dictare este deja în curs."); return; }
        session = s;
        s.learn = learningAllowed(getCurrentInputEditorInfo());
        field = fieldKey(getCurrentInputEditorInfo());
        sessionGeneration = inputGeneration;
        main.removeCallbacks(undoGone);
        main.removeCallbacks(errorGone);
        lastInserted = null;
        mic.setMode(Bubble.Mode.STARTING);
        setStatus(translating ? "Vorbește în " + label(src).toLowerCase() + " · traduc în " + label(dst) : "Pornesc microfonul…");
        if (!RecorderService.send(this, RecorderService.START)) s.micFailed("Microfonul nu a pornit. Încearcă din nou.");
    }

    private void stopDictation() {
        if (session == null) return;
        if (!RecorderService.send(this, RecorderService.STOP)) session.cancel();
    }

    private void cancelDictation() {
        if (session == null) return;
        if (session.phase() == Session.Phase.PROCESSING) session.cancel();
        else if (!RecorderService.send(this, RecorderService.CANCEL)) session.cancel();
    }

    @Override public void onLevel(float level) {
        if (mic == null || session == null) return;
        if (mic.mode() == Bubble.Mode.STARTING && !translating) setStatus("Ascult… atinge 🎤 când termini");
        if (mic.mode() == Bubble.Mode.STARTING || mic.mode() == Bubble.Mode.RECORDING) mic.setLevel(level);
    }

    @Override public void onPhase(Session s, Session.Phase phase, String message) {
        if (s != session || mic == null) return;
        switch (phase) {
            case PROCESSING:
                mic.setMode(Bubble.Mode.PROCESSING);
                setStatus(translating ? "Scriu și traduc…" : "Scriu textul…");
                break;
            case FAILED:
                mic.setMode(Bubble.Mode.ERROR);
                setStatus("");
                if (message != null) toast(message);
                main.postDelayed(errorGone, 2500);
                break;
            case CANCELLED:
                mic.setMode(Bubble.Mode.IDLE);
                setStatus("Anulat");
                main.postDelayed(undoGone, 1500);
                break;
            default:
        }
    }

    @Override public void onResult(Session s, String text, String warning) {
        if (s != session) return;
        if (mic != null) mic.setMode(Bubble.Mode.IDLE);
        setStatus("");
        if (warning != null) toast(warning); // only a failed "Engleză, …" translation reaches here
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || !isInputViewShown() || !field.equals(fieldKey(getCurrentInputEditorInfo())) || sessionGeneration != inputGeneration) {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("TalkText", text));
            toast("Câmpul s-a schimbat. Textul e copiat și apare în TalkText → Recente.");
            return;
        }
        if (translating) { // dictate in your language, get it translated
            if (buffer.length() > 0 && !Character.isWhitespace(buffer.charAt(buffer.length() - 1))) buffer.append(' ');
            buffer.append(text);
            translateChanged();
            return;
        }
        CharSequence before = ic.getTextBeforeCursor(1, 0), after = ic.getTextAfterCursor(1, 0);
        String piece = Spacing.fit(before == null ? "" : before.toString(), text, after == null ? "" : after.toString());
        ic.commitText(piece, 1);
        lastInserted = piece;
        mic.setMode(Bubble.Mode.UNDO);
        setStatus("Gata · atinge ↶ ca să anulezi");
        main.removeCallbacks(undoGone);
        main.postDelayed(undoGone, UNDO_MS);
    }

    private void undoDictation() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && lastInserted != null) {
            CharSequence before = ic.getTextBeforeCursor(lastInserted.length(), 0);
            if (before != null && before.toString().equals(lastInserted)) ic.deleteSurroundingText(lastInserted.length(), 0);
        }
        main.removeCallbacks(undoGone);
        undoGone.run();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (shotObserver != null) getContentResolver().unregisterContentObserver(shotObserver);
        worker.shutdownNow();
        if (clipListener != null) ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).removePrimaryClipChangedListener(clipListener);
        if (session != null && session.phase() == Session.Phase.RECORDING) RecorderService.send(this, RecorderService.STOP);
        super.onDestroy();
    }
}
