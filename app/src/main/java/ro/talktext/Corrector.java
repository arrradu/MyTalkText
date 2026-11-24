package ro.talktext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Autocorrect and suggestions for Romanian, English and Russian, all on the phone.
 * Conservative on purpose: a word that exists is left alone, except
 *  - Romanian without diacritics when the diacritic form is clearly more common (si → și, sa → să, in → în;
 *    but ca / că and casa / casă stay as typed, both are common);
 *  - obvious swapped letters (teh → the).
 * Unknown words get the best one-letter fix, weighted by keyboard distance.
 * Thread-safe: the keyboard asks for suggestions in the background and corrects on the main thread.
 */
public final class Corrector {
    /** Diacritic form must be at least ~2× as frequent (0.3 Zipf): multumesc → mulțumesc, but ca / că stays. */
    static final int DIACRITIC_GAIN = 30;
    /** A known word is only "fixed" by swapping two letters if the result is ~60× more frequent (teh, recieve). */
    static final int SWAP_GAIN = 180;
    /** A word of the other language protects itself only if it is reasonably common there (cse, an English abbreviation, does not). */
    static final int OTHER_KNOWN = 350;
    /** A correction must be a reasonably common word. */
    static final int MIN_SCORE = 260;

    private static final String LATIN = "abcdefghijklmnopqrstuvwxyz", CYRILLIC = "абвгдежзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] QWERTY = {"qwertyuiop", "asdfghjkl", "zxcvbnm"}, JCUKEN = {"йцукенгшщзх", "фывапролджэ", "ячсмитьбю"};

    /** Your dictionary words count as very common: they are corrected TO ("ruseste" → "rusește", "artinx" → "Artinox"). */
    static final int USER_ZIPF = 600;

    private final Set<String> userWords = new HashSet<>();
    private final java.util.Map<String, String> userSpelling = new java.util.HashMap<>();
    private Lexicon user;
    private Lexicon personal;                        // words you use (2+ times), weighted by how often
    private java.util.Map<String, String> personalCaps = new java.util.HashMap<>(); // names you write with a capital

    /** What Personal learned: called again from time to time as it learns more. */
    public synchronized void setPersonal(Lexicon learned, java.util.Map<String, String> capitals) {
        personal = learned;
        personalCaps = capitals == null ? new java.util.HashMap<>() : capitals;
    }

    /**
     * dictionary: your words, never changed and used as corrections (with the capitals you wrote them with);
     * learned: words you restored with Backspace, only protected.
     */
    public synchronized void setUserWords(Iterable<String> dictionary, Iterable<String> learned) {
        userWords.clear();
        userSpelling.clear();
        List<String> mine = new ArrayList<>();
        for (String w : dictionary) {
            String t = w.trim();
            if (t.isEmpty() || t.contains(" ")) continue;
            String l = t.toLowerCase(Locale.ROOT);
            userWords.add(l);
            userSpelling.put(l, t);
            mine.add(l);
        }
        learnedCount.clear();
        for (String w : learned) if (!w.trim().isEmpty()) learnOne(w.trim().toLowerCase(Locale.ROOT));
        user = mine.isEmpty() ? null : Lexicon.ofWords("user", mine, USER_ZIPF);
    }

    /**
     * A word you took back from autocorrect (Backspace, or your own spelling picked from the bar).
     * Once: it is no longer "fixed" as a typo, but missing diacritics are still added (sa → să).
     * Twice: it is yours and never changed. (One tap on the wrong slot must not switch off "sa → să" forever.)
     */
    private final java.util.Map<String, Integer> learnedCount = new java.util.HashMap<>();

    private void learnOne(String lower) {
        int n = learnedCount.merge(lower, 1, Integer::sum);
        if (n >= 2) userWords.add(lower);
    }

