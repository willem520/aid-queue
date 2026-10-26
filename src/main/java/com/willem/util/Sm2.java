package com.willem.util;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * SM2（sm2p256v1）与 SM3 的最小实现，仅覆盖津医保号源接口所需的
 * C1C3C2 加密/解密，避免运行时依赖 Python 脚本。
 */
public final class Sm2 {

    private static final BigInteger P =
            new BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFF", 16);
    private static final BigInteger A =
            new BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFC", 16);
    private static final BigInteger N =
            new BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFF7203DF6B21C6052B53BBF40939D54123", 16);
    private static final BigInteger GX =
            new BigInteger("32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7", 16);
    private static final BigInteger GY =
            new BigInteger("BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0", 16);

    private static final SecureRandom RANDOM = new SecureRandom();

    private Sm2() {
    }

    /** 仿射坐标点；null 表示无穷远点 */
    private record Point(BigInteger x, BigInteger y) {
    }

    /**
     * SM2 加密，输出 {@code 04||x1||y1||C3||C2} 的 hex 串（C1C3C2 布局）
     *
     * @param plaintext 明文字节
     * @param qx        接收方公钥 x
     * @param qy        接收方公钥 y
     */
    public static String encrypt(byte[] plaintext, BigInteger qx, BigInteger qy) {
        Point pub = new Point(qx, qy);
        while (true) {
            BigInteger k = nextScalar();
            Point c1Point = multiply(k, G());
            Point x2y2 = multiply(k, pub);
            if (x2y2 == null) {
                continue;
            }
            byte[] x2b = to32(x2y2.x().toByteArray());
            byte[] y2b = to32(x2y2.y().toByteArray());
            byte[] z = concat(x2b, y2b);
            byte[] t = kdf(z, plaintext.length);
            if (isAllZero(t)) {
                continue; // k 退化，换下一个
            }
            byte[] c2 = new byte[plaintext.length];
            for (int i = 0; i < plaintext.length; i++) {
                c2[i] = (byte) (plaintext[i] ^ t[i]);
            }
            byte[] c3 = sm3(concat(concat(x2b, plaintext), y2b));
            byte[] c1 = concat(new byte[]{0x04},
                    concat(to32(c1Point.x().toByteArray()), to32(c1Point.y().toByteArray())));
            return toHex(concat(concat(c1, c3), c2));
        }
    }

    /**
     * SM2 解密（C1C3C2）
     *
     * @param cipherHex 密文 hex，可带 {@code 04} 前缀
     * @param dHex      私钥 hex
     * @return 明文；C3 校验失败时抛异常
     */
    public static byte[] decrypt(String cipherHex, String dHex) {
        byte[] data = fromHex(cipherHex);
        int offset = data[0] == 0x04 ? 1 : 0;
        BigInteger x1 = new BigInteger(1, slice(data, offset, 32));
        BigInteger y1 = new BigInteger(1, slice(data, offset + 32, 32));
        byte[] rest = slice(data, offset + 64, data.length - offset - 64);
        byte[] c3 = slice(rest, 0, 32);
        byte[] c2 = slice(rest, 32, rest.length - 32);

        BigInteger d = new BigInteger(1, fromHex(dHex));
        Point s = multiply(d, new Point(x1, y1));
        if (s == null) {
            throw new IllegalStateException("SM2 解密失败：私钥与密文 C1 不匹配");
        }
        byte[] x2b = to32(s.x().toByteArray());
        byte[] y2b = to32(s.y().toByteArray());
        byte[] z = concat(x2b, y2b);
        byte[] t = kdf(z, c2.length);
        byte[] plaintext = new byte[c2.length];
        for (int i = 0; i < c2.length; i++) {
            plaintext[i] = (byte) (c2[i] ^ t[i]);
        }
        byte[] check = sm3(concat(concat(x2b, plaintext), y2b));
        if (!java.util.Arrays.equals(check, c3)) {
            throw new IllegalStateException("SM2 解密失败：C3 校验不通过，私钥与本次会话公钥不匹配");
        }
        return plaintext;
    }

    /** SM3 摘要 */
    public static byte[] sm3(byte[] msg) {
        int[] v = {0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
                0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e};
        byte[] padded = withSm3Padding(msg);
        for (int off = 0; off < padded.length; off += 64) {
            int[] w = new int[68];
            for (int i = 0; i < 16; i++) {
                w[i] = ((padded[off + i * 4] & 0xff) << 24) | ((padded[off + i * 4 + 1] & 0xff) << 16)
                        | ((padded[off + i * 4 + 2] & 0xff) << 8) | (padded[off + i * 4 + 3] & 0xff);
            }
            for (int j = 16; j < 68; j++) {
                w[j] = p1(w[j - 16] ^ w[j - 9] ^ rotl(w[j - 3], 15)) ^ rotl(w[j - 13], 7) ^ w[j - 6];
            }
            int[] wp = new int[64];
            for (int j = 0; j < 64; j++) {
                wp[j] = w[j] ^ w[j + 4];
            }
            int a = v[0], b = v[1], c = v[2], d = v[3], e = v[4], f = v[5], g = v[6], h = v[7];
            for (int j = 0; j < 64; j++) {
                int t = j < 16 ? 0x79cc4519 : 0x7a879d8a;
                int ss1 = rotl(rotl(a, 12) + e + rotl(t, j % 32), 7);
                int ss2 = ss1 ^ rotl(a, 12);
                int ff = j < 16 ? a ^ b ^ c : (a & b) | (a & c) | (b & c);
                int gg = j < 16 ? e ^ f ^ g : (e & f) | (~e & g);
                int tt1 = ff + d + ss2 + wp[j];
                int tt2 = gg + h + ss1 + w[j];
                d = c;
                c = rotl(b, 9);
                b = a;
                a = tt1;
                h = g;
                g = rotl(f, 19);
                f = e;
                e = p0(tt2);
            }
            v[0] ^= a; v[1] ^= b; v[2] ^= c; v[3] ^= d;
            v[4] ^= e; v[5] ^= f; v[6] ^= g; v[7] ^= h;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (v[i] >>> 24);
            out[i * 4 + 1] = (byte) (v[i] >>> 16);
            out[i * 4 + 2] = (byte) (v[i] >>> 8);
            out[i * 4 + 3] = (byte) v[i];
        }
        return out;
    }

