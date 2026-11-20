package ro.talktext;

import java.io.FileInputStream;
import java.util.Arrays;
import java.util.List;

/** Autocorrect on the real word lists that ship in the app. Run from the project folder. */
public class CorrectorTest {
    static int passed;
    static Corrector c = new Corrector();

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
    }

    static void fix(String typed, Lexicon p, Lexicon s, String expected) {
        String got = c.correct(typed, p, s);
        check(expected == null ? got == null : expected.equals(got), typed + " → " + got + " (expected " + expected + ")");
    }

    static Lexicon load(String lang) throws Exception {
        return Lexicon.load(lang, new FileInputStream("app/src/main/res/raw/lex_" + lang + ".txt"));
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        Lexicon ro = load("ro"), en = load("en"), ru = load("ru");
        System.out.println("loaded " + ro.size() + " / " + en.size() + " / " + ru.size() + " words in " + (System.nanoTime() - t0) / 1_000_000 + " ms");

        // Romanian diacritics: restored when the plain form is clearly rarer, left alone when both are common
        fix("si", ro, en, "și");
        fix("sa", ro, en, "să");
        fix("in", ro, en, "în");
        fix("Si", ro, en, "Și");
        fix("ca", ro, en, null);
        fix("casa", ro, en, null);
        fix("este", ro, en, null);
        fix("și", ro, en, null);
        // Romanian typos
        String cse = c.correct("cse", ro, en);
        check("ce".equals(cse) || "se".equals(cse), "cse → " + cse);
        fix("pentrj", ro, en, "pentru");
        fix("multumesc", ro, en, "mulțumesc");
        // English typed inside Romanian text stays English
        fix("the", ro, en, null);
        fix("meeting", ro, en, null);
        // English sentence
        fix("teh", en, ro, "the");
        fix("recieve", en, ro, "receive");
        String helo = c.correct("helo", en, ro);
        check("hello".equals(helo) || "help".equals(helo), "helo → " + helo);
        fix("in", en, ro, null);
        // Russian
        fix("привт", ru, null, "привет");
        fix("еще", ru, null, null);
        fix("спасибо", ru, null, null);
        // Words that only the bigger Romanian list knows
        fix("ruseste", ro, en, "rusește");
        // Your dictionary: never changed, and used as the correction (with its capitals)
        c.setUserWords(Arrays.asList("Artinox", "Molinart", "Horodok"), Arrays.asList());
        fix("Artinox", ro, en, null);
        fix("artinox", ro, en, "Artinox"); // written lower case: gets the capital it has in your dictionary
        fix("artinx", ro, en, "Artinox");
        fix("molinrat", ro, en, "Molinart");
        c.setUserWords(Arrays.asList("ședința"), Arrays.asList());
        fix("sedinta", ro, en, "ședința");
        c.setUserWords(Arrays.asList(), Arrays.asList("ruseste"));
        fix("ruseste", ro, en, "rusește"); // taken back once: diacritics are still added
        c.setUserWords(Arrays.asList(), Arrays.asList("ruseste", "ruseste"));
        fix("ruseste", ro, en, null); // taken back twice: yours, left alone
        // What it learns from you
        c.setUserWords(Arrays.asList(), Arrays.asList());
        Personal me = new Personal();
        for (int i = 0; i < 3; i++)
            me.learnText("Compania Artinox livrează. Vorbesc cu Eugen despre Lynk. Compania Artinox are clienți noi. Mulțumesc frumos.");
        me.learnText("Trimit oferta azi. Trimit oferta mâine.");
        c.setPersonal(me.lexicon(2), me.capitalized(3));
        fix("artinox", ro, en, "Artinox");
        fix("Artinox", ro, en, null);
        fix("artinx", ro, en, "Artinox");
        fix("lynk", ro, en, "Lynk");
        check(me.predict("compania", 3).contains("Artinox"), "after 'compania' comes Artinox: " + me.predict("compania", 3));
        check(me.predict("trimit", 3).contains("oferta"), "after 'trimit' comes oferta: " + me.predict("trimit", 3));
        check(me.predict("azi", 3).isEmpty(), "a pair seen once is not predicted yet");
        check(me.count("artinox") == 6 && me.spelling("artinox").equals("Artinox"), "count and spelling");
        Personal back = Personal.fromText(me.toText());
        check(back.count("artinox") == 6 && back.predict("compania", 3).contains("Artinox") && back.pairs() == me.pairs(), "saved and loaded");
        List<String> sa = c.suggest("Art", ro, en);
        check(!sa.isEmpty() && sa.contains("Artinox"), "your word first in suggestions: " + sa);
        // Shouting and line starts are not names; the biggest names come first; the store stays bounded
        Personal caps = new Personal();
        for (int i = 0; i < 5; i++) caps.learnText("Totul e OK azi.");
        check(caps.spelling("ok").equals("ok"), "OK in capitals is not a name");
        check(me.topNames(5, 3).get(0).equals("Artinox"), "most used name first: " + me.topNames(5, 3));
        Personal big = new Personal();
        for (int i = 0; i < Personal.MAX_WORDS + 500; i++) { big.learn(null, "cuv" + i); big.learn(null, "cuv" + i); }
        check(big.size() <= Personal.MAX_WORDS, "bounded: " + big.size());
        c.setPersonal(null, null);

        // Two words run together (space hit as v / missed) and two mistakes in one word
        fix("transformatvautomat", ro, en, "transformat automat");
        fix("transformatautomat", ro, en, "transformat automat");
        fix("vorbimnmaine", ro, en, "vorbim mâine");
        List<String> two = c.suggest("inbunatatime", ro, en);
        check(two.contains("îmbunătățim") || two.contains("îmbunătățime"), "two mistakes: " + two);
        check(c.suggest("sugesriile", ro, en).contains("sugestiile"), "sugesriile: " + c.suggest("sugesriile", ro, en));
        // Taken back once: still gets its diacritics; twice: left alone
        c.setUserWords(Arrays.asList(), Arrays.asList("sa"));
        fix("sa", ro, en, "să");
        c.setUserWords(Arrays.asList(), Arrays.asList("sa", "sa"));
        fix("sa", ro, en, null);
        c.setUserWords(Arrays.asList(), Arrays.asList());

        // Language of the sentence
        check(Corrector.pick(Arrays.asList("the", "meeting", "is"), ro, en) == en, "English sentence");
        check(Corrector.pick(Arrays.asList("mâine", "este", "ședința"), ro, en) == ro, "Romanian sentence");
        check(Corrector.pick(Arrays.asList(), ro, en) == ro, "Romanian by default");
        // Suggestions: middle = the fix, the typed word stays on the left
        List<String> s = c.suggest("si", ro, en);
        check(s.size() >= 2 && s.get(0).equals("si") && s.get(1).equals("și"), "suggest si: " + s);
        List<String> m = c.suggest("mul", ro, en);
        check(m.contains("mult") || m.contains("mulțumesc") || m.contains("multe"), "suggest mul: " + m);

        long t1 = System.nanoTime();
        for (int i = 0; i < 200; i++) c.correct("pentrj", ro, en);
        System.out.println("correct(): " + (System.nanoTime() - t1) / 200_000 + " µs per word");
        System.out.println(passed + " autocorrect checks passed");
    }
}
