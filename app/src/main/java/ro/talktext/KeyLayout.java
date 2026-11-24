package ro.talktext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Key rows of the TalkText keyboard, Gboard-style. Pure Java, so it is tested without a phone. */
public final class KeyLayout {
    public static final int CHAR = 0, SHIFT = -1, DELETE = -2, ENTER = -3, SYMBOLS = -4, LETTERS = -5, MORE = -6, GLOBE = -7, SPACE = -8, GAP = -9;
    public static final int PAGE_LETTERS = 0, PAGE_SYMBOLS = 1, PAGE_MORE = 2;

    public static final class Key {
        public final String label, out, hint;
        public final int code;
        public final float units;
        public final String[] alts;
        public float x, y, w, h; // set by the view when it is laid out

        Key(String label, String out, int code, float units, String hint, String[] alts) {
            this.label = label;
            this.out = out;
            this.code = code;
            this.units = units;
            this.hint = hint;
            this.alts = alts;
        }

        public boolean isChar() { return code == CHAR; }

        /** A single letter, so Shift changes it. */
        public boolean isLetter() { return code == CHAR && out.length() == 1 && Character.isLetter(out.charAt(0)); }

        public boolean contains(float px, float py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }

    // Long-press alternates: Romanian diacritics first, then the rest.
    private static final String[][] LATIN_ALTS = {
            {"a", "ă", "â", "á", "à", "ä"}, {"e", "é", "è", "ê", "ë", "€"}, {"i", "î", "í", "ì", "ï"},
            {"o", "ö", "ó", "ò", "ô"}, {"u", "ü", "ú", "ù", "û"}, {"s", "ș", "ß", "ş"}, {"t", "ț", "ţ"},
            {"c", "ç", "č", "ć"}, {"n", "ñ", "ń"}, {"z", "ž", "ź", "ż"}, {"y", "ý", "ÿ"}, {"l", "ł"}, {"d", "đ"}, {"g", "ğ"}};
    private static final String[][] CYRILLIC_ALTS = {{"е", "ё"}, {"ь", "ъ"}, {"и", "і", "ї"}, {"г", "ґ"}, {"э", "є"}};
    // Small symbols in the key corners, as on Gboard; a long press types them.
    private static final String ROW2_HINTS = "@#$_&-+()", ROW3_HINTS = "*\"':;!?";
    private static final String[] DOT_ALTS = {",", "?", "!", ":", ";", "-", "…", "'", "\"", "@"};

    public static List<List<Key>> rows(int page, boolean cyrillic) {
        if (page == PAGE_SYMBOLS) return symbols();
        if (page == PAGE_MORE) return more();
        return cyrillic ? cyrillic() : latin();
    }

    private static List<List<Key>> latin() {
        List<List<Key>> r = new ArrayList<>();
        r.add(letters("qwertyuiop", "1234567890", LATIN_ALTS));
        List<Key> two = new ArrayList<>();
        two.add(gap(0.5f));
        two.addAll(letters("asdfghjkl", ROW2_HINTS, LATIN_ALTS));
        two.add(gap(0.5f));
        r.add(two);
        List<Key> three = new ArrayList<>();
        three.add(special("⇧", SHIFT, 1.5f));
        three.addAll(letters("zxcvbnm", ROW3_HINTS, LATIN_ALTS));
        three.add(special("⌫", DELETE, 1.5f));
        r.add(three);
        r.add(bottom("?123", SYMBOLS, 4));
        return r;
    }

    private static List<List<Key>> cyrillic() {
        List<List<Key>> r = new ArrayList<>();
        r.add(letters("йцукенгшщзх", "1234567890", CYRILLIC_ALTS));
        r.add(letters("фывапролджэ", "@#$_&-+()\"'", CYRILLIC_ALTS));
        List<Key> three = new ArrayList<>();
        three.add(special("⇧", SHIFT, 1));
        three.addAll(letters("ячсмитьбю", "*\":;!?,.-", CYRILLIC_ALTS));
        three.add(special("⌫", DELETE, 1));
        r.add(three);
        r.add(bottom("?123", SYMBOLS, 5));
        return r;
    }

