package ro.talktext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What TalkText learns from how you write, kept only on the phone:
 *  - the words you use and how often (your words are suggested first and never "corrected" once used twice);
 *  - how you spell them, capitals included ("Artinox", not "artinox");
 *  - which word you usually write after which ("compania" → "Artinox"), offered right after a space.
 * Pure Java, tested without a phone.
 */
public final class Personal {
    public static final int MAX_WORDS = 20_000, MAX_PAIRS = 40_000;
    private static final Pattern WORD = Pattern.compile("[\\p{L}]+(?:['’][\\p{L}]+)?");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?…\\n]");

    private final HashMap<String, int[]> words = new HashMap<>();              // lower → {count, times written with a capital mid-sentence}
    private final HashMap<String, HashMap<String, Integer>> next = new HashMap<>(); // lower → lower → count
    private int pairs;

    /** One finished word. prev = the word before it in the same sentence, or null at the start of a sentence. */
    public synchronized void learn(String prev, String word) {
        if (word == null || word.length() < 2 || word.length() > 40) return;
        String lower = word.toLowerCase(Locale.ROOT);
        int[] c = words.computeIfAbsent(lower, k -> new int[2]);
        c[0]++;
        boolean shouting = word.equals(word.toUpperCase(Locale.ROOT)); // OK, ATENȚIE: not a name
        if (prev != null && !shouting && Character.isUpperCase(word.charAt(0))) c[1]++; // a capital that is not just the sentence start
        if (prev != null && prev.length() > 0) {
            HashMap<String, Integer> m = next.computeIfAbsent(prev.toLowerCase(Locale.ROOT), k -> new HashMap<>());
            Integer old = m.put(lower, m.getOrDefault(lower, 0) + 1);
            if (old == null) pairs++;
        }
        if (words.size() > MAX_WORDS || pairs > MAX_PAIRS) prune();
    }

    /** A whole text you wrote or dictated: words in order, sentences kept apart. */
    public synchronized void learnText(String text) {
        if (text == null) return;
        for (String sentence : SENTENCE_END.split(text)) {
            Matcher m = WORD.matcher(sentence);
            String prev = null;
            while (m.find()) {
                learn(prev, m.group());
                prev = m.group();
            }
        }
    }

    public synchronized int count(String word) {
        int[] c = words.get(word.toLowerCase(Locale.ROOT));
        return c == null ? 0 : c[0];
    }

    /** How you write the word: with a capital if you usually do so mid-sentence. */
    public synchronized String spelling(String lower) {
        int[] c = words.get(lower);
        if (c == null || c[1] * 2 <= c[0]) return lower;
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }

    /** Up to n words you have written after `prev` at least twice, most frequent first, in your spelling. */
    public synchronized List<String> predict(String prev, int n) {
        List<String> out = new ArrayList<>();
        if (prev == null) return out;
        HashMap<String, Integer> m = next.get(prev.toLowerCase(Locale.ROOT));
        if (m == null) return out;
        List<Map.Entry<String, Integer>> e = new ArrayList<>(m.entrySet());
        e.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> x : e) {
            if (x.getValue() < 2 || out.size() >= n) break;
            out.add(spelling(x.getKey()));
        }
        return out;
    }

    /** Your words used at least minCount times, as a lexicon: the more you use one, the more it weighs. */
    public synchronized Lexicon lexicon(int minCount) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, int[]> e : words.entrySet())
            if (e.getValue()[0] >= minCount) b.append(e.getKey()).append(':').append(zipf(e.getValue()[0])).append(' ');
        return b.length() == 0 ? null : Lexicon.of("personal", b.toString());
    }

    /** Your spellings with a capital (names, brands), for the corrector and for the voice transcriber. */
    public synchronized Map<String, String> capitalized(int minCount) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, int[]> e : words.entrySet())
            if (e.getValue()[0] >= minCount && e.getValue()[1] * 2 > e.getValue()[0]) out.put(e.getKey(), spelling(e.getKey()));
        return out;
    }

    /** Count → weight on the same scale as the word lists: 2 uses ≈ a rare word, 50 uses ≈ a very common one. */
    static int zipf(int count) {
        return (int) Math.min(650, 300 + 70 * Math.log(Math.max(1, count)));
    }

    public synchronized int size() { return words.size(); }

    public synchronized int pairs() { return pairs; }

    public synchronized void clear() {
        words.clear();
        next.clear();
        pairs = 0;
    }

    /** Over the limits: forget the words and pairs used only once. */
    private void prune() {
        // Forget the rarest until there is room again (to 80% of the limit), so pruning is rare and the store stays small.
        if (words.size() > MAX_WORDS) {
            int[] counts = words.values().stream().mapToInt(c -> c[0]).sorted().toArray();
            int cut = counts[counts.length - MAX_WORDS * 4 / 5];
            words.values().removeIf(c -> c[0] <= cut);
        }
        if (pairs > MAX_PAIRS) {
            int[] counts = next.values().stream().flatMap(m -> m.values().stream()).mapToInt(Integer::intValue).sorted().toArray();
            int cut = counts[counts.length - MAX_PAIRS * 4 / 5];
            for (HashMap<String, Integer> m : next.values()) m.values().removeIf(v -> v <= cut);
            next.values().removeIf(HashMap::isEmpty);
        }
        int left = 0;
        for (HashMap<String, Integer> m : next.values()) left += m.size();
        pairs = left;
    }

    /** The names you write most (with a capital), most used first. */
    public synchronized List<String> topNames(int n, int minCount) {
        List<Map.Entry<String, int[]>> e = new ArrayList<>();
        for (Map.Entry<String, int[]> x : words.entrySet())
            if (x.getValue()[0] >= minCount && x.getValue()[1] * 2 > x.getValue()[0]) e.add(x);
        e.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, e.size()); i++) out.add(spelling(e.get(i).getKey()));
        return out;
    }

    private static final char TAB = '\t', NL = '\n';

    /** Saved as plain lines: "w<TAB>word<TAB>count<TAB>capitals" and "n<TAB>word<TAB>next word<TAB>count". */
    public synchronized String toText() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, int[]> e : words.entrySet())
            b.append('w').append(TAB).append(e.getKey()).append(TAB).append(e.getValue()[0]).append(TAB).append(e.getValue()[1]).append(NL);
        for (Map.Entry<String, HashMap<String, Integer>> e : next.entrySet())
            for (Map.Entry<String, Integer> x : e.getValue().entrySet())
                b.append('n').append(TAB).append(e.getKey()).append(TAB).append(x.getKey()).append(TAB).append(x.getValue()).append(NL);
        return b.toString();
    }

    public static Personal fromText(String text) {
        Personal p = new Personal();
        if (text == null) return p;
        for (String line : text.split(String.valueOf(NL))) {
            String[] f = line.split(String.valueOf(TAB));
            try {
                if (f.length == 4 && f[0].equals("w")) p.words.put(f[1], new int[]{Integer.parseInt(f[2]), Integer.parseInt(f[3])});
                else if (f.length == 4 && f[0].equals("n")) {
                    p.next.computeIfAbsent(f[1], k -> new HashMap<>()).put(f[2], Integer.parseInt(f[3]));
                    p.pairs++;
                }
            } catch (NumberFormatException ignored) {
                // a damaged line: skip it rather than crash the keyboard
            }
        }
        return p;
    }
}
