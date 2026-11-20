package ro.talktext;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Voice command at the start of a dictation.
 *  - The language named in Romanian means "translate what I say into it":
 *    "Engleză, …" / "în engleză, …" → speak Romanian, get English; "rusă" / "pe rusește" → get Russian.
 *  - The language named in its own tongue means "I am speaking it": "English, …", "ruski, …", "русский, …".
 *  - "română, …" just confirms Romanian.
 */
public final class Rules {
    public static final class Parsed {
        private final String language, target, text;
        private final boolean prefixed;

        public Parsed(String language, String target, String text, boolean prefixed) {
            this.language = language;
            this.target = target;
            this.text = text;
            this.prefixed = prefixed;
        }

        /** The language being spoken (what the transcriber should listen for). */
        public String language() { return language; }
        /** The language to translate into, or null to keep the spoken one. */
        public String target() { return target; }
        public String text() { return text; }
        public boolean prefixed() { return prefixed; }
        @Override public String toString() { return language + "→" + target + "|" + prefixed + "|" + text; }
    }

    // How the command word comes back from the transcriber in RO, EN or RU.
    // (?iu) only: Android's ICU regex rejects the (?U) flag that desktop Java accepts.
    private static final Pattern PREFIX = Pattern.compile(
            "(?iu)^\\s*(?:în\\s+|in\\s+|pe\\s+|на\\s+)?("
                    + "engleză|engleza|engleze[șs]te|"                                     // translate → English
                    + "rusă|rusa|ruse[șs]te|"                                                // translate → Russian
                    + "english|inglish|ingli[șşs]|инглиш|английский|"                      // speaking English
                    + "ruski|russki|rusky|ruskiy|russkiy|russian|русский|русски|руски|рашн|" // speaking Russian
                    + "română|romana|românește|romaneste|romanian|румынский"
                    + ")\\b\\s*(?:[,:;.!?—–-]+\\s*|$)"); // the pause after the word: "Engleza e grea" is no command

    public static Parsed parse(String text, String fallback) {
        String source = text == null ? "" : text;
        Matcher m = PREFIX.matcher(source);
        if (!m.find()) return new Parsed(fallback, null, source.trim(), false);
        String word = m.group(1).toLowerCase(Locale.ROOT);
        String rest = source.substring(m.end()).trim();
        if (word.startsWith("englez")) return new Parsed(fallback, "en", rest, true);
        if (word.startsWith("rusă") || word.equals("rusa") || word.startsWith("ruse")) return new Parsed(fallback, "ru", rest, true);
        if (word.startsWith("eng") || word.startsWith("ingl") || word.startsWith("инг") || word.startsWith("англ")) return new Parsed("en", null, rest, true);
        if (word.startsWith("ru") || word.startsWith("рус") || word.startsWith("раш")) return new Parsed("ru", null, rest, true);
        return new Parsed("ro", null, rest, true);
    }

    private Rules() {}
}
