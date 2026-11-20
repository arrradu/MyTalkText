package ro.talktext;

import java.util.ArrayList;
import java.util.List;

/** The keyboard's clipboard history: newest first, no duplicates, at most 20 texts. Pure Java, tested without a phone. */
public final class ClipHistory {
    public static final int MAX = 20, MAX_CHARS = 5000;

    public static List<String> add(List<String> clips, String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return new ArrayList<>(clips);
        String t = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        out.add(t);
        for (String c : clips) if (!c.equals(t) && out.size() < MAX) out.add(c);
        return out;
    }

    private ClipHistory() {}
}
