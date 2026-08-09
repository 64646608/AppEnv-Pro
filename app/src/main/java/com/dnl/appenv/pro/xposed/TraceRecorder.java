package com.dnl.appenv.pro.xposed;

import android.content.Context;
import android.os.Process;

import com.dnl.appenv.pro.core.Identity;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class TraceRecorder {
    private static final Object LOCK = new Object();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final long MAX_VALUE_CHARS = 1024L * 1024L;

    private static volatile File sessionFile;
    private static volatile File latestFile;
    private static volatile File traceDir;
    private static volatile String packageName = "";
    private static volatile long generation;

    private TraceRecorder() {
    }

    static void init(Context context, String pkg, long gen, Identity identity) {
        synchronized (LOCK) {
            packageName = pkg == null ? "" : pkg;
            generation = gen;
            File base = context.getExternalFilesDir(null);
            if (base == null) base = context.getFilesDir();
            traceDir = new File(base, "AppEnvPro/trace");
            if (!traceDir.exists()) traceDir.mkdirs();

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            sessionFile = new File(traceDir, "appenv_native_trace_" + stamp + "_p" + Process.myPid() + ".log");
            latestFile = new File(traceDir, "appenv_native_trace_latest.log");
            truncate(latestFile);

            writeRaw("=== APPENV PRO TRACE START ===\n");
            writeRaw("time=" + now() + "\n");
            writeRaw("package=" + packageName + "\n");
            writeRaw("pid=" + Process.myPid() + " uid=" + Process.myUid() + "\n");
            writeRaw("generation=" + generation + "\n");
            writeRaw("identity=" + safe(identity) + "\n\n");
        }
    }

    static File getTraceDir() {
        return traceDir;
    }

    static File getLatestFile() {
        return latestFile;
    }

    static long nextId() {
        return SEQ.incrementAndGet();
    }

    static void log(String category, String message) {
        synchronized (LOCK) {
            writeRaw(now() + " [" + category + "] " + (message == null ? "" : message) + "\n");
        }
    }

    static void block(String category, String message) {
        synchronized (LOCK) {
            writeRaw("\n" + now() + " [" + category + "]\n" + (message == null ? "" : message) + "\n");
        }
    }

    static String args(Object[] values) {
        if (values == null) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            Object v = values[i];
            sb.append(i).append(':');
            if (v != null) sb.append(v.getClass().getName()).append('=');
            sb.append(safe(v));
        }
        sb.append(']');
        return sb.toString();
    }

    static String safe(Object value) {
        try {
            return safe(value, 0);
        } catch (Throwable t) {
            return "<describe-error:" + t.getClass().getSimpleName() + ">";
        }
    }

    private static String safe(Object value, int depth) {
        if (value == null) return "null";
        if (value instanceof CharSequence || value instanceof Number
                || value instanceof Boolean || value instanceof Character
                || value.getClass().isEnum()) {
            return limit(String.valueOf(value));
        }
        if (depth >= 2) return limit(String.valueOf(value));

        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < length; i++) {
                if (i > 0) sb.append(',');
                sb.append(safe(Array.get(value, i), depth + 1));
            }
            return limit(sb.append(']').toString());
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
            boolean first = true;
            while (it.hasNext()) {
                Map.Entry<?, ?> entry = it.next();
                if (!first) sb.append(',');
                first = false;
                sb.append(safe(entry.getKey(), depth + 1)).append(':')
                        .append(safe(entry.getValue(), depth + 1));
            }
            return limit(sb.append('}').toString());
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) sb.append(',');
                first = false;
                sb.append(safe(item, depth + 1));
            }
            return limit(sb.append(']').toString());
        }
        return limit(String.valueOf(value));
    }

    private static String limit(String value) {
        if (value == null) return "null";
        if (value.length() <= MAX_VALUE_CHARS) return value;
        return value.substring(0, (int) MAX_VALUE_CHARS)
                + "\n<TRUNCATED length=" + value.length() + ">";
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static void writeRaw(String text) {
        append(sessionFile, text);
        append(latestFile, text);
    }

    private static void append(File file, String text) {
        if (file == null) return;
        FileOutputStream out = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            out = new FileOutputStream(file, true);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static void truncate(File file) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, false);
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) { }
            }
        }
    }
}
