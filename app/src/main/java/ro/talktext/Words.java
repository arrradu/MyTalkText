package ro.talktext;

import android.content.Context;
import java.io.InputStream;

/**
 * The word lists, loaded once for the whole app and shared by the keyboard and the spell checker.
 * Each getter loads on first use and can take a few seconds: call it from a background thread.
 */
final class Words {
    private static Lexicon ro, en, ru;

    static synchronized Lexicon ro(Context c) { if (ro == null) ro = load(c, "ro", R.raw.lex_ro); return ro; }
    static synchronized Lexicon en(Context c) { if (en == null) en = load(c, "en", R.raw.lex_en); return en; }
    static synchronized Lexicon ru(Context c) { if (ru == null) ru = load(c, "ru", R.raw.lex_ru); return ru; }

    private static Lexicon load(Context c, String lang, int res) {
        try (InputStream in = c.getApplicationContext().getResources().openRawResource(res)) {
            return Lexicon.load(lang, in);
        } catch (Exception e) {
            return null;
        }
    }

    private Words() {}
}
