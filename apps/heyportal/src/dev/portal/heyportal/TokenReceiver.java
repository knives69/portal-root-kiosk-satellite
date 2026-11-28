package dev.portal.heyportal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receives the ephemeral per-boot daemon auth token delivered directly
 * over Binder by RootDaemon (uid 0). Protected by signature permission
 * dev.portal.heyportal.permission.DAEMON_AUTH so no other app can invoke it.
 */
public class TokenReceiver extends BroadcastReceiver {
    private static final String TAG = "HeyPortal_Auth";
    public static final String ACTION_DAEMON_TOKEN = "dev.portal.heyportal.ACTION_DAEMON_TOKEN";
    public static final String EXTRA_TOKEN = "token";
    private static final String PREFS_NAME = "daemon_auth";
    private static final String KEY_TOKEN = "auth_token";

    private static volatile String sCachedToken = "";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_DAEMON_TOKEN.equals(intent.getAction())) {
            return;
        }
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (token != null && token.length() >= 32) {
            saveToken(context, token);
            Log.i(TAG, "Received daemon auth token via Binder (" + token.length() + " chars)");
        }
    }

    public static void saveToken(Context context, String token) {
        sCachedToken = token;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TOKEN, token)
                .commit();
    }

    public static String getToken(Context context) {
        String cached = sCachedToken;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        String stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TOKEN, "");
        sCachedToken = stored;
        return stored;
    }
}
