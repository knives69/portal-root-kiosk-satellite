package dev.portal.heyportal.daemon;

import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import dev.portal.heyportal.binder.AudioServiceHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * RootDaemon — Privileged background service executing as UID 0.
 * Controls Meta's audiomanager wake-word recognition 24/7.
 *
 * Valid WakeWordManager single keywords on aloha:
 *   - "HEY_PORTAL_FB" -> /vendor/firmware/fb-ww-1g/hp/kwsEngine.json ("Hey Portal")
 *   - "HEY_FACEBOOK"  -> /vendor/firmware/fb-ww-1g/hfb/kwsEngine.json ("Hey Facebook")
 *   - "ALEXA"         -> AmazonWakeWordEngine
 */
public final class RootDaemon {
    private static final String TAG = "HeyPortal_Daemon";
    public static final int PORT = 45454;
    private static final File LOG_FILE = new File("/sdcard/heyportal_events.log");
    private static final long WAKE_COOLDOWN_MS = 2500L;

    private static volatile boolean running = true;
    private static volatile boolean enrolled = false;
    private static volatile int activeEnrollId = -1;
    private static volatile String targetKeyword = "HEY_PORTAL_FB";
    private static volatile boolean autoLaunch = true;
    private static volatile int detectionCount = 0;
    private static volatile String lastKeyword = "";
    private static volatile float lastConfidence = 0.0f;
    private static volatile long lastDetectedMs = 0;
    private static volatile String lastError = "";

    private static IBinder audioServiceBinder;

    public static void main(String[] args) {
        // Bind IPv4 loopback so /proc/net/tcp reports :B18E as well as tcp6.
        System.setProperty("java.net.preferIPv4Stack", "true");

        int uid = Process.myUid();
        int pid = Process.myPid();
        System.out.println("[RootDaemon] Starting. PID=" + pid + " UID=" + uid);
        Log.i(TAG, "RootDaemon starting. PID=" + pid + " UID=" + uid);

        // 1. Open TCP server immediately so dashboard never sees a startup gap or enroll failure blackness.
        Thread serverThread = new Thread(RootDaemon::startControlServer, "heyportal-tcp");
        serverThread.setDaemon(false);
        serverThread.start();

        // 2. Enroll single keyword (WakeWordManager rejects >1 wakeword per call).
        enrollWithFallback();

        try {
            serverThread.join();
        } catch (InterruptedException ignored) {}
    }

    private static void enrollWithFallback() {
        if (enrollDsp(targetKeyword)) {
            return;
        }
        if ("HEY_PORTAL_FB".equals(targetKeyword)) {
            Log.w(TAG, "HEY_PORTAL_FB enroll failed (" + lastError + "), trying HEY_FACEBOOK fallback");
            if (enrollDsp("HEY_FACEBOOK")) {
                return;
            }
        }
    }

