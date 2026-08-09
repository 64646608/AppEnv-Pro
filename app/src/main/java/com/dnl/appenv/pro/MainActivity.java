package com.dnl.appenv.pro;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity implements AppEnvApplication.ServiceStateListener {
    private static final String PREF_GROUP = "appenv";

    private XposedService service;
    private LinearLayout pageHost;
    private LinearLayout appList;
    private EditText searchBox;
    private TextView frameworkBadge;
    private TextView homeTab;
    private TextView appsTab;

    private final List<AppEntry> allApps = new ArrayList<>();
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildShell();
        showAppsPage();
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppEnvApplication.addServiceStateListener(this, true);
    }

    @Override
    protected void onStop() {
        AppEnvApplication.removeServiceStateListener(this);
        super.onStop();
    }

    @Override
    public void onServiceStateChanged(XposedService newService) {
        service = newService;
        runOnUiThread(() -> {
            updateFrameworkBadge();
            if (appList != null) {
                loadInstalledApps();
            }
        });
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(249, 249, 253));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(16), dp(16), dp(10));

        TextView title = new TextView(this);
        title.setText("应用");
        title.setTextSize(26f);
        title.setTextColor(Color.rgb(35, 35, 43));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1f));

        frameworkBadge = new TextView(this);
        frameworkBadge.setTextSize(12f);
        frameworkBadge.setGravity(Gravity.CENTER);
        frameworkBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        header.addView(frameworkBadge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView more = new TextView(this);
        more.setText("⋮");
        more.setTextSize(30f);
        more.setGravity(Gravity.CENTER);
        more.setTextColor(Color.rgb(70, 70, 80));
        more.setOnClickListener(v -> showFrameworkToast());
        header.addView(more, new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pageHost = new LinearLayout(this);
        pageHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(16), dp(8), dp(16), dp(10));
        bottom.setBackgroundColor(Color.rgb(245, 244, 253));

        homeTab = bottomTab("⌂\n首页");
        homeTab.setOnClickListener(v -> showHomePage());
        bottom.addView(homeTab, new LinearLayout.LayoutParams(0, dp(62), 1f));

        appsTab = bottomTab("▦\n应用");
        appsTab.setOnClickListener(v -> showAppsPage());
        bottom.addView(appsTab, new LinearLayout.LayoutParams(0, dp(62), 1f));

        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        updateFrameworkBadge();
    }

    private void showAppsPage() {
        setTabSelected(false, true);
        pageHost.removeAllViews();

        LinearLayout searchWrap = new LinearLayout(this);
        searchWrap.setPadding(dp(20), dp(4), dp(20), dp(10));
        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("搜索应用或包名");
        searchBox.setTextSize(15f);
        searchBox.setPadding(dp(16), 0, dp(16), 0);
        searchBox.setBackground(roundRect(Color.WHITE, dp(18), Color.rgb(225, 225, 234), 1));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                renderAppList();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        searchWrap.addView(searchBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        pageHost.addView(searchWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appList.setPadding(dp(20), dp(2), dp(20), dp(20));
        scroll.addView(appList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pageHost.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView loading = new TextView(this);
        loading.setText("正在读取已安装应用…");
        loading.setTextSize(15f);
        loading.setPadding(dp(8), dp(20), dp(8), dp(20));
        appList.addView(loading);
        loadInstalledApps();
    }

    private void showHomePage() {
        setTabSelected(true, false);
        pageHost.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(16), dp(24), dp(30));
        scroll.addView(content);

        TextView name = new TextView(this);
        name.setText("应用变量 Pro");
        name.setTextSize(28f);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setTextColor(Color.rgb(40, 40, 48));
        content.addView(name);

        TextView version = new TextView(this);
        version.setText("1.0.1-dev · core 0.002");
        version.setTextSize(15f);
        version.setTextColor(Color.DKGRAY);
        version.setPadding(0, dp(4), 0, dp(18));
        content.addView(version);

        TextView status = new TextView(this);
        status.setText(buildFrameworkStatus());
        status.setTextSize(15f);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        status.setBackground(roundRect(Color.WHITE, dp(18), Color.rgb(226, 226, 235), 1));
        content.addView(status, matchWrap());

        TextView help = new TextView(this);
        help.setText("工作方式\n\n"
                + "• 在“应用”页打开开关，即把该 App 加入应用变量 Pro。\n"
                + "• 已启用应用自动置顶；其余应用按首次安装时间从新到旧排序。\n"
                + "• 每个目标 App 保存独立测试身份。\n"
                + "• com.zygote.* 应用额外启用游客会话重建与注册门禁。\n"
                + "• 日志关键词：DNLAPPENV");
        help.setTextSize(15f);
        help.setTextColor(Color.rgb(65, 65, 72));
        help.setPadding(dp(4), dp(22), dp(4), dp(8));
        content.addView(help, matchWrap());

        pageHost.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void loadInstalledApps() {
        new Thread(() -> {
            List<AppEntry> loaded = new ArrayList<>();
            PackageManager pm = getPackageManager();
            SharedPreferences remote = null;
            try {
                if (service != null) {
                    remote = service.getRemotePreferences(PREF_GROUP);
                }
            } catch (Throwable ignored) {
            }

            try {
                List<ApplicationInfo> infos = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                for (ApplicationInfo info : infos) {
                    if (getPackageName().equals(info.packageName)) {
                        continue;
                    }
                    String label;
                    Drawable icon;
                    long installTime = 0L;
                    try {
                        CharSequence raw = pm.getApplicationLabel(info);
                        label = raw == null ? info.packageName : raw.toString();
                    } catch (Throwable t) {
                        label = info.packageName;
                    }
                    try {
                        icon = pm.getApplicationIcon(info);
                    } catch (Throwable t) {
                        icon = getApplicationInfo().loadIcon(pm);
                    }
                    try {
                        PackageInfo pi = pm.getPackageInfo(info.packageName, 0);
                        installTime = pi.firstInstallTime;
                    } catch (Throwable ignored) {
                    }
                    boolean enabled = false;
                    if (remote != null) {
                        try {
                            enabled = remote.getBoolean(info.packageName + ".enabled", false);
                        } catch (Throwable ignored) {
                        }
                    }
                    loaded.add(new AppEntry(label, info.packageName, icon, installTime, enabled));
                }
            } catch (Throwable t) {
                runOnUiThread(() -> toast("读取应用列表失败：" + t.getMessage()));
            }

            Collections.sort(loaded, APP_COMPARATOR);
            runOnUiThread(() -> {
                allApps.clear();
                allApps.addAll(loaded);
                renderAppList();
            });
        }, "AppEnv-AppScanner").start();
    }

    private void renderAppList() {
        if (appList == null) return;
        appList.removeAllViews();

        int visible = 0;
        for (AppEntry entry : allApps) {
            if (!query.isEmpty()) {
                String haystack = (entry.label + "\n" + entry.packageName).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) continue;
            }
            appList.addView(buildAppCard(entry), cardLayoutParams());
            visible++;
        }

        if (visible == 0) {
            TextView empty = new TextView(this);
            empty.setText(allApps.isEmpty() ? "正在读取已安装应用…" : "没有匹配的应用");
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(15f);
            empty.setPadding(0, dp(40), 0, dp(40));
            appList.addView(empty, matchWrap());
        }
    }

    private View buildAppCard(AppEntry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(14), dp(12));
        card.setBackground(roundRect(
                entry.enabled ? Color.rgb(244, 246, 255) : Color.rgb(250, 250, 255),
                dp(20), Color.rgb(229, 229, 238), 1));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(entry.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(8), 0);

        TextView name = new TextView(this);
        name.setText(entry.label);
        name.setTextSize(18f);
        name.setTextColor(Color.rgb(36, 36, 45));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        texts.addView(name, matchWrap());

        TextView pkg = new TextView(this);
        pkg.setText(entry.packageName);
        pkg.setTextSize(13.5f);
        pkg.setTextColor(Color.rgb(75, 75, 86));
        texts.addView(pkg, matchWrap());

        if (entry.installTime > 0L) {
            TextView time = new TextView(this);
            time.setText("安装：" + DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT).format(new Date(entry.installTime)));
            time.setTextSize(11.5f);
            time.setTextColor(Color.rgb(135, 135, 145));
            texts.addView(time, matchWrap());
        }

        card.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(entry.enabled);
        toggle.setShowText(false);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked == entry.enabled) return;
            if (isChecked) {
                enablePackage(entry, toggle);
            } else {
                disablePackage(entry);
            }
        });
        card.addView(toggle, new LinearLayout.LayoutParams(dp(70), dp(52)));

        card.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        return card;
    }

    private void enablePackage(AppEntry entry, Switch toggle) {
        if (service == null) {
            toggle.setChecked(false);
            toast("Xposed Service 尚未连接");
            return;
        }

        try {
            if (service.getScope().contains(entry.packageName)) {
                setPackageEnabled(entry, true);
                return;
            }
            toast("正在申请 " + entry.label + " 的作用域…");
            service.requestScope(Collections.singletonList(entry.packageName),
                    new XposedService.OnScopeEventListener() {
                        @Override
                        public void onScopeRequestApproved(List<String> approved) {
                            runOnUiThread(() -> {
                                if (approved.contains(entry.packageName)) {
                                    setPackageEnabled(entry, true);
                                } else {
                                    toggle.setChecked(false);
                                    toast("作用域未包含该应用");
                                }
                            });
                        }

                        @Override
                        public void onScopeRequestFailed(String message) {
                            runOnUiThread(() -> {
                                toggle.setChecked(false);
                                toast("作用域申请失败：" + message);
                            });
                        }
                    });
        } catch (Throwable t) {
            toggle.setChecked(false);
            toast("启用失败：" + t.getMessage());
        }
    }

    private void disablePackage(AppEntry entry) {
        setPackageEnabled(entry, false);
    }

    private void setPackageEnabled(AppEntry entry, boolean enabled) {
        if (service == null) {
            toast("Xposed Service 尚未连接");
            return;
        }
        try {
            SharedPreferences p = service.getRemotePreferences(PREF_GROUP);
            p.edit().putBoolean(entry.packageName + ".enabled", enabled).commit();
            entry.enabled = enabled;
            Collections.sort(allApps, APP_COMPARATOR);
            renderAppList();
            toast((enabled ? "已启用：" : "已停用：") + entry.label
                    + (enabled ? "；重新启动该应用后生效" : ""));
        } catch (Throwable t) {
            toast("写入配置失败：" + t.getMessage());
            loadInstalledApps();
        }
    }

    private void updateFrameworkBadge() {
        if (frameworkBadge == null) return;
        if (service == null) {
            frameworkBadge.setText("LSP 未连接");
            frameworkBadge.setTextColor(Color.rgb(170, 65, 65));
            return;
        }
        frameworkBadge.setText("LSP ✓");
        frameworkBadge.setTextColor(Color.rgb(30, 135, 70));
    }

    private String buildFrameworkStatus() {
        if (service == null) {
            return "框架状态：未连接\n请在 LSPosed/LSP 中启用应用变量 Pro。";
        }
        try {
            return "框架状态：已连接 ✅\n"
                    + "框架：" + service.getFrameworkName() + " " + service.getFrameworkVersion() + "\n"
                    + "API：" + service.getApiVersion() + "\n"
                    + "当前 Scope 数：" + service.getScope().size();
        } catch (Throwable t) {
            return "框架已连接，但读取状态失败：" + t.getMessage();
        }
    }

    private void showFrameworkToast() {
        toast(buildFrameworkStatus());
    }

    private TextView bottomTab(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(14f);
        v.setTextColor(Color.rgb(95, 95, 108));
        return v;
    }

    private void setTabSelected(boolean home, boolean apps) {
        if (homeTab != null) {
            homeTab.setTextColor(home ? Color.rgb(70, 65, 125) : Color.rgb(110, 110, 120));
            homeTab.setTypeface(null, home ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (appsTab != null) {
            appsTab.setTextColor(apps ? Color.rgb(70, 65, 125) : Color.rgb(110, 110, 120));
            appsTab.setTypeface(null, apps ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private LinearLayout.LayoutParams cardLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        return lp;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable roundRect(int fillColor, int radiusPx, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private static final Comparator<AppEntry> APP_COMPARATOR = (a, b) -> {
        if (a.enabled != b.enabled) return a.enabled ? -1 : 1;
        int time = Long.compare(b.installTime, a.installTime);
        if (time != 0) return time;
        return a.label.compareToIgnoreCase(b.label);
    };

    private static final class AppEntry {
        final String label;
        final String packageName;
        final Drawable icon;
        final long installTime;
        boolean enabled;

        AppEntry(String label, String packageName, Drawable icon, long installTime, boolean enabled) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
            this.installTime = installTime;
            this.enabled = enabled;
        }
    }
}
