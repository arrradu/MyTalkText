package ro.talktext;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Calendar;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity implements Session.Listener {
    private static final String[] TABS = {"Acasă", "Dicționar", "Expresii", "Setări"};
    private static final int[] ICONS = {R.drawable.ic_home, R.drawable.ic_book, R.drawable.ic_bolt, R.drawable.ic_tune};
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT, MATCH = LinearLayout.LayoutParams.MATCH_PARENT;

    private FrameLayout content;
    private LinearLayout nav;
    private int tab;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        try {
            Rules.parse("English, test", "ro"); // fails here, visibly, if this Android rejects the language pattern
        } catch (RuntimeException e) {
            toast("Eroare internă (comanda de limbă): " + e.getMessage());
        }
        tab = b == null ? 0 : b.getInt("tab", 0);

        getWindow().getDecorView().setBackgroundColor(Ui.BG);
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) bars.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1));
        nav = Ui.row(this);
        nav.setBackground(Ui.round(Ui.CARD, 0, Ui.LINE, dp(1)));
        root.addView(nav, new LinearLayout.LayoutParams(MATCH, WRAP));
        for (int i = 0; i < TABS.length; i++) nav.addView(navItem(i), Ui.weight(1));
        setContentView(root);

        // Android 15 draws apps edge to edge: keep text clear of the status bar, nav bar and keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            boolean typing = ime.bottom > sys.bottom;
            content.setPadding(0, sys.top, 0, 0);
            nav.setVisibility(typing ? View.GONE : View.VISIBLE);
            nav.setPadding(0, dp(8), 0, sys.bottom + dp(8));
            v.setPadding(0, 0, 0, typing ? ime.bottom : 0);
            return WindowInsets.CONSUMED;
        });
    }

    @Override protected void onSaveInstanceState(Bundle b) {
        b.putInt("tab", tab);
        super.onSaveInstanceState(b);
    }

    @Override protected void onResume() {
        super.onResume();
        show(tab);
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) { show(tab); }

    // ---- navigation ----

    private View navItem(int i) {
        LinearLayout item = Ui.column(this);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setTag(i);
        ImageView icon = new ImageView(this);
        icon.setImageResource(ICONS[i]);
        icon.setPadding(dp(18), dp(4), dp(18), dp(4));
        item.addView(icon, new LinearLayout.LayoutParams(WRAP, dp(32)));
        TextView label = Ui.text(this, TABS[i], 12, Ui.MUTED, false, 600);
        label.setPadding(0, dp(4), 0, 0);
        label.setGravity(Gravity.CENTER_HORIZONTAL); // the label spans the whole tab; centre its text under the icon
        item.addView(label, new LinearLayout.LayoutParams(MATCH, WRAP));
        item.setOnClickListener(v -> show(i));
        return item;
    }

    private void show(int i) {
        tab = i;
        for (int k = 0; k < nav.getChildCount(); k++) {
            LinearLayout item = (LinearLayout) nav.getChildAt(k);
            boolean on = k == i;
            ImageView icon = (ImageView) item.getChildAt(0);
            icon.setImageTintList(ColorStateList.valueOf(on ? Ui.INK : Ui.MUTED));
            icon.setBackground(on ? Ui.round(Ui.LAVENDER, dp(16), 0, 0) : null);
            ((TextView) item.getChildAt(1)).setTextColor(on ? Ui.INK : Ui.MUTED);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout page = Ui.column(this);
        page.setPadding(dp(20), dp(12), dp(20), dp(28));
        scroll.addView(page);
        switch (i) {
            case 1: dictionary(page); break;
            case 2: snippets(page); break;
            case 3: settings(page); break;
            default: home(page);
        }
        content.removeAllViews();
        content.addView(scroll);
    }

    // ---- Acasă ----

    private void home(LinearLayout page) {
        LinearLayout brand = Ui.row(this);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView name = Ui.text(this, "TalkText", 22, Ui.INK, true, 600);
        name.setPadding(dp(10), 0, 0, 0);
        brand.addView(name);
        page.addView(brand, Ui.lp(MATCH, WRAP, 0, dp(6), 0, dp(26)));

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        page.addView(Ui.h1(this, hour < 12 ? "Bună dimineața" : hour < 18 ? "Bună ziua" : "Bună seara"));
        page.addView(Ui.muted(this, "Atinge un câmp de text în orice aplicație, apoi bula lila. Vorbește liber, atinge din nou, iar textul apare singur."),
                Ui.lp(MATCH, WRAP, 0, dp(6), 0, dp(22)));

        SharedPreferences p = Prefs.get(this);
        String pending = p.getString("pending_error", null);
        if (pending != null) {
            LinearLayout card = Ui.card(this);
            card.setBackground(Ui.round(Ui.LAVENDER, dp(22), 0, 0));
            card.addView(Ui.strong(this, "O dictare așteaptă transcrierea"));
            card.addView(Ui.muted(this, pending + "\nAudio e păstrat pe telefon."), Ui.lp(MATCH, WRAP, 0, dp(4), 0, dp(12)));
            card.addView(Ui.pill(this, "Reîncearcă", true, v -> {
                if (Session.retry(this, this) == null) toast("Nu am ce reîncerca acum.");
                else toast("Retrimit audio salvat…");
            }), new LinearLayout.LayoutParams(WRAP, WRAP));
            page.addView(card, Ui.lp(MATCH, WRAP, 0, 0, 0, dp(14)));
        }

        String polishWarning = p.getString("polish_warning", null);
        if (polishWarning != null) {
            LinearLayout card = Ui.card(this);
            card.setBackground(Ui.round(Ui.ERROR_BG, dp(22), 0, 0));
            card.addView(Ui.strong(this, "Corectarea Claude n-a mers la ultima dictare"));
            card.addView(Ui.muted(this, polishWarning), Ui.lp(MATCH, WRAP, 0, dp(4), 0, dp(10)));
            LinearLayout actions = Ui.row(this);
            actions.addView(Ui.pill(this, "Setări", true, v -> show(3)));
            TextView ok = Ui.link(this, "Am înțeles", v -> { p.edit().remove("polish_warning").apply(); show(0); });
            ok.setPadding(dp(18), dp(8), 0, dp(8));
            actions.addView(ok);
            card.addView(actions);
            page.addView(card, Ui.lp(MATCH, WRAP, 0, 0, 0, dp(14)));
        }

        boolean mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean bubble = accessibilityOn();
        boolean notes = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean openai = Vault.has(this, Vault.OPENAI), claude = Vault.has(this, Vault.ANTHROPIC);
        boolean kbEnabled = keyboardEnabled(), kbSelected = keyboardSelected();
        if (!(mic && (kbSelected || bubble) && notes && openai && claude)) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.h2(this, "Hai să pornim"));
            card.addView(Ui.muted(this, "Câțiva pași, o singură dată."), Ui.lp(MATCH, WRAP, 0, dp(2), 0, dp(6)));
            card.addView(step(1, mic, "Microfonul", "Ca să te pot auzi.",
                    Ui.pill(this, "Permite", true, v -> requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1)), null));
            card.addView(step(2, kbSelected, "Tastatura TalkText",
                    kbEnabled ? "Alege TalkText ca tastatură. Microfonul din dreapta sus dictează, butonul din stânga traduce."
                            : "Pornește TalkText în lista de tastaturi, apoi alege-l. Microfonul din dreapta sus dictează, butonul din stânga traduce.",
                    kbEnabled ? Ui.pill(this, "Alege", true, v -> pickKeyboard())
                            : Ui.pill(this, "Activează", true, v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))),
                    kbEnabled ? null : Ui.link(this, "Alege", v -> pickKeyboard())));
            card.addView(step(3, bubble || kbSelected, "Bula (opțional)",
                    "Doar dacă vrei să dictezi și cu altă tastatură, de exemplu Gboard. Settings → Accessibility → TalkText → On; "
                            + "dacă apare „Restricted setting”: Deblochează → ⋮ → Allow restricted settings.",
                    Ui.pill(this, "Deschide", true, v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))),
                    Ui.link(this, "Deblochează", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))));
            card.addView(step(4, notes, "Notificări", "Îți arată când ascult.",
                    Ui.pill(this, "Permite", true, v -> requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2)), null));
            card.addView(step(5, openai, "Cheia OpenAI", "Transcrie vocea. Obligatorie.", Ui.pill(this, "Adaugă", true, v -> show(3)), null));
            card.addView(step(6, claude, "Cheia Claude", "Corectează textul și traduce.", Ui.pill(this, "Adaugă", true, v -> show(3)), null));
            page.addView(card, Ui.lp(MATCH, WRAP, 0, 0, 0, dp(14)));
        }

        long words = p.getLong("total_words", 0), ms = p.getLong("total_ms", 0);
        long saved = Math.max(0, words * 60_000L / 40 - ms); // typing ~40 words/min vs your speaking time
        LinearLayout stats = Ui.card(this);
        LinearLayout numbers = Ui.row(this);
        numbers.addView(stat(String.valueOf(words), "cuvinte dictate"), Ui.weight(1));
        numbers.addView(stat(minutes(ms), "vorbite"), Ui.weight(1));
        numbers.addView(stat(minutes(saved), "câștigate"), Ui.weight(1));
        stats.addView(numbers);
        page.addView(stats, Ui.lp(MATCH, WRAP, 0, 0, 0, dp(14)));

        LinearLayout tryIt = Ui.card(this);
        tryIt.addView(Ui.strong(this, "Încearcă aici"));
        tryIt.addView(Ui.muted(this, "Atinge câmpul, apoi bula. Începe cu „Engleză, …” sau „Rusă, …” și vorbește în română: textul apare tradus și îmbunătățit."),
                Ui.lp(MATCH, WRAP, 0, dp(4), 0, dp(12)));
        tryIt.addView(Ui.input(this, "Textul dictat apare aici…", "", false, true));
        page.addView(tryIt, Ui.lp(MATCH, WRAP, 0, 0, 0, dp(26)));

        page.addView(Ui.h2(this, "Recente"), Ui.lp(MATCH, WRAP, 0, 0, 0, dp(10)));
        JSONArray history = Prefs.history(this);
        if (history.length() == 0) {
            page.addView(Ui.muted(this, "Dictările tale apar aici. Atinge una ca s-o copiezi."));
            return;
        }
        LinearLayout list = Ui.card(this);
        list.setPadding(0, dp(4), 0, dp(4));
        for (int i = 0; i < history.length(); i++) {
            JSONObject h = history.optJSONObject(i);
            if (h == null) continue;
            String text = h.optString("text");
            LinearLayout item = Ui.column(this);
            item.setPadding(dp(18), dp(12), dp(18), dp(12));
            item.setBackground(Ui.pressable(Ui.round(0x00000000, 0, 0, 0)));
            item.addView(Ui.text(this, DateFormat.format("dd MMM, HH:mm", h.optLong("t")), 12, Ui.MUTED, false, 500));
            item.addView(Ui.ellipsized(Ui.body(this, text), 3), Ui.lp(MATCH, WRAP, 0, dp(3), 0, 0));
            item.setOnClickListener(v -> copy(text));
            if (i > 0) list.addView(divider());
            list.addView(item);
        }
        page.addView(list);
        page.addView(Ui.link(this, "Șterge istoricul", v -> { Prefs.clearHistory(this); show(0); }), Ui.lp(WRAP, WRAP, dp(4), dp(6), 0, 0));
    }

    private View step(int n, boolean done, String title, String detail, View action, View extra) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(14), 0, 0);
        row.addView(Ui.step(this, n, done));
        LinearLayout text = Ui.column(this);
        text.setPadding(dp(14), dp(2), 0, 0);
        TextView t = Ui.strong(this, title);
        if (done) t.setTextColor(Ui.MUTED);
        text.addView(t);
        if (!done) {
            text.addView(Ui.muted(this, detail), Ui.lp(MATCH, WRAP, 0, dp(2), 0, dp(8)));
            LinearLayout actions = Ui.row(this);
            actions.addView(action);
            if (extra != null) {
                extra.setPadding(dp(16), dp(8), 0, dp(8));
                actions.addView(extra);
            }
            text.addView(actions);
        }
        row.addView(text, Ui.weight(1));
        return row;
    }

    private View stat(String number, String label) {
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, number, 30, Ui.INK, true, 500));
        col.addView(Ui.text(this, label, 13, Ui.MUTED, false, 500));
        return col;
    }

    private static String minutes(long ms) {
        long m = ms / 60_000;
        return m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + "m";
    }

    // ---- Dicționar ----

    private void dictionary(LinearLayout page) {
        page.addView(Ui.h1(this, "Dicționar"), Ui.lp(MATCH, WRAP, 0, dp(20), 0, 0));

        // "Despre mine": who you are, so Claude understands what you mean (company, car, people, projects).
        LinearLayout about = Ui.card(this);
        about.addView(Ui.strong(this, "Despre mine"));
        about.addView(Ui.muted(this, "Câteva rânduri despre tine, ca să înțeleagă la ce te referi când dictezi, traduci sau îmbunătățești. "
                + "De exemplu: „Lucrez la ARTINOX (MOLINART GRUP SRL), fabricăm rezervoare din inox. Mașina mea: Lynk & Co 900. Colegi: Eugen, Anatol, Ștefan.”"),
                Ui.lp(MATCH, WRAP, 0, dp(4), 0, dp(10)));
        EditText aboutText = Ui.input(this, "Compania, mașina, oamenii și proiectele tale…", Prefs.about(this), false, true);
        about.addView(aboutText);
        about.addView(Ui.pill(this, "Salvează", true, v -> {
            Prefs.get(this).edit().putString("about", aboutText.getText().toString().trim()).apply();
            toast("Salvat. Claude îl folosește de la următoarea dictare.");
        }), Ui.lp(WRAP, WRAP, 0, dp(10), 0, 0));
        page.addView(about, Ui.lp(MATCH, WRAP, 0, dp(16), 0, dp(20)));
        page.addView(Ui.muted(this, "Nume, firme și termeni pe care vrei să-i scriu corect: Artinox, Molinart, nume de clienți. Ajută și transcrierea, și corectarea."),
                Ui.lp(MATCH, WRAP, 0, dp(6), 0, dp(20)));
        List<String> words = Prefs.lines(Prefs.dictionary(this));
        LinearLayout add = Ui.row(this);
        EditText input = Ui.input(this, "Cuvânt nou", "", false, false);
        add.addView(input, Ui.weight(1));
        TextView button = Ui.pill(this, "Adaugă", true, v -> {
            String w = input.getText().toString().trim();
            if (w.isEmpty()) return;
            if (!words.contains(w)) words.add(0, w);
            Prefs.get(this).edit().putString("dictionary", String.join("\n", words)).apply();
            show(1);
        });
        add.addView(button, Ui.lp(WRAP, WRAP, dp(10), 0, 0, 0));
        page.addView(add, Ui.lp(MATCH, WRAP, 0, 0, 0, dp(16)));
        list(page, words, null, i -> {
            words.remove((int) i);
            Prefs.get(this).edit().putString("dictionary", String.join("\n", words)).apply();
            show(1);
        }, "Niciun cuvânt încă.");
    }

    // ---- Expresii ----

    private void snippets(LinearLayout page) {
        page.addView(Ui.h1(this, "Expresii"), Ui.lp(MATCH, WRAP, 0, dp(20), 0, 0));
        page.addView(Ui.muted(this, "Spui doar declanșatorul, ca dictare separată, și primești textul întreg. De exemplu „semnătura” → „Cu stimă, Adrian Radu, ARTINOX”."),
                Ui.lp(MATCH, WRAP, 0, dp(6), 0, dp(20)));
        List<String> lines = Prefs.lines(Prefs.snippets(this));
        LinearLayout card = Ui.card(this);
        EditText trigger = Ui.input(this, "Declanșator (ce spui)", "", false, false);
        EditText expansion = Ui.input(this, "Textul care apare", "", false, true);
        card.addView(trigger);
        card.addView(expansion, Ui.lp(MATCH, WRAP, 0, dp(10), 0, dp(12)));
        card.addView(Ui.pill(this, "Adaugă", true, v -> {
            String t = trigger.getText().toString().trim().replace("=>", "→");
            String x = expansion.getText().toString().trim().replaceAll("\\s*\\n\\s*", " ");
            if (t.isEmpty() || x.isEmpty()) return;
            lines.add(0, t + " => " + x);
            Prefs.get(this).edit().putString("snippets", String.join("\n", lines)).apply();
            show(2);
        }), new LinearLayout.LayoutParams(WRAP, WRAP));
        page.addView(card, Ui.lp(MATCH, WRAP, 0, 0, 0, dp(16)));
        list(page, lines, " => ", i -> {
            lines.remove((int) i);
            Prefs.get(this).edit().putString("snippets", String.join("\n", lines)).apply();
            show(2);
        }, "Nicio expresie încă.");
    }

    /** A card with one row per entry and a ✕ to delete it. With a separator, the part before it is bold. */
    private void list(LinearLayout page, List<String> items, String separator, java.util.function.IntConsumer remove, String empty) {
        if (items.isEmpty()) {
            page.addView(Ui.muted(this, empty));
            return;
        }
        LinearLayout card = Ui.card(this);
        card.setPadding(0, dp(4), 0, dp(4));
        for (int i = 0; i < items.size(); i++) {
            String s = items.get(i);
            LinearLayout row = Ui.row(this);
            row.setPadding(dp(18), dp(10), dp(8), dp(10));
            LinearLayout text = Ui.column(this);
            int cut = separator == null ? -1 : s.indexOf(separator);
            if (cut < 0) text.addView(Ui.body(this, s));
            else {
                text.addView(Ui.strong(this, s.substring(0, cut)));
                text.addView(Ui.ellipsized(Ui.muted(this, s.substring(cut + separator.length())), 2));
            }
            row.addView(text, Ui.weight(1));
            int index = i;
            TextView x = Ui.text(this, "✕", 16, Ui.MUTED, false, 500);
            x.setPadding(dp(12), dp(6), dp(12), dp(6));
            x.setOnClickListener(v -> remove.accept(index));
            row.addView(x);
            if (i > 0) card.addView(divider());
            card.addView(row);
        }
        page.addView(card);
    }

    // ---- Setări ----

    private void settings(LinearLayout page) {
        SharedPreferences prefs = Prefs.get(this);
        page.addView(Ui.h1(this, "Setări"), Ui.lp(MATCH, WRAP, 0, dp(20), 0, dp(20)));

        LinearLayout keys = section(page, "Chei API");
        keys.addView(Ui.muted(this, (Vault.has(this, Vault.OPENAI) ? "✓ OpenAI salvată" : "○ OpenAI lipsește (obligatorie)") + "\n"
                + (Vault.has(this, Vault.ANTHROPIC) ? "✓ Claude salvată" : "○ Claude lipsește (pentru corectare)")), Ui.lp(MATCH, WRAP, 0, dp(4), 0, dp(12)));
        EditText openai = Ui.input(this, "Cheie OpenAI (sk-…)", "", true, false);
        EditText anthropic = Ui.input(this, "Cheie Anthropic (sk-ant-…)", "", true, false);
        keys.addView(openai);
        keys.addView(anthropic, Ui.lp(MATCH, WRAP, 0, dp(10), 0, dp(12)));
        LinearLayout keyActions = Ui.row(this);
        keyActions.addView(Ui.pill(this, "Salvează cheile", true, v -> {
            try {
                if (!openai.getText().toString().trim().isEmpty()) Vault.save(this, Vault.OPENAI, openai.getText().toString().trim());
                if (!anthropic.getText().toString().trim().isEmpty()) Vault.save(this, Vault.ANTHROPIC, anthropic.getText().toString().trim());
                toast("Chei salvate criptat pe telefon");
            } catch (Exception e) {
                toast("Nu am putut salva cheia în siguranță.");
            }
            show(3);
        }));
        TextView clear = Ui.link(this, "Șterge cheile", v -> {
            Vault.clear(this, Vault.OPENAI);
            Vault.clear(this, Vault.ANTHROPIC);
            show(3);
        });
        clear.setPadding(dp(18), dp(8), 0, dp(8));
        keyActions.addView(clear);
        keys.addView(keyActions);
        keys.addView(Ui.muted(this, "Cheile rămân criptate pe telefon. Le iei de pe platform.openai.com și console.anthropic.com."), Ui.lp(MATCH, WRAP, 0, dp(10), 0, 0));

        LinearLayout writing = section(page, "Scriere");
        LinearLayout polishRow = Ui.row(this);
        LinearLayout polishText = Ui.column(this);
        polishText.addView(Ui.body(this, "Corectare cu Claude"));
        polishText.addView(Ui.muted(this, "Punctuație, diacritice, fără „ăăă”. Oprit: textul exact cum l-ai spus."));
        polishRow.addView(polishText, Ui.weight(1));
        Switch polish = toggle(Prefs.polish(this));
        polish.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("polish", on).apply());
        polishRow.addView(polish);
        writing.addView(polishRow, Ui.lp(MATCH, WRAP, 0, dp(6), 0, dp(16)));
        writing.addView(Ui.body(this, "Limba implicită"));
        LinearLayout langs = Ui.row(this);
        String[][] options = {{"ro", "Română"}, {"en", "English"}, {"ru", "Русский"}};
        for (String[] o : options) {
            boolean on = o[0].equals(Prefs.language(this));
            TextView chip = Ui.pill(this, o[1], on, v -> { prefs.edit().putString("lang", o[0]).apply(); show(3); });
            if (!on) chip.setBackground(Ui.pressable(Ui.round(Ui.BG, dp(100), Ui.LINE, dp(1))));
            langs.addView(chip, Ui.lp(WRAP, WRAP, 0, 0, dp(8), 0));
        }
        writing.addView(langs, Ui.lp(MATCH, WRAP, 0, dp(8), 0, dp(6)));
        writing.addView(Ui.muted(this, "Pentru o singură dictare: „Engleză, …” / „Rusă, …” = vorbești în română, primești textul tradus. „English, …” / „ruski, …” = chiar vorbești în engleză sau rusă."));

        LinearLayout keyboard = section(page, "Tastatura");
        LinearLayout vibrateRow = Ui.row(this);
        LinearLayout vibrateText = Ui.column(this);
        vibrateText.addView(Ui.body(this, "Vibrație la apăsare"));
        vibrateText.addView(Ui.muted(this, "O atingere scurtă la fiecare tastă, ca la Gboard."));
        vibrateRow.addView(vibrateText, Ui.weight(1));
        Switch vibrate = toggle(prefs.getBoolean("kb_vibrate", true));
        vibrate.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("kb_vibrate", on).apply());
        vibrateRow.addView(vibrate);
        keyboard.addView(vibrateRow, Ui.lp(MATCH, WRAP, 0, dp(6), 0, dp(12)));
        keyboard.addView(switchRow("Corectare automată", "La spațiu sau punctuație: greșeli de tastare și diacritice (si → și, sa → să). Backspace imediat după o corectare o anulează și învață cuvântul.", "kb_autocorrect"),
                Ui.lp(MATCH, WRAP, 0, 0, 0, dp(12)));
        // The red underlines in Telegram & co. come from the phone's spell checker: make it TalkText, Romanian first.
        boolean spell = spellCheckerIsOurs();
        keyboard.addView(Ui.body(this, "Verificator ortografic (liniuțele roșii)"));
        keyboard.addView(Ui.muted(this, spell
                ? "✓ TalkText verifică ortografia: română întâi, engleza acceptată, plus Dicționarul tău. Atinge un cuvânt subliniat pentru sugestii („sa” → „să”)."
                : "Liniuțele roșii vin de la verificatorul telefonului, acum pe engleză. Alege TalkText: Verificator ortografic → TalkText, limba Română."));
        if (!spell) keyboard.addView(Ui.pill(this, "Alege TalkText", true, v -> openSpellCheckerSettings()), Ui.lp(WRAP, WRAP, 0, dp(8), 0, 0));
        keyboard.addView(new View(this), new LinearLayout.LayoutParams(1, dp(12)));
        keyboard.addView(switchRow("Liste numerotate", "Începi un rând cu „1.” și spațiu: primește un spațiu în față, iar fiecare Enter lasă un rând liber "
                + "și pune numărul următor (2., 3., …). Enter pe un punct gol încheie lista.", "kb_lists"), Ui.lp(MATCH, WRAP, 0, 0, 0, dp(12)));
        keyboard.addView(switchRow("Sugestii", "Trei cuvinte în bara de sus cât scrii; atinge unul ca să-l folosești.", "kb_suggest"),
                Ui.lp(MATCH, WRAP, 0, 0, 0, dp(12)));
        boolean shots = checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        keyboard.addView(Ui.body(this, "Capturi de ecran"));
        keyboard.addView(Ui.muted(this, shots
                ? "✓ Când faci o captură, în bara tastaturii apare o miniatură: atinge-o ca s-o pui în chat (ține apăsat ca s-o ascunzi)."
                : "Ca să-ți arate miniatura unei capturi noi în bara tastaturii, TalkText trebuie să vadă imaginile. Alege „Permite tot” / „Allow all”."));
        if (!shots) keyboard.addView(Ui.pill(this, "Permite", true, v -> requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_IMAGES}, 3)),
                Ui.lp(WRAP, WRAP, 0, dp(8), 0, 0));
        keyboard.addView(new View(this), new LinearLayout.LayoutParams(1, dp(12)));
        keyboard.addView(switchRow("Învață cum scriu", "Ține minte cuvintele tale, cum le scrii (și cu majusculă: Artinox) și ce cuvânt urmează "
                + "de obicei după altul, ca să ți le propună primele. Învață și din dictări, dar niciodată din filele incognito. "
                + "Totul rămâne pe telefon; doar cele mai folosite nume cu majusculă (ex. Artinox) însoțesc vocea la transcriere, ca să fie scrise corect.", "kb_learn"),
                Ui.lp(MATCH, WRAP, 0, 0, 0, dp(6)));
        Personal me = PersonalStore.get(this);
        keyboard.addView(Ui.muted(this, "A învățat până acum " + me.size() + " cuvinte și " + me.pairs() + " perechi de cuvinte."),
                Ui.lp(MATCH, WRAP, 0, 0, 0, dp(4)));
        TextView forget = Ui.link(this, "Uită tot ce a învățat", v -> {
            prefs.edit().remove("kb_learned").apply();
            PersonalStore.clear(this);
            toast("Gata, am uitat.");
            show(3);
        });
        keyboard.addView(forget, Ui.lp(WRAP, WRAP, 0, 0, 0, dp(6)));
        keyboard.addView(Ui.muted(this, "Butonul lila din dreapta sus: dictare, fără bulă. Atinge-l ca să pornești și din nou ca să termini; ține apăsat ca să anulezi.\n"
                + "文A stânga sus: traducere. Scrii în română, în câmp apare engleza; Enter fixează fraza, ✕ închide. Limbile se schimbă din butoanele lila.\n"
                + "✨ lângă traducere: îmbunătățește textul deja scris (sau doar selecția), în limba lui; alegi Înlocuiește sau Renunță.\n"
                + "📋 lângă microfon: ultimele 20 de texte copiate; atinge ca să lipești, ține apăsat ca să ștergi.\n"
                + "🌐: română ↔ rusă. Ține apăsat pe 🌐 ca să alegi altă tastatură (de exemplu Gboard).\n"
                + "Apasă lung pe a, i, s, t pentru ă â î ș ț. Glisează pe spațiu ca să muți cursorul. Ține pe ⌫ și trage spre stânga ca să ștergi cuvinte întregi."));
        keyboard.addView(Ui.link(this, keyboardEnabled() ? "Alege tastatura" : "Activează tastatura", v -> pickKeyboard()), Ui.lp(WRAP, WRAP, 0, dp(6), 0, 0));

        LinearLayout bubble = section(page, "Bula");
        bubble.addView(Ui.muted(this, "Aplicații în care bula nu apare (de exemplu banca): numele pachetului, câte unul pe linie, ca md.maib.mobile."),
                Ui.lp(MATCH, WRAP, 0, dp(4), 0, dp(10)));
        EditText excluded = Ui.input(this, "md.maib.mobile", prefs.getString("excluded", ""), false, true);
        bubble.addView(excluded);
        LinearLayout bubbleActions = Ui.row(this);
        bubbleActions.addView(Ui.pill(this, "Salvează", true, v -> {
            prefs.edit().putString("excluded", excluded.getText().toString()).apply();
            toast("Salvat");
        }));
        TextView reset = Ui.link(this, "Bula înapoi la locul ei", v -> {
            prefs.edit().remove("bubble_x").remove("bubble_y").apply();
            toast("Se aplică după ce repornești bula din Accessibility.");
        });
        reset.setPadding(dp(18), dp(8), 0, dp(8));
        bubbleActions.addView(reset);
        bubble.addView(bubbleActions, Ui.lp(MATCH, WRAP, 0, dp(12), 0, 0));
        bubble.addView(Ui.muted(this, "Atinge = pornește / oprește. Ține apăsat în timp ce asculți = anulează. Trage-o oriunde."),
                Ui.lp(MATCH, WRAP, 0, dp(10), 0, 0));

        // Choices instead of a text field: a mistyped model name made every correction fail.
        LinearLayout models = section(page, "Model Claude");
        models.addView(Ui.muted(this, "Cine corectează textul. Haiku e de obicei suficient și cel mai rapid."), Ui.lp(MATCH, WRAP, 0, dp(4), 0, dp(10)));
        String current = Prefs.claudeModel(this);
        for (String[] m : Prefs.CLAUDE_MODELS) {
            boolean on = m[0].equals(current);
            LinearLayout option = Ui.row(this);
            option.setPadding(dp(14), dp(12), dp(14), dp(12));
            option.setBackground(Ui.pressable(on ? Ui.round(Ui.LAVENDER, dp(14), 0, 0) : Ui.round(Ui.BG, dp(14), Ui.LINE, dp(1))));
            LinearLayout label = Ui.column(this);
            label.addView(Ui.strong(this, m[1]));
            label.addView(Ui.muted(this, m[2]));
            option.addView(label, Ui.weight(1));
            if (on) option.addView(Ui.text(this, "✓", 18, Ui.INK, false, 700));
            option.setOnClickListener(v -> { prefs.edit().putString("model", m[0]).remove("polish_warning").apply(); show(3); });
            models.addView(option, Ui.lp(MATCH, WRAP, 0, 0, 0, dp(8)));
        }

        LinearLayout about = section(page, "Despre");
        about.addView(Ui.muted(this, "TalkText 0.19 · voce → OpenAI → Claude → text.\n"
                + "Spre OpenAI pleacă doar vocea, spre Anthropic doar textul dictat, dicționarul și expresiile. "
                + "Ce e pe ecran nu pleacă nicăieri. Textul deja scris în câmp pleacă la Anthropic doar când atingi ✨ sau traduci. "
                + "Istoricul dictărilor și clipboard-ul stau doar pe telefon.\n\n"
                + "Fonturi: EB Garamond și Figtree, SIL Open Font License 1.1.\n"
                + "Listele de cuvinte pentru corectare: wordfreq (Robyn Speer) și FrequencyWords (Hermit Dave, OpenSubtitles), CC BY-SA 4.0."), Ui.lp(MATCH, WRAP, 0, dp(4), 0, 0));
    }

    private LinearLayout section(LinearLayout page, String title) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, title, 20, Ui.INK, true, 600));
        page.addView(card, Ui.lp(MATCH, WRAP, 0, 0, 0, dp(14)));
        return card;
    }

    private View switchRow(String title, String detail, String key) {
        SharedPreferences prefs = Prefs.get(this);
        LinearLayout row = Ui.row(this);
        LinearLayout text = Ui.column(this);
        text.addView(Ui.body(this, title));
        text.addView(Ui.muted(this, detail));
        row.addView(text, Ui.weight(1));
        Switch s = toggle(prefs.getBoolean(key, true));
        s.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        row.addView(s);
        return row;
    }

    private Switch toggle(boolean on) {
        Switch s = new Switch(this);
        s.setChecked(on);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{Ui.CREAM, Ui.CARD}));
        s.setTrackTintList(new ColorStateList(states, new int[]{Ui.INK, 0xFFD9D3C5}));
        return s;
    }

    // ---- helpers ----

    private boolean keyboardEnabled() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        for (var info : imm.getEnabledInputMethodList()) if (getPackageName().equals(info.getPackageName())) return true;
        return false;
    }

    private boolean keyboardSelected() {
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return ime != null && ime.startsWith(getPackageName() + "/");
    }

    /** The system picker only lists keyboards already switched on, so send the user to switch TalkText on first. */
    private boolean spellCheckerIsOurs() {
        try {
            android.view.textservice.TextServicesManager tsm = getSystemService(android.view.textservice.TextServicesManager.class);
            android.view.textservice.SpellCheckerInfo info = tsm == null ? null : tsm.getCurrentSpellCheckerInfo();
            return tsm != null && tsm.isSpellCheckerEnabled() && info != null && getPackageName().equals(info.getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Android's "Spell checker" screen; on phones that hide it, the keyboard settings where it lives. */
    private void openSpellCheckerSettings() {
        toast("Verificator ortografic → pornit → Verificator implicit: TalkText → Limba: Română.");
        try {
            startActivity(new Intent().setClassName("com.android.settings", "com.android.settings.Settings$SpellCheckersSettingsActivity"));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        }
    }

    private void pickKeyboard() {
        if (!keyboardEnabled()) {
            toast("Pornește întâi „TalkText” în lista care se deschide, apoi revino și apasă din nou Alege.");
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            return;
        }
        getSystemService(InputMethodManager.class).showInputMethodPicker();
    }

    /** The keyboard picker is a dialog, not a new screen: refresh the steps when it closes. */
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && tab == 0 && content != null) show(0);
    }

    private boolean accessibilityOn() {
        AccessibilityManager am = getSystemService(AccessibilityManager.class);
        for (var info : am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
            if (info.getId() != null && info.getId().startsWith(getPackageName() + "/")) return true;
        return false;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        v.setLayoutParams(Ui.lp(MATCH, dp(1), dp(18), 0, dp(18), 0));
        return v;
    }

    private void copy(String text) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("TalkText", text));
        toast("Copiat");
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ---- retry callbacks ----

    @Override public void onPhase(Session s, Session.Phase phase, String message) {
        if (message != null) toast(message);
        if (!isDestroyed()) show(tab);
    }

    @Override public void onLevel(float level) { }

    @Override public void onResult(Session s, String text, String warning) {
        if (warning != null) toast(warning);
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("TalkText", text));
        toast("Gata. Textul e copiat și apare la Recente.");
        if (!isDestroyed()) show(tab);
    }
}
