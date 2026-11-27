package dev.portal.heyportal;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String[] KEYWORDS = new String[]{
            "HEY_PORTAL_FB",
            "HEY_FACEBOOK",
            "HEY_PORTAL",
            "ALEXA"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;

    private TextView statusBadge;
    private TextView statDaemonValue;
    private TextView statEnrollValue;
    private TextView statCountValue;
    private TextView statLastWakeValue;
    private TextView logTextView;
    private Switch autoLaunchSwitch;
    private Button btnCycleKeyword;

    private boolean isPolling = false;
    private boolean updatingSwitch = false;
    private String activeKeyword = KEYWORDS[0];

    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshData();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(wakeReceiver, new IntentFilter("dev.portal.heyportal.ACTION_WAKE_DETECTED"));
        startPolling();
        refreshData();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(wakeReceiver);
        } catch (Throwable ignored) {}
        stopPolling();
    }

    private void startPolling() {
        if (isPolling) return;
        isPolling = true;
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                refreshData();
                if (isPolling) handler.postDelayed(this, 2500);
            }
        };
        handler.post(pollRunnable);
    }

    private void stopPolling() {
        isPolling = false;
        if (pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    private void refreshData() {
        new Thread(() -> {
            DaemonClient.Status s = DaemonClient.queryStatus();
            String logContent = readRecentLog();
            handler.post(() -> updateUI(s, logContent));
        }).start();
    }

    private void updateUI(DaemonClient.Status s, String logContent) {
        if (s.daemonOnline) {
            if (s.keyword != null && !s.keyword.isEmpty()) {
                activeKeyword = s.keyword;
            }
            if (btnCycleKeyword != null) {
                btnCycleKeyword.setText("KEYWORD: " + activeKeyword);
            }

            if (s.enrolled) {
                statusBadge.setText("●  ACTIVE · LISTENING (" + activeKeyword + ")");
                statusBadge.setTextColor(Color.parseColor("#34A853"));
                statusBadge.setBackground(makePill(Color.parseColor("#122E1D"), Color.parseColor("#34A853"), 12));
                statEnrollValue.setText("Enrolled (ID #" + s.enrollId + " · " + activeKeyword + ")");
                statEnrollValue.setTextColor(Color.parseColor("#81C995"));
            } else {
                statusBadge.setText("▲  DAEMON ONLINE · NOT ENROLLED");
                statusBadge.setTextColor(Color.parseColor("#FBBC04"));
                statusBadge.setBackground(makePill(Color.parseColor("#332608"), Color.parseColor("#FBBC04"), 12));
                statEnrollValue.setText("Idle (" + (s.error.isEmpty() ? activeKeyword : s.error) + ")");
                statEnrollValue.setTextColor(Color.parseColor("#FDD663"));
            }
            statDaemonValue.setText("UID " + s.uid + " (PID " + s.pid + ") · Online");
            statDaemonValue.setTextColor(Color.parseColor("#81C995"));
            statCountValue.setText(String.valueOf(s.count));

            if (s.lastTs > 0) {
                String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(s.lastTs));
                statLastWakeValue.setText(String.format(Locale.US, "'%s' @ %s (conf: %.2f)", s.lastKw, time, s.lastConf));
            } else {
                statLastWakeValue.setText("None yet (Say 'Hey Portal' to test)");
            }

            if (autoLaunchSwitch != null && autoLaunchSwitch.isChecked() != s.autoLaunch) {
                updatingSwitch = true;
                autoLaunchSwitch.setChecked(s.autoLaunch);
                updatingSwitch = false;
            }
        } else {
            statusBadge.setText("✕  DAEMON OFFLINE · WAITING FOR ROOT");
            statusBadge.setTextColor(Color.parseColor("#EA4335"));
            statusBadge.setBackground(makePill(Color.parseColor("#3C1412"), Color.parseColor("#EA4335"), 12));
            statDaemonValue.setText("Offline (127.0.0.1:45454 not responding)");
            statDaemonValue.setTextColor(Color.parseColor("#F28B82"));
            statEnrollValue.setText("Disconnected");
            statEnrollValue.setTextColor(Color.parseColor("#9AA0A6"));
            statCountValue.setText("-");
            statLastWakeValue.setText("Waiting for privileged daemon to start");
        }

        if (logTextView != null) {
            logTextView.setText(logContent.isEmpty() ? "No events recorded yet." : logContent);
        }
    }

    private String readRecentLog() {
        File f = new File("/sdcard/heyportal_events.log");
        if (!f.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            java.util.List<String> lines = new java.util.ArrayList<>();
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
            int start = Math.max(0, lines.size() - 15);
            for (int i = start; i < lines.size(); i++) {
                sb.append(lines.get(i)).append("\n");
            }
        } catch (Throwable ignored) {}
        return sb.toString().trim();
    }

    private String nextKeyword(String current) {
        for (int i = 0; i < KEYWORDS.length; i++) {
            if (KEYWORDS[i].equals(current)) {
                return KEYWORDS[(i + 1) % KEYWORDS.length];
            }
        }
        return KEYWORDS[0];
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0F1117"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(40));

        // 1. Header
        TextView title = new TextView(this);
        title.setText("Hey Portal — AudioService Wake Test");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Testing Meta Portal native wake-word (audiomanager) -> Google Assistant");
        subtitle.setTextColor(Color.parseColor("#9AA0A6"));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        // 2. Status Badge
        statusBadge = new TextView(this);
        statusBadge.setText("Checking daemon status...");
        statusBadge.setTextColor(Color.parseColor("#9AA0A6"));
        statusBadge.setTextSize(13);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setPadding(dp(16), dp(10), dp(16), dp(10));
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setBackground(makePill(Color.parseColor("#1A1D26"), Color.parseColor("#2A2E3B"), 12));
        root.addView(statusBadge);

        addSpace(root, 20);

        // 3. Telemetry Card
        LinearLayout cardTel = makeCard();
        cardTel.addView(makeSectionTitle("1. WAKEWORD ENGINE & DAEMON STATUS"));

        statDaemonValue = makeRow(cardTel, "Privileged Daemon:", "Connecting...");
        statEnrollValue = makeRow(cardTel, "Wake Enrollment:", "Checking...");
        statCountValue = makeRow(cardTel, "Detection Count:", "0");
        statLastWakeValue = makeRow(cardTel, "Last Wake Trigger:", "None");
        root.addView(cardTel);

        addSpace(root, 16);

        // 4. Action & Assistant Integration Card
        LinearLayout cardAction = makeCard();
        cardAction.addView(makeSectionTitle("2. ASSISTANT INTEGRATION & CONTROLS"));

        autoLaunchSwitch = new Switch(this);
        autoLaunchSwitch.setText("Launch Google Assistant on detection (VOICE_COMMAND)");
        autoLaunchSwitch.setTextColor(Color.WHITE);
        autoLaunchSwitch.setTextSize(14);
        autoLaunchSwitch.setChecked(true);
        autoLaunchSwitch.setPadding(0, dp(6), 0, dp(14));
        autoLaunchSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (updatingSwitch) return;
            new Thread(() -> DaemonClient.sendCommand("SET_AUTO_LAUNCH:" + isChecked)).start();
        });
        cardAction.addView(autoLaunchSwitch);

        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);

        Button btnTestAssistant = makeButton("TEST ASSISTANT INTENT", Color.parseColor("#4285F4"));
        btnTestAssistant.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(this, "Dispatched ACTION_VOICE_COMMAND", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "Failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        btnRow1.addView(btnTestAssistant, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        addHSpace(btnRow1, 12);

        Button btnReEnroll = makeButton("RE-ENROLL", Color.parseColor("#34A853"));
        btnReEnroll.setOnClickListener(v -> {
            new Thread(() -> {
                DaemonClient.sendCommand("RE_ENROLL");
                handler.post(this::refreshData);
            }).start();
            Toast.makeText(this, "Requested re-enrollment (" + activeKeyword + ")", Toast.LENGTH_SHORT).show();
        });
        btnRow1.addView(btnReEnroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        cardAction.addView(btnRow1);

        addSpace(cardAction, 12);

        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);

        btnCycleKeyword = makeButton("KEYWORD: " + activeKeyword, Color.parseColor("#7B1FA2"));
        btnCycleKeyword.setOnClickListener(v -> {
            String next = nextKeyword(activeKeyword);
            activeKeyword = next;
            btnCycleKeyword.setText("KEYWORD: " + next);
            new Thread(() -> {
                DaemonClient.sendCommand("SET_KEYWORD:" + next);
                handler.post(this::refreshData);
            }).start();
            Toast.makeText(this, "Switching to " + next, Toast.LENGTH_SHORT).show();
        });
        btnRow2.addView(btnCycleKeyword, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

        addHSpace(btnRow2, 12);

        Button btnStop = makeButton("STOP", Color.parseColor("#D93025"));
        btnStop.setOnClickListener(v -> {
            new Thread(() -> {
                DaemonClient.sendCommand("STOP");
                handler.post(this::refreshData);
            }).start();
            Toast.makeText(this, "Stopped wake-word recognition", Toast.LENGTH_SHORT).show();
        });
        btnRow2.addView(btnStop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f));

        cardAction.addView(btnRow2);
        root.addView(cardAction);

        addSpace(root, 16);

        // 5. Live Events Log Card
        LinearLayout cardLog = makeCard();
        cardLog.addView(makeSectionTitle("3. LIVE DETECTION EVENT LOG (/sdcard/heyportal_events.log)"));

        logTextView = new TextView(this);
        logTextView.setText("No log events yet.");
        logTextView.setTextColor(Color.parseColor("#8AB4F8"));
        logTextView.setTextSize(12);
        logTextView.setTypeface(Typeface.MONOSPACE);
        logTextView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logTextView.setBackground(makePill(Color.parseColor("#12141C"), Color.parseColor("#222632"), 8));
        cardLog.addView(logTextView);

        root.addView(cardLog);

        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout makeCard() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(18), dp(20), dp(20));
        l.setBackground(makeRoundedCard(Color.parseColor("#1A1D26"), Color.parseColor("#2A2E3B"), 14, 1));
        return l;
    }

    private TextView makeSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#8AB4F8"));
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(12));
        return tv;
    }

    private TextView makeRow(LinearLayout parent, String label, String initialValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.parseColor("#9AA0A6"));
        tvLabel.setTextSize(13);
        row.addView(tvLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f));

        TextView tvVal = new TextView(this);
        tvVal.setText(initialValue);
        tvVal.setTextColor(Color.WHITE);
        tvVal.setTextSize(13);
        tvVal.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(tvVal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f));

        parent.addView(row);
        return tvVal;
    }

    private Button makeButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(makePill(color, 0, 10));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }

    private void addSpace(LinearLayout parent, int dpHeight) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(dpHeight)));
    }

    private void addHSpace(LinearLayout parent, int dpWidth) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(dp(dpWidth), ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private GradientDrawable makeRoundedCard(int bgColor, int borderColor, float radiusDp, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(bgColor);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), borderColor);
        return d;
    }

    private GradientDrawable makePill(int bgColor, int borderColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(bgColor);
        d.setCornerRadius(dp(radiusDp));
        if (borderColor != 0) d.setStroke(dp(1.2f), borderColor);
        return d;
    }

    private int dp(float val) {
        return (int) (val * getResources().getDisplayMetrics().density + 0.5f);
    }
}
