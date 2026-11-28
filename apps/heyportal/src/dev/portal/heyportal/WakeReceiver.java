package dev.portal.heyportal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.PowerManager;
import android.util.Log;

/**
 * Signature-protected receiver for wake-word events pushed by RootDaemon (uid 0).
 * Plays the Nest-style rising chime earcon, briefly wakes the screen from Dream,
 * and notifies MainActivity if in foreground.
 */
public class WakeReceiver extends BroadcastReceiver {
    private static final String TAG = "HeyPortal_Wake";
    public static final String ACTION_WAKE_DETECTED = "dev.portal.heyportal.ACTION_WAKE_DETECTED";
    public static final String ACTION_LOCAL_UI_REFRESH = "dev.portal.heyportal.ACTION_LOCAL_UI_REFRESH";

    private static SoundPool sSoundPool;
    private static int sChimeId = -1;
    private static volatile boolean sChimeLoaded = false;
    private static volatile boolean sPlayOnLoad = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_WAKE_DETECTED.equals(intent.getAction())) {
            return;
        }
        boolean playChime = intent.getBooleanExtra("chime", true);
        boolean wakeScreen = intent.getBooleanExtra("wakeScreen", true);

        if (wakeScreen) {
            wakeDisplayBriefly(context);
        }
        if (playChime) {
            playChime(context);
        }

        // Notify in-process MainActivity UI immediately without exposing wake receiver publicly.
        Intent ui = new Intent(ACTION_LOCAL_UI_REFRESH);
        ui.setPackage(context.getPackageName());
        context.sendBroadcast(ui);
    }

    @SuppressWarnings("deprecation")
    private static void wakeDisplayBriefly(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "heyportal:wake_screen"
                );
                wl.acquire(3000L);
            }
        } catch (Throwable t) {
            Log.w(TAG, "wakeDisplayBriefly failed", t);
        }
    }

    public static synchronized void preloadChime(Context context) {
        if (sSoundPool != null) return;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            sSoundPool = new SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(attrs)
                    .build();
            sSoundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
                if (status == 0) {
                    sChimeLoaded = true;
                    if (sPlayOnLoad) {
                        sPlayOnLoad = false;
                        sp.play(sampleId, 1.0f, 1.0f, 1, 0, 1.0f);
                    }
                }
            });
            sChimeId = sSoundPool.load(context.getApplicationContext(), R.raw.chime, 1);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to preload chime SoundPool", t);
        }
    }

    public static synchronized void playChime(Context context) {
        if (sSoundPool == null) {
            sPlayOnLoad = true;
            preloadChime(context);
            return;
        }
        if (sChimeLoaded && sChimeId != -1) {
            sSoundPool.play(sChimeId, 1.0f, 1.0f, 1, 0, 1.0f);
        } else {
            sPlayOnLoad = true;
        }
    }
}
