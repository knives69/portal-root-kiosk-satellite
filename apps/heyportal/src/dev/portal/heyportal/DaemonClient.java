package dev.portal.heyportal;

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
        public int pid;
        public int uid;
        public boolean enrolled;
        public int enrollId;
        public String keyword = "HEY_PORTAL_FB";
        public int count;
        public String lastKw = "";
        public float lastConf;
        public long lastTs;
        public boolean autoLaunch;
        public String error = "";
    }

    public static Status queryStatus() {
        Status s = new Status();
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 1500);
            sock.setSoTimeout(2000);

            PrintWriter out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));

            out.println("STATUS");
            String line = in.readLine();
            if (line != null) {
                JSONObject obj = new JSONObject(line);
                s.daemonOnline = obj.optBoolean("ok", false);
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
                s.error = obj.optString("error", "");
            }
        } catch (Throwable t) {
            s.daemonOnline = false;
            s.error = t.getMessage();
        }
        return s;
    }

    public static boolean sendCommand(String cmd) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 1500);
            sock.setSoTimeout(2000);

            PrintWriter out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));

            out.println(cmd);
            String line = in.readLine();
            return line != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
