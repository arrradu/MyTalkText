package ro.talktext;

/** Adds the spaces needed when dictated text lands between existing characters. */
public final class Spacing {
    public static String fit(String before, String text, String after) {
        String out = text;
        if (out.isEmpty()) return out;
        if (!before.isEmpty() && !Character.isWhitespace(before.charAt(before.length() - 1))
                && !opensWithPunctuation(out) && !Character.isWhitespace(out.charAt(0))) out = " " + out;
        if (!after.isEmpty() && !Character.isWhitespace(after.charAt(0))
                && ".,!?;:)]}…".indexOf(after.charAt(0)) < 0
                && !Character.isWhitespace(out.charAt(out.length() - 1))) out = out + " ";
        return out;
    }

    /**
     * How many characters at the end of the text the last n words take, with the spaces after them
     * ("hello world " and 1 → "world ", 6). For swipe-to-delete on ⌫.
     */
    public static int wordsBack(CharSequence text, int n) {
        int i = text.length();
        for (int w = 0; w < n && i > 0; w++) {
            while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
            while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
        }
        return text.length() - i;
    }

    private static boolean opensWithPunctuation(String s) {
        return ".,!?;:)]}…".indexOf(s.charAt(0)) >= 0;
    }

    private Spacing() {}
}
