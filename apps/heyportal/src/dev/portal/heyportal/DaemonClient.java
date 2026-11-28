package dev.portal.heyportal;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class DaemonClient {
    public static final int PORT = 45454;

    public static class Status {
        public boolean daemonOnline;
        public boolean authenticated;
        public String banner = "";
        public int pid;
        public int uid;
        public boolean enrolled;
        public int enrollId;
        public String keyword = "HEY_PORTAL_FB";
        public int count;
        public String lastKw = "";
        public float lastConf;
        public long lastTs;
        public boolean autoLaunch = true;
        public boolean chimeEnabled = true;
        public String logs = "";
        public String error = "";
    }

    private static String requestFreshTokenAndWait(Context ctx, String oldToken) throws Exception {
        try (Socket reqSock = new Socket()) {
            reqSock.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 1500);
            reqSock.setSoTimeout(2500);
            BufferedReader rIn = new BufferedReader(new InputStreamReader(reqSock.getInputStream()));
            PrintWriter rOut = new PrintWriter(new OutputStreamWriter(reqSock.getOutputStream()), true);
            String banner = rIn.readLine();
            if (banner == null || !banner.startsWith("HEYPORTAL_DAEMON ")) {
                throw new IllegalStateException("Unexpected daemon banner: " + banner);
            }
            rOut.println("REQUEST_TOKEN");
            rIn.readLine();
        }
        // Poll up to 1500ms so fast responses return in ~75ms while heavy boot loads still succeed.
        for (int i = 0; i < 20; i++) {
            Thread.sleep(75);
            String updated = TokenReceiver.getToken(ctx);
            if (!updated.isEmpty() && !updated.equals(oldToken)) {
                return updated;
            }
        }
        return TokenReceiver.getToken(ctx);
    }

    private static String exchangeAuthenticated(Context ctx, String cmd) throws Exception {
        String token = TokenReceiver.getToken(ctx);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (token.isEmpty()) {
                token = requestFreshTokenAndWait(ctx, "");
                if (token.isEmpty()) {
                    throw new SecurityException("Token unavailable via Binder");
                }
            }

            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 1500);
                sock.setSoTimeout(2500);

                PrintWriter out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));

                String banner = in.readLine();
                if (banner == null || !banner.startsWith("HEYPORTAL_DAEMON ")) {
                    throw new IllegalStateException("Unexpected daemon banner: " + banner);
                }

                out.println("AUTH " + token);
                String authResp = in.readLine();
                if (authResp == null) {
                    throw new IllegalStateException("Empty auth response");
                }
                JSONObject authObj = new JSONObject(authResp);
                if (!authObj.optBoolean("auth", false)) {
                    if (attempt == 0) {
                        token = requestFreshTokenAndWait(ctx, token);
                        continue;
                    }
                    throw new SecurityException("Daemon rejected token (" + authObj.optString("error") + ")");
                }

                out.println(cmd);
                return in.readLine();
            }
        }
        throw new SecurityException("Token unavailable via Binder");
    }

    public static Status queryStatus(Context ctx) {
        Status s = new Status();
        try {
            String line = exchangeAuthenticated(ctx, "STATUS");
            if (line != null) {
                JSONObject obj = new JSONObject(line);
                s.daemonOnline = obj.optBoolean("ok", false);
                s.authenticated = true;
                s.pid = obj.optInt("pid", -1);
                s.uid = obj.optInt("uid", -1);
                s.enrolled = obj.optBoolean("enrolled", false);
                s.enrollId = obj.optInt("enrollId", -1);
                s.keyword = obj.optString("keyword", "HEY_PORTAL_FB");
                s.count = obj.optInt("count", 0);
                s.lastKw = obj.optString("lastKw", "");
                s.lastConf = (float) obj.optDouble("lastConf", 0.0);
                s.lastTs = obj.optLong("lastTs", 0);
                s.autoLaunch = obj.optBoolean("autoLaunch", true);
                s.chimeEnabled = obj.optBoolean("chime", true);
                s.error = obj.optString("error", "");
            }
            String logsLine = exchangeAuthenticated(ctx, "LOGS");
            if (logsLine != null) {
                JSONObject logsObj = new JSONObject(logsLine);
                if (logsObj.optBoolean("ok", false)) {
                    s.logs = logsObj.optString("logs", "");
                }
            }
        } catch (SecurityException se) {
            s.daemonOnline = true;
            s.authenticated = false;
            s.error = se.getMessage();
        } catch (Throwable t) {
            s.daemonOnline = false;
            s.error = t.getMessage();
        }
        return s;
    }

    public static boolean sendCommand(Context ctx, String cmd) {
        try {
            String line = exchangeAuthenticated(ctx, cmd);
            return line != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
