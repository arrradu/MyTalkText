package ro.talktext;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One dictation: pieces of audio arrive while recording continues, are transcribed in order,
 * then the joined text goes through Claude and back to the listener for insertion.
 * Audio files stay on the phone until the transcription succeeded, so a failed dictation can be retried.
 */
final class Session {
    enum Phase { RECORDING, PROCESSING, DONE, FAILED, CANCELLED }

    interface Listener {
        void onPhase(Session s, Phase phase, String message);
        void onLevel(float level);
        void onResult(Session s, String text, String warning);
    }

    private static final int MIN_SPEECH_MS = 150;
    private static final List<String> ALL = Arrays.asList("ro", "en", "ru");
    private static Session active;

    final Context app;
    final File dir;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> texts = new ArrayList<>();
    private volatile Phase phase = Phase.RECORDING;
    private volatile boolean cancelled, saveFailed;
    private String language;          // decided by the first piece with speech; worker thread only
    volatile boolean learn = true;    // false for incognito fields: nothing from this dictation is learned
    private String translateTo;       // "Engleză, ..." / "Rusă, ...": write the result in this language; worker thread only
    private volatile String failure;
    private int pieces;               // recorder thread only
    private volatile long spokenMs;
    boolean trampolineTried;

    private Session(Context c, File dir, Listener listener) {
        this.app = c.getApplicationContext();
        this.dir = dir;
        this.listener = listener;
    }

    static synchronized Session start(Context c, Listener listener) {
        if (active != null && (active.phase == Phase.RECORDING || active.phase == Phase.PROCESSING)) return null;
        File dir = new File(c.getFilesDir(), "rec/" + System.currentTimeMillis());
        dir.mkdirs();
        active = new Session(c, dir, listener);
        return active;
    }

    static synchronized Session active() { return active; }

    Phase phase() { return phase; }

    // ---- called by the recorder thread ----

