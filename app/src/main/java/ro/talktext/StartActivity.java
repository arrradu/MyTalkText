package ro.talktext;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Invisible window used only when Android refuses to start the microphone from the bubble alone.
 * While it is visible the recording service may start; then it closes and you return to your field.
 */
public class StartActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        RecorderService.send(this, RecorderService.START);
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int waited;
            @Override public void run() {
                waited += 100;
                if (RecorderService.running || waited >= 1500) finish();
                else h.postDelayed(this, 100);
            }
        }, 100);
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
