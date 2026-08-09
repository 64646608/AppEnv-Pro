package com.dnl.appenv.pro.xposed;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import com.dnl.appenv.pro.core.Identity;
import com.dnl.appenv.pro.core.IdentityStore;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.github.libxposed.api.XposedModule;

public final class ModuleMain extends XposedModule {
    private static final String TAG = "AppEnvPro";
    private static final String PREF_GROUP = "appenv";

    private static final Set<String> SESSION_KEYS = Set.of(
            "key_is_login",
            "user_login_accesskey",
            "user_temp_accesskey"
    );

    private final AtomicBoolean attachHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean runtimeHooksInstalled = new AtomicBoolean(false);
    private volatile String activePackage;
    private volatile Identity activeIdentity;
    private volatile Context activeContext;
    private volatile long activeGeneration;
    private volatile boolean sessionResetPending;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "DNLAPPENV_MODULE_LOADED process=" + param.getProcessName()
                + " framework=" + getFrameworkName()
                + " version=" + getFrameworkVersion()
                + " api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!param.isFirstPackage()) {
            return;
        }

        String pkg = param.getPackageName();
        if (!isEnabled(pkg)) {
            return;
        }

        activePackage = pkg;
        log(Log.INFO, TAG, "DNLAPPENV_ENTRY package=" + pkg);
        installAttachGate(param.getDefaultClassLoader());
    }

    private boolean isEnabled(String pkg) {
        try {
            SharedPreferences p = getRemotePreferences(PREF_GROUP);
            return p.getBoolean(pkg + ".enabled", false);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "DNLAPPENV_REMOTE_PREFS_UNAVAILABLE package=" + pkg, t);
            return false;
        }
    }

    private long requestedGeneration(String pkg) {
        try {
            return getRemotePreferences(PREF_GROUP).getLong(pkg + ".generation", 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private void installAttachGate(ClassLoader classLoader) {
        if (!attachHookInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setPriority(PRIORITY_HIGHEST)
                    .intercept(chain -> {
                        Context context = (Context) chain.getArg(0);
                        String pkg = activePackage != null ? activePackage : context.getPackageName();
                        long generation = requestedGeneration(pkg);

                        activeContext = context;
                        activeGeneration = generation;
                        activeIdentity = IdentityStore.getOrCreate(context, generation);
                        sessionResetPending = IdentityStore.isSessionResetPending(context, generation);

                        log(Log.INFO, TAG, "DNLAPPENV_IDENTITY_READY package=" + pkg
                                + " sessionResetPending=" + sessionResetPending
                                + " " + activeIdentity);

                        installRuntimeHooks(classLoader);
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "DNLAPPENV_ATTACH_GATE_OK package=" + activePackage);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "DNLAPPENV_ATTACH_GATE_FAIL package=" + activePackage, t);
        }
    }

    private void installRuntimeHooks(ClassLoader classLoader) {
        if (!runtimeHooksInstalled.compareAndSet(false, true)) {
            return;
        }

        hookAndroidId();
        hookSessionStore(classLoader);

        hookStringNoArgs(classLoader,
                "com.zygote.base.library.tool.MDevice", "getOaid",
                () -> identity().oaid);
        hookStringNoArgs(classLoader,
                "com.zygote.base.library.tool.MDevice", "getAndroidId",
                () -> identity().androidId);
        hookStringNoArgs(classLoader,
                "com.zygote.base.library.tool.MDevice", "getDeviceId",
                () -> identity().deviceId);

        hookStringNoArgs(classLoader,
                "com.zygote.app.AppInfoImpl", "oaid",
                () -> identity().oaid);
        hookStringNoArgs(classLoader,
                "com.zygote.app.AppInfoImpl", "deviceId",
                () -> identity().deviceId);
        hookStringNoArgs(classLoader,
                "com.zygote.app.AppInfoImpl", "androidId",
                () -> identity().androidId);

        hookStepRegisterGuest(classLoader);
        hookRegisterGate(classLoader);
        hookTraceByName(classLoader,
                "com.zygote.base.business.net.request.MNet", "bindWechat",
                "DNLAPPENV_BIND_WECHAT_CALL");

        log(Log.INFO, TAG, "DNLAPPENV_RUNTIME_HOOKS_READY package=" + activePackage);
    }

    private Identity identity() {
        Identity id = activeIdentity;
        if (id == null) {
            throw new IllegalStateException("identity not initialized");
        }
        return id;
    }

    private void hookAndroidId() {
        hookSettingsGetString(Settings.Secure.class, "Secure");
        hookSettingsGetString(Settings.System.class, "System");
    }

    private void hookSettingsGetString(Class<?> settingsClass, String label) {
        try {
            Method m = settingsClass.getDeclaredMethod(
                    "getString", ContentResolver.class, String.class);
            hook(m).intercept(chain -> {
                Object key = chain.getArg(1);
                if (Settings.Secure.ANDROID_ID.equals(key)) {
                    String value = identity().androidId;
                    log(Log.INFO, TAG, "DNLAPPENV_GET_ANDROID_ID source=" + label + " value=" + value);
                    return value;
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "DNLAPPENV_HOOK_OK target=Settings." + label + ".getString");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "DNLAPPENV_HOOK_MISS target=Settings." + label + ".getString", t);
        }
    }

    private void hookSessionStore(ClassLoader classLoader) {
        String className = "com.zygote.base.library.tool.MStore";
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            int getterCount = 0;
            int setterCount = 0;

            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getParameterCount() < 1 || method.getParameterTypes()[0] != String.class) {
                    continue;
                }
                String name = method.getName();
                method.setAccessible(true);

                if (name.startsWith("get") || name.startsWith("decode") || name.equals("contains")) {
                    Class<?> returnType = method.getReturnType();
                    hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                        String key = String.valueOf(chain.getArg(0));
                        if (sessionResetPending && isSessionKey(key)) {
                            Object hidden = emptyValue(returnType);
                            log(Log.INFO, TAG, "DNLAPPENV_SESSION_READ_BLOCK key=" + key
                                    + " method=" + method.getName());
                            return hidden;
                        }
                        return chain.proceed();
                    });
                    getterCount++;
                } else if (name.startsWith("put") || name.startsWith("set") || name.startsWith("encode")) {
                    Class<?> returnType = method.getReturnType();
                    hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                        String key = String.valueOf(chain.getArg(0));
                        if (sessionResetPending && isSessionKey(key)) {
                            log(Log.INFO, TAG, "DNLAPPENV_SESSION_WRITE_BLOCK key=" + key
                                    + " method=" + method.getName());
                            return emptyValue(returnType);
                        }
                        return chain.proceed();
                    });
                    setterCount++;
                }
            }

            log(Log.INFO, TAG, "DNLAPPENV_SESSION_STORE_HOOK_OK getters=" + getterCount
                    + " setters=" + setterCount);
        } catch (ClassNotFoundException e) {
            log(Log.INFO, TAG, "DNLAPPENV_SESSION_STORE_SKIP reason=class_not_found");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "DNLAPPENV_SESSION_STORE_HOOK_FAIL", t);
        }
    }

    private void hookStepRegisterGuest(ClassLoader classLoader) {
        String className = "com.zygote.base.business.enter.after.StepRegisterGuest";
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            int count = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals("doStep")) continue;
                method.setAccessible(true);
                hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                    if (sessionResetPending) {
                        clearSessionStoreNow(classLoader);
                        log(Log.INFO, TAG, "DNLAPPENV_REGISTER_GATE_PRE package=" + activePackage
                                + " generation=" + activeGeneration
                                + " oaid=" + identity().oaid
                                + " androidId=" + identity().androidId);
                    } else {
                        log(Log.INFO, TAG, "DNLAPPENV_STEP_REGISTER_CALL package=" + activePackage
                                + " resetPending=false");
                    }
                    return chain.proceed();
                });
                count++;
            }
            log(Log.INFO, TAG, "DNLAPPENV_REGISTER_STEP_HOOK count=" + count);
        } catch (ClassNotFoundException e) {
            log(Log.INFO, TAG, "DNLAPPENV_REGISTER_STEP_SKIP reason=class_not_found");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "DNLAPPENV_REGISTER_STEP_FAIL", t);
        }
    }

    private void hookRegisterGate(ClassLoader classLoader) {
        String className = "com.zygote.base.business.net.request.MNet";
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            int count = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals("register")) continue;
                method.setAccessible(true);
                hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                    boolean consumeReset = sessionResetPending;
                    if (consumeReset) {
                        clearSessionStoreNow(classLoader);
                        log(Log.INFO, TAG, "DNLAPPENV_REGISTER_GATE_PASS package=" + activePackage
                                + " generation=" + activeGeneration
                                + " oaid=" + identity().oaid
                                + " deviceId=" + identity().deviceId
                                + " androidId=" + identity().androidId);
                    } else {
                        log(Log.INFO, TAG, "DNLAPPENV_REGISTER_CALL package=" + activePackage
                                + " resetPending=false");
                    }

                    Object result = chain.proceed();

                    if (consumeReset && activeContext != null) {
                        IdentityStore.markSessionResetConsumed(activeContext, activeGeneration);
                        sessionResetPending = false;
                        log(Log.INFO, TAG, "DNLAPPENV_SESSION_RESET_CONSUMED package=" + activePackage
                                + " generation=" + activeGeneration);
                    }
                    return result;
                });
                count++;
            }
            log(Log.INFO, TAG, "DNLAPPENV_REGISTER_GATE_HOOK count=" + count);
        } catch (ClassNotFoundException e) {
            log(Log.INFO, TAG, "DNLAPPENV_REGISTER_GATE_SKIP reason=class_not_found");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "DNLAPPENV_REGISTER_GATE_FAIL", t);
        }
    }

    private void clearSessionStoreNow(ClassLoader classLoader) {
        String className = "com.zygote.base.library.tool.MStore";
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            int removed = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals("remove")
                        || method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != String.class
                        || !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                method.setAccessible(true);
                for (String key : SESSION_KEYS) {
                    try {
                        method.invoke(null, key);
                        removed++;
                    } catch (Throwable one) {
                        log(Log.WARN, TAG, "DNLAPPENV_SESSION_REMOVE_FAIL key=" + key, one);
                    }
                }
            }
            log(Log.INFO, TAG, "DNLAPPENV_SESSION_REMOVE_DONE count=" + removed);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "DNLAPPENV_SESSION_REMOVE_UNAVAILABLE", t);
        }
    }

    private boolean isSessionKey(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT);
        for (String sessionKey : SESSION_KEYS) {
            if (normalized.equals(sessionKey) || normalized.endsWith(sessionKey)) {
                return true;
            }
        }
        return false;
    }

    private Object emptyValue(Class<?> type) {
        if (type == void.class || type == Void.class) return null;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == byte.class || type == Byte.class) return (byte) 0;
        if (type == short.class || type == Short.class) return (short) 0;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == float.class || type == Float.class) return 0f;
        if (type == double.class || type == Double.class) return 0d;
        if (type == char.class || type == Character.class) return '\0';
        if (type == String.class || CharSequence.class.isAssignableFrom(type)) return "";
        return null;
    }

    private void hookStringNoArgs(ClassLoader classLoader,
                                  String className,
                                  String methodName,
                                  Supplier<String> valueSupplier) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            int count = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterCount() != 0) {
                    continue;
                }
                if (method.getReturnType() != String.class
                        && !CharSequence.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    String value = valueSupplier.get();
                    log(Log.INFO, TAG, "DNLAPPENV_GETTER target=" + className + "." + methodName
                            + " value=" + value);
                    return value;
                });
                count++;
            }
            if (count > 0) {
                log(Log.INFO, TAG, "DNLAPPENV_HOOK_OK target=" + className + "." + methodName
                        + " count=" + count);
            } else {
                log(Log.WARN, TAG, "DNLAPPENV_HOOK_MISS target=" + className + "." + methodName
                        + " reason=no_matching_method");
            }
        } catch (ClassNotFoundException e) {
            log(Log.INFO, TAG, "DNLAPPENV_HOOK_SKIP target=" + className + "." + methodName
                    + " reason=class_not_found");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "DNLAPPENV_HOOK_FAIL target=" + className + "." + methodName, t);
        }
    }

    private void hookTraceByName(ClassLoader classLoader,
                                 String className,
                                 String methodName,
                                 String marker) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            int count = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    Identity id = activeIdentity;
                    log(Log.INFO, TAG, marker
                            + " package=" + activePackage
                            + " identity=" + (id == null ? "null" : id));
                    return chain.proceed();
                });
                count++;
            }
            log(Log.INFO, TAG, "DNLAPPENV_TRACE_HOOK target=" + className + "." + methodName
                    + " count=" + count);
        } catch (ClassNotFoundException e) {
            log(Log.INFO, TAG, "DNLAPPENV_TRACE_SKIP target=" + className + "." + methodName
                    + " reason=class_not_found");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "DNLAPPENV_TRACE_FAIL target=" + className + "." + methodName, t);
        }
    }
}