    /** A word as you wrote it in the dictionary ("Artinox"), unless you typed it in capitals yourself. */
    private String spelled(String typed, String lowerWord) {
        String mine = userSpelling.get(lowerWord);
        if (mine == null) mine = personalCaps.get(lowerWord);
        if (mine != null && !mine.equals(lowerWord) && typed.equals(typed.toLowerCase(Locale.ROOT))) return mine;
        return withCase(typed, lowerWord);
    }

    public synchronized void learn(String word) { learnOne(word.toLowerCase(Locale.ROOT)); }

    /** Which Latin language the sentence so far is in: Romanian unless the recent words look English. */
    public static Lexicon pick(List<String> recentWords, Lexicon ro, Lexicon en) {
        if (en == null) return ro;
        if (ro == null) return en;
        int score = 0;
        for (String w : recentWords) {
            String l = w.toLowerCase(Locale.ROOT);
            score += Integer.signum(ro.zipf(l) - en.zipf(l));
        }
        return score >= 0 ? ro : en;
    }

    /** The replacement to make when a word is finished (space, punctuation), or null to keep it. */
    public synchronized String correct(String typed, Lexicon primary, Lexicon secondary) {
        if (primary == null || typed.length() < 2) return null;
        String lower = typed.toLowerCase(Locale.ROOT);
        if (userWords.contains(lower)) { // yours: never changed, except for the capital you gave it in the dictionary
            String mine = userSpelling.get(lower);
            return mine != null && !mine.equals(lower) && typed.equals(lower) ? mine : null;
        }
        int zp = primary.zipf(lower), zs = secondary == null ? 0 : secondary.zipf(lower);
        String skel = Lexicon.skeleton(lower);

        // 0. Your dictionary, typed without its diacritics: always yours. A name you write with a capital gets it.
        if (user != null) {
            String v = user.best(skel);
            if (v != null && !v.equals(lower)) return spelled(typed, v);
            if (v != null && typed.equals(lower) && !userSpelling.getOrDefault(lower, lower).equals(lower)) return userSpelling.get(lower);
        }
        if (typed.equals(lower) && personalCaps.containsKey(lower)) return personalCaps.get(lower);
        int zme = personal == null ? 0 : personal.zipf(lower);
        // Words you often write with diacritics, typed without them this time.
        if (personal != null && zme == 0 && skel.equals(lower)) {
            String v = personal.best(skel);
            if (v != null && !v.equals(lower) && personal.zipf(v) >= 400) return spelled(typed, v);
        }

        // 1. Diacritics skipped (Romanian): only when the plain form is not just as common.
        if ("ro".equals(primary.lang) && skel.equals(lower)) {
            String v = primary.best(skel);
            if (v != null && !v.equals(lower) && primary.zipf(v) - zp >= DIACRITIC_GAIN) return withCase(typed, v);
        }
        if (learnedCount.containsKey(lower)) return null; // taken back once: diacritics only, no typo fixing
        boolean known = zp > 0 || zs >= OTHER_KNOWN || zme > 0; // a word you used twice is a word
        if (known) {
            String swap = bestEdit(skel, primary, true);
            if (swap != null && primary.zipf(swap) - Math.max(zp, zs) >= SWAP_GAIN) return withCase(typed, swap);
            return null;
        }
        // 2. Unknown word: best one-letter fix in the sentence's language, the other language a little behind.
        String a = bestEdit(skel, primary, false);
        int sa = a == null ? 0 : lastScore;
        String b = secondary == null ? null : bestEdit(skel, secondary, false);
        int sb = b == null ? 0 : lastScore - 30;
        String u = user == null ? null : bestEdit(skel, user, false);
        int su = u == null ? 0 : lastScore;
        String me = personal == null ? null : bestEdit(skel, personal, false);
        if (me != null && lastScore > su) { u = me; su = lastScore; }
        String w = sa >= sb ? a : b;
        int best = Math.max(sa, sb);
        if (u != null && su >= best) { w = u; best = su; }
        // 3. Two words run together, the space hit as c/v/b/n/m or missed: "transformatvautomat" → "transformat automat".
        String split = skel.length() >= 5 ? bestSplit(skel, primary) : null;
        if (split != null && lastScore > best) return withCase(typed, split);
        // 4. Two mistakes in a longer word: "inbunatatime" → "îmbunătățim".
        if ((w == null || best < MIN_SCORE) && skel.length() >= 6) {
            List<String> near = nearest(skel, primary, 1);
            if (!near.isEmpty() && primary.zipf(near.get(0)) - 200 >= MIN_SCORE) return withCase(typed, near.get(0));
        }
        return w == null || best < MIN_SCORE ? null : spelled(typed, w);
    }

