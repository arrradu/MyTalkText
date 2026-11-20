package ro.talktext;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps Personal in one private file (personal.txt), shared by the keyboard, the bubble and dictation.
 * All writes go through one background queue, are flushed to disk, then replace the old file in one step;
 * "forget everything" cancels any write still waiting, so nothing comes back.
 */
final class PersonalStore {
    private static Personal personal;
    private static Context app;
    private static int generation; // bumped by clear(): a write prepared before it is dropped
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService disk = Executors.newSingleThreadExecutor();
    private static final Runnable save = PersonalStore::saveNow;

    private static File file(Context c) { return new File(c.getFilesDir(), "personal.txt"); }

    static synchronized Personal get(Context c) {
        if (personal == null) {
            app = c.getApplicationContext();
            String text = null;
            try {
                File f = file(app);
                if (f.isFile()) text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (Exception ignored) { }
            personal = Personal.fromText(text);
        }
        return personal;
    }

    static boolean enabled(Context c) { return Prefs.get(c).getBoolean("kb_learn", true); }

    /** Learn from a whole text (a dictation) when learning is switched on. */
    static void learnText(Context c, String text) {
        if (!enabled(c)) return;
        get(c).learnText(text);
        saveSoon();
    }

    /** Writes a few seconds later, so fast typing does not write the file after every word. */
    static void saveSoon() {
        main.removeCallbacks(save);
        main.postDelayed(save, 15_000);
    }

    static void saveNow() {
        main.removeCallbacks(save);
        final int gen;
        synchronized (PersonalStore.class) { gen = generation; }
        disk.execute(() -> writeNow(gen));
    }

    private static void writeNow(int gen) {
        Personal p;
        Context c;
        synchronized (PersonalStore.class) {
            p = personal;
            c = app;
        }
        if (p == null || c == null) return;
        String text = p.toText();
        synchronized (PersonalStore.class) { if (gen != generation) return; } // forgotten meanwhile
        File tmp = new File(c.getFilesDir(), "personal.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync(); // on disk before it replaces the old file
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        synchronized (PersonalStore.class) {
            if (gen != generation) { tmp.delete(); return; }
            tmp.renameTo(file(c)); // atomic replace on Android: the old file stays until the new one is complete
        }
    }

    static void clear(Context c) {
        main.removeCallbacks(save);
        synchronized (PersonalStore.class) {
            generation++;
            get(c).clear();
        }
        disk.execute(() -> file(c.getApplicationContext()).delete());
    }

    private PersonalStore() {}
}
