package com.dnl.appenv.pro.xposed;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.dnl.appenv.pro.core.Identity;
import com.dnl.appenv.pro.core.IdentityStore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import io.github.libxposed.api.XposedModule;

/**
 * AppEnv Pro core 0.003 clean module entry.
 *
 * This class intentionally keeps observation at the application boundary:
 * - identity getters / stores
 * - guest registration and WeChat binding entry points
 * - JS bridge values
 * - current decrypted Cocos main/index request/response trace bootstrap
 */
public final class ModuleMainV3 extends XposedModule {
    private static final String TAG = "AppEnvPro";
    private static final String PREF = "appenv";
    private static final String SEARCH_PATH_KEY = "HotUpdateSearchPaths";
    private static final Set<String> SESSION_KEYS = Set.of(
            "key_is_login",
            "user_login_accesskey",
            "user_temp_accesskey"
    );

    private volatile String packageName;
    private volatile String overrideRoot = "";
    private volatile Context context;
    private volatile Identity identity;
    private volatile long generation;
    private volatile boolean resetPending;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG,
                "DNLAPPENV_MODULE_LOADED framework=" + getFrameworkName()
                        + " version=" + getFrameworkVersion()
                        + " api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;
        String pkg = param.getPackageName();
        if (!readBool(pkg, "enabled", false)) return;
        packageName = pkg;
        mark("ENTRY", "package=" + pkg);
        installSearchPathHooks(param.getDefaultClassLoader());
        installAttachGate(param.getDefaultClassLoader());
    }

    private boolean readBool(String pkg, String key, boolean fallback) {
        try {
            return getRemotePreferences(PREF).getBoolean(pkg + "." + key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private long readGeneration(String pkg) {
        try {
            return getRemotePreferences(PREF).getLong(pkg + ".generation", 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private void installAttachGate(ClassLoader classLoader) {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                context = (Context) chain.getArg(0);
                if (packageName == null) packageName = context.getPackageName();
                generation = readGeneration(packageName);
                identity = IdentityStore.getOrCreate(context, generation);
                resetPending = IdentityStore.isSessionResetPending(context, generation);

                TraceRecorder.init(context, packageName, generation, identity);
                mark("IDENTITY_READY",
                        "generation=" + generation
                                + " resetPending=" + resetPending
                                + " identity=" + identity);

                CocosTraceBootstrap.Result result = CocosTraceBootstrap.prepare(
                        context,
                        packageName,
                        classLoader,
                        readBool(packageName, "traceEnabled", true),
                        readBool(packageName, "debugEnabled", true));
                if (result.ready) overrideRoot = result.overrideRoot;
                mark("JSC_BOOT",
                        "ready=" + result.ready
                                + " source=" + result.source
                                + " debugPatch=" + result.debugPatchCount
                                + " trace=" + result.traceInjected
                                + " traceFile=" + result.traceFile
                                + " message=" + result.message);

                installRuntimeHooks(classLoader);
                return chain.proceed();
            });
        } catch (Throwable error) {
            fail("Application.attach", error);
        }
    }

    private void installRuntimeHooks(ClassLoader classLoader) {
        installSettingsHook(Settings.Secure.class);
        installSettingsHook(Settings.System.class);
        installMStoreHooks(classLoader);
        installAppCacheHooks(classLoader);

        installStringGetter(classLoader,
                "com.zygote.base.library.tool.MDevice", "getOaid", () -> requireIdentity().oaid);
        installStringGetter(classLoader,
                "com.zygote.base.library.tool.MDevice", "getAndroidId", () -> requireIdentity().androidId);
        installStringGetter(classLoader,
                "com.zygote.base.library.tool.MDevice", "getDeviceId", () -> requireIdentity().deviceId);
        installStringGetter(classLoader,
                "com.zygote.app.AppInfoImpl", "oaid", () -> requireIdentity().oaid);
        installStringGetter(classLoader,
                "com.zygote.app.AppInfoImpl", "androidId", () -> requireIdentity().androidId);
        installStringGetter(classLoader,
                "com.zygote.app.AppInfoImpl", "deviceId", () -> requireIdentity().deviceId);

        installGuestStepHook(classLoader);
        installMNetHooks(classLoader);
        installMapBoundaryHook();
        installJsBridgeHooks(classLoader);
        mark("RUNTIME_READY", "package=" + packageName);
    }

    private Identity requireIdentity() {
        if (identity == null) throw new IllegalStateException("identity not ready");
        return identity;
    }

    private void installSettingsHook(Class<?> settingsClass) {
        try {
            Method getString = settingsClass.getDeclaredMethod(
                    "getString", ContentResolver.class, String.class);
            hook(getString).intercept(chain -> {
                String key = String.valueOf(chain.getArg(1));
                if (Settings.Secure.ANDROID_ID.equals(key)) {
                    mark("ANDROID_ID", "value=" + requireIdentity().androidId);
                    return requireIdentity().androidId;
                }
                return chain.proceed();
            });
        } catch (Throwable error) {
            fail(settingsClass.getName() + ".getString", error);
        }
    }

    private void installMStoreHooks(ClassLoader classLoader) {
        try {
            Class<?> type = Class.forName(
                    "com.zygote.base.library.tool.MStore", false, classLoader);
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() < 1
                        || method.getParameterTypes()[0] != String.class) continue;
                method.setAccessible(true);
                String methodName = method.getName();

                boolean stringGetter = (methodName.startsWith("get")
                        || methodName.startsWith("decode"))
                        && (method.getReturnType() == String.class
                        || CharSequence.class.isAssignableFrom(method.getReturnType()));

                if (stringGetter) {
                    hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                        String key = String.valueOf(chain.getArg(0));
                        if (key.endsWith("store_key_oaid")) {
                            mark("MSTORE_ID", "oaid=" + requireIdentity().oaid);
                            return requireIdentity().oaid;
                        }
                        if (key.endsWith("store_key_android_id")) {
                            mark("MSTORE_ID", "androidId=" + requireIdentity().androidId);
                            return requireIdentity().androidId;
                        }
                        if (resetPending && isSessionKey(key)) {
                            mark("SESSION_READ_BLOCK", "key=" + key);
                            return "";
                        }
                        Object value = chain.proceed();
                        if (isSessionKey(key)) {
                            mark("SESSION_READ", key + "=" + TraceRecorder.safe(value));
                        }
                        return value;
                    });
                } else if (methodName.startsWith("put")
                        || methodName.startsWith("set")
                        || methodName.startsWith("encode")
                        || methodName.equals("remove")) {
                    hook(method).intercept(chain -> {
                        String key = String.valueOf(chain.getArg(0));
                        if (isSessionKey(key) || key.contains("store_key_")) {
                            TraceRecorder.log("STORE-WRITE",
                                    "method=" + methodName
                                            + " key=" + key
                                            + " args=" + readArgs(chain, method.getParameterCount()));
                        }
                        return chain.proceed();
                    });
                }
            }
            mark("HOOK_OK", "MStore");
        } catch (ClassNotFoundException ignored) {
            mark("HOOK_SKIP", "MStore");
        } catch (Throwable error) {
            fail("MStore", error);
        }
    }

    private void installAppCacheHooks(ClassLoader classLoader) {
        try {
            Class<?> type = Class.forName(
                    "com.zygote.base.business.cache.AppCache$User", false, classLoader);
            for (Method method : type.getDeclaredMethods()) {
                method.setAccessible(true);
                String name = method.getName();
                String lower = name.toLowerCase(Locale.ROOT);

                if ((lower.equals("getaccesskey") || lower.equals("getaccesskey"))
                        && method.getParameterCount() == 0) {
                    hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                        if (resetPending) {
                            mark("APPCACHE_BLOCK", "accessKey");
                            return "";
                        }
                        Object value = chain.proceed();
                        mark("APPCACHE_ACCESS", TraceRecorder.safe(value));
                        return value;
                    });
                } else if (lower.startsWith("set")
                        && (lower.contains("access")
                        || lower.contains("user")
                        || lower.contains("anonymous"))) {
                    hook(method).intercept(chain -> {
                        TraceRecorder.block("APPCACHE " + name + " BEFORE",
                                "args=" + readArgs(chain, method.getParameterCount())
                                        + "\n" + accountSnapshot(classLoader));
                        Object value = chain.proceed();
                        TraceRecorder.block("APPCACHE " + name + " AFTER",
                                accountSnapshot(classLoader));
                        return value;
                    });
                }
            }
            mark("HOOK_OK", "AppCache.User");
        } catch (ClassNotFoundException ignored) {
            mark("HOOK_SKIP", "AppCache.User");
        } catch (Throwable error) {
            fail("AppCache.User", error);
        }
    }

    private void installGuestStepHook(ClassLoader classLoader) {
        try {
            Class<?> type = Class.forName(
                    "com.zygote.base.business.enter.after.StepRegisterGuest",
                    false, classLoader);
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("doStep")) continue;
                method.setAccessible(true);
                hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                    if (resetPending) clearLoginSession(classLoader);
                    TraceRecorder.block("STEP_REGISTER BEFORE", accountSnapshot(classLoader));
                    Object result = chain.proceed();
                    TraceRecorder.block("STEP_REGISTER AFTER", accountSnapshot(classLoader));
                    return result;
                });
            }
            mark("HOOK_OK", "StepRegisterGuest");
        } catch (ClassNotFoundException ignored) {
            mark("HOOK_SKIP", "StepRegisterGuest");
        } catch (Throwable error) {
            fail("StepRegisterGuest", error);
        }
    }

    private void installMNetHooks(ClassLoader classLoader) {
        try {
            Class<?> type = Class.forName(
                    "com.zygote.base.business.net.request.MNet", false, classLoader);
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.equals("register") && !name.equals("bindWechat")) continue;
                method.setAccessible(true);
                hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                    if (name.equals("register") && resetPending) {
                        clearLoginSession(classLoader);
                    }
                    TraceRecorder.block("MNET " + name + " BEFORE",
                            "args=" + readArgs(chain, method.getParameterCount())
                                    + "\n" + accountSnapshot(classLoader)
                                    + "\nidentity=" + requireIdentity());
                    Object result = chain.proceed();
                    TraceRecorder.block("MNET " + name + " RETURN",
                            "return=" + TraceRecorder.safe(result)
                                    + "\n" + accountSnapshot(classLoader));

                    // Once the real register method has been entered, allow its async callback
                    // to populate the new accessKey instead of permanently masking it.
                    if (name.equals("register") && resetPending && context != null) {
                        IdentityStore.markSessionResetConsumed(context, generation);
                        resetPending = false;
                        mark("SESSION_RESET_CONSUMED", "generation=" + generation);
                    }
                    return result;
                });
            }
            mark("HOOK_OK", "MNet");
        } catch (ClassNotFoundException ignored) {
            mark("HOOK_SKIP", "MNet");
        } catch (Throwable error) {
            fail("MNet", error);
        }
    }

    private void installMapBoundaryHook() {
        try {
            Method put = java.util.HashMap.class.getDeclaredMethod(
                    "put", Object.class, Object.class);
            hook(put).intercept(chain -> {
                if (insideMNet()) {
                    Object key = chain.getArg(0);
                    Object value = chain.getArg(1);
                    TraceRecorder.log("MNET-PAYLOAD",
                            "put " + TraceRecorder.safe(key)
                                    + "=" + TraceRecorder.safe(value));
                    if ("oaid".equals(String.valueOf(key))
                            && !requireIdentity().oaid.equals(String.valueOf(value))) {
                        mark("OAID_MISMATCH",
                                "expected=" + requireIdentity().oaid
                                        + " actual=" + value);
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable error) {
            fail("HashMap.put", error);
        }
    }

    private boolean insideMNet() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (element.getClassName().equals(
                    "com.zygote.base.business.net.request.MNet")
                    && (element.getMethodName().equals("register")
                    || element.getMethodName().equals("bindWechat"))) {
                return true;
            }
        }
        return false;
    }

    private void installJsBridgeHooks(ClassLoader classLoader) {
        try {
            Class<?> type = Class.forName(
                    "org.cocos2dx.javascript.bridge.JSFunction", false, classLoader);
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!name.equals("getCommentInfo")
                        && !name.equals("loginWeChat")
                        && !name.equals("reLoginDialog")
                        && !lower.contains("blackbox")) continue;
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    TraceRecorder.block("JS-BRIDGE " + name + " ENTER",
                            readArgs(chain, method.getParameterCount()));
                    Object result = chain.proceed();
                    TraceRecorder.block("JS-BRIDGE " + name + " RETURN",
                            TraceRecorder.safe(result));
                    return result;
                });
            }
            mark("HOOK_OK", "JSFunction");
        } catch (ClassNotFoundException ignored) {
            mark("HOOK_SKIP", "JSFunction");
        } catch (Throwable error) {
            fail("JSFunction", error);
        }
    }

    private void installSearchPathHooks(ClassLoader classLoader) {
        try {
            Class<?> type = Class.forName(
                    "org.cocos2dx.lib.Cocos2dxLocalStorage", false, classLoader);
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() < 1
                        || method.getParameterTypes()[0] != String.class) continue;
                method.setAccessible(true);
                String name = method.getName();

                if (name.equals("getItem")
                        && (method.getReturnType() == String.class
                        || CharSequence.class.isAssignableFrom(method.getReturnType()))) {
                    hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                        Object original = chain.proceed();
                        if (SEARCH_PATH_KEY.equals(String.valueOf(chain.getArg(0)))
                                && !overrideRoot.isEmpty()) {
                            String merged = CocosTraceBootstrap.mergeSearchPaths(
                                    overrideRoot,
                                    original == null ? "" : String.valueOf(original));
                            mark("SEARCH_PATH_GET", merged);
                            return merged;
                        }
                        return original;
                    });
                } else if (name.equals("setItem") || name.equals("removeItem")) {
                    hook(method).intercept(chain -> {
                        if (SEARCH_PATH_KEY.equals(String.valueOf(chain.getArg(0)))) {
                            mark("SEARCH_PATH_" + name,
                                    readArgs(chain, method.getParameterCount()));
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Non-Cocos app: identity hooks still remain useful.
        } catch (Throwable error) {
            fail("Cocos2dxLocalStorage", error);
        }
    }

    private void clearLoginSession(ClassLoader classLoader) {
        mark("SESSION_RESET_BEGIN", accountSnapshot(classLoader));
        int removed = 0;

        try {
            Class<?> type = Class.forName(
                    "com.zygote.base.business.cache.AppCache$User", true, classLoader);
            Object user = singleton(type);
            Method clear = type.getDeclaredMethod("clearLoginCache");
            clear.setAccessible(true);
            clear.invoke(user);
            mark("SESSION_RESET", "AppCache.User.clearLoginCache ok");
        } catch (Throwable error) {
            TraceRecorder.log("SESSION-RESET", "AppCache: " + error);
        }

        try {
            Class<?> type = Class.forName(
                    "com.zygote.base.library.tool.MStore", true, classLoader);
            Object store = singleton(type);
            Method remove = type.getDeclaredMethod("remove", String.class);
            remove.setAccessible(true);
            for (String key : SESSION_KEYS) {
                remove.invoke(store, key);
                removed++;
            }
        } catch (Throwable error) {
            TraceRecorder.log("SESSION-RESET", "MStore: " + error);
        }

        try {
            Class<?> type = Class.forName("com.tencent.mmkv.MMKV", true, classLoader);
            Method defaultMMKV = type.getDeclaredMethod("defaultMMKV");
            defaultMMKV.setAccessible(true);
            Object mmkv = defaultMMKV.invoke(null);
            if (mmkv != null) {
                Method remove = type.getDeclaredMethod("removeValueForKey", String.class);
                remove.setAccessible(true);
                for (String key : SESSION_KEYS) {
                    remove.invoke(mmkv, key);
                    removed++;
                }
            }
        } catch (Throwable error) {
            TraceRecorder.log("SESSION-RESET", "MMKV: " + error);
        }

        mark("SESSION_RESET_DONE",
                "removed=" + removed + " " + accountSnapshot(classLoader));
    }

    private String accountSnapshot(ClassLoader classLoader) {
        String accessKey = "";
        String userId = "";
        String anonymous = "";
        try {
            Class<?> type = Class.forName(
                    "com.zygote.base.business.cache.AppCache$User", true, classLoader);
            Object user = singleton(type);
            accessKey = firstNonEmpty(
                    stringCall(type, user, "getAccesskey"),
                    stringCall(type, user, "getAccessKey"));
            userId = firstNonEmpty(
                    stringCall(type, user, "getUserId"),
                    stringCall(type, user, "getUserid"),
                    userIdFromAccessKey(accessKey));
            Object value = call(type, user, "isAnonymous");
            if (value == null) value = call(type, user, "getAnonymous");
            if (value != null) anonymous = String.valueOf(value);
        } catch (Throwable ignored) {
        }
        return "userId=" + display(userId)
                + " accessKey=" + display(accessKey)
                + " isAnonymous=" + display(anonymous)
                + " resetPending=" + resetPending;
    }

    private Object singleton(Class<?> type) throws Exception {
        Field field = type.getDeclaredField("INSTANCE");
        field.setAccessible(true);
        return field.get(null);
    }

    private Object call(Class<?> type, Object target, String name) {
        try {
            Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String stringCall(Class<?> type, Object target, String name) {
        Object value = call(type, target, name);
        return value instanceof String ? (String) value : "";
    }

    private String userIdFromAccessKey(String accessKey) {
        if (accessKey == null) return "";
        int index = accessKey.lastIndexOf('_');
        return index >= 0 && index + 1 < accessKey.length()
                ? accessKey.substring(index + 1) : "";
    }

    private String firstNonEmpty(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isEmpty()) return value;
            }
        }
        return "";
    }

    private String display(String value) {
        return value == null || value.isEmpty() ? "<empty>" : value;
    }

    private void installStringGetter(
            ClassLoader classLoader,
            String className,
            String methodName,
            Supplier<String> supplier) {
        try {
            Class<?> type = Class.forName(className, false, classLoader);
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)
                        || method.getParameterCount() != 0
                        || !(method.getReturnType() == String.class
                        || CharSequence.class.isAssignableFrom(method.getReturnType()))) continue;
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    String value = supplier.get();
                    mark("IDENTITY_GETTER",
                            className + "." + methodName + "=" + value);
                    return value;
                });
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable error) {
            fail(className + "." + methodName, error);
        }
    }

    private boolean isSessionKey(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT);
        for (String item : SESSION_KEYS) {
            if (normalized.equals(item) || normalized.endsWith(item)) return true;
        }
        return false;
    }

    private String readArgs(Object chain, int count) {
        Object[] values = new Object[count];
        try {
            Method getter = chain.getClass().getMethod("getArg", int.class);
            for (int i = 0; i < count; i++) values[i] = getter.invoke(chain, i);
        } catch (Throwable ignored) {
        }
        return TraceRecorder.args(values);
    }

    private void mark(String category, String message) {
        try {
            log(Log.INFO, TAG, "DNLAPPENV_" + category + " " + message);
        } catch (Throwable ignored) {
        }
        try {
            TraceRecorder.log(category, message);
        } catch (Throwable ignored) {
        }
    }

    private void fail(String target, Throwable error) {
        try {
            log(Log.WARN, TAG, "DNLAPPENV_HOOK_FAIL target=" + target, error);
        } catch (Throwable ignored) {
        }
        try {
            TraceRecorder.log("HOOK-FAIL", target + " " + error);
        } catch (Throwable ignored) {
        }
    }
}