    private static final String NEAR_SPACE_LATIN = "cvbnm", NEAR_SPACE_CYRILLIC = "смитьб";

    /** Up to three words run together ("vezibsinsingur" → "vezi și singur"). */
    static final int MAX_SPLIT_WORDS = 3;

    /**
     * The best way to read the word as two or three words. Sets lastScore (the rarest part's frequency minus a penalty
     * per joint). A joint is a letter next to the space bar typed instead of the space (c/v/b/n/m, cheaper and with
     * rarer parts allowed, since the finger clearly aimed at the space) or a space simply missed.
     */
    private String bestSplit(String skel, Lexicon lex) {
        String near = "ru".equals(lex.lang) ? NEAR_SPACE_CYRILLIC : NEAR_SPACE_LATIN;
        splitBest = null;
        splitBestScore = Integer.MIN_VALUE;
        split(skel, 0, lex, near, new ArrayList<>(), 0, true);
        lastScore = splitBest == null ? Integer.MIN_VALUE : splitBestScore;
        return splitBest != null && splitBestScore >= (splitAllNear ? NEAR_SPLIT_MIN : MIN_SCORE) ? splitBest : null;
    }

    /** A split made only at letters next to the space bar may use rarer words ("suntbsetate" → "sunt setate"). */
    static final int NEAR_SPLIT_MIN = 220, NEAR_JOINT = 10, MISSED_JOINT = 70, THIRD_WORD = 120;

    private String splitBest;
    private int splitBestScore;
    private boolean splitAllNear;

    /** Tries every way to cut skel[from..] into words; parts holds the words so far, penalty the joints' cost. */
    private void split(String skel, int from, Lexicon lex, String near, List<String> parts, int penalty, boolean allNear) {
        if (parts.size() >= 1) { // the rest as the last word
            String rest = skel.substring(from);
            if (!rest.isEmpty()) {
                parts.add(rest);
                int s = splitScore(parts, lex, allNear);
                // Three words only when both joints are letters next to the space bar: two missed spaces are too unlikely.
                int cost = penalty + (parts.size() - 2) * THIRD_WORD; // fewer words first: "vorbimnmaine" → "vorbim mâine", not "vorbim mai e"
                if (s != Integer.MIN_VALUE && (parts.size() < 3 || allNear) && s - cost > splitBestScore) {
                    splitBestScore = s - cost;
                    splitAllNear = allNear;
                    StringBuilder b = new StringBuilder();
                    for (String p : parts) b.append(b.length() == 0 ? "" : " ").append(lex.best(p));
                    splitBest = b.toString();
                }
                parts.remove(parts.size() - 1);
            }
        }
        if (parts.size() + 1 >= MAX_SPLIT_WORDS) return;
        for (int i = from + 1; i < skel.length(); i++) {
            String part = skel.substring(from, i);
            if (lex.best(part) == null) continue;
            parts.add(part);
            if (near.indexOf(skel.charAt(i)) >= 0 && i + 1 < skel.length()) split(skel, i + 1, lex, near, parts, penalty + NEAR_JOINT, allNear);
            split(skel, i, lex, near, parts, penalty + MISSED_JOINT, false);
            parts.remove(parts.size() - 1);
        }
    }