    public static synchronized boolean enrollDsp(String keyword) {
        targetKeyword = keyword;
        try {
            lastError = "";
            if (audioServiceBinder == null || !audioServiceBinder.pingBinder()) {
                IBinder mgr = AudioServiceHelper.getManagerService();
                if (mgr == null) {
                    lastError = "AudioServiceManager is not available in ServiceManager";
                    Log.e(TAG, lastError);
                    appendLog("ENROLL_ERROR: " + lastError);
                    return false;
                }
                audioServiceBinder = AudioServiceHelper.getAudioService(mgr);
                if (audioServiceBinder == null) {
                    lastError = "getAudioService() returned null binder";
                    Log.e(TAG, lastError);
                    appendLog("ENROLL_ERROR: " + lastError);
                    return false;
                }
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

        String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(now));
        String logLine = String.format(Locale.US, "[%s] WAKE DETECTED: id=%d kw='%s' conf=%.3f (count=%d%s)",
                timeStr, id, lastKeyword, conf, detectionCount, withinCooldown ? ", cooldown" : "");
        System.out.println("[RootDaemon] " + logLine);
        Log.i(TAG, logLine);
        appendLog(logLine);

        broadcastWakeEvent(lastKeyword, conf, now);

        if (autoLaunch && !withinCooldown) {
            launchAssistant();
        }
    }

    private static void launchAssistant() {
        try {
            Log.i(TAG, "Triggering Google Assistant: android.intent.action.VOICE_COMMAND");
            Runtime.getRuntime().exec(new String[]{
                    "/system/bin/am", "start", "-a", "android.intent.action.VOICE_COMMAND"
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch VOICE_COMMAND intent", t);
        }
    }

    private static void broadcastWakeEvent(String keyword, float conf, long timeMs) {
        try {
            Runtime.getRuntime().exec(new String[]{
                    "/system/bin/am", "broadcast",
                    "-a", "dev.portal.heyportal.ACTION_WAKE_DETECTED",
                    "--es", "keyword", keyword,
                    "--ef", "confidence", String.valueOf(conf),
                    "--el", "timestamp", String.valueOf(timeMs)
            });
        } catch (Throwable ignored) {}
    }

    private static void appendLog(String line) {
        try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            fw.write("[" + time + "] " + line + "\n");
        } catch (Throwable ignored) {}
    }

    private static void startControlServer() {
        try (ServerSocket server = new ServerSocket(PORT, 10, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            Log.i(TAG, "TCP Control Server listening on 127.0.0.1:" + PORT);
            while (running) {
                try {
                    Socket client = server.accept();
                    handleClient(client);
                } catch (Throwable t) {
                    Log.e(TAG, "Client socket error", t);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to bind TCP server on port " + PORT, t);
        }
    }

    private static void handleClient(Socket socket) {
        new Thread(() -> {
            try (
                Socket s = socket;
                BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)
            ) {
                String line = reader.readLine();
                if (line == null) return;
                line = line.trim();

                if ("STATUS".equalsIgnoreCase(line)) {
                    String json = String.format(Locale.US,
                            "{\"ok\":true,\"pid\":%d,\"uid\":%d,\"enrolled\":%b,\"enrollId\":%d,\"keyword\":\"%s\",\"count\":%d,\"lastKw\":\"%s\",\"lastConf\":%.3f,\"lastTs\":%d,\"autoLaunch\":%b,\"error\":\"%s\"}",
                            Process.myPid(), Process.myUid(), enrolled, activeEnrollId, escape(targetKeyword),
                            detectionCount, escape(lastKeyword), lastConfidence, lastDetectedMs, autoLaunch, escape(lastError));
                    writer.println(json);
                } else if ("RE_ENROLL".equalsIgnoreCase(line)) {
                    stopDsp();
                    boolean ok = enrollDsp(targetKeyword);
                    writer.println("{\"ok\":" + ok + ",\"enrollId\":" + activeEnrollId + ",\"keyword\":\"" + escape(targetKeyword) + "\"}");
                } else if (line.startsWith("SET_KEYWORD:")) {
                    String kw = line.substring("SET_KEYWORD:".length()).trim();
                    if (!kw.isEmpty()) {
                        stopDsp();
                        boolean ok = enrollDsp(kw);
                        writer.println("{\"ok\":" + ok + ",\"enrollId\":" + activeEnrollId + ",\"keyword\":\"" + escape(targetKeyword) + "\"}");
                    } else {
                        writer.println("{\"ok\":false,\"error\":\"empty_keyword\"}");
                    }
                } else if ("STOP".equalsIgnoreCase(line)) {
                    stopDsp();
                    writer.println("{\"ok\":true}");
                } else if ("TRIGGER".equalsIgnoreCase(line)) {
                    launchAssistant();
                    writer.println("{\"ok\":true}");
                } else if (line.startsWith("SET_AUTO_LAUNCH:")) {
                    autoLaunch = Boolean.parseBoolean(line.substring("SET_AUTO_LAUNCH:".length()));
                    writer.println("{\"ok\":true,\"autoLaunch\":" + autoLaunch + "}");
                } else if ("PING".equalsIgnoreCase(line)) {
                    writer.println("PONG");
                } else {
                    writer.println("{\"error\":\"unknown_command\"}");
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
