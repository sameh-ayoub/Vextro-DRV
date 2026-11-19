package com.sameh.drover;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_VPN = 101;
    private static final int REQ_NOTIF = 102;

    private Button toggle;
    private TextView status;
    private TextView statUdp;
    private TextView statDecoy;
    private TextView statReturn;
    private TextView statTcp;
    private TextView statIpv6;
    private View rowIpv6;
    private TextView logView;
    private EditText delay;
    private CheckBox exact;
    private CheckBox fake;
    private ImageView imgAr;
    private ImageView imgEn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 800);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toggle = findViewById(R.id.btnToggle);
        status = findViewById(R.id.tvStatus);
        statUdp = findViewById(R.id.statUdp);
        statDecoy = findViewById(R.id.statDecoy);
        statReturn = findViewById(R.id.statReturn);
        statTcp = findViewById(R.id.statTcp);
        statIpv6 = findViewById(R.id.statIpv6);
        rowIpv6 = findViewById(R.id.rowIpv6);
        logView = findViewById(R.id.tvLog);
        delay = findViewById(R.id.etDelay);
        exact = findViewById(R.id.cbExact);
        fake = findViewById(R.id.cbFake);
        imgAr = findViewById(R.id.imgAr);
        imgEn = findViewById(R.id.imgEn);

        SharedPreferences sp = getSharedPreferences(DroverVpnService.PREFS, MODE_PRIVATE);
        delay.setText(String.valueOf(sp.getInt("delay", 50)));
        exact.setChecked(sp.getBoolean("exact74", true));
        fake.setChecked(sp.getBoolean("fake", true));

        toggle.setOnClickListener(v -> {
            if (DroverVpnService.running) {
                stopVpn();
            } else {
                startVpn();
            }
        });

        imgAr.setOnClickListener(v -> selectLang(Lang.AR));
        imgEn.setOnClickListener(v -> selectLang(Lang.EN));

        applyLangMarks();
        requestNotificationPermissionIfNeeded();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private void applyLangMarks() {
        boolean arabic = Lang.AR.equals(Lang.current(this));
        imgAr.setBackgroundResource(arabic ? R.drawable.bg_lang_selected : R.drawable.bg_lang_idle);
        imgEn.setBackgroundResource(arabic ? R.drawable.bg_lang_idle : R.drawable.bg_lang_selected);
    }

    private void selectLang(String code) {
        if (code.equals(Lang.current(this))) {
            applyLangMarks();
            return;
        }
        Lang.set(this, code);
        recreate();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }

    private void saveOptions() {
        int ms = 50;
        try {
            ms = Integer.parseInt(delay.getText().toString().trim());
        } catch (Exception ignored) {
        }
        if (ms < 0) {
            ms = 0;
        }
        if (ms > 1000) {
            ms = 1000;
        }
        delay.setText(String.valueOf(ms));
        getSharedPreferences(DroverVpnService.PREFS, MODE_PRIVATE).edit()
                .putInt("delay", ms)
                .putBoolean("exact74", exact.isChecked())
                .putBoolean("fake", fake.isChecked())
                .apply();
    }

    private void startVpn() {
        saveOptions();
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            startActivityForResult(prepare, REQ_VPN);
            return;
        }
        doStart();
    }

    private void doStart() {
        Intent intent = new Intent(this, DroverVpnService.class)
                .setAction(DroverVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        status.setText(R.string.status_starting);
    }

    private void stopVpn() {
        Intent intent = new Intent(this, DroverVpnService.class)
                .setAction(DroverVpnService.ACTION_STOP);
        startService(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                doStart();
            } else {
                Toast.makeText(this, R.string.toast_vpn_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void refreshUi() {
        boolean run = DroverVpnService.running;
        setText(toggle, getString(run ? R.string.btn_stop : R.string.btn_start));
        if (run) {
            setText(status, getString(R.string.status_running));
        } else if (!DroverVpnService.errorText.isEmpty()) {
            setText(status, getString(R.string.status_error_prefix, DroverVpnService.errorText));
        } else {
            String text = DroverVpnService.statusText;
            setText(status, text.isEmpty() ? getString(R.string.status_stopped) : text);
        }
        setText(statUdp, num(DroverVpnService.udpCount.get()));
        setText(statDecoy, num(DroverVpnService.decoyCount.get()));
        setText(statReturn, num(DroverVpnService.injectedCount.get()));
        setText(statTcp, num(DroverVpnService.tcpCount.get()));
        long v6 = DroverVpnService.ipv6Dropped.get();
        setText(statIpv6, num(v6));
        rowIpv6.setVisibility(v6 > 0 ? View.VISIBLE : View.GONE);
        setText(logView, DroverVpnService.logsText());
    }

    private static String num(long value) {
        return Long.toString(value);
    }

    private static void setText(TextView view, CharSequence value) {
        CharSequence current = view.getText();
        if (current == null || !current.toString().contentEquals(value)) {
            view.setText(value);
        }
    }
}
