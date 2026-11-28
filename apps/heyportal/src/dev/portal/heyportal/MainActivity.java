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
    private TextView statA11yValue;
    private TextView logTextView;
    private Switch autoLaunchSwitch;
    private Switch chimeSwitch;
    private Switch autoDismissSwitch;
    private Button btnCycleKeyword;

    private boolean isPolling = false;
    private boolean updatingSwitches = false;
    private String activeKeyword = KEYWORDS[0];

    private final BroadcastReceiver wakeUiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshData();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WakeReceiver.preloadChime(this);
        FgTracker.ensureAccessibilityEnabled(this);
        buildUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FgTracker.ensureAccessibilityEnabled(this);
        registerReceiver(wakeUiReceiver, new IntentFilter(WakeReceiver.ACTION_LOCAL_UI_REFRESH));
        startPolling();
        refreshData();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(wakeUiReceiver);
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
            DaemonClient.Status s = DaemonClient.queryStatus(this);
            String logContent = (s.logs != null && !s.logs.isEmpty()) ? s.logs : readRecentLog();
            handler.post(() -> updateUI(s, logContent));
        }).start();
    }

    private void updateUI(DaemonClient.Status s, String logContent) {
        if (s.daemonOnline && s.authenticated) {
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
            statDaemonValue.setText("UID " + s.uid + " (PID " + s.pid + ") · Auth OK");
            statDaemonValue.setTextColor(Color.parseColor("#81C995"));
            statCountValue.setText(String.valueOf(s.count));

            if (s.lastTs > 0) {
                String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(s.lastTs));
                statLastWakeValue.setText(String.format(Locale.US, "'%s' @ %s (conf: %.2f)", s.lastKw, time, s.lastConf));
            } else {
                statLastWakeValue.setText("None yet (Say 'Hey Portal' to test)");
            }

            updatingSwitches = true;
            if (autoLaunchSwitch != null && autoLaunchSwitch.isChecked() != s.autoLaunch) {
                autoLaunchSwitch.setChecked(s.autoLaunch);
            }
            if (chimeSwitch != null && chimeSwitch.isChecked() != s.chimeEnabled) {
                chimeSwitch.setChecked(s.chimeEnabled);
            }
            if (autoDismissSwitch != null) {
                boolean adDef = getSharedPreferences(FgTracker.PREFS_NAME, MODE_PRIVATE).getBoolean(FgTracker.PREF_AUTO_DISMISS, true);
                if (autoDismissSwitch.isChecked() != adDef) {
                    autoDismissSwitch.setChecked(adDef);
                }
            }
            updatingSwitches = false;
        } else if (s.daemonOnline) {
            statusBadge.setText("▲  AUTH HANDSHAKE PENDING");
            statusBadge.setTextColor(Color.parseColor("#FBBC04"));
            statusBadge.setBackground(makePill(Color.parseColor("#332608"), Color.parseColor("#FBBC04"), 12));
            statDaemonValue.setText("Authenticating (" + s.error + ")");
            statDaemonValue.setTextColor(Color.parseColor("#FDD663"));
            statEnrollValue.setText("Locked");
            statEnrollValue.setTextColor(Color.parseColor("#9AA0A6"));
            statCountValue.setText("-");
            statLastWakeValue.setText("Waiting for Binder token delivery");
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

        if (statA11yValue != null) {
            boolean active = FgTracker.isConnected();
            statA11yValue.setText(active ? "Connected · Active" : "Configured · Awaiting event");
            statA11yValue.setTextColor(active ? Color.parseColor("#81C995") : Color.parseColor("#9AA0A6"));
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
        title.setText("Hey Portal — AudioService Wake v1.3");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Meta Portal native wake-word (audiomanager) -> Chime + Assistant + Auto-dismiss");
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
        statA11yValue = makeRow(cardTel, "Overlay Auto-Dismiss:", "Initializing...");
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
        autoLaunchSwitch.setPadding(0, dp(4), 0, dp(8));
        autoLaunchSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (updatingSwitches) return;
            new Thread(() -> DaemonClient.sendCommand(this, "SET_AUTO_LAUNCH:" + isChecked)).start();
        });
        cardAction.addView(autoLaunchSwitch);

        chimeSwitch = new Switch(this);
        chimeSwitch.setText("Play earcon chime & wake display on detection");
        chimeSwitch.setTextColor(Color.WHITE);
        chimeSwitch.setTextSize(14);
        chimeSwitch.setChecked(true);
        chimeSwitch.setPadding(0, dp(4), 0, dp(8));
        chimeSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (updatingSwitches) return;
            new Thread(() -> DaemonClient.sendCommand(this, "SET_CHIME:" + isChecked)).start();
        });
        cardAction.addView(chimeSwitch);

        autoDismissSwitch = new Switch(this);
        autoDismissSwitch.setText("Auto-dismiss Google Assistant after 15s idle");
        autoDismissSwitch.setTextColor(Color.WHITE);
        autoDismissSwitch.setTextSize(14);
        boolean adDef = getSharedPreferences(FgTracker.PREFS_NAME, MODE_PRIVATE).getBoolean(FgTracker.PREF_AUTO_DISMISS, true);
        autoDismissSwitch.setChecked(adDef);
        autoDismissSwitch.setPadding(0, dp(4), 0, dp(14));
        autoDismissSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (updatingSwitches) return;
            getSharedPreferences(FgTracker.PREFS_NAME, MODE_PRIVATE).edit().putBoolean(FgTracker.PREF_AUTO_DISMISS, isChecked).apply();
            FgTracker.setEnabled(isChecked);
        });
        cardAction.addView(autoDismissSwitch);

        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);

        Button btnTestAssistant = makeButton("TEST ASSISTANT", Color.parseColor("#4285F4"));
        btnTestAssistant.setOnClickListener(v -> {
            try {
                if (chimeSwitch != null && chimeSwitch.isChecked()) {
                    WakeReceiver.playChime(this);
                }
                FgTracker.onNewVoiceInvocation();
                FgTracker.scheduleAutoDismiss();
                Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(this, "Dispatched ACTION_VOICE_COMMAND (auto-dismiss in 15s)", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "Failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        btnRow1.addView(btnTestAssistant, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        addHSpace(btnRow1, 10);

        Button btnTestChime = makeButton("TEST CHIME", Color.parseColor("#00897B"));
        btnTestChime.setOnClickListener(v -> {
            WakeReceiver.playChime(this);
            Toast.makeText(this, "Playing earcon chime", Toast.LENGTH_SHORT).show();
        });
        btnRow1.addView(btnTestChime, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.75f));

        addHSpace(btnRow1, 10);

        Button btnReEnroll = makeButton("RE-ENROLL", Color.parseColor("#34A853"));
        btnReEnroll.setOnClickListener(v -> {
            new Thread(() -> {
                DaemonClient.sendCommand(this, "RE_ENROLL");
                handler.post(this::refreshData);
            }).start();
            Toast.makeText(this, "Requested re-enrollment (" + activeKeyword + ")", Toast.LENGTH_SHORT).show();
        });
        btnRow1.addView(btnReEnroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f));

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
                DaemonClient.sendCommand(this, "SET_KEYWORD:" + next);
                handler.post(this::refreshData);
            }).start();
            Toast.makeText(this, "Switching to " + next, Toast.LENGTH_SHORT).show();
        });
        btnRow2.addView(btnCycleKeyword, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

        addHSpace(btnRow2, 12);

        Button btnStop = makeButton("STOP", Color.parseColor("#D93025"));
        btnStop.setOnClickListener(v -> {
            new Thread(() -> {
                DaemonClient.sendCommand(this, "STOP");
                handler.post(this::refreshData);
            }).start();
            Toast.makeText(this, "Sent STOP command", Toast.LENGTH_SHORT).show();
        });
        btnRow2.addView(btnStop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));

        cardAction.addView(btnRow2);
        root.addView(cardAction);

        addSpace(root, 16);

        // 5. Live Event Log Card
        LinearLayout cardLog = makeCard();
        cardLog.addView(makeSectionTitle("3. LIVE EVENT LOG (Authenticated IPC)"));

        logTextView = new TextView(this);
        logTextView.setText("Loading live events...");
        logTextView.setTextColor(Color.parseColor("#AECBFA"));
        logTextView.setTextSize(11);
        logTextView.setTypeface(Typeface.MONOSPACE);
        logTextView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logTextView.setBackground(makePill(Color.parseColor("#0A0C10"), Color.parseColor("#1F232B"), 8));
        cardLog.addView(logTextView);

        root.addView(cardLog);

        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(makePill(Color.parseColor("#151821"), Color.parseColor("#222736"), 16));
        return card;
    }

    private TextView makeSectionTitle(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(Color.parseColor("#8AB4F8"));
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(14));
        return tv;
    }

    private TextView makeRow(LinearLayout parent, String label, String initialValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(Color.parseColor("#9AA0A6"));
        lbl.setTextSize(13);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));
        row.addView(lbl);

        TextView val = new TextView(this);
        val.setText(initialValue);
        val.setTextColor(Color.WHITE);
        val.setTextSize(13);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.9f));
        row.addView(val);

        parent.addView(row);
        return val;
    }

    private Button makeButton(String text, int bgColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(12);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(12), dp(12), dp(12), dp(12));
        btn.setBackground(makePill(bgColor, darken(bgColor), 10));
        return btn;
    }

    private GradientDrawable makePill(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(1), strokeColor);
        return gd;
    }

    private int darken(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= 0.8f;
        return Color.HSVToColor(hsv);
    }

    private void addSpace(LinearLayout parent, int dpVal) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(dpVal)));
        parent.addView(v);
    }

    private void addHSpace(LinearLayout parent, int dpVal) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(dpVal), ViewGroup.LayoutParams.MATCH_PARENT));
        parent.addView(v);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
