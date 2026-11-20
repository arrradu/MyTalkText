package ro.talktext;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.ByteArrayOutputStream;

/**
 * Continuous microphone recording in a foreground service. One AudioRecord for the whole dictation:
 * there are no restarts between pieces, so nothing said is lost between them.
 */
public class RecorderService extends Service {
    static final String START = "ro.talktext.START", STOP = "ro.talktext.STOP", CANCEL = "ro.talktext.CANCEL";
    private static final int NOTIFICATION = 7;
    private static final long MAX_MS = 30 * 60_000L;
    private static final int SILENT_CHECK_MS = 1200;

    static volatile boolean running;
    private volatile boolean recording, cancelled;
    private volatile Thread thread;
    private PowerManager.WakeLock wake;

    /** False when Android refuses to start the service from the background. */
    static boolean send(android.content.Context c, String action) {
        try {
            c.startService(new Intent(c, RecorderService.class).setAction(action));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (START.equals(action)) begin();
        else if (STOP.equals(action) || CANCEL.equals(action)) {
            if (thread == null) {
                // Microphone never started: nothing recorded, so Stop and Cancel both end the dictation.
                Session s = Session.active();
                if (s != null && s.phase() == Session.Phase.RECORDING) s.cancel();
                stopSelf();
            } else {
                if (CANCEL.equals(action)) cancelled = true;
                recording = false;
            }
        }
        return START_NOT_STICKY;
    }

    private void begin() {
        Session session = Session.active();
        if (session == null || session.phase() != Session.Phase.RECORDING || thread != null) {
            if (thread == null) stopSelf();
            return;
        }
        try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (RuntimeException e) {
            // Android refused microphone access from the background; the bubble falls back to a short visible start.
            stopSelf();
            blocked(session);
            return;
        }
        wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "talktext:rec");
        wake.acquire(MAX_MS + 60_000L);
        recording = true;
        cancelled = false;
        running = true;
        thread = new Thread(() -> record(session), "talktext-rec");
        thread.start();
    }

    private void record(Session session) {
        int frame = Wav.RATE * Chunker.FRAME_MS / 1000;
        int buffer = Math.max(AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), Wav.RATE * 2);
        AudioRecord rec = null;
        boolean blocked = false;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("init");
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("start");

            Chunker chunker = new Chunker();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream(Wav.RATE * 2 * 30);
            short[] samples = new short[frame];
            byte[] bytes = new byte[frame * 2];
            long started = System.currentTimeMillis();
            boolean heard = false;
            int frames = 0;
            double peak = 0;
            while (recording && !session.broken() && System.currentTimeMillis() - started < MAX_MS) {
                int n = rec.read(samples, 0, frame);
                if (n < 0) break; // microphone lost (call, other app): keep what was said so far
                if (n == 0) {
                    Thread.sleep(10);
                    continue;
                }
                boolean allZero = true;
                for (int i = 0; i < n; i++) {
                    bytes[2 * i] = (byte) samples[i];
                    bytes[2 * i + 1] = (byte) (samples[i] >> 8);
                    if (samples[i] != 0) allZero = false;
                }
                pcm.write(bytes, 0, n * 2);
                double rms = Chunker.rms(samples, n);
                frames++;
                if (!allZero && !heard) {
                    heard = true;
                    session.level(0.01f); // first real sound: the bubble turns red, speak now
                }
                if (!heard && frames * Chunker.FRAME_MS >= SILENT_CHECK_MS) {
                    blocked = true; // Android delivers pure zeros when it silently blocks the microphone
                    break;
                }
                peak = Math.max(peak, rms);
                if (heard && frames % 5 == 0) {
                    session.level((float) Math.min(1, peak / 6000));
                    peak = 0;
                }
                if (chunker.feed(rms)) {
                    session.piece(Wav.wrap(pcm.toByteArray(), pcm.size()), chunker.speechMs());
                    pcm.reset();
                    chunker.next();
                }
            }
            if (!blocked && !cancelled) {
                if (pcm.size() > 0) session.piece(Wav.wrap(pcm.toByteArray(), pcm.size()), chunker.speechMs());
                session.recordingDone();
            }
        } catch (Exception e) {
            if (!cancelled) session.micFailed("Microfonul nu a pornit (" + e.getMessage() + "). Poate îl folosește alt program.");
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
            }
            if (cancelled) session.cancel();
            if (wake != null && wake.isHeld()) wake.release();
            running = false;
            thread = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        if (blocked) blocked(session);
    }

    private void blocked(Session session) {
        InsertService service = InsertService.instance;
        // The invisible start window only helps the bubble: it would close the keyboard and end a keyboard dictation.
        if (!session.trampolineTried && service != null && service.owns(session)) {
            session.trampolineTried = true;
            service.startViaActivity();
        } else {
            session.micFailed("Android nu permite microfonul din fundal. Deschide TalkText o dată și încearcă din nou.");
        }
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("rec", "Dictare", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RecorderService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 2, new Intent(this, RecorderService.class).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "rec")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("TalkText ascultă")
                .setContentText("Atinge bula sau Gata când termini.")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Gata", stop).build())
                .addAction(new Notification.Action.Builder(null, "Anulează", cancel).build())
                .build();
    }
}