    void piece(byte[] wav, int speechMs) {
        if (cancelled || saveFailed) return;
        int index = pieces++;
        spokenMs += (wav.length - 44) / 32; // 16 kHz × 2 bytes = 32 bytes per ms
        File f = new File(dir, String.format(Locale.ROOT, "%03d_%d.wav", index, speechMs));
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(wav);
        } catch (Exception e) {
            // The recorder sees broken() and stops, so no later piece is saved past the gap.
            failure = "Nu pot salva audio (memorie plină?): " + e.getMessage();
            saveFailed = true;
            return;
        }
        worker.execute(() -> transcribe(index, f, speechMs));
    }

    /** Recording continues through network errors (audio is kept for retry), but not past lost audio. */
    boolean broken() { return saveFailed || cancelled; }

    void level(float level) {
        main.post(() -> listener.onLevel(level));
    }

    void recordingDone() {
        if (cancelled) return;
        set(Phase.PROCESSING, null);
        worker.execute(this::finish);
    }

    void micFailed(String message) {
        failure = message;
        cleanup();
        set(Phase.FAILED, message);
    }

    void cancel() {
        cancelled = true;
        worker.execute(this::cleanup);
        set(Phase.CANCELLED, "Anulat");
    }

    // ---- worker thread ----

    private void transcribe(int index, File f, int speechMs) {
        if (cancelled || failure != null) return;
        String text = "";
        if (speechMs >= MIN_SPEECH_MS) {
            try {
                text = language == null ? first(f) : Transcriber.run(app, f, List.of(language), tail()).text;
            } catch (Http.Failure e) {
                failure = e.getMessage();
                return;
            } catch (RuntimeException e) {
                failure = "Eroare internă la transcriere: " + e;
                return;
            }
        }
        while (texts.size() <= index) texts.add("");
        texts.set(index, text);
    }

    /** First piece: listen for "English" / "ruski" in any of the three languages, then use only that language. */
    private String first(File f) throws Http.Failure {
        Transcriber.Result r = Transcriber.run(app, f, ALL, null);
        Rules.Parsed p = Rules.parse(r.text, Prefs.language(app));
        if (!p.prefixed() && r.text.trim().split("\\s+").length < 2) return r.text; // a cough or "ăă": let the next piece decide
        language = p.language();
        translateTo = p.target();
        if (language.equals(r.language)) return p.text();
        Transcriber.Result again = Transcriber.run(app, f, List.of(language), null);
        return Rules.parse(again.text, language).text();
    }

    private String tail() {
        for (int i = texts.size() - 1; i >= 0; i--) if (!texts.get(i).isEmpty()) return texts.get(i);
        return null;
    }

    private void finish() {
        if (cancelled) return;
        if (failure != null) {
            String old = Prefs.get(app).getString("pending", null);
            if (old != null && !old.equals(dir.getAbsolutePath())) delete(new File(old));
            Prefs.get(app).edit().putString("pending", dir.getAbsolutePath()).putString("pending_error", failure).apply();
            set(Phase.FAILED, failure + "\nAudio păstrat: deschide TalkText → Reîncearcă.");
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (String t : texts) if (!t.isEmpty()) joined.append(joined.length() == 0 ? "" : " ").append(t);
        String raw = joined.toString().trim();
        if (raw.isEmpty()) {
            cleanup();
            set(Phase.FAILED, "Nu am auzit nimic.");
            return;
        }
        String out = raw, warning = null;
        if (translateTo != null) { // asked for by voice: translate even if correction is switched off
            if (!Vault.has(app, Vault.ANTHROPIC)) warning = "Lipsește cheia Claude, așa că textul n-a putut fi tradus.";
            else try {
                out = ClaudeClient.translateDictation(app, language == null ? Prefs.language(app) : language, translateTo, raw);
            } catch (Http.Failure | RuntimeException e) {
                warning = e.getMessage() + ". Am pus textul netradus.";
            }
        } else if (Prefs.polish(app)) {
            if (!Vault.has(app, Vault.ANTHROPIC)) warning = "Lipsește cheia Claude, așa că textul a rămas necorectat.";
            else try {
                out = ClaudeClient.polish(app, language == null ? Prefs.language(app) : language, raw);
            } catch (Http.Failure | RuntimeException e) {
                warning = e.getMessage() + ". Textul a fost pus necorectat.";
            }
        }
        if (cancelled) return;
        Prefs.last(app, out, raw);
        if (translateTo == null && learn) PersonalStore.learnText(app, out); // your own words, in your own language
        Prefs.record(app, out, spokenMs);
        // No pop-up over the app you are writing in: a failed correction shows as a card on TalkText's home page.
        SharedPreferences.Editor edit = Prefs.get(app).edit().remove("pending").remove("pending_error");
        if (warning == null) edit.remove("polish_warning");
        else edit.putString("polish_warning", warning);
        edit.apply();
        cleanup();
        String text = out;
        String said = translateTo != null ? warning : null; // a translation you asked for by voice and did not get: say so now
        phase = Phase.DONE;
        main.post(() -> { if (!cancelled) listener.onResult(this, text, said); });
    }

    private void cleanup() { delete(dir); }

    private static void delete(File folder) {
        File[] files = folder.listFiles();
        if (files != null) for (File f : files) f.delete();
        folder.delete();
    }

    private void set(Phase p, String message) {
        phase = p;
        main.post(() -> listener.onPhase(this, p, message));
    }

    /** Re-runs a dictation whose transcription failed, from the audio kept on the phone. */
    static synchronized Session retry(Context c, Listener listener) {
        String path = Prefs.get(c).getString("pending", null);
        if (path == null || !new File(path).isDirectory()) return null;
        if (active != null && (active.phase == Phase.RECORDING || active.phase == Phase.PROCESSING)) return null;
        Session s = new Session(c, new File(path), listener);
        File[] files = s.dir.listFiles((d, n) -> n.matches("\\d{3}_\\d+\\.wav"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files);
        s.phase = Phase.PROCESSING;
        active = s;
        for (File f : files) {
            String name = f.getName();
            int index = Integer.parseInt(name.substring(0, 3));
            int speech = Integer.parseInt(name.substring(4, name.length() - 4));
            s.spokenMs += (f.length() - 44) / 32;
            s.worker.execute(() -> s.transcribe(index, f, speech));
        }
        s.worker.execute(s::finish);
        return s;
    }
}
