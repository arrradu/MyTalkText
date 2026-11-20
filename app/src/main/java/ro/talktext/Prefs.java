package ro.talktext;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Prefs {
    static final String DEFAULT_STT = "gpt-transcribe";
    static final String DEFAULT_CLAUDE = "claude-haiku-5-5";

    static SharedPreferences get(Context c) { return c.getSharedPreferences("prefs", 0); }

    static String sttModel(Context c) {
        String v = get(c).getString("stt_model", "").trim();
        return v.startsWith("gpt-") || v.startsWith("whisper-") ? v : DEFAULT_STT;
    }
    static final String[][] CLAUDE_MODELS = {
            {"claude-haiku-5-5", "Haiku 5.5", "rapid, ieftin"},
            {"claude-sonnet-5-5", "Sonnet 5.5", "mai atent"},
            {"claude-opus-5-5", "Opus 5.5", "cel mai bun, mai lent"}};

    /** Always a valid API model ID, also for hand-typed values like "opus 5.5" from older versions. */
    static String claudeModel(Context c) {
        String v = get(c).getString("model", "").trim().toLowerCase(java.util.Locale.ROOT);
        for (String[] m : CLAUDE_MODELS) if (m[0].equals(v)) return v;
        if (v.contains("opus")) return "claude-opus-5-5";
        if (v.contains("sonnet")) return "claude-sonnet-5-5";
        return DEFAULT_CLAUDE;
    }
    static boolean polish(Context c) { return get(c).getBoolean("polish", true); }
    static String dictionary(Context c) { return get(c).getString("dictionary", ""); }
    static String snippets(Context c) { return get(c).getString("snippets", ""); }
    /** "Despre mine": the user's notes about themselves (company, car, people) that Claude uses to understand what they mean. */
    static String about(Context c) { return get(c).getString("about", ""); }

    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) if (!line.trim().isEmpty()) out.add(line.trim());
        return out;
    }

    static boolean excluded(Context c, CharSequence pkg) {
        if (pkg == null) return false;
        for (String line : lines(get(c).getString("excluded", ""))) if (line.contentEquals(pkg)) return true;
        return false;
    }

    static String language(Context c) { return get(c).getString("lang", "ro"); }

    /** Last result stays only on the phone, so nothing is lost if insertion fails. */
    static void last(Context c, String text, String raw) {
        get(c).edit().putString("last_text", text).putString("last_raw", raw).putLong("last_time", System.currentTimeMillis()).apply();
    }

    // ---- history and totals, kept only on the phone ----

    static final int HISTORY_MAX = 40;

    static synchronized void record(Context c, String text, long spokenMs) {
        SharedPreferences p = get(c);
        int words = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
        JSONArray old = history(c), next = new JSONArray();
        try {
            next.put(new JSONObject().put("t", System.currentTimeMillis()).put("text", text));
            for (int i = 0; i < old.length() && next.length() < HISTORY_MAX; i++) next.put(old.get(i));
        } catch (JSONException ignored) { }
        p.edit().putString("history", next.toString())
                .putLong("total_words", p.getLong("total_words", 0) + words)
                .putLong("total_ms", p.getLong("total_ms", 0) + spokenMs)
                .putLong("total_count", p.getLong("total_count", 0) + 1).apply();
    }

    static JSONArray history(Context c) {
        try {
            return new JSONArray(get(c).getString("history", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static void clearHistory(Context c) { get(c).edit().remove("history").apply(); }

    private static String orDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private Prefs() {}
}
