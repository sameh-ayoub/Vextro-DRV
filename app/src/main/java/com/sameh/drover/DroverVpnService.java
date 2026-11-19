package com.sameh.drover;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public class DroverVpnService extends VpnService {

    private static final String TAG = "Vextro";

    public static final String ACTION_START = "com.sameh.drover.START";
    public static final String ACTION_STOP = "com.sameh.drover.STOP";
    public static final String DISCORD_PACKAGE = "com.discord";
    public static final String PREFS = "drover";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "vextro_tunnel";

    static volatile boolean running = false;
    static volatile String statusText = "";
    static volatile String errorText = "";
    static final AtomicLong udpCount = new AtomicLong();
    static final AtomicLong decoyCount = new AtomicLong();
    static final AtomicLong injectedCount = new AtomicLong();
    static final AtomicLong tcpCount = new AtomicLong();
    static final AtomicLong ipv6Dropped = new AtomicLong();

    private static final ArrayDeque<String> LOGS = new ArrayDeque<>();

    private ParcelFileDescriptor tunFd;
    private FileInputStream tin;
    private FileOutputStream tout;
    private volatile boolean active;
    private final Object tunLock = new Object();

    private UdpRelay udpRelay;
    private TcpRelay tcpRelay;
    private Thread readerThread;
    private int delayMs = 50;

    static void log(String line) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (LOGS) {
            LOGS.addFirst(t + "  " + line);
            while (LOGS.size() > 80) {
                LOGS.removeLast();
            }
        }
        Log.i(TAG, line);
    }

    static String logsText() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOGS) {
            for (String s : LOGS) {
                sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            log(getString(R.string.log_manual_stop));
            stopTunnel();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_preparing)));

        if (active) {
            return START_STICKY;
        }

        errorText = "";
        new Thread(this::startTunnel, "vextro-start").start();
        return START_STICKY;
    }

    private void startTunnel() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            delayMs = sp.getInt("delay", 50);
            boolean exact74 = sp.getBoolean("exact74", true);
            boolean useFakePacket = sp.getBoolean("fake", true);

            Builder builder = new Builder();
            builder.setSession("Vextro DRV");
            builder.addAddress("10.0.0.2", 32);
            builder.addRoute("0.0.0.0", 0);
            builder.setMtu(1500);
            builder.setBlocking(true);

            try {
                builder.addAllowedApplication(DISCORD_PACKAGE);
            } catch (PackageManager.NameNotFoundException e) {
                fail(getString(R.string.error_discord_missing));
                return;
            }

            addDnsServers(builder);

            tunFd = builder.establish();
            if (tunFd == null) {
                fail(getString(R.string.error_establish));
                return;
            }

            tin = new FileInputStream(tunFd.getFileDescriptor());
            tout = new FileOutputStream(tunFd.getFileDescriptor());

            byte[] fake = useFakePacket ? readAsset("drover-packet.bin") : null;

            udpRelay = new UdpRelay(this, this::writeTun);
            udpRelay.setOptions(delayMs, exact74, fake);
            tcpRelay = new TcpRelay(this, this::writeTun);

            resetStats();
            active = true;
            running = true;
            statusText = getString(R.string.status_running);
            log(getString(R.string.log_opened, delayMs,
                    getString(exact74 ? R.string.yes : R.string.no),
                    getString(fake != null ? R.string.yes : R.string.no)));
            updateNotification(getString(R.string.notif_active));

            readerThread = new Thread(this::readLoop, "vextro-tun");
            readerThread.start();
        } catch (Throwable t) {
            Log.e(TAG, "start failure", t);
            fail(getString(R.string.error_start, String.valueOf(t)));
        }
    }

    private void addDnsServers(Builder builder) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            Network net = cm.getActiveNetwork();
            LinkProperties lp = net != null ? cm.getLinkProperties(net) : null;
            if (lp == null) {
                return;
            }
            int added = 0;
            for (java.net.InetAddress addr : lp.getDnsServers()) {
                byte[] raw = addr.getAddress();
                if (raw.length != 4) {
                    continue;
                }
                builder.addDnsServer(addr.getHostAddress());
                added++;
                if (added >= 2) {
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "dns " + t);
        }
    }

    private byte[] readAsset(String name) {
        try (InputStream is = getAssets().open(name)) {
            byte[] buf = new byte[is.available()];
            int read = is.read(buf);
            if (read <= 0) {
                return null;
            }
            byte[] out = new byte[read];
            System.arraycopy(buf, 0, out, 0, read);
            log(getString(R.string.log_asset, name, read));
            return out;
        } catch (IOException e) {
            Log.w(TAG, "asset " + e);
            return null;
        }
    }

    private void readLoop() {
        byte[] buf = new byte[32768];
        while (active) {
            int n;
            try {
                n = tin.read(buf);
            } catch (IOException e) {
                if (active) {
                    Log.w(TAG, "tun read " + e);
                }
                break;
            }
            if (n <= 0) {
                continue;
            }
            try {
                dispatch(buf, n);
            } catch (Throwable t) {
                Log.w(TAG, "dispatch " + t);
            }
        }
        if (active) {
            fail(getString(R.string.error_unexpected));
        }
    }

    private void dispatch(byte[] buf, int n) {
        if (n < 20) {
            return;
        }
        int version = (buf[0] & 0xF0) >> 4;
        if (version != 4) {
            ipv6Dropped.incrementAndGet();
            return;
        }
        int ihl = (buf[0] & 0x0F) * 4;
        if (ihl < 20 || n < ihl + 8) {
            return;
        }
        int total = Net.u16(buf, 2);
        if (total <= 0 || total > n) {
            total = n;
        }
        int proto = buf[9] & 0xFF;
        int srcIp = Net.ip4(buf, 12);
        int dstIp = Net.ip4(buf, 16);

        if (proto == Net.PROTO_UDP) {
            int sport = Net.u16(buf, ihl);
            int dport = Net.u16(buf, ihl + 2);
            int payloadOff = ihl + 8;
            int payloadLen = total - payloadOff;
            if (payloadLen < 0) {
                return;
            }
            udpCount.incrementAndGet();
            udpRelay.onPacket(srcIp, dstIp, sport, dport, buf, payloadOff, payloadLen);
            decoyCount.set(udpRelay.decoys.get());
            injectedCount.set(udpRelay.injected.get());
        } else if (proto == Net.PROTO_TCP) {
            tcpCount.set(tcpRelay.accepted.get());
            tcpRelay.onPacket(buf, total, ihl, srcIp, dstIp);
        }
    }

    private void writeTun(byte[] packet) {
        synchronized (tunLock) {
            FileOutputStream o = tout;
            if (o == null || !active) {
                return;
            }
            try {
                o.write(packet);
            } catch (IOException e) {
                Log.w(TAG, "tun write " + e);
            }
        }
    }

    private void resetStats() {
        udpCount.set(0);
        decoyCount.set(0);
        injectedCount.set(0);
        tcpCount.set(0);
        ipv6Dropped.set(0);
    }

    private void fail(String message) {
        errorText = message;
        statusText = getString(R.string.status_error);
        log(message);
        stopTunnel();
        try {
            updateNotification(message);
        } catch (Throwable ignored) {
        }
        running = false;
        stopForeground(false);
    }

    private void stopTunnel() {
        if (!active && tin == null && tunFd == null) {
            return;
        }
        active = false;
        running = false;
        statusText = getString(R.string.status_stopped);

        if (udpRelay != null) {
            udpRelay.shutdown();
            udpRelay = null;
        }
        if (tcpRelay != null) {
            tcpRelay.shutdown();
            tcpRelay = null;
        }
        try {
            if (tin != null) {
                tin.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (tout != null) {
                tout.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (tunFd != null) {
                tunFd.close();
            }
        } catch (Exception ignored) {
        }
        tin = null;
        tout = null;
        tunFd = null;
        log(getString(R.string.log_closed));
    }

    @Override
    public void onRevoke() {
        log(getString(R.string.log_revoked));
        stopTunnel();
        stopForeground(true);
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, DroverVpnService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle("Vextro DRV")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build());
        return b.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
