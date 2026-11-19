package com.sameh.drover;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

final class UdpRelay {

    private static final String TAG = "VextroUdp";
    private static final long IDLE_TIMEOUT_MS = 180_000L;
    private static final int MAX_FLOWS = 48;
    private static final int MAX_EARLY = 64;

    interface TunSink {
        void writeTun(byte[] packet);
    }

    private final VpnService vpn;
    private final TunSink sink;
    private final Map<Long, Flow> flows = new ConcurrentHashMap<>();
    private final ExecutorService decoyPool = Executors.newCachedThreadPool();
    private final Selector selector;
    private final Thread loopThread;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(65535);
    private volatile boolean running = true;
    private volatile long lastPurge;

    private volatile int delayMs = 50;
    private volatile boolean exact74 = true;
    private volatile byte[] fakePacket;

    final AtomicLong intercepted = new AtomicLong();
    final AtomicLong decoys = new AtomicLong();
    final AtomicLong injected = new AtomicLong();

    UdpRelay(VpnService vpn, TunSink sink) throws IOException {
        this.vpn = vpn;
        this.sink = sink;
        this.selector = Selector.open();
        this.loopThread = new Thread(this::loop, "udp-loop");
        this.loopThread.setDaemon(true);
        this.loopThread.start();
    }

    void setOptions(int delayMs, boolean exact74, byte[] fakePacket) {
        this.delayMs = Math.max(0, Math.min(1000, delayMs));
        this.exact74 = exact74;
        this.fakePacket = (fakePacket != null && fakePacket.length > 0) ? fakePacket : null;
    }

    void shutdown() {
        running = false;
        selector.wakeup();
        decoyPool.shutdownNow();
        for (Flow f : flows.values()) {
            f.close();
        }
        flows.clear();
    }

    void onPacket(int srcIp, int dstIp, int sport, int dport,
                  byte[] buf, int off, int len) {
        long key = key(srcIp, sport, dstIp, dport);
        Flow f = flows.get(key);
        if (f == null) {
            if (flows.size() >= MAX_FLOWS) {
                return;
            }
            f = createFlow(key, srcIp, dstIp, sport, dport);
            if (f == null) {
                return;
            }
        }
        f.lastActive = System.currentTimeMillis();
        intercepted.incrementAndGet();
        f.send(buf, off, len);
    }

    private boolean shouldDecoy(int len) {
        return !exact74 || len == 74;
    }

    private Flow createFlow(long key, int srcIp, int dstIp, int sport, int dport) {
        InetAddress addr = Net.toAddress(dstIp);
        if (addr == null) {
            return null;
        }
        try {
            DatagramChannel ch = DatagramChannel.open();
            ch.configureBlocking(false);
            if (!vpn.protect(ch.socket())) {
                Log.w(TAG, vpn.getString(R.string.err_protect_udp));
            }
            ch.connect(new InetSocketAddress(addr, dport));
            Flow f = new Flow(key, srcIp, dstIp, sport, dport, ch);
            flows.put(key, f);
            selector.wakeup();
            ch.register(selector, SelectionKey.OP_READ, f);
            Log.i(TAG, vpn.getString(R.string.flow_new, Net.ipToString(dstIp), dport));
            return f;
        } catch (IOException e) {
            Log.w(TAG, "create flow: " + e);
            return null;
        }
    }

    private void loop() {
        long purgeTick = 0;
        while (running) {
            try {
                selector.select(200);

                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove();
                    if (!k.isValid()) {
                        continue;
                    }
                    Flow f = (Flow) k.attachment();
                    if (f == null || f.closed) {
                        continue;
                    }
                    try {
                        readBuffer.clear();
                        int n = f.channel.read(readBuffer);
                        if (n > 0) {
                            f.lastActive = System.currentTimeMillis();
                            sink.writeTun(Net.udp4(f.dstIp, f.srcIp, f.dport, f.sport,
                                    readBuffer.array(), 0, n));
                            injected.incrementAndGet();
                        }
                    } catch (IOException e) {
                        f.close();
                        flows.remove(f.key, f);
                    }
                }

                long now = System.currentTimeMillis();
                if (now - purgeTick > 30_000L) {
                    purgeTick = now;
                    for (Flow f : flows.values()) {
                        if (now - f.lastActive > IDLE_TIMEOUT_MS) {
                            f.close();
                            flows.remove(f.key, f);
                        }
                    }
                }
            } catch (ClosedSelectorException e) {
                break;
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "selector: " + e);
                }
                break;
            } catch (Throwable t) {
                Log.w(TAG, "loop: " + t);
                break;
            }
        }
        try {
            selector.close();
        } catch (IOException ignored) {
        }
    }

    private static long key(int srcIp, int sport, int dstIp, int dport) {
        return ((long) srcIp & 0xFFFFFFFFL) << 32
                | ((long) (sport & 0xFFFF) << 16)
                | (long) (dport & 0xFFFF);
    }

    private final class Flow {
        final long key;
        final int srcIp;
        final int dstIp;
        final int sport;
        final int dport;
        final DatagramChannel channel;

        volatile boolean decoyDone;
        volatile boolean decoyRunning;
        volatile boolean closed;
        volatile long lastActive = System.currentTimeMillis();

        private final ArrayDeque<byte[]> early = new ArrayDeque<>();

        Flow(long key, int srcIp, int dstIp, int sport, int dport, DatagramChannel channel) {
            this.key = key;
            this.srcIp = srcIp;
            this.dstIp = dstIp;
            this.sport = sport;
            this.dport = dport;
            this.channel = channel;
        }

        void send(byte[] data, int off, int len) {
            if (closed) {
                return;
            }
            if (!decoyDone && shouldDecoy(len)) {
                decoyDone = true;
                decoyRunning = true;
                final byte[] copy = Arrays.copyOfRange(data, off, off + len);
                decoyPool.execute(() -> runDecoy(copy));
                return;
            }
            if (decoyRunning) {
                synchronized (early) {
                    if (early.size() < MAX_EARLY) {
                        early.add(Arrays.copyOfRange(data, off, off + len));
                    }
                }
                return;
            }
            writeRaw(data, off, len);
        }

        private void runDecoy(byte[] real) {
            try {
                decoys.incrementAndGet();
                byte[] fake = fakePacket;
                if (fake != null) {
                    writeRaw(fake, 0, fake.length);
                }
                writeRaw(new byte[]{0x00}, 0, 1);
                writeRaw(new byte[]{0x01}, 0, 1);
                int d = delayMs;
                if (d > 0) {
                    try {
                        Thread.sleep(d);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "decoy: " + t);
            }
            writeRaw(real, 0, real.length);

            while (true) {
                byte[] next;
                synchronized (early) {
                    next = early.poll();
                    if (next == null) {
                        decoyRunning = false;
                        return;
                    }
                }
                writeRaw(next, 0, next.length);
            }
        }

        private void writeRaw(byte[] data, int off, int len) {
            if (closed || len <= 0) {
                return;
            }
            try {
                channel.write(ByteBuffer.wrap(data, off, len));
            } catch (IOException e) {
                Log.w(TAG, "udp send: " + e);
                close();
                flows.remove(key, this);
            }
        }

        void close() {
            closed = true;
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }
}
