package com.dnl.appenv.pro.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

public final class IdentityStore {
    private static final String PREF = "appenv_pro_identity";
    private static final String KEY_SEED = "seed";
    private static final String KEY_GENERATION = "generation";
    private static final String KEY_SESSION_RESET_GENERATION = "session_reset_generation";

    private IdentityStore() {
    }

    public static Identity getOrCreate(Context context, long requestedGeneration) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String seed = prefs.getString(KEY_SEED, null);
        long storedGeneration = prefs.getLong(KEY_GENERATION, Long.MIN_VALUE);

        if (seed == null || seed.isEmpty() || storedGeneration != requestedGeneration) {
            seed = UUID.randomUUID().toString();
            storedGeneration = requestedGeneration;
            prefs.edit()
                    .putString(KEY_SEED, seed)
                    .putLong(KEY_GENERATION, storedGeneration)
                    .commit();
        }

        String oaid = UUID.nameUUIDFromBytes((seed + "|oaid").getBytes(StandardCharsets.UTF_8)).toString();
        String androidId = firstHex(seed + "|android_id", 16);
        return new Identity(seed, oaid, oaid, androidId, storedGeneration);
    }

    public static boolean isSessionResetPending(Context context, long generation) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_SESSION_RESET_GENERATION, Long.MIN_VALUE) != generation;
    }

    public static void markSessionResetConsumed(Context context, long generation) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_SESSION_RESET_GENERATION, generation)
                .commit();
    }

    private static String firstHex(String input, int chars) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] data = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(data.length * 2);
            for (byte b : data) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.substring(0, Math.min(chars, sb.length()));
        } catch (Throwable ignored) {
            String fallback = UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8))
                    .toString().replace("-", "").toLowerCase(Locale.US);
            return fallback.substring(0, Math.min(chars, fallback.length()));
        }
    }
}
