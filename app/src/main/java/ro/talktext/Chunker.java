package ro.talktext;

/**
 * Decides where to cut the continuous recording into pieces for transcription.
 * Cuts only inside a pause, so no word is split. The first piece is short, so the
 * voice language command is known early; later pieces are long for better context.
 * Audio is never dropped: every frame goes into exactly one piece.
 */
public final class Chunker {
    public static final int FRAME_MS = 20;

    private static final int FIRST_MIN_MS = 6_000, FIRST_MAX_MS = 20_000;
    private static final int MIN_MS = 40_000, MAX_MS = 90_000;
    private static final int PAUSE_MS = 350, SHORT_PAUSE_MS = 150;
    private static final double MIN_THRESHOLD = 250;

    private static final double MAX_FLOOR = 800;

    private int index, frames, silentRun, speechFrames;
    // Background noise level: drops at once to any quieter frame, rises slowly, capped so loud speech is never "background".
    private double floor = 100;

    /** Feeds one 20 ms frame. Returns true when the piece should be closed after this frame. */
    public boolean feed(double rms) {
        if (rms < floor) floor = rms;
        else floor = Math.min(MAX_FLOOR, floor + (rms - floor) * 0.0005);
        double threshold = Math.max(MIN_THRESHOLD, floor * 3);

        frames++;
        if (rms < threshold) silentRun++;
        else {
            silentRun = 0;
            speechFrames++;
        }

        int ms = frames * FRAME_MS;
        int min = index == 0 ? FIRST_MIN_MS : MIN_MS;
        int max = index == 0 ? FIRST_MAX_MS : MAX_MS;
        int pause = ms >= (min + max) / 2 ? SHORT_PAUSE_MS : PAUSE_MS;
        return ms >= max || (ms >= min && silentRun * FRAME_MS >= pause);
    }

    /** Milliseconds of loud audio in the current piece; pieces with almost none are not sent (avoids invented text on silence). */
    public int speechMs() { return speechFrames * FRAME_MS; }

    public int pieceMs() { return frames * FRAME_MS; }

    public void next() {
        index++;
        frames = 0;
        silentRun = 0;
        speechFrames = 0;
    }

    public static double rms(short[] samples, int count) {
        if (count <= 0) return 0;
        double sum = 0;
        for (int i = 0; i < count; i++) sum += (double) samples[i] * samples[i];
        return Math.sqrt(sum / count);
    }
}
