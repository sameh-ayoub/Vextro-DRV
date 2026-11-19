package com.sameh.drover;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class TcpRelay {

    private static final String TAG = "VextroTcp";
    private static final int MSS = 1380;
    private static final int READ_TIMEOUT_MS = 40;
    private static final long IDLE_TIMEOUT_MS = 300_000L;
    private static final int MAX_PENDING = 1 << 20;
    private static final int MAX_FLOWS = 128;

    interface TunSink {
        void writeTun(byte[] packet);
    }

    private final VpnService vpn;
    private final TunSink sink;
    private final Map<Long, Flow> flows = new ConcurrentHashMap<>();
    private volatile long lastPurge;

    final AtomicLong accepted = new AtomicLong();
    final AtomicLong bytesToClient = new AtomicLong();

    TcpRelay(VpnService vpn, TunSink sink) {
        this.vpn = vpn;
        this.sink = sink;
    }

    void shutdown() {
        for (Flow f : flows.values()) {
            f.kill();
        }
        flows.clear();
    }

    void onPacket(byte[] b, int total, int ihl, int srcIp, int dstIp) {
        int tcpOff = ihl;
        if (total < tcpOff + 20) {
            return;
        }
        int sport = Net.u16(b, tcpOff);
        int dport = Net.u16(b, tcpOff + 2);
        int seq = (int) Net.u32(b, tcpOff + 4);
        int ackNum = (int) Net.u32(b, tcpOff + 8);
        int dataOff = ((b[tcpOff + 12] & 0xF0) >> 4) * 4;
        int flags = b[tcpOff + 13] & 0xFF;
        int window = Net.u16(b, tcpOff + 14);
        int payloadOff = tcpOff + dataOff;
        int payloadLen = total - payloadOff;
        if (payloadLen < 0) {
            payloadLen = 0;
        }

        long key = key(srcIp, sport, dstIp, dport);
        Flow f = flows.get(key);

        if (f == null || f.dead) {
            if ((flags & Net.SYN) != 0 && (flags & Net.ACK) == 0) {
                if (flows.size() >= MAX_FLOWS) {
                    sendRst(srcIp, dstIp, sport, dport, 0, seq + 1);
                    return;
                }
                f = new Flow(key, srcIp, dstIp, sport, dport, seq);
                flows.put(key, f);
                f.handshake();
            } else {

                sendRst(srcIp, dstIp, sport, dport, 0,
                        seq + payloadLen + (((flags & Net.FIN) != 0) ? 1 : 0));
            }
            return;
        }

        f.onSegment(seq, ackNum, flags, window, b, payloadOff, payloadLen);

        long now = System.currentTimeMillis();
        if (now - lastPurge > 30_000L) {
            lastPurge = now;
            purge(now);
        }
    }

    private void purge(long now) {
        for (Flow f : flows.values()) {
            if (now - f.lastActive > IDLE_TIMEOUT_MS) {
                f.kill();
            }
        }
    }

    private void sendRst(int srcIp, int dstIp, int sport, int dport, int seq, int ack) {
        sink.writeTun(Net.tcp4(dstIp, srcIp, dport, sport, seq, ack,
                Net.RST | Net.ACK, 0, null, 0, 0, false));
    }

    private static long key(int srcIp, int sport, int dstIp, int dport) {
        return ((long) srcIp & 0xFFFFFFFFL) << 32
                | ((long) (sport & 0xFFFF) << 16)
                | (long) (dport & 0xFFFF);
    }

    private final class Flow implements Runnable {
        final long key;
        final int srcIp;
        final int dstIp;
        final int sport;
        final int dport;

        private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
        private int pendingBytes;
        private Socket socket;
        private OutputStream out;
        private InputStream in;

        volatile boolean dead;
        private volatile boolean connected;
        private volatile boolean clientFin;
        volatile long lastActive = System.currentTimeMillis();

        private int sndNxt;
        private int rcvNxt;
        private int cliAck;

        Flow(long key, int srcIp, int dstIp, int sport, int dport, int clientSeq) {
            this.key = key;
            this.srcIp = srcIp;
            this.dstIp = dstIp;
            this.sport = sport;
            this.dport = dport;
            int isn = (int) ((System.nanoTime() & 0x7FFFFFFFL) ^ (((long) sport) << 16) ^ dport);
            this.sndNxt = isn;
            this.rcvNxt = clientSeq + 1;
            this.cliAck = isn + 1;
        }

        void handshake() {
            sendSegment(sndNxt, rcvNxt, Net.SYN | Net.ACK, 65535, null, 0, 0, true);
            sndNxt += 1;
            accepted.incrementAndGet();
            Thread t = new Thread(this, "tcp-" + dport);
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void run() {

            SocketChannel channel = null;
            Socket s;
            try {
                channel = SocketChannel.open();
                s = channel.socket();
                socket = s;
                if (!vpn.protect(s)) {
                    Log.w(TAG, vpn.getString(R.string.err_protect_tcp));
                }
                InetAddress addr = Net.toAddress(dstIp);
                if (addr == null) {
                    throw new IOException("bad address");
                }
                s.connect(new InetSocketAddress(addr, dport), 8000);
                s.setSoTimeout(READ_TIMEOUT_MS);
                s.setTcpNoDelay(true);
                in = s.getInputStream();
                out = s.getOutputStream();
                connected = true;

                byte[] rbuf = new byte[MSS];
                while (!dead) {
                    flushPending();
                    int n;
                    try {
                        n = in.read(rbuf);
                    } catch (SocketTimeoutException e) {

                        continue;
                    }
                    if (n < 0) {
                        sendFin();
                        break;
                    }
                    if (n > 0) {
                        sendData(rbuf, n);
                    }
                }
            } catch (IOException e) {
                if (!dead) {
                    Log.w(TAG, "tcp " + Net.ipToString(dstIp) + ":" + dport + " → " + e);
                    sendRstToClient();
                }
            } finally {
                closeQuietly();
                try {
                    if (channel != null) {
                        channel.close();
                    }
                } catch (Exception ignored) {
                }
                dead = true;
                flows.remove(key, this);
            }
        }

        private void flushPending() throws IOException {
            while (true) {
                byte[] chunk;
                synchronized (this) {
                    chunk = pending.poll();
                    if (chunk == null) {
                        return;
                    }
                    pendingBytes -= chunk.length;
                }
                if (chunk.length > 0) {
                    out.write(chunk);
                }
                out.flush();
            }
        }

        private synchronized void sendData(byte[] data, int len) {
            int off = 0;
            while (off < len && !dead) {
                long inflight = ((long) (sndNxt - cliAck)) & 0xFFFFFFFFL;
                if (inflight > 512 * 1024) {
                    try {
                        wait(10);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                int chunk = Math.min(MSS, len - off);
                sendSegment(sndNxt, rcvNxt, Net.ACK | Net.PSH, 65535, data, off, chunk, false);
                sndNxt += chunk;
                off += chunk;
                bytesToClient.addAndGet(chunk);
            }
        }

        private synchronized void sendFin() {
            if (dead) {
                return;
            }
            sendSegment(sndNxt, rcvNxt, Net.ACK | Net.FIN, 65535, null, 0, 0, false);
            sndNxt += 1;
        }

        private synchronized void sendRstToClient() {
            sendSegment(sndNxt, rcvNxt, Net.RST | Net.ACK, 0, null, 0, 0, false);
        }

        synchronized void onSegment(int seq, int ackNum, int flags, int window,
                                    byte[] b, int off, int len) {
            if (dead) {
                return;
            }
            lastActive = System.currentTimeMillis();

            if ((flags & Net.ACK) != 0) {
                cliAck = ackNum;
                notifyAll();
            }
            if ((flags & Net.RST) != 0) {
                kill();
                return;
            }

            if (len > 0) {
                if (seq == rcvNxt && pendingBytes < MAX_PENDING) {
                    byte[] copy = new byte[len];
                    System.arraycopy(b, off, copy, 0, len);
                    pending.add(copy);
                    pendingBytes += len;
                    rcvNxt += len;
                }

                sendSegment(sndNxt, rcvNxt, Net.ACK, 65535, null, 0, 0, false);
            }

            if ((flags & Net.FIN) != 0) {
                if ((int) (seq + len - rcvNxt) == 0) {
                    rcvNxt += 1;
                    clientFin = true;

                    sendSegment(sndNxt, rcvNxt, Net.ACK, 65535, null, 0, 0, false);
                    try {
                        if (out != null) {
                            out.flush();
                        }
                        Socket s = socket;
                        if (s != null && !s.isOutputShutdown()) {
                            s.shutdownOutput();
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private void sendSegment(int seq, int ack, int flags, int window,
                                 byte[] data, int off, int len, boolean mssOption) {
            sink.writeTun(Net.tcp4(dstIp, srcIp, dport, sport, seq, ack, flags, window,
                    data, off, len, mssOption));
        }

        void kill() {
            dead = true;
            closeQuietly();
            flows.remove(key, this);
        }

        private void closeQuietly() {
            Socket s = socket;
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }

        boolean isConnected() {
            return connected;
        }
    }
}