    /** 解析未压缩公钥 hex（可带 04 前缀）得到 x */
    public static BigInteger publicKeyX(String uncompressedHex) {
        byte[] raw = fromHex(uncompressedHex);
        int offset = raw.length == 65 && raw[0] == 0x04 ? 1 : 0;
        return new BigInteger(1, slice(raw, offset, 32));
    }

    /** 解析未压缩公钥 hex（可带 04 前缀）得到 y */
    public static BigInteger publicKeyY(String uncompressedHex) {
        byte[] raw = fromHex(uncompressedHex);
        int offset = raw.length == 65 && raw[0] == 0x04 ? 1 : 0;
        return new BigInteger(1, slice(raw, offset + 32, 32));
    }

    // ---------- 内部 ----------

    private static Point G() {
        return new Point(GX, GY);
    }

    private static BigInteger nextScalar() {
        BigInteger k;
        do {
            k = new BigInteger(256, RANDOM);
        } while (k.signum() <= 0 || k.compareTo(N) >= 0);
        return k;
    }

    private static Point add(Point p1, Point p2) {
        if (p1 == null) {
            return p2;
        }
        if (p2 == null) {
            return p1;
        }
        if (p1.x().equals(p2.x()) && p1.y().add(p2.y()).mod(P).signum() == 0) {
            return null;
        }
        BigInteger lam;
        if (p1.x().equals(p2.x()) && p1.y().equals(p2.y())) {
            lam = p1.x().multiply(p1.x()).multiply(BigInteger.valueOf(3)).add(A)
                    .multiply(p1.y().shiftLeft(1).modInverse(P)).mod(P);
        } else {
            lam = p2.y().subtract(p1.y())
                    .multiply(p2.x().subtract(p1.x()).mod(P).modInverse(P)).mod(P);
        }
        BigInteger x3 = lam.multiply(lam).subtract(p1.x()).subtract(p2.x()).mod(P);
        BigInteger y3 = lam.multiply(p1.x().subtract(x3)).subtract(p1.y()).mod(P);
        return new Point(x3, y3);
    }

    private static Point multiply(BigInteger k, Point point) {
        Point r = null;
        Point addend = point;
        BigInteger scalar = k;
        while (scalar.signum() > 0) {
            if (scalar.testBit(0)) {
                r = add(r, addend);
            }
            addend = add(addend, addend);
            scalar = scalar.shiftRight(1);
        }
        return r;
    }

    private static byte[] kdf(byte[] z, int length) {
        byte[] out = new byte[length];
        int counter = 1;
        int done = 0;
        while (done < length) {
            byte[] round = sm3(concat(z, new byte[]{
                    (byte) (counter >>> 24), (byte) (counter >>> 16), (byte) (counter >>> 8), (byte) counter}));
            int n = Math.min(round.length, length - done);
            System.arraycopy(round, 0, out, done, n);
            done += n;
            counter++;
        }
        return out;
    }

    private static boolean isAllZero(byte[] b) {
        for (byte value : b) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] withSm3Padding(byte[] msg) {
        long bitLen = (long) msg.length * 8;
        int blocks = (msg.length + 9 + 63) / 64;
        byte[] out = new byte[blocks * 64];
        System.arraycopy(msg, 0, out, 0, msg.length);
        out[msg.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            out[out.length - 1 - i] = (byte) (bitLen >>> (8 * i));
        }
        return out;
    }

    private static int rotl(int x, int n) {
        n %= 32;
        return n == 0 ? x : (x << n) | (x >>> (32 - n));
    }

    private static int p0(int x) {
        return x ^ rotl(x, 9) ^ rotl(x, 17);
    }

    private static int p1(int x) {
        return x ^ rotl(x, 15) ^ rotl(x, 23);
    }

    private static byte[] to32(byte[] value) {
        if (value.length == 32) {
            return value;
        }
        byte[] out = new byte[32];
        // BigInteger.toByteArray 可能带符号零或裁掉前导零，统一右对齐
        System.arraycopy(value, Math.max(0, value.length - 32), out, Math.max(0, 32 - value.length),
                Math.min(value.length, 32));
        return out;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    private static byte[] slice(byte[] src, int from, int length) {
        byte[] out = new byte[length];
        System.arraycopy(src, from, out, 0, length);
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        String cleaned = hex.trim().replaceAll("^\"|\"$", "");
        byte[] out = new byte[cleaned.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