    /**
     * All parts must be real words; one-letter parts only a, o, e; two-letter parts only if very common (în, să, eu…).
     * When every joint is a letter next to the space bar, rarer words are allowed (setate, tipăresc).
     */
    private static int splitScore(List<String> parts, Lexicon lex, boolean allNear) {
        int min = Integer.MAX_VALUE;
        int floor = allNear ? 250 : 350, shortFloor = allNear ? 450 : 600;
        for (String p : parts) {
            String w = lex.best(p);
            if (w == null) return Integer.MIN_VALUE;
            int z = lex.zipf(w);
            if (p.length() == 1 && "aoe".indexOf(p.charAt(0)) < 0) return Integer.MIN_VALUE;
            if (p.length() <= 2 && z < shortFloor) return Integer.MIN_VALUE;
            if (z < floor) return Integer.MIN_VALUE;
            min = Math.min(min, z);
        }
        return min;
    }

    /**
     * Words at most two mistakes away (letters wrong, missing, extra or swapped; diacritics are free), closest
     * and most frequent first. Only words with the same first letter and a similar length are compared, so it stays fast.
     */
    public List<String> nearest(String skel, Lexicon lex, int n) {
        List<String> out = new ArrayList<>();
        if (lex == null || skel.length() < 4) return out;
        List<int[]> found = new ArrayList<>(); // {index, distance}
        char first = skel.charAt(0);
        for (int i = 0; i < lex.size(); i++) {
            String s = lex.skeletonAt(i);
            if (s.isEmpty() || s.charAt(0) != first || Math.abs(s.length() - skel.length()) > 2 || lex.zipfAt(i) < 300) continue;
            int d = distance(skel, s, 2);
            if (d <= 2 && d > 0) found.add(new int[]{i, d});
        }
        found.sort((x, y) -> x[1] != y[1] ? x[1] - y[1] : lex.zipfAt(y[0]) - lex.zipfAt(x[0]));
        for (int k = 0; k < Math.min(n, found.size()); k++) out.add(lex.wordAt(found.get(k)[0]));
        return out;
    }

