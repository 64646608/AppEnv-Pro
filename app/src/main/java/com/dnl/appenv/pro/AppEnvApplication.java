package com.dnl.appenv.pro;

import android.app.Application;

import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class AppEnvApplication extends Application implements XposedServiceHelper.OnServiceListener {
    private static volatile XposedService service;
    private static final CopyOnWriteArraySet<ServiceStateListener> listeners = new CopyOnWriteArraySet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService xposedService) {
        service = xposedService;
        notifyListeners(xposedService);
    }

    @Override
    public void onServiceDied(XposedService xposedService) {
        if (service == xposedService) {
            service = null;
        }
        notifyListeners(null);
    }

    private static void notifyListeners(XposedService current) {
        for (ServiceStateListener listener : listeners) {
            listener.onServiceStateChanged(current);
        }
    }

    public static void addServiceStateListener(ServiceStateListener listener, boolean notifyImmediately) {
        listeners.add(listener);
        if (notifyImmediately) {
            listener.onServiceStateChanged(service);
        }
    }

    public static void removeServiceStateListener(ServiceStateListener listener) {
        listeners.remove(listener);
    }

    public interface ServiceStateListener {
        void onServiceStateChanged(XposedService service);
    }
}
