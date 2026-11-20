package ro.talktext;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The TalkText look: warm cream paper, ink text, serif headlines, lavender accent. */
final class Ui {
    static final int BG = 0xFFFBF9F2, CARD = 0xFFFFFFFF, INK = 0xFF1C1B19, MUTED = 0xFF75706A, LINE = 0xFFEAE5D9,
            LAVENDER = 0xFFE9DDFF, LAVENDER_DARK = 0xFF6B4FBB, CREAM = 0xFFFFFDF2, GREEN = 0xFF2F7A4D, RED = 0xFFB3261E,
            ERROR_BG = 0xFFF9DEDA;

    private static Typeface serif, sans;

    static void init(Context c) {
        if (serif != null) return;
        try {
            serif = c.getResources().getFont(R.font.garamond);
            sans = c.getResources().getFont(R.font.figtree);
        } catch (RuntimeException e) {
            serif = Typeface.SERIF;
            sans = Typeface.SANS_SERIF;
        }
    }

    static Typeface sans(Context c) { init(c); return sans; }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable round(int color, float radius, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, stroke);
        return d;
    }

    static Drawable pressable(Drawable base) {
        return new RippleDrawable(ColorStateList.valueOf(0x22000000), base, null);
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean serifFace, int weight) {
        init(c);
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(serifFace ? serif : sans);
        t.setFontVariationSettings("'wght' " + weight);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    static TextView h1(Context c, String s) { return text(c, s, 36, INK, true, 500); }
    static TextView h2(Context c, String s) { return text(c, s, 24, INK, true, 500); }
    static TextView body(Context c, CharSequence s) { return text(c, s, 15, INK, false, 400); }
    static TextView muted(Context c, CharSequence s) { return text(c, s, 14, MUTED, false, 400); }
    static TextView strong(Context c, CharSequence s) { return text(c, s, 16, INK, false, 600); }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        int p = dp(c, 18);
        l.setPadding(p, p, p, p);
        l.setBackground(round(CARD, dp(c, 22), LINE, dp(c, 1)));
        return l;
    }

    /** Dark pill = main action, lavender pill = secondary. */
    static TextView pill(Context c, String label, boolean primary, View.OnClickListener click) {
        TextView t = text(c, label, 15, primary ? CREAM : INK, false, 600);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 20), dp(c, 11), dp(c, 20), dp(c, 11));
        t.setBackground(pressable(round(primary ? INK : LAVENDER, dp(c, 100), 0, 0)));
        t.setOnClickListener(click);
        return t;
    }

    static TextView link(Context c, String label, View.OnClickListener click) {
        TextView t = text(c, label, 14, LAVENDER_DARK, false, 600);
        t.setPadding(0, dp(c, 8), 0, dp(c, 8));
        t.setOnClickListener(click);
        return t;
    }

    static EditText input(Context c, String hint, String value, boolean secret, boolean multiline) {
        init(c);
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(15);
        e.setTextColor(INK);
        e.setHintTextColor(0xFFA39E95);
        e.setTypeface(sans);
        int p = dp(c, 14);
        e.setPadding(p, dp(c, 12), p, dp(c, 12));
        e.setBackground(round(BG, dp(c, 14), LINE, dp(c, 1)));
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(3);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    /** Small round step marker: ✓ when done, the step number when not. */
    static TextView step(Context c, int number, boolean done) {
        TextView t = text(c, done ? "✓" : String.valueOf(number), 14, done ? CREAM : MUTED, false, 700);
        t.setGravity(Gravity.CENTER);
        int s = dp(c, 28);
        t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        t.setBackground(done ? round(GREEN, s, 0, 0) : round(CARD, s, LINE, dp(c, 2)));
        return t;
    }

    static TextView ellipsized(TextView t, int lines) {
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static LinearLayout.LayoutParams lp(int w, int h, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(left, top, right, bottom);
        return p;
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    private Ui() {}
}