    /** Edit distance with swaps (optimal string alignment), giving up above max. */
    static int distance(String a, String b, int max) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > max) return max + 1;
        int[] prev2 = new int[m + 1], prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) v = Math.min(v, prev2[j - 2] + 1);
                cur[j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > max) return max + 1;
            int[] t = prev2; prev2 = prev; prev = cur; cur = t;
        }
        return prev[m];
    }

    /** For the spell checker: a word that exists for you (lists, your dictionary, what you use, a common word of the other language). */
    public synchronized boolean known(String word, Lexicon primary, Lexicon secondary) {
        String lower = word.toLowerCase(Locale.ROOT);
        return userWords.contains(lower) || (primary != null && primary.zipf(lower) > 0)
                || (secondary != null && secondary.zipf(lower) >= OTHER_KNOWN)
                || (personal != null && personal.zipf(lower) > 0) || (user != null && user.zipf(lower) > 0);
    }

    /** Up to three suggestions for the word being typed; the middle one is what autocorrect would pick. */
    public synchronized List<String> suggest(String typed, Lexicon primary, Lexicon secondary) {
        List<String> out = new ArrayList<>();
        if (primary == null || typed.isEmpty()) return out;
        String fix = correct(typed, primary, secondary);
        List<String> more = new ArrayList<>();
        if (personal != null) for (String m : personal.completions(typed, 2)) more.add(spelled(typed, m));
        if (user != null) for (String m : user.completions(typed, 2)) if (!more.contains(spelled(typed, m))) more.add(spelled(typed, m));
        // A word that does not exist: the closest real words first (up to two mistakes), then what it could become.
        if (typed.length() >= 4 && !known(typed, primary, secondary))
            for (String m : nearest(Lexicon.skeleton(typed), primary, 3)) if (!more.contains(m)) more.add(m);
        for (String m : primary.completions(typed, 4)) if (!more.contains(m)) more.add(m);
        if (secondary != null) for (String s : secondary.completions(typed, 2)) if (!more.contains(s)) more.add(s);
        String lower = typed.toLowerCase(Locale.ROOT);
        String middle = fix != null ? fix : !more.isEmpty() ? withCase(typed, more.get(0)) : typed;
        String left = fix != null ? typed : null;
        String right = null;
        for (String m : more) {
            String c = withCase(typed, m);
            if (c.equals(middle) || c.equals(left)) continue;
            if (left == null && !m.equals(lower)) left = c;
            else if (right == null) right = c;
            if (left != null && right != null) break;
        }
        if (left != null) out.add(left);
        out.add(middle);
        if (right != null) out.add(right);
        return out;
    }

    private int lastScore;

    /** Best word one edit away (on skeletons); swapsOnly for already-known words. Sets lastScore. */
    private String bestEdit(String skel, Lexicon lex, boolean swapsOnly) {
        boolean cyrillic = "ru".equals(lex.lang) || (!skel.isEmpty() && Character.UnicodeBlock.of(skel.charAt(0)) == Character.UnicodeBlock.CYRILLIC);
        String alphabet = cyrillic ? CYRILLIC : LATIN;
        String[] rows = cyrillic ? JCUKEN : QWERTY;
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        int n = skel.length();
        for (int i = 0; i + 1 < n; i++) { // swapped neighbours
            String c = skel.substring(0, i) + skel.charAt(i + 1) + skel.charAt(i) + skel.substring(i + 2);
            int s = score(c, lex, 40);
            if (s > bestScore) { bestScore = s; best = lex.best(c); }
        }
        if (!swapsOnly) {
            for (int i = 0; i < n; i++) { // a wrong letter: cheaper when it is next to the right one on the keyboard
                for (int k = 0; k < alphabet.length(); k++) {
                    char ch = alphabet.charAt(k);
                    if (ch == skel.charAt(i)) continue;
                    String c = skel.substring(0, i) + ch + skel.substring(i + 1);
                    int s = score(c, lex, adjacent(rows, skel.charAt(i), ch) ? 60 : 120);
                    if (s > bestScore) { bestScore = s; best = lex.best(c); }
                }
            }
            for (int i = 0; i < n && n > 2; i++) { // an extra letter
                String c = skel.substring(0, i) + skel.substring(i + 1);
                int s = score(c, lex, 90);
                if (s > bestScore) { bestScore = s; best = lex.best(c); }
            }
            for (int i = 0; i <= n; i++) { // a missing letter
                for (int k = 0; k < alphabet.length(); k++) {
                    String c = skel.substring(0, i) + alphabet.charAt(k) + skel.substring(i);
                    int s = score(c, lex, 90);
                    if (s > bestScore) { bestScore = s; best = lex.best(c); }
                }
            }
        }
        lastScore = best == null ? 0 : bestScore;
        return best;
    }

    private static int score(String candidateSkeleton, Lexicon lex, int penalty) {
        String w = lex.best(candidateSkeleton);
        return w == null ? Integer.MIN_VALUE : lex.zipf(w) - penalty;
    }

    private static boolean adjacent(String[] rows, char a, char b) {
        int[] pa = find(rows, a), pb = find(rows, b);
        return pa != null && pb != null && Math.abs(pa[0] - pb[0]) <= 1 && Math.abs(pa[1] - pb[1]) <= 1;
    }

    private static int[] find(String[] rows, char ch) {
        for (int r = 0; r < rows.length; r++) {
            int c = rows[r].indexOf(ch);
            if (c >= 0) return new int[]{r, c};
        }
        return null;
    }

    /** Keep how the word was typed: "Si" → "Și", "SI" → "ȘI". */
    public static String withCase(String typed, String word) {
        if (typed.length() > 1 && typed.equals(typed.toUpperCase(Locale.ROOT)) && !typed.equals(typed.toLowerCase(Locale.ROOT)))
            return word.toUpperCase(Locale.ROOT);
        if (!typed.isEmpty() && Character.isUpperCase(typed.charAt(0)))
            return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
        return word;
    }
}