    private static List<List<Key>> symbols() {
        List<List<Key>> r = new ArrayList<>();
        r.add(chars("1234567890", new String[][]{{"1", "¹", "½", "⅓", "¼"}, {"2", "²", "⅔"}, {"3", "³", "¾"}, {"4", "⁴"}, {"0", "°", "ⁿ", "∅"}}));
        r.add(chars("@#$_&-+()/", new String[][]{{"$", "€", "£", "¥", "¢"}, {"-", "–", "—", "·"}, {"+", "±"}, {"(", "<", "{", "["}, {")", ">", "}", "]"}}));
        List<Key> three = new ArrayList<>();
        three.add(special("=\\<", MORE, 1.5f));
        three.addAll(chars("*\"':;!?", new String[][]{{"\"", "„", "”", "«", "»"}, {"'", "‘", "’", "‚"}, {"!", "¡"}, {"?", "¿"}}));
        three.add(special("⌫", DELETE, 1.5f));
        r.add(three);
        r.add(bottom("ABC", LETTERS, 4));
        return r;
    }

    private static List<List<Key>> more() {
        List<List<Key>> r = new ArrayList<>();
        r.add(chars("~`|•√π÷×¶∆", null));
        r.add(chars("£¢€¥^°={}\\", null));
        List<Key> three = new ArrayList<>();
        three.add(special("?123", SYMBOLS, 1.5f));
        three.addAll(chars("%©®™✓[]", null));
        three.add(special("⌫", DELETE, 1.5f));
        r.add(three);
        r.add(bottom("ABC", LETTERS, 4));
        return r;
    }

    private static List<Key> bottom(String switchLabel, int switchCode, float space) {
        List<Key> row = new ArrayList<>();
        row.add(special(switchLabel, switchCode, 1.5f));
        row.add(new Key(",", ",", CHAR, 1, null, new String[]{",", "'", "\"", "@"}));
        row.add(special("🌐", GLOBE, 1));
        row.add(special("", SPACE, space));
        row.add(new Key(".", ".", CHAR, 1, null, DOT_ALTS));
        row.add(special("↵", ENTER, 1.5f));
        return row;
    }

    private static List<Key> letters(String letters, String hints, String[][] altTable) {
        List<Key> row = new ArrayList<>();
        int[] cps = letters.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            String l = new String(Character.toChars(cps[i]));
            String hint = hints != null && i < hints.length() ? String.valueOf(hints.charAt(i)) : null;
            List<String> alts = new ArrayList<>();
            if (hint != null) alts.add(hint);
            for (String[] a : altTable) if (a[0].equals(l)) alts.addAll(Arrays.asList(a).subList(1, a.length));
            row.add(new Key(l, l, CHAR, 1, hint, alts.isEmpty() ? null : alts.toArray(new String[0])));
        }
        return row;
    }

    private static List<Key> chars(String chars, String[][] altTable) {
        return letters(chars, null, altTable == null ? new String[0][] : altTable);
    }

    private static Key special(String label, int code, float units) { return new Key(label, null, code, units, null, null); }

    private static Key gap(float units) { return new Key("", null, GAP, units, null, null); }

    public static float units(List<Key> row) {
        float u = 0;
        for (Key k : row) u += k.units;
        return u;
    }

    /**
     * The space bar takes the lower part of the letter keys right above it (c v b n m on the Latin layout):
     * thumbs aiming at the space land a little high, and "merge b chiar" turns into "mergebchiar".
     * Gboard does the same.
     */
    public static final float SPACE_REACH = 0.3f;

    /** The key under a touch. Touches on the gaps between keys hit the nearest key. Keys must be laid out (x, y, w, h). */
    public static Key keyAt(List<List<Key>> rows, float x, float y) {
        Key space = null;
        for (List<Key> row : rows) for (Key k : row) if (k.code == SPACE) space = k;
        if (space != null && x >= space.x && x < space.x + space.w && y < space.y && y >= space.y - SPACE_REACH * space.h) {
            Key above = nearest(rows, x, y);
            if (above != null && above.isLetter()) return space;
        }
        return nearest(rows, x, y);
    }

    private static Key nearest(List<List<Key>> rows, float x, float y) {
        Key best = null;
        float bestDist = Float.MAX_VALUE;
        for (List<Key> row : rows)
            for (Key k : row) {
                if (k.code == GAP) continue;
                if (k.contains(x, y)) return k;
                float dx = Math.max(0, Math.max(k.x - x, x - (k.x + k.w))), dy = Math.max(0, Math.max(k.y - y, y - (k.y + k.h)));
                float d = dx * dx + dy * dy;
                if (d < bestDist) { bestDist = d; best = k; }
            }
        return best;
    }

    /** What a letter key types (and shows) with Shift on. */
    public static String shifted(String s, boolean upper) {
        return upper ? s.toUpperCase(Locale.ROOT) : s;
    }

    private KeyLayout() {}
}
