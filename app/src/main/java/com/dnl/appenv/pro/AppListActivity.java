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
import android.widget.Button;
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

public final class AppListActivity extends Activity implements AppEnvApplication.ServiceStateListener {
    private static final String PREF_GROUP = "appenv";

    private XposedService service;
    private LinearLayout pageHost;
    private LinearLayout appList;
    private TextView frameworkBadge;
    private TextView titleView;
    private TextView homeTab;
    private TextView appsTab;

    private final List<AppEntry> allApps = new ArrayList<>();
    private String query = "";
    private AppEntry detailEntry;

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
    public void onBackPressed() {
        if (detailEntry != null) {
            showAppsPage();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onServiceStateChanged(XposedService newService) {
        service = newService;
        runOnUiThread(() -> {
            updateFrameworkBadge();
            if (detailEntry == null && appList != null) loadInstalledApps();
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

        titleView = new TextView(this);
        titleView.setText("应用");
        titleView.setTextSize(26f);
        titleView.setTextColor(Color.rgb(35, 35, 43));
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(titleView, new LinearLayout.LayoutParams(0, dp(56), 1f));

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
        more.setOnClickListener(v -> toast(buildFrameworkStatus()));
        header.addView(more, new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(header);

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
        root.addView(bottom);

        setContentView(root);
        updateFrameworkBadge();
    }

    private void showAppsPage() {
        detailEntry = null;
        titleView.setText("应用");
        setTabSelected(false, true);
        pageHost.removeAllViews();

        LinearLayout searchWrap = new LinearLayout(this);
        searchWrap.setPadding(dp(20), dp(4), dp(20), dp(10));
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索应用或包名");
        search.setTextSize(15f);
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setBackground(roundRect(Color.WHITE, dp(18), Color.rgb(225, 225, 234), 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                renderAppList();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        searchWrap.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        pageHost.addView(searchWrap);

        ScrollView scroll = new ScrollView(this);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appList.setPadding(dp(20), dp(2), dp(20), dp(20));
        scroll.addView(appList);
        pageHost.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        loadInstalledApps();
    }

    private void showHomePage() {
        detailEntry = null;
        titleView.setText("应用变量 Pro");
        setTabSelected(true, false);
        pageHost.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(16), dp(24), dp(30));
        scroll.addView(content);

        TextView version = text("1.0.1-dev · core 0.002", 16f, Color.DKGRAY);
        content.addView(version, matchWrap());

        TextView status = text(buildFrameworkStatus(), 15f, Color.rgb(55, 55, 64));
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        status.setBackground(roundRect(Color.WHITE, dp(18), Color.rgb(226, 226, 235), 1));
        content.addView(status, cardLayoutParams());

        TextView help = text("\n使用方式\n\n"
                + "• “应用”页显示已安装应用。\n"
                + "• 打开右侧开关后，该 App 自动置顶。\n"
                + "• 未启用 App 按首次安装时间从新到旧排序。\n"
                + "• 点击应用卡片进入变量详情，可生成下一套测试身份。\n"
                + "• 日志关键词：DNLAPPENV", 15f, Color.rgb(70, 70, 78));
        content.addView(help, matchWrap());
        pageHost.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void showAppDetail(AppEntry entry) {
        detailEntry = entry;
        titleView.setText("‹  " + entry.label);
        setTabSelected(false, true);
        pageHost.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(10), dp(22), dp(30));
        scroll.addView(content);

        LinearLayout identity = new LinearLayout(this);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setPadding(dp(16), dp(14), dp(16), dp(14));
        identity.setBackground(roundRect(Color.WHITE, dp(20), Color.rgb(226, 226, 235), 1));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(entry.icon);
        identity.addView(icon, new LinearLayout.LayoutParams(dp(66), dp(66)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(16), 0, 0, 0);
        TextView name = text(entry.label, 20f, Color.rgb(35, 35, 43));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        names.addView(name);
        names.addView(text(entry.packageName, 14f, Color.rgb(90, 90, 100)));
        identity.addView(names, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(identity, cardLayoutParams());

        boolean isSelf = getPackageName().equals(entry.packageName);
        Switch enabled = new Switch(this);
        enabled.setText(isSelf ? "模块本体（无需加入作用域）" : "启用应用变量");
        enabled.setTextSize(16f);
        enabled.setChecked(entry.enabled);
        enabled.setEnabled(!isSelf);
        enabled.setPadding(dp(12), dp(12), dp(12), dp(12));
        enabled.setOnCheckedChangeListener((buttonView, checked) -> {
            if (checked == entry.enabled) return;
            if (checked) enablePackage(entry, enabled); else disablePackage(entry);
        });
        content.addView(enabled, cardLayoutParams());

        long generation = readGeneration(entry.packageName);
        TextView generationView = text("当前身份代次：" + generation, 15f, Color.rgb(65, 65, 75));
        generationView.setPadding(dp(12), dp(12), dp(12), dp(4));
        content.addView(generationView, matchWrap());

        Button rotate = new Button(this);
        rotate.setText("🎲 生成下一套身份");
        rotate.setAllCaps(false);
        rotate.setEnabled(!isSelf);
        rotate.setOnClickListener(v -> {
            if (!entry.enabled) {
                toast("请先打开“启用应用变量”开关");
                return;
            }
            if (bumpGeneration(entry.packageName)) {
                long next = readGeneration(entry.packageName);
                generationView.setText("当前身份代次：" + next);
                toast("已生成下一套身份；强制停止 " + entry.label + " 后重新启动");
            }
        });
        content.addView(rotate, cardLayoutParams());

        TextView info = text("\n当前 core 0.002：\n"
                + "• Android ID：通用 Framework Hook\n"
                + "• com.zygote.*：OAID/deviceId/AppInfo 兼容\n"
                + "• 新身份代次：隐藏旧登录会话，并等待游客注册重新执行\n"
                + "• Register Gate 日志：DNLAPPENV_REGISTER_GATE_*", 14f,
                Color.rgb(85, 85, 95));
        content.addView(info, matchWrap());

        titleView.setOnClickListener(v -> showAppsPage());
        pageHost.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void loadInstalledApps() {
        new Thread(() -> {
            List<AppEntry> loaded = new ArrayList<>();
            PackageManager pm = getPackageManager();
            SharedPreferences remote = null;
            try {
                if (service != null) remote = service.getRemotePreferences(PREF_GROUP);
            } catch (Throwable ignored) { }

            try {
                List<ApplicationInfo> infos = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                for (ApplicationInfo info : infos) {
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
                    } catch (Throwable ignored) { }

                    boolean enabled = false;
                    if (remote != null) {
                        try {
                            enabled = remote.getBoolean(info.packageName + ".enabled", false);
                        } catch (Throwable ignored) { }
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
            TextView empty = text(allApps.isEmpty() ? "正在读取已安装应用…" : "没有匹配的应用",
                    15f, Color.DKGRAY);
            empty.setGravity(Gravity.CENTER);
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
        card.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(8), 0);
        TextView name = text(entry.label, 18f, Color.rgb(36, 36, 45));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        texts.addView(name);
        texts.addView(text(entry.packageName, 13.5f, Color.rgb(75, 75, 86)));
        if (entry.installTime > 0L) {
            texts.addView(text("安装：" + DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT).format(new Date(entry.installTime)),
                    11.5f, Color.rgb(135, 135, 145)));
        }
        card.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(entry.enabled);
        boolean isSelf = getPackageName().equals(entry.packageName);
        toggle.setEnabled(!isSelf);
        toggle.setOnCheckedChangeListener((buttonView, checked) -> {
            if (checked == entry.enabled) return;
            if (checked) enablePackage(entry, toggle); else disablePackage(entry);
        });
        card.addView(toggle, new LinearLayout.LayoutParams(dp(70), dp(52)));

        card.setOnClickListener(v -> showAppDetail(entry));
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
        if (service == null) return;
        try {
            service.getRemotePreferences(PREF_GROUP).edit()
                    .putBoolean(entry.packageName + ".enabled", enabled)
                    .commit();
            entry.enabled = enabled;
            Collections.sort(allApps, APP_COMPARATOR);
            if (detailEntry == null) renderAppList();
            toast((enabled ? "已启用：" : "已停用：") + entry.label
                    + (enabled ? "；重新启动该应用后生效" : ""));
        } catch (Throwable t) {
            toast("写入配置失败：" + t.getMessage());
        }
    }

    private boolean bumpGeneration(String pkg) {
        if (service == null) {
            toast("Xposed Service 尚未连接");
            return false;
        }
        try {
            SharedPreferences p = service.getRemotePreferences(PREF_GROUP);
            String key = pkg + ".generation";
            long next = p.getLong(key, 0L) + 1L;
            return p.edit().putBoolean(pkg + ".enabled", true).putLong(key, next).commit();
        } catch (Throwable t) {
            toast("生成新身份失败：" + t.getMessage());
            return false;
        }
    }

    private long readGeneration(String pkg) {
        if (service == null) return 0L;
        try {
            return service.getRemotePreferences(PREF_GROUP).getLong(pkg + ".generation", 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private String buildFrameworkStatus() {
        if (service == null) return "框架状态：未连接\n请在 LSP/LSPosed 中启用应用变量 Pro。";
        try {
            return "框架状态：已连接 ✅\n"
                    + "框架：" + service.getFrameworkName() + " " + service.getFrameworkVersion() + "\n"
                    + "API：" + service.getApiVersion() + "\n"
                    + "Scope：" + service.getScope().size() + " 个应用";
        } catch (Throwable t) {
            return "框架已连接，但读取状态失败：" + t.getMessage();
        }
    }

    private void updateFrameworkBadge() {
        if (frameworkBadge == null) return;
        if (service == null) {
            frameworkBadge.setText("LSP 未连接");
            frameworkBadge.setTextColor(Color.rgb(170, 65, 65));
        } else {
            frameworkBadge.setText("LSP ✓");
            frameworkBadge.setTextColor(Color.rgb(30, 135, 70));
        }
    }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private TextView bottomTab(String value) {
        TextView v = text(value, 14f, Color.rgb(95, 95, 108));
        v.setGravity(Gravity.CENTER);
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

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private static final Comparator<AppEntry> APP_COMPARATOR = (a, b) -> {
        if (a.enabled != b.enabled) return a.enabled ? -1 : 1;
        int byInstall = Long.compare(b.installTime, a.installTime);
        if (byInstall != 0) return byInstall;
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
