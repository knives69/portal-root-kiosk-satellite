package dev.portal.heyportal.daemon;

import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import dev.portal.heyportal.binder.AudioServiceHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RootDaemon — Privileged background service executing as UID 0 (v1.2.0).
 * Controls Meta's audiomanager wake-word recognition 24/7.
 *
 * Improvements in v1.2.0:
 *   - Binder DeathRecipient + automatic exponential-backoff re-enrollment + 60s watchdog.
 *   - Persistent preferences in /data/local/tmp/heyportal.conf (0600).
 *   - Capped / rotated /sdcard/heyportal_events.log (max ~200 lines) + in-memory LOGS ring.
 *   - Direct Binder dispatch for VOICE_COMMAND, STOP_DREAMING, and signature-protected WakeReceiver chime.
 */
public final class RootDaemon {
    private static final String TAG = "HeyPortal_Daemon";
    public static final int PORT = 45454;
    public static final String BANNER = "HEYPORTAL_DAEMON v2 AUTH_REQUIRED";

    private static final File LOG_FILE = new File("/sdcard/heyportal_events.log");
    private static final File CONF_FILE = new File("/data/local/tmp/heyportal.conf");
    private static final long LOG_MAX_BYTES = 32 * 1024;
    private static final int LOG_KEEP_LINES = 150;
    private static final int MAX_MEM_LOG_LINES = 40;

    private static final long WAKE_COOLDOWN_MS = 2500L;
    private static final long TOKEN_PUSH_COOLDOWN_MS = 1000L;
    private static final int CLIENT_TIMEOUT_MS = 3000;
    private static final long WATCHDOG_INTERVAL_MS = 60_000L;

    private static final String APP_PKG = "dev.portal.heyportal";
    private static final String TOKEN_ACTION = "dev.portal.heyportal.ACTION_DAEMON_TOKEN";
    private static final String TOKEN_RECEIVER = "dev.portal.heyportal.TokenReceiver";
    private static final String WAKE_ACTION = "dev.portal.heyportal.ACTION_WAKE_DETECTED";
    private static final String WAKE_RECEIVER = "dev.portal.heyportal.WakeReceiver";
    private static final String TOKEN_PERM = "dev.portal.heyportal.permission.DAEMON_AUTH";
    private static final String ACTION_STOP_DREAMING = "com.facebook.alohaapps.superframe.STOP_DREAMING";

    private static final String AUTH_TOKEN = generateTokenHex();
    private static final LinkedList<String> RECENT_LOGS = new LinkedList<>();
    private static final ExecutorService CLIENT_POOL = Executors.newFixedThreadPool(8);

    private static volatile boolean running = true;
    private static volatile boolean enrolled = false;
    private static volatile boolean userStopped = false;
    private static volatile boolean recovering = false;
    private static volatile int activeEnrollId = -1;
    private static volatile String targetKeyword = "HEY_PORTAL_FB";
    private static volatile boolean autoLaunch = true;
    private static volatile boolean chimeEnabled = true;
    private static volatile int detectionCount = 0;
    private static volatile String lastKeyword = "";
    private static volatile float lastConfidence = 0.0f;
    private static volatile long lastDetectedMs = 0;
    private static volatile String lastError = "";
    private static volatile long lastTokenPushMs = 0;

    private static IBinder audioMgrBinder;
    private static IBinder audioServiceBinder;
    private static Object cachedIam;
    private static Method cachedBroadcastMethod;
    private static Method cachedStartActivityAsUserMethod;

    private static final IBinder.DeathRecipient AUDIO_DEATH_RECIPIENT = () -> {
        Log.w(TAG, "audiomanager binder died! Scheduling automatic re-enrollment.");
        appendLog("AUDIO_SERVICE_DIED: scheduling recovery");
        synchronized (RootDaemon.class) {
            enrolled = false;
            activeEnrollId = -1;
            audioServiceBinder = null;
            audioMgrBinder = null;
            lastError = "audiomanager binderDied";
        }
        if (!userStopped) {
            triggerRecoveryAsync();
        }
    };

    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv4Stack", "true");

        int uid = Process.myUid();
        int pid = Process.myPid();
        System.out.println("[RootDaemon] v1.2.0 starting. PID=" + pid + " UID=" + uid);
        Log.i(TAG, "RootDaemon v1.2.0 starting. PID=" + pid + " UID=" + uid);

