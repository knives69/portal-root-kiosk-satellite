package dev.portal.heyportal;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Method;
import java.util.List;

/**
 * FgTracker — Tracks foreground windows, yields/monitors speech dictation,
 * and auto-dismisses Assistant overlays after an idle timeout (Nest Hub style 15s).
 * Ported directly from dev.nuc.okgoogle for identical smart-display behavior.
 */
public class FgTracker extends AccessibilityService {
    private static final String TAG = "HeyPortal_Fg";
    private static final String GSA = "com.google.android.googlequicksearchbox";
    public static final String PREFS_NAME = "heyportal_prefs";
    public static final String PREF_AUTO_DISMISS = "auto_dismiss";
    private static final long AUTO_DISMISS_DELAY_MS = 15000; // 15 seconds idle timeout (Google Nest Hub standard)

    static volatile FgTracker instance;
    static volatile String foregroundPackage = "";
    static volatile String foregroundClass = "";
    static volatile boolean voiceInputActive = false;
    private static volatile boolean autoDismissScheduled = false;
    private static volatile boolean dismissedForCurrentSession = false;
    private static volatile int speechPostponeCount = 0;
    private static volatile boolean wasSpeaking = false;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable autoDismissRunnable = new Runnable() {
        @Override
        public void run() {
            triggerAutoDismiss();
        }
    };

    public static boolean isConnected() {
        return instance != null;
    }

    public static void setEnabled(boolean enabled) {
        if (!enabled) {
            cancelAutoDismiss();
        }
    }

    public static void ensureAccessibilityEnabled(Context ctx) {
        try {
            String serviceName = ctx.getPackageName() + "/" + FgTracker.class.getName();
            String enabledServices = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null || !enabledServices.contains(serviceName)) {
                String updated = (enabledServices == null || enabledServices.isEmpty())
                        ? serviceName : enabledServices + ":" + serviceName;
                Settings.Secure.putString(ctx.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
                Settings.Secure.putInt(ctx.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Log.i(TAG, "Auto-enabled accessibility service: " + serviceName);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not write secure settings from context: " + t.getMessage());
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "FgTracker accessibility service connected");
    }

    @Override
    public void onDestroy() {
        cancelAutoDismiss();
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (pkg != null) foregroundPackage = pkg.toString();
            if (cls != null) foregroundClass = cls.toString();
            updateState();
        } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // User intentionally clicked a button or card: reset the timer
            resetAutoDismissTimer();
        }
    }

    private static void updateState() {
        if (instance != null) {
            try {
                AccessibilityNodeInfo root = instance.getRootInActiveWindow();
                if (root != null && root.getPackageName() != null) {
                    foregroundPackage = root.getPackageName().toString();
                }
            } catch (Throwable ignored) { }
        }

        boolean isVoice = checkIsVoice(foregroundPackage, foregroundClass);
        if (isVoice != voiceInputActive) {
            voiceInputActive = isVoice;
            Log.i(TAG, "voiceInputActive=" + voiceInputActive + " (pkg=" + foregroundPackage + " cls=" + foregroundClass + ")");
            if (!voiceInputActive && GSA.equalsIgnoreCase(foregroundPackage)) {
                // Speech recognition completed and Assistant result/suggestions card is displayed:
                // reset the 15s timer so the user has the full window to read and interact.
                resetAutoDismissTimer();
            }
        }

        checkAssistantOverlayState();
    }

    public static void onNewVoiceInvocation() {
        dismissedForCurrentSession = false;
        autoDismissScheduled = false;
        speechPostponeCount = 0;
        wasSpeaking = false;
    }

    private static void checkAssistantOverlayState() {
        if (instance == null) return;
        boolean isAssistantOverlay = GSA.equalsIgnoreCase(foregroundPackage);

        if (isAssistantOverlay) {
            scheduleAutoDismiss();
        } else {
            dismissedForCurrentSession = false;
            speechPostponeCount = 0;
            wasSpeaking = false;
            cancelAutoDismiss();
        }
    }

    public static void resetAutoDismissTimer() {
        if (instance == null) return;
        if (!GSA.equalsIgnoreCase(foregroundPackage)) return;
        if (dismissedForCurrentSession) return;
        autoDismissScheduled = false;
        speechPostponeCount = 0;
        wasSpeaking = false;
        mainHandler.removeCallbacks(autoDismissRunnable);
        scheduleAutoDismiss();
    }

