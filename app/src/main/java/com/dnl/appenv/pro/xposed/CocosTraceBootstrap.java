package com.dnl.appenv.pro.xposed;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CocosTraceBootstrap {
    private static final String MAIN_JS_ASSET = "main.js";
    private static final String EMBEDDED_JSC_ASSET = "assets/main/index.jsc";
    private static final String OUTPUT_DIRECTORY = "appenv-pro-trace";
    private static final String META_FILE = "source.meta";

    static final class Result {
        boolean ready;
        boolean cached;
        boolean traceInjected;
        int debugPatchCount;
        String overrideRoot = "";
        String source = "";
        String sourceSha256 = "";
        String traceFile = "";
        String message = "";
    }

    private CocosTraceBootstrap() { }

    static Result prepare(Context context, String packageName, ClassLoader classLoader,
                          boolean traceEnabled, boolean debugEnabled) {
        Result result = new Result();
        if (context == null) {
            result.message = "CONTEXT_NULL";
            return result;
        }
        if (!traceEnabled && !debugEnabled) {
            result.message = "DISABLED";
            return result;
        }
        try {
            if (!isCompatibleMainJs(context.getAssets())) {
                result.message = "MAIN_JS_FINGERPRINT_MISMATCH";
                return result;
            }
            if (!hasCocosLocalStorage(classLoader)) {
                result.message = "COCOS_LOCAL_STORAGE_CLASS_MISSING";
                return result;
            }

            SourceData source = chooseSource(context);
            if (source.bytes == null || source.bytes.length == 0) {
                result.message = "SOURCE_JSC_NOT_FOUND";
                return result;
            }
            result.source = source.name;
            result.sourceSha256 = sha256(source.bytes);

            File overrideRoot = new File(context.getFilesDir(), OUTPUT_DIRECTORY);
            File output = new File(overrideRoot, "assets/main/index.jsc");
            File meta = new File(overrideRoot, META_FILE);
            if (!output.getParentFile().exists() && !output.getParentFile().mkdirs()) {
                result.message = "OUTPUT_DIRECTORY_CREATE_FAILED";
                return result;
            }

            File traceDir = TraceRecorder.getTraceDir();
            if (traceDir == null) {
                File base = context.getExternalFilesDir(null);
                if (base == null) base = context.getFilesDir();
                traceDir = new File(base, "AppEnvPro/trace");
                if (!traceDir.exists()) traceDir.mkdirs();
            }
            File jsTrace = new File(traceDir, "appenv_js_http_trace_latest.log");
            truncate(jsTrace);
            result.traceFile = jsTrace.getAbsolutePath();

            String metaValue = result.sourceSha256 + "|v3|trace=" + traceEnabled
                    + "|debug=" + debugEnabled + "|path=" + jsTrace.getAbsolutePath();
            String cachedMeta = readSmallText(meta);
            if (output.isFile() && output.length() > 0L && metaValue.equals(cachedMeta)) {
                result.ready = true;
                result.cached = true;
                result.overrideRoot = trailingSlash(overrideRoot.getAbsolutePath());
                result.message = "READY_FROM_CACHE";
                return result;
            }

            JscTracePatcher.Result patch = JscTracePatcher.patch(
                    source.bytes, jsTrace.getAbsolutePath(), traceEnabled, debugEnabled);
            result.debugPatchCount = patch.debugPatchCount;
            result.traceInjected = patch.traceInjected;
            if (!patch.success || patch.output == null || patch.output.length == 0) {
                result.message = patch.message;
                writeText(new File(traceDir, "jsc_patch_error.txt"), patch.message);
                return result;
            }

            atomicWrite(output, patch.output);
            writeSmallText(meta, metaValue);
            if (patch.originalPlain != null && !patch.originalPlain.isEmpty()) {
                writeText(new File(traceDir, "index.source.decrypted.js"), patch.originalPlain);
            }
            if (patch.patchedPlain != null && !patch.patchedPlain.isEmpty()) {
                writeText(new File(traceDir, "index.active.decrypted.js"), patch.patchedPlain);
            }

            result.ready = true;
            result.overrideRoot = trailingSlash(overrideRoot.getAbsolutePath());
            result.message = "READY_FIRST_PROCESS";
            return result;
        } catch (Throwable error) {
            result.message = "BOOTSTRAP_EXCEPTION:" + error.getClass().getName()
                    + ":" + String.valueOf(error.getMessage());
            return result;
        }
    }

    static String mergeSearchPaths(String overrideRoot, String existingJson) {
        List<String> values = new ArrayList<>();
        addUnique(values, trailingSlash(overrideRoot));
        List<String> existing = parseJsonStrings(existingJson);
        for (String value : existing) addUnique(values, value);
        addUnique(values, "");
        return toJson(values);
    }

    private static boolean isCompatibleMainJs(AssetManager assets) {
        InputStream input = null;
        try {
            input = assets.open(MAIN_JS_ASSET);
            String source = new String(readAll(input), "UTF-8");
            return source.contains("HotUpdateSearchPaths")
                    && source.contains("jsb.fileUtils.setSearchPaths")
                    && source.contains("loadBundle");
        } catch (Throwable error) {
            return false;
        } finally {
            close(input);
        }
    }

    private static boolean hasCocosLocalStorage(ClassLoader classLoader) {
        try {
            Class.forName("org.cocos2dx.lib.Cocos2dxLocalStorage", false, classLoader);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private static SourceData chooseSource(Context context) throws Exception {
        File files = context.getFilesDir();
        String[] relatives = new String[] {
                "remote-asset/assets/main/index.jsc",
                "remote-assets/assets/main/index.jsc",
                "hot-update/assets/main/index.jsc",
                "assets/main/index.jsc"
        };
        for (String relative : relatives) {
            File file = new File(files, relative);
            if (file.isFile() && file.length() > 0L) {
                return new SourceData("FILE:" + file.getAbsolutePath(), readFile(file));
            }
        }
        InputStream input = null;
        try {
            input = context.getAssets().open(EMBEDDED_JSC_ASSET);
            return new SourceData("APK_ASSET:" + EMBEDDED_JSC_ASSET, readAll(input));
        } finally {
            close(input);
        }
    }

    private static void atomicWrite(File target, byte[] data) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(parent, target.getName() + ".tmp");
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(temp, false);
            output.write(data);
            output.flush();
            output.getFD().sync();
        } finally {
            close(output);
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("OLD_OUTPUT_DELETE_FAILED");
        }
        if (!temp.renameTo(target)) {
            throw new IllegalStateException("ATOMIC_RENAME_FAILED");
        }
    }

    private static void writeText(File file, String value) throws Exception {
        atomicWrite(file, value.getBytes("UTF-8"));
    }

    private static byte[] readFile(File file) throws Exception {
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            return readAll(input);
        } finally {
            close(input);
        }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally {
            close(output);
        }
    }

    private static String readSmallText(File file) {
        if (!file.isFile() || file.length() <= 0L || file.length() > 64 * 1024L) return "";
        try {
            return new String(readFile(file), "UTF-8").trim();
        } catch (Throwable error) {
            return "";
        }
    }

    private static void writeSmallText(File file, String value) throws Exception {
        atomicWrite(file, value.getBytes("UTF-8"));
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder output = new StringBuilder(hash.length * 2);
            for (byte b : hash) output.append(String.format(Locale.US, "%02x", b & 0xFF));
            return output.toString();
        } catch (Throwable error) {
            return "";
        }
    }

    private static void addUnique(List<String> values, String value) {
        if (value == null) return;
        for (String current : values) if (value.equals(current)) return;
        values.add(value);
    }

    private static List<String> parseJsonStrings(String source) {
        List<String> values = new ArrayList<>();
        if (source == null) return values;
        boolean inString = false;
        boolean escaping = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (!inString) {
                if (value == '"') {
                    inString = true;
                    current.setLength(0);
                }
                continue;
            }
            if (escaping) {
                if (value == 'n') current.append('\n');
                else if (value == 'r') current.append('\r');
                else if (value == 't') current.append('\t');
                else current.append(value);
                escaping = false;
            } else if (value == '\\') {
                escaping = true;
            } else if (value == '"') {
                inString = false;
                values.add(current.toString());
            } else {
                current.append(value);
            }
        }
        return values;
    }

    private static String toJson(List<String> values) {
        StringBuilder output = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) output.append(',');
            output.append('"').append(jsonEscape(values.get(i))).append('"');
        }
        return output.append(']').toString();
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String trailingSlash(String path) {
        if (path == null || path.isEmpty() || path.endsWith("/")) return path == null ? "" : path;
        return path + "/";
    }

    private static void truncate(File file) {
        FileOutputStream out = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            out = new FileOutputStream(file, false);
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            close(out);
        }
    }

    private static void close(Object object) {
        if (object == null) return;
        try {
            if (object instanceof InputStream) ((InputStream) object).close();
            else if (object instanceof java.io.OutputStream) ((java.io.OutputStream) object).close();
        } catch (Throwable ignored) { }
    }

    private static final class SourceData {
        final String name;
        final byte[] bytes;
        SourceData(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }
}
