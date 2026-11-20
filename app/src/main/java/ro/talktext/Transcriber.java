package ro.talktext;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** OpenAI /v1/audio/transcriptions. gpt-transcribe takes languages[] and keywords[]; older models take one language. */
final class Transcriber {
    static final class Result {
        final String text, language;
        Result(String text, String language) { this.text = text; this.language = language; }
    }

    static Result run(Context c, File wav, List<String> languages, String previous) throws Http.Failure {
        String key = Vault.read(c, Vault.OPENAI);
        if (key.isEmpty()) throw new Http.Failure("Lipsește cheia OpenAI (transcriere). Deschide TalkText.");
        String model = Prefs.sttModel(c);
        boolean modern = model.startsWith("gpt-transcribe");
        List<String> words = new java.util.ArrayList<>(Prefs.lines(Prefs.dictionary(c)));
        if (PersonalStore.enabled(c)) // names you often write (Artinox, Lynk…) help the transcriber spell them
            for (String name : PersonalStore.get(c).topNames(20, 3)) if (!words.contains(name) && words.size() < 50) words.add(name);

        String boundary = "talktext" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "model", model);
        if (modern) {
            for (String l : languages) field(body, boundary, "languages[]", l);
            int n = 0;
            for (String w : words) {
                String k = w.replaceAll("[<>\\r\\n]", "").trim();
                if (k.isEmpty() || k.length() > 60) continue;
                field(body, boundary, "keywords[]", k);
                if (++n == 50) break;
            }
        } else if (languages.size() == 1) {
            field(body, boundary, "language", languages.get(0));
        }
        // Only this step hears the voice, so punctuation from intonation is asked for here; Claude later keeps it.
        StringBuilder prompt = new StringBuilder(intonation(languages.size() == 1 ? languages.get(0) : Prefs.language(c)));
        if (!modern && !words.isEmpty()) prompt.append("\n").append(String.join(", ", words)).append(".");
        if (previous != null && !previous.isEmpty())
            prompt.append("\n").append(previous.length() > 300 ? previous.substring(previous.length() - 300) : previous);
        if (prompt.length() > 0) field(body, boundary, "prompt", prompt.toString().trim());
        try {
            write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n");
            body.write(Files.readAllBytes(wav.toPath()));
            write(body, "\r\n--" + boundary + "--\r\n");
        } catch (java.io.IOException e) {
            throw new Http.Failure("Audio salvat necitibil: " + e.getMessage());
        }

        String raw = Http.post("https://api.openai.com/v1/audio/transcriptions",
                Map.of("Authorization", "Bearer " + key, "Content-Type", "multipart/form-data; boundary=" + boundary),
                body.toByteArray(), "OpenAI");
        try {
            JSONObject json = new JSONObject(raw);
            String language = null;
            JSONArray langs = json.optJSONArray("languages");
            if (langs != null && langs.length() > 0) language = langs.getJSONObject(0).optString("code", null);
            else if (json.has("language")) language = json.optString("language", null);
            return new Result(json.optString("text", "").trim(), normalize(language));
        } catch (Exception e) {
            throw new Http.Failure("OpenAI: răspuns neașteptat.");
        }
    }

    /** Punctuation that follows how it was said, written in the dictation's language so the example style matches. */
    static String intonation(String language) {
        switch (language) {
            case "en":
                return "Dictation with full punctuation that follows the speaker's intonation: a question mark when the voice rises at the end "
                        + "(You sent the offer?), commas at short pauses, a period at longer pauses, an exclamation mark for emphasis (Great!).";
            case "ru":
                return "Диктовка с полной пунктуацией по интонации говорящего: вопросительный знак, когда голос повышается в конце "
                        + "(Ты отправил предложение?), запятые на коротких паузах, точка на длинных, восклицательный знак при эмфазе (Отлично!).";
            default:
                return "Dictare cu punctuație completă, după intonația vorbitorului: semnul întrebării când vocea urcă la final "
                        + "(Ai trimis oferta?), virgulă la pauzele scurte, punct la pauzele lungi, semnul exclamării la accent (Super!).";
        }
    }

    private static String normalize(String language) {
        if (language == null) return null;
        String l = language.toLowerCase(java.util.Locale.ROOT);
        if (l.startsWith("ro")) return "ro";
        if (l.startsWith("en")) return "en";
        if (l.startsWith("ru")) return "ru";
        return l;
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
    }

    private Transcriber() {}
}
