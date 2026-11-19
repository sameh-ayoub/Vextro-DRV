package com.sameh.drover;

final class Net {

    static final int PROTO_TCP = 6;
    static final int PROTO_UDP = 17;

    static final int FIN = 0x01;
    static final int SYN = 0x02;
    static final int RST = 0x04;
    static final int PSH = 0x08;
    static final int ACK = 0x10;

    private Net() {
    }

    static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    static long u32(byte[] b, int i) {
        return ((long) (b[i] & 0xFF) << 24)
                | ((long) (b[i + 1] & 0xFF) << 16)
                | ((long) (b[i + 2] & 0xFF) << 8)
                | (long) (b[i + 3] & 0xFF);
    }

    static int ip4(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    static void p16(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 8);
        b[i + 1] = (byte) v;
    }

    static void p32(byte[] b, int i, long v) {
        b[i] = (byte) (v >>> 24);
        b[i + 1] = (byte) (v >>> 16);
        b[i + 2] = (byte) (v >>> 8);
        b[i + 3] = (byte) v;
    }

    static int checksum(byte[] b, int off, int len, int sum) {
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            sum += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) {
            sum += (b[i] & 0xFF) << 8;
        }
        sum = (sum & 0xFFFF) + (sum >>> 16);
        sum = (sum & 0xFFFF) + (sum >>> 16);
        return (~sum) & 0xFFFF;
    }

    private static int pseudoHeader(int src, int dst, int proto, int len) {
        int sum = 0;
        sum += (src >>> 16) & 0xFFFF;
        sum += src & 0xFFFF;
        sum += (dst >>> 16) & 0xFFFF;
        sum += dst & 0xFFFF;
        sum += proto;
        sum += len;
        return sum;
    }

    static byte[] udp4(int srcIp, int dstIp, int sport, int dport,
                       byte[] payload, int off, int len) {
        int total = 20 + 8 + len;
        byte[] p = new byte[total];

        p[0] = 0x45;
        p[8] = 64;
        p[9] = (byte) PROTO_UDP;
        p16(p, 2, total);
        p16(p, 6, 0x4000);
        p32(p, 12, srcIp & 0xFFFFFFFFL);
        p32(p, 16, dstIp & 0xFFFFFFFFL);

        p16(p, 20, sport);
        p16(p, 22, dport);
        p16(p, 24, 8 + len);
        p16(p, 26, 0);
        if (len > 0) {
            System.arraycopy(payload, off, p, 28, len);
        }

        p16(p, 10, checksum(p, 0, 20, 0));
        int c = checksum(p, 20, 8 + len, pseudoHeader(srcIp, dstIp, PROTO_UDP, 8 + len));

        p16(p, 26, c == 0 ? 0xFFFF : c);
        return p;
    }

    static byte[] tcp4(int srcIp, int dstIp, int sport, int dport,
                       int seq, int ack, int flags, int window,
                       byte[] data, int off, int len, boolean mssOption) {
        int hdr = mssOption ? 24 : 20;
        byte[] p = new byte[20 + hdr + len];

        p[0] = 0x45;
        p[8] = 64;
        p[9] = (byte) PROTO_TCP;
        p16(p, 2, p.length);
        p16(p, 6, 0x4000);
        p32(p, 12, srcIp & 0xFFFFFFFFL);
        p32(p, 16, dstIp & 0xFFFFFFFFL);

        int t = 20;
        p16(p, t, sport);
        p16(p, t + 2, dport);
        p32(p, t + 4, seq & 0xFFFFFFFFL);
        p32(p, t + 8, ack & 0xFFFFFFFFL);
        p[t + 12] = (byte) ((hdr / 4) << 4);
        p[t + 13] = (byte) flags;
        p16(p, t + 14, window);
        p16(p, t + 16, 0);
        p16(p, t + 18, 0);
        if (mssOption) {
            p[t + 20] = 2;
            p[t + 21] = 4;
            p16(p, t + 22, 1400);
        }
        if (len > 0) {
            System.arraycopy(data, off, p, t + hdr, len);
        }

        p16(p, 10, checksum(p, 0, 20, 0));
        int c = checksum(p, t, hdr + len, pseudoHeader(srcIp, dstIp, PROTO_TCP, hdr + len));
        p16(p, t + 16, c == 0 ? 0xFFFF : c);
        return p;
    }

    static String ipToString(int ip) {
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "."
                + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    static java.net.InetAddress toAddress(int ip) {
        byte[] b = new byte[]{
                (byte) (ip >>> 24), (byte) (ip >>> 16), (byte) (ip >>> 8), (byte) ip
        };
        try {
            return java.net.InetAddress.getByAddress(b);
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }
}