        loadConfig();

        // 1. Open authenticated TCP server immediately.
        Thread serverThread = new Thread(RootDaemon::startControlServer, "heyportal-tcp");
        serverThread.setDaemon(false);
        serverThread.start();

        // 2. Push ephemeral token to dev.portal.heyportal via Binder.
        deliverTokenViaBinder();

        // 3. Enroll single keyword with fallback, plus 60s watchdog.
        enrollWithFallback();
        startWatchdogThread();

        try {
            serverThread.join();
        } catch (InterruptedException ignored) {}
    }

    private static synchronized void loadConfig() {
        if (!CONF_FILE.isFile()) return;
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(CONF_FILE)) {
            p.load(fis);
            String kw = p.getProperty("keyword", "").trim();
            if (!kw.isEmpty()) targetKeyword = kw;
            autoLaunch = Boolean.parseBoolean(p.getProperty("autoLaunch", "true"));
            chimeEnabled = Boolean.parseBoolean(p.getProperty("chimeEnabled", "true"));
            Log.i(TAG, "Loaded config: kw=" + targetKeyword + " autoLaunch=" + autoLaunch + " chime=" + chimeEnabled);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load config", t);
        }
    }

    private static synchronized void saveConfig() {
        Properties p = new Properties();
        p.setProperty("keyword", targetKeyword);
        p.setProperty("autoLaunch", String.valueOf(autoLaunch));
        p.setProperty("chimeEnabled", String.valueOf(chimeEnabled));
        try (FileOutputStream fos = new FileOutputStream(CONF_FILE)) {
            p.store(fos, "HeyPortal RootDaemon v1.2.0 config");
            CONF_FILE.setReadable(false, false);
            CONF_FILE.setReadable(true, true);
            CONF_FILE.setWritable(false, false);
            CONF_FILE.setWritable(true, true);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to save config", t);
        }
    }

    private static String generateTokenHex() {
        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static synchronized void ensureActivityManager() throws Exception {
        if (cachedIam != null) return;
        Class<?> amClass = Class.forName("android.app.ActivityManager");
        cachedIam = amClass.getMethod("getService").invoke(null);
        for (Method m : cachedIam.getClass().getMethods()) {
            if (cachedBroadcastMethod == null && "broadcastIntent".equals(m.getName())) {
                cachedBroadcastMethod = m;
            } else if (cachedStartActivityAsUserMethod == null && "startActivityAsUser".equals(m.getName())) {
                cachedStartActivityAsUserMethod = m;
            }
        }
    }

    private static synchronized boolean sendProtectedBroadcast(Intent intent) {
        try {
            ensureActivityManager();
            if (cachedBroadcastMethod == null) return false;
            cachedBroadcastMethod.invoke(
                    cachedIam,
                    null,
                    intent,
                    null,
                    null,
                    0,
                    null,
                    null,
                    new String[]{TOKEN_PERM},
                    -1,
                    null,
                    false,
                    false,
                    0
            );
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "sendProtectedBroadcast failed for " + intent.getAction(), t);
            return false;
        }
    }

    private static synchronized boolean sendSystemBroadcast(String action) {
        try {
            ensureActivityManager();
            if (cachedBroadcastMethod == null) return false;
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            cachedBroadcastMethod.invoke(
                    cachedIam,
                    null,
                    intent,
                    null,
                    null,
                    0,
                    null,
                    null,
                    null,
                    -1,
                    null,
                    false,
                    false,
                    0
            );
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized boolean deliverTokenViaBinder() {
        long now = System.currentTimeMillis();
        if (now - lastTokenPushMs < TOKEN_PUSH_COOLDOWN_MS) {
            return true;
        }
        lastTokenPushMs = now;
        Intent intent = new Intent(TOKEN_ACTION);
        intent.setComponent(new ComponentName(APP_PKG, TOKEN_RECEIVER));
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.putExtra("token", AUTH_TOKEN);
        boolean ok = sendProtectedBroadcast(intent);
        if (ok) {
            Log.i(TAG, "Dispatched daemon auth token via Binder to " + APP_PKG);
        }
        return ok;
    }

    private static void triggerRecoveryAsync() {
        synchronized (RootDaemon.class) {
            if (recovering) return;
            recovering = true;
        }
        new Thread(() -> {
            long[] delaysMs = new long[]{2000L, 4000L, 8000L, 15000L};
            try {
                for (long delay : delaysMs) {
                    if (userStopped || enrolled) return;
                    Thread.sleep(delay);
                    if (userStopped || enrolled) return;
                    if (enrollDsp(targetKeyword)) {
                        appendLog("RECOVERED kw=" + targetKeyword + " enrollId=" + activeEnrollId);
                        return;
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                recovering = false;
            }
        }, "heyportal-recover").start();
    }

    private static void startWatchdogThread() {
        Thread wd = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS);
                    if (!userStopped) {
                        boolean alive = enrolled && audioServiceBinder != null && audioServiceBinder.pingBinder();
                        if (!alive) {
                            Log.w(TAG, "Watchdog detected un-enrolled/dead audio binder, recovering");
                            enrollWithFallback();
                        }
                    }
                } catch (InterruptedException ignored) {
                } catch (Throwable t) {
                    Log.w(TAG, "Watchdog tick error", t);
                }
            }
        }, "heyportal-watchdog");
        wd.setDaemon(true);
        wd.start();
    }

    private static void enrollWithFallback() {
        if (enrollDsp(targetKeyword)) {
            return;
        }
        if ("HEY_PORTAL_FB".equals(targetKeyword)) {
            Log.w(TAG, "HEY_PORTAL_FB enroll failed (" + lastError + "), trying HEY_FACEBOOK fallback");
            enrollDsp("HEY_FACEBOOK");
        }
    }

    public static synchronized boolean enrollDsp(String keyword) {
        userStopped = false;
        targetKeyword = keyword;
        try {
            lastError = "";
            if (audioServiceBinder == null || !audioServiceBinder.pingBinder()) {
                AudioServiceHelper.unlinkToDeath(audioServiceBinder, AUDIO_DEATH_RECIPIENT);
                AudioServiceHelper.unlinkToDeath(audioMgrBinder, AUDIO_DEATH_RECIPIENT);

                audioMgrBinder = AudioServiceHelper.getManagerService();
                if (audioMgrBinder == null) {
                    lastError = "AudioServiceManager is not available in ServiceManager";
                    Log.e(TAG, lastError);
                    appendLog("ENROLL_ERROR: " + lastError);
                    return false;
                }
                AudioServiceHelper.linkToDeath(audioMgrBinder, AUDIO_DEATH_RECIPIENT);

                audioServiceBinder = AudioServiceHelper.getAudioService(audioMgrBinder);
                if (audioServiceBinder == null) {
                    lastError = "getAudioService() returned null binder";
                    Log.e(TAG, lastError);
                    appendLog("ENROLL_ERROR: " + lastError);
                    return false;
                }
                AudioServiceHelper.linkToDeath(audioServiceBinder, AUDIO_DEATH_RECIPIENT);
            }

            if (activeEnrollId >= 0) {
                try {
                    AudioServiceHelper.stopRecognition(audioServiceBinder, activeEnrollId);
                } catch (Throwable ignored) {}
                activeEnrollId = -1;
                enrolled = false;
            }

            activeEnrollId = AudioServiceHelper.startRecognitionWithMetadata(
                    audioServiceBinder,
                    keyword,
                    (enrollId, kw, beginTime, endTime, confidence, metadata) ->
                            handleWakeEvent(enrollId, kw, confidence)
            );

            enrolled = (activeEnrollId >= 0);
            if (enrolled) {
                Log.i(TAG, "DSP Enrollment SUCCESS! keyword=" + keyword + " enrollId=" + activeEnrollId);
                appendLog("ENROLLED kw=" + keyword + " enrollId=" + activeEnrollId);
                saveConfig();
            } else {
                lastError = "startRecognition returned invalid enrollId=" + activeEnrollId;
                appendLog("ENROLL_ERROR: " + lastError);
            }
            return enrolled;
        } catch (Throwable t) {
            enrolled = false;
            activeEnrollId = -1;
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "Enrollment exception for " + keyword + ": " + lastError, t);
            appendLog("ENROLL_ERROR (" + keyword + "): " + t.getMessage());
            return false;
        }
    }

    public static synchronized void stopDsp() {
        userStopped = true;
        if (audioServiceBinder != null && activeEnrollId >= 0) {
            try {
                AudioServiceHelper.stopRecognition(audioServiceBinder, activeEnrollId);
                Log.i(TAG, "DSP recognition stopped for enrollId=" + activeEnrollId);
                appendLog("STOPPED enrollId=" + activeEnrollId);
            } catch (Throwable t) {
                Log.e(TAG, "Error stopping recognition", t);
            }
        }
        enrolled = false;
        activeEnrollId = -1;
    }

    private static void handleWakeEvent(int id, String keyword, float conf) {
        long now = System.currentTimeMillis();
        boolean withinCooldown = (now - lastDetectedMs) < WAKE_COOLDOWN_MS;

        detectionCount++;
        lastKeyword = keyword != null ? keyword : targetKeyword;
        lastConfidence = conf;
        lastDetectedMs = now;

        String logLine = String.format(Locale.US, "WAKE DETECTED: id=%d kw='%s' conf=%.3f (count=%d%s)",
                id, lastKeyword, conf, detectionCount, withinCooldown ? ", cooldown" : "");
        System.out.println("[RootDaemon] " + logLine);
        Log.i(TAG, logLine);
        appendLog(logLine);

        if (!withinCooldown) {
            // Wake ambient dream + deliver chime & UI notification via protected Binder broadcast.
            sendSystemBroadcast(ACTION_STOP_DREAMING);
            broadcastWakeEvent(lastKeyword, conf, now, chimeEnabled, true);
            if (autoLaunch) {
                launchAssistant();
            }
        }
    }

    private static void launchAssistant() {
        try {
            ensureActivityManager();
            if (cachedStartActivityAsUserMethod != null) {
                Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                cachedStartActivityAsUserMethod.invoke(
                        cachedIam, null, "com.android.shell", intent, null, null, null, 0, 0, null, null, 0
                );
                Log.i(TAG, "Dispatched VOICE_COMMAND via Binder");
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Binder startActivityAsUser failed, falling back to am start", t);
        }
        try {
            Runtime.getRuntime().exec(new String[]{
                    "/system/bin/am", "start", "-a", "android.intent.action.VOICE_COMMAND"
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch VOICE_COMMAND intent", t);
        }
    }

    private static void broadcastWakeEvent(String keyword, float conf, long timeMs, boolean playChime, boolean wakeScreen) {
        Intent intent = new Intent(WAKE_ACTION);
        intent.setComponent(new ComponentName(APP_PKG, WAKE_RECEIVER));
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.putExtra("keyword", keyword);
        intent.putExtra("confidence", conf);
        intent.putExtra("timestamp", timeMs);
        intent.putExtra("chime", playChime);
        intent.putExtra("wakeScreen", wakeScreen);
        sendProtectedBroadcast(intent);
    }

    private static void appendLog(String line) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String full = "[" + time + "] " + line;
        synchronized (RECENT_LOGS) {
            RECENT_LOGS.addLast(full);
            while (RECENT_LOGS.size() > MAX_MEM_LOG_LINES) {
                RECENT_LOGS.removeFirst();
            }
        }
        synchronized (LOG_FILE) {
            try {
                if (LOG_FILE.exists() && LOG_FILE.length() > LOG_MAX_BYTES) {
                    trimLogFile();
                }
                try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
                    fw.write(full + "\n");
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void trimLogFile() {
        try {
            List<String> lines = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(LOG_FILE))) {
                String l;
                while ((l = br.readLine()) != null) {
                    lines.add(l);
                }
            }
            int start = Math.max(0, lines.size() - LOG_KEEP_LINES);
            try (FileWriter fw = new FileWriter(LOG_FILE, false)) {
                for (int i = start; i < lines.size(); i++) {
                    fw.write(lines.get(i) + "\n");
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String getRecentLogsJoined() {
        StringBuilder sb = new StringBuilder();
        synchronized (RECENT_LOGS) {
            for (String l : RECENT_LOGS) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(l);
            }
        }
        return sb.toString();
    }

    private static void startControlServer() {
        try (ServerSocket server = new ServerSocket(PORT, 10, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            Log.i(TAG, "TCP Control Server listening on 127.0.0.1:" + PORT + " (banner + token auth enabled)");
            while (running) {
                try {
                    Socket client = server.accept();
                    CLIENT_POOL.execute(() -> handleClient(client));
                } catch (Throwable t) {
                    Log.e(TAG, "Client socket error", t);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to bind TCP server on port " + PORT, t);
        }
    }

    private static boolean verifyToken(String candidate) {
        if (candidate == null) return false;
        byte[] a = AUTH_TOKEN.getBytes(StandardCharsets.US_ASCII);
        byte[] b = candidate.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(a, b);
    }

    private static void handleClient(Socket socket) {
        try (
            Socket s = socket;
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)
        ) {
            s.setSoTimeout(CLIENT_TIMEOUT_MS);
            writer.println(BANNER);

            String firstLine = reader.readLine();
            if (firstLine == null) return;
            firstLine = firstLine.trim();

            if ("REQUEST_TOKEN".equalsIgnoreCase(firstLine)) {
                boolean pushed = deliverTokenViaBinder();
                writer.println("{\"ok\":false,\"error\":\"" +
                        (pushed ? "token_dispatched_via_binder" : "token_dispatch_failed") + "\"}");
                return;
            }

            if (!firstLine.startsWith("AUTH ")) {
                writer.println("{\"ok\":false,\"error\":\"unauthorized\"}");
                return;
            }

            String providedToken = firstLine.substring(5).trim();
            if (!verifyToken(providedToken)) {
                writer.println("{\"ok\":false,\"error\":\"unauthorized\"}");
                return;
            }

            writer.println("{\"ok\":true,\"auth\":true}");

            String cmd = reader.readLine();
            if (cmd == null) return;
            cmd = cmd.trim();

            if ("STATUS".equalsIgnoreCase(cmd)) {
                String json = String.format(Locale.US,
                        "{\"ok\":true,\"pid\":%d,\"uid\":%d,\"enrolled\":%b,\"enrollId\":%d,\"keyword\":\"%s\",\"count\":%d,\"lastKw\":\"%s\",\"lastConf\":%.3f,\"lastTs\":%d,\"autoLaunch\":%b,\"chime\":%b,\"error\":\"%s\"}",
                        Process.myPid(), Process.myUid(), enrolled, activeEnrollId, escape(targetKeyword),
                        detectionCount, escape(lastKeyword), lastConfidence, lastDetectedMs, autoLaunch, chimeEnabled, escape(lastError));
                writer.println(json);
            } else if ("LOGS".equalsIgnoreCase(cmd)) {
                writer.println("{\"ok\":true,\"logs\":\"" + escape(getRecentLogsJoined()) + "\"}");
            } else if ("RE_ENROLL".equalsIgnoreCase(cmd)) {
                stopDsp();
                boolean ok = enrollDsp(targetKeyword);
                writer.println("{\"ok\":" + ok + ",\"enrollId\":" + activeEnrollId + ",\"keyword\":\"" + escape(targetKeyword) + "\"}");
            } else if (cmd.startsWith("SET_KEYWORD:")) {
                String kw = cmd.substring("SET_KEYWORD:".length()).trim();
                if (!kw.isEmpty()) {
                    stopDsp();
                    boolean ok = enrollDsp(kw);
                    writer.println("{\"ok\":" + ok + ",\"enrollId\":" + activeEnrollId + ",\"keyword\":\"" + escape(targetKeyword) + "\"}");
                } else {
                    writer.println("{\"ok\":false,\"error\":\"empty_keyword\"}");
                }
            } else if ("STOP".equalsIgnoreCase(cmd)) {
                stopDsp();
                writer.println("{\"ok\":true}");
            } else if ("TRIGGER".equalsIgnoreCase(cmd)) {
                launchAssistant();
                writer.println("{\"ok\":true}");
            } else if (cmd.startsWith("SET_AUTO_LAUNCH:")) {
                autoLaunch = Boolean.parseBoolean(cmd.substring("SET_AUTO_LAUNCH:".length()));
                saveConfig();
                writer.println("{\"ok\":true,\"autoLaunch\":" + autoLaunch + "}");
            } else if (cmd.startsWith("SET_CHIME:")) {
                chimeEnabled = Boolean.parseBoolean(cmd.substring("SET_CHIME:".length()));
                saveConfig();
                writer.println("{\"ok\":true,\"chime\":" + chimeEnabled + "}");
            } else if ("PING".equalsIgnoreCase(cmd)) {
                writer.println("PONG");
            } else {
                writer.println("{\"ok\":false,\"error\":\"unknown_command\"}");
            }
        } catch (Throwable ignored) {}
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