    public static void scheduleAutoDismiss() {
        if (instance == null) return;
        if (dismissedForCurrentSession) {
            return; // Already fired BACK once this turn; do not re-arm and close foreground apps
        }
        SharedPreferences sp = instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = sp.getBoolean(PREF_AUTO_DISMISS, true);
        if (!enabled) return;

        if (autoDismissScheduled) {
            return; // Already armed! Do not reset countdown on background polling or subtle UI animations
        }

        autoDismissScheduled = true;
        Log.i(TAG, "scheduleAutoDismiss: armed for " + AUTO_DISMISS_DELAY_MS + "ms");
        mainHandler.removeCallbacks(autoDismissRunnable);
        mainHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_DELAY_MS);
    }

    public static void cancelAutoDismiss() {
        if (autoDismissScheduled) {
            autoDismissScheduled = false;
            mainHandler.removeCallbacks(autoDismissRunnable);
            Log.i(TAG, "cancelAutoDismiss: disarmed");
        }
    }

    private static boolean isAssistantSpeaking() {
        if (instance == null) return false;
        AudioManager am = (AudioManager) instance.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;

        boolean gsaTrackFound = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                List<AudioPlaybackConfiguration> configs = am.getActivePlaybackConfigurations();
                if (configs != null && !configs.isEmpty()) {
                    int gsaUid = -1;
                    try {
                        gsaUid = instance.getPackageManager().getPackageUid(GSA, 0);
                    } catch (Throwable ignored) { }

                    if (gsaUid != -1) {
                        for (AudioPlaybackConfiguration cfg : configs) {
                            try {
                                Method getClientUid = cfg.getClass().getDeclaredMethod("getClientUid");
                                Method getPlayerState = cfg.getClass().getDeclaredMethod("getPlayerState");
                                getClientUid.setAccessible(true);
                                getPlayerState.setAccessible(true);
                                Object uidObj = getClientUid.invoke(cfg);
                                Object stateObj = getPlayerState.invoke(cfg);
                                if (uidObj instanceof Integer && ((Integer) uidObj) == gsaUid) {
                                    gsaTrackFound = true;
                                    if (stateObj instanceof Integer && ((Integer) stateObj) == 2) {
                                        return true; // GSA actively playing audio
                                    }
                                }
                            } catch (Throwable ignored) { }
                        }
                    }
                }
            } catch (Throwable ignored) { }
        }

        // If reflection confirmed GSA has tracks registered and none is in PLAYING state, GSA is not speaking
        if (gsaTrackFound) return false;

        // Fallback: check isMusicActive() (capped at 4 cycles in triggerAutoDismiss to prevent Spotify hangs)
        return am.isMusicActive();
    }

    private static void triggerAutoDismiss() {
        autoDismissScheduled = false;
        if (instance == null) return;

        try {
            AccessibilityNodeInfo root = instance.getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                foregroundPackage = root.getPackageName().toString();
            }
        } catch (Throwable ignored) { }

        boolean stillAssistant = GSA.equalsIgnoreCase(foregroundPackage);
        Log.i(TAG, "triggerAutoDismiss fired! pkg=" + foregroundPackage + " stillAssistant=" + stillAssistant);

        if (stillAssistant) {
            if (voiceInputActive) {
                Log.i(TAG, "triggerAutoDismiss: voiceInputActive=true, postponing 5s");
                autoDismissScheduled = true;
                mainHandler.postDelayed(autoDismissRunnable, 5000);
                return;
            }

            // Check if Assistant is actively speaking a spoken reply (news, recipe, TTS)
            if (isAssistantSpeaking() && speechPostponeCount < 4) {
                speechPostponeCount++;
                wasSpeaking = true;
                Log.i(TAG, "triggerAutoDismiss: assistant still speaking (TTS active), postponing 5s (count="
                        + speechPostponeCount + "/4)");
                autoDismissScheduled = true;
                mainHandler.postDelayed(autoDismissRunnable, 5000);
                return;
            }

            // If assistant just finished speaking a long response, grant the full 15s reading window
            if (wasSpeaking) {
                wasSpeaking = false;
                Log.i(TAG, "triggerAutoDismiss: assistant finished speaking, granting 15s reading window");
                autoDismissScheduled = true;
                mainHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_DELAY_MS);
                return;
            }

            dismissedForCurrentSession = true;
            Log.i(TAG, "Assistant card idle timeout (15s) reached: auto-dismissing via BACK");
            instance.performGlobalAction(GLOBAL_ACTION_BACK);

            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (instance != null) {
                        try {
                            AccessibilityNodeInfo r = instance.getRootInActiveWindow();
                            if (r != null && r.getPackageName() != null) {
                                foregroundPackage = r.getPackageName().toString();
                            }
                        } catch (Throwable ignored) { }

                        if (GSA.equalsIgnoreCase(foregroundPackage)) {
                            Log.i(TAG, "Assistant still visible after 1st BACK, sending secondary BACK");
                            instance.performGlobalAction(GLOBAL_ACTION_BACK);
                        }
                    }
                }
            }, 500);
        } else {
            dismissedForCurrentSession = true;
            Log.i(TAG, "triggerAutoDismiss skipped: foreground is not Assistant (" + foregroundPackage + ")");
        }
    }

    private static boolean checkIsVoice(String pkg, String cls) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        String c = cls != null ? cls.toLowerCase() : "";

        // Universal speech recognition or voice search across any app (SmartTube, YouTube, Chrome, etc.)
        if (c.contains("voicesearch")
                || c.contains("speechrecognizer")
                || c.contains("recognizerintent")
                || c.contains("transcription")
                || c.contains("voiceinteraction")) {
            return true;
        }

        // GSA-specific transient assistant dialogs
        if (p.equals(GSA)) {
            return c.contains("intentapi") || c.contains("handsfreeactivity");
        }

        return false;
    }
}
