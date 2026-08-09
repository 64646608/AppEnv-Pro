package com.dnl.appenv.pro.xposed;

public final class Xxtea {
    private static final int DELTA = 0x9E3779B9;

    private Xxtea() {
    }

    public static byte[] encrypt(byte[] data, String key) {
        try {
            return encrypt(data, key.getBytes("UTF-8"));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public static byte[] decrypt(byte[] data, String key) {
        try {
            return decrypt(data, key.getBytes("UTF-8"));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public static byte[] encrypt(byte[] data, byte[] key) {
        if (data == null || data.length == 0) {
            return data;
        }
        int[] values = toIntArray(data, true);
        int[] keyValues = fixKey(key);
        int n = values.length - 1;
        int rounds = 6 + 52 / (n + 1);
        int sum = 0;
        int z = values[n];
        while (rounds-- > 0) {
            sum += DELTA;
            int e = (sum >>> 2) & 3;
            int p;
            for (p = 0; p < n; p++) {
                int y = values[p + 1];
                z = values[p] += mx(sum, y, z, p, e, keyValues);
            }
            int y = values[0];
            z = values[n] += mx(sum, y, z, p, e, keyValues);
        }
        return toByteArray(values, false);
    }

    public static byte[] decrypt(byte[] data, byte[] key) {
        if (data == null || data.length == 0) {
            return data;
        }
        if ((data.length & 3) != 0) {
            throw new IllegalArgumentException("XXTEA cipher length is not aligned");
        }
        int[] values = toIntArray(data, false);
        int[] keyValues = fixKey(key);
        int n = values.length - 1;
        if (n < 1) {
            return data;
        }
        int rounds = 6 + 52 / (n + 1);
        int sum = rounds * DELTA;
        int y = values[0];
        while (sum != 0) {
            int e = (sum >>> 2) & 3;
            int p;
            for (p = n; p > 0; p--) {
                int z = values[p - 1];
                y = values[p] -= mx(sum, y, z, p, e, keyValues);
            }
            int z = values[n];
            y = values[0] -= mx(sum, y, z, p, e, keyValues);
            sum -= DELTA;
        }
        return toByteArray(values, true);
    }

    private static int mx(int sum, int y, int z, int p, int e, int[] key) {
        return (((z >>> 5 ^ y << 2) + (y >>> 3 ^ z << 4))
                ^ ((sum ^ y) + (key[(p & 3) ^ e] ^ z)));
    }

    private static int[] fixKey(byte[] key) {
        byte[] normalized = new byte[16];
        if (key != null) {
            System.arraycopy(key, 0, normalized, 0,
                    Math.min(key.length, normalized.length));
        }
        return toIntArray(normalized, false);
    }

    private static int[] toIntArray(byte[] data, boolean includeLength) {
        int count = (data.length + 3) >>> 2;
        int[] result = includeLength ? new int[count + 1] : new int[count];
        if (includeLength) {
            result[count] = data.length;
        }
        for (int index = 0; index < data.length; index++) {
            result[index >>> 2] |= (data[index] & 0xFF)
                    << ((index & 3) << 3);
        }
        return result;
    }

    private static byte[] toByteArray(int[] values, boolean includeLength) {
        int byteLength = values.length << 2;
        if (includeLength) {
            int originalLength = values[values.length - 1];
            int maximum = byteLength - 4;
            if (originalLength < maximum - 3 || originalLength > maximum) {
                throw new IllegalArgumentException(
                        "XXTEA length field is invalid: "
                                + originalLength + "/" + maximum);
            }
            byteLength = originalLength;
        }
        byte[] result = new byte[byteLength];
        for (int index = 0; index < byteLength; index++) {
            result[index] = (byte) (values[index >>> 2]
                    >>> ((index & 3) << 3));
        }
        return result;
    }
}
