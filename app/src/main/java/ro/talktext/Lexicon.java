package ro.talktext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One language's words with their frequency (Zipf × 100; wordfreq, Romanian topped up from FrequencyWords, CC BY-SA 4.0).
 * Words are also indexed by their "skeleton" (lower case, no diacritics, ё → е), which is what
 * people type when they skip diacritics: "si" → și, "sa" → să, "in" → în.
 * Stored as plain sorted arrays (binary search), not hash maps: a 200 000-word list stays small in a keyboard.
 */
public final class Lexicon {
    public final String lang;
    private final String[] words;    // most frequent first
    private final short[] zipfs;
    private final String[] skeletons; // same order; the very same String when a word has no diacritics
    private final int[] alphabetical; // word indexes sorted by word
    private final String[] skeletonKeys; // unique skeletons, sorted
    private final int[] skeletonBest;    // most frequent word for each key

    private Lexicon(String lang, List<String> ws, List<Integer> zs) {
        this.lang = lang;
        int n = ws.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(zs.get(b), zs.get(a))); // stable: file order kept for ties
        words = new String[n];
        zipfs = new short[n];
        skeletons = new String[n];
        for (int i = 0; i < n; i++) {
            words[i] = ws.get(order[i]);
            zipfs[i] = (short) (int) zs.get(order[i]);
            String sk = skeleton(words[i]);
            skeletons[i] = sk.equals(words[i]) ? words[i] : sk;
        }
        Integer[] alpha = new Integer[n];
        for (int i = 0; i < n; i++) alpha[i] = i;
        Arrays.sort(alpha, (a, b) -> words[a].compareTo(words[b]));
        alphabetical = new int[n];
        for (int i = 0; i < n; i++) alphabetical[i] = alpha[i];
        Integer[] bySkel = new Integer[n];
        for (int i = 0; i < n; i++) bySkel[i] = i;
        Arrays.sort(bySkel, (a, b) -> {
            int c = skeletons[a].compareTo(skeletons[b]);
            return c != 0 ? c : Integer.compare(a, b); // lower index = more frequent comes first
        });
        List<String> keys = new ArrayList<>();
        List<Integer> best = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int w = bySkel[i];
            if (keys.isEmpty() || !keys.get(keys.size() - 1).equals(skeletons[w])) {
                keys.add(skeletons[w]);
                best.add(w);
            }
        }
        skeletonKeys = keys.toArray(new String[0]);
        skeletonBest = new int[best.size()];
        for (int i = 0; i < skeletonBest.length; i++) skeletonBest[i] = best.get(i);
    }

    private static final class Builder {
        final List<String> ws = new ArrayList<>();
        final List<Integer> zs = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        void add(String word, int z) {
            String w = word.toLowerCase(Locale.ROOT);
            if (w.isEmpty() || !seen.add(w)) return;
            ws.add(w);
            zs.add(z);
        }
    }

    /** Lines "word\tzipf×100". */
    public static Lexicon load(String lang, InputStream in) throws IOException {
        Builder b = new Builder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                b.add(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1).trim()));
            }
        }
        return new Lexicon(lang, b.ws, b.zs);
    }

    /** For tests: "word:zipf word:zipf …". */
    public static Lexicon of(String lang, String entries) {
        Builder b = new Builder();
        for (String e : entries.trim().split("\\s+")) {
            int c = e.lastIndexOf(':');
            b.add(e.substring(0, c), Integer.parseInt(e.substring(c + 1)));
        }
        return new Lexicon(lang, b.ws, b.zs);
    }

    public static Lexicon ofWords(String lang, Iterable<String> words, int z) {
        Builder b = new Builder();
        for (String w : words) b.add(w, z);
        return new Lexicon(lang, b.ws, b.zs);
    }

    public static String skeleton(String word) {
        StringBuilder b = new StringBuilder(word.length());
        for (char ch : word.toLowerCase(Locale.ROOT).toCharArray()) {
            switch (ch) {
                case 'ă': case 'â': b.append('a'); break;
                case 'î': b.append('i'); break;
                case 'ș': case 'ş': b.append('s'); break;
                case 'ț': case 'ţ': b.append('t'); break;
                case 'ё': b.append('е'); break;
                default: b.append(ch);
            }
        }
        return b.toString();
    }

    /** Frequency of the exact (lower-case) word, 0 if unknown. */
    public int zipf(String lowerWord) {
        int lo = 0, hi = alphabetical.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = words[alphabetical[mid]].compareTo(lowerWord);
            if (c == 0) return zipfs[alphabetical[mid]];
            if (c < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return 0;
    }

    /** The most frequent word with this skeleton, or null. */
    public String best(String skeleton) {
        int i = Arrays.binarySearch(skeletonKeys, skeleton);
        return i < 0 ? null : words[skeletonBest[i]];
    }

    /** Up to n words starting with the typed prefix (compared without diacritics), most frequent first. */
    public List<String> completions(String typedPrefix, int n) {
        String p = skeleton(typedPrefix);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < skeletons.length && out.size() < n; i++)
            if (skeletons[i].startsWith(p)) out.add(words[i]);
        return out;
    }

    public int size() { return words.length; }

    // By position, most frequent first: for the two-mistake search, which walks the list itself.
    public String wordAt(int i) { return words[i]; }
    public String skeletonAt(int i) { return skeletons[i]; }
    public int zipfAt(int i) { return zipfs[i]; }
}
