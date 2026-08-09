package com.dnl.appenv.pro.xposed;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.dnl.appenv.pro.core.Identity;
import com.dnl.appenv.pro.core.IdentityStore;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.github.libxposed.api.XposedModule;

public final class ModuleMain extends XposedModule {
    private static final String TAG = "AppEnvPro";
    private static final String PREF_GROUP = "appenv";
    private static final Set<String> TARGETS = Set.of(
            "com.tyylt.hxy",
            "com.sm.hdhsg"
    );

    private final AtomicBoolean attachHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean runtimeHooksInstalled = new AtomicBoolean(false);
    private volatile String activePackage;
    private volatile Identity activeIdentity;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, TAG, "DNLAPPENV_MODULE_LOADED process=" + param.getProcessName()
                + " framework=" + getFrameworkName()
                + " version=" + getFrameworkVersion()
                + " api=" + getApiVersion());
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!TARGETS.contains(pkg) || !param.isFirstPackage()) {
            return;
        }

        activePackage = pkg;
        if (!isEnabled(pkg)) {
            log(Log.INFO, TAG, "DNLAPPENV_DISABLED package=" + pkg);
            return;
        }

        log(Log.INFO, TAG, "DNLAPPENV_ENTRY package=" + pkg);
        installAttachGate(param.getDefaultClassLoader());
    }

    private boolean isEnabled(String pkg) {
        try {
            SharedPreferences p = getRemotePreferences(PREF_GROUP);
            return p.getBoolean(pkg + ".enabled", true);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "DNLAPPENV_REMOTE_PREFS_UNAVAILABLE defaultEnabled=true", t);
            return true;
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
                        activeIdentity = IdentityStore.getOrCreate(context, generation);

                        log(Log.INFO, TAG, "DNLAPPENV_IDENTITY_READY package=" + pkg + " " + activeIdentity);
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

        hookTraceByName(classLoader,
                "com.zygote.base.business.enter.after.StepRegisterGuest", "doStep",
                "DNLAPPENV_STEP_REGISTER_CALL");
        hookTraceByName(classLoader,
                "com.zygote.base.business.net.request.MNet", "register",
                "DNLAPPENV_REGISTER_CALL");
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
