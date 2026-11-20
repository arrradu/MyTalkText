package ro.talktext;

/** Pure-Java checks, no phone needed: language command, cut points, spacing at the cursor. */
public class RulesTest {
    static int passed;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
    }

    static void lang(String input, String language, String output) {
        Rules.Parsed r = Rules.parse(input, "ro");
        check(r.language().equals(language) && r.text().equals(output), input + " => " + r);
    }

    static void tr(String input, String target, String output) {
        Rules.Parsed r = Rules.parse(input, "ro");
        check(r.language().equals("ro") && target.equals(r.target()) && r.text().equals(output), input + " => " + r);
    }

    public static void main(String[] args) {
        // Voice language command
        lang("Salut, Eugen.", "ro", "Salut, Eugen.");
        lang("English: Please send the offer.", "en", "Please send the offer.");
        lang("English, please send the offer.", "en", "please send the offer.");
        lang("Inglish, hello.", "en", "hello.");
        lang("ruski, Отправь предложение.", "ru", "Отправь предложение.");
        lang("Ruski. Привет!", "ru", "Привет!");
        lang("Русский. Привет!", "ru", "Привет!");
        lang("Руски, отправь счёт.", "ru", "отправь счёт.");
        // A language named in Romanian = translate into it; the speaker keeps talking Romanian
        tr("Engleză, trimite-i oferta lui Eugen.", "en", "trimite-i oferta lui Eugen.");
        tr("În engleză: mulțumesc pentru răspuns.", "en", "mulțumesc pentru răspuns.");
        tr("engleza, buna ziua", "en", "buna ziua");
        tr("Pe englezește, vă rog.", "en", "vă rog.");
        tr("Rusă, mâine vin la birou.", "ru", "mâine vin la birou.");
        tr("În rusă, mulțumesc.", "ru", "mulțumesc.");
        tr("Pe rusește: bună dimineața.", "ru", "bună dimineața.");
        check(Rules.parse("English, hello", "ro").target() == null, "English = speaking English, no translation");
        check(Rules.parse("Salut", "ro").target() == null, "no command, no translation");
        // Without a pause after it, the word is just part of the sentence
        lang("Engleza e grea pentru mine.", "ro", "Engleza e grea pentru mine.");
        check(Rules.parse("Engleza e grea pentru mine.", "ro").target() == null, "Engleza e grea: no translation");
        lang("Rusa de la etaj a venit.", "ro", "Rusa de la etaj a venit.");
        lang("În engleză se spune altfel.", "ro", "În engleză se spune altfel.");
        lang("Pe rusește nu știu.", "ro", "Pe rusește nu știu.");
        lang("Russian troops arrived.", "ro", "Russian troops arrived.");
        lang("Русский язык сложный.", "ro", "Русский язык сложный.");
        lang("Englishman arrived.", "ro", "Englishman arrived.");
        lang("În text apare English.", "ro", "În text apare English.");
        lang("Rusalka e o operă.", "ro", "Rusalka e o operă.");
        lang("English", "en", "");

        // Cutting: never before 6 s for the first piece, always inside a pause, never longer than the maximum
        Chunker c = new Chunker();
        int frames = 0;
        boolean cut = false;
        while (!cut) { cut = c.feed(frames % 50 < 40 ? 3000 : 50); frames++; }
        int first = frames * Chunker.FRAME_MS;
        check(first >= 6000 && first <= 20000, "first piece " + first);
        check(c.speechMs() > 4000, "speech counted " + c.speechMs());
        c.next();
        frames = 0;
        cut = false;
        // Fast talking: only 40 ms gaps between syllables, no real pause -> cut only at the limit
        while (!cut) { cut = c.feed(frames % 22 < 20 ? 3000 : 100); frames++; }
        check(frames * Chunker.FRAME_MS == 90000, "hard limit " + frames * Chunker.FRAME_MS);
        c.next();
        frames = 0;
        cut = false;
        // Talking over steady noise (car): never treated as silence
        while (!cut) { cut = c.feed(frames % 22 < 20 ? 2600 : 900); frames++; }
        check(frames * Chunker.FRAME_MS == 90000 && c.speechMs() > 60000, "noisy speech " + frames * Chunker.FRAME_MS + "/" + c.speechMs());
        c.next();
        frames = 0;
        cut = false;
        while (!cut && frames < 10000) { cut = c.feed(40); frames++; }
        check(c.speechMs() == 0, "silence is not speech");

        // Spacing at the cursor
        check(Spacing.fit("Salut", "ce faci?", "").equals(" ce faci?"), "space before");
        check(Spacing.fit("", "Salut", "").equals("Salut"), "empty field");
        check(Spacing.fit("Salut ", "Eugen", "!").equals("Eugen"), "before punctuation");
        check(Spacing.fit("A", "B", "C").equals(" B "), "middle of word");
        check(Spacing.fit("Salut", ", Eugen", "").equals(", Eugen"), "starts with comma");

        // Keyboard rows: every row of a page has the same width, Romanian diacritics sit on the right keys
        for (int page = 0; page < 3; page++)
            for (boolean cyr : new boolean[]{false, true}) {
                java.util.List<java.util.List<KeyLayout.Key>> rows = KeyLayout.rows(page, cyr);
                float width = KeyLayout.units(rows.get(0));
                for (java.util.List<KeyLayout.Key> row : rows)
                    check(KeyLayout.units(row) == width, "row width page " + page + " cyr " + cyr + ": " + KeyLayout.units(row) + " vs " + width);
            }
        check(altsOf("a").contains("ă") && altsOf("a").contains("â"), "a → ă â");
        check(altsOf("i").contains("î") && altsOf("s").contains("ș") && altsOf("t").contains("ț"), "î ș ț");
        check(altsOf("q").get(0).equals("1") && altsOf("p").get(0).equals("0"), "numbers on the top row");
        check(KeyLayout.rows(0, true).get(0).get(0).out.equals("й"), "Russian layout starts with й");
        check(KeyLayout.shifted("ș", true).equals("Ș"), "capital Ș");

        // Swipe-to-delete: whole words from the cursor back, with their spaces
        check(Spacing.wordsBack("Salut Eugen, ce faci ", 1) == 5, "one word: faci + space");
        check(Spacing.wordsBack("Salut Eugen, ce faci ", 2) == 8, "two words");
        check(Spacing.wordsBack("Salut Eugen, ce faci", 3) == 14, "Eugen, ce faci");
        check(Spacing.wordsBack("Salut", 5) == 5, "more words than text: all");
        check(Spacing.wordsBack("Salut ", 0) == 0, "zero words: nothing");

        // Clipboard history: newest first, no duplicates, at most 20
        java.util.List<String> clips = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) clips = ClipHistory.add(clips, "text " + i);
        check(clips.size() == 20 && clips.get(0).equals("text 24") && clips.get(19).equals("text 5"), "20 newest: " + clips.size());
        clips = ClipHistory.add(clips, "text 10");
        check(clips.size() == 20 && clips.get(0).equals("text 10") && clips.indexOf("text 10") == clips.lastIndexOf("text 10"), "copied again moves to the top");
        check(ClipHistory.add(clips, "   ").equals(clips), "blank ignored");

        System.out.println(passed + " checks passed");
    }

    static java.util.List<String> altsOf(String letter) {
        for (java.util.List<KeyLayout.Key> row : KeyLayout.rows(0, false))
            for (KeyLayout.Key k : row)
                if (letter.equals(k.out)) return k.alts == null ? java.util.List.of() : java.util.Arrays.asList(k.alts);
        throw new AssertionError("no key " + letter);
    }
}
