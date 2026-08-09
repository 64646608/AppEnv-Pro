package com.dnl.appenv.pro;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;

import io.github.libxposed.service.XposedService;

public final class MainActivity extends Activity implements AppEnvApplication.ServiceStateListener {
    private static final String HXY = "com.tyylt.hxy";
    private static final String HDHSG = "com.sm.hdhsg";
    private static final String PREF_GROUP = "appenv";

    private XposedService service;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("应用变量 Pro");
        title.setTextSize(26f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView sub = new TextView(this);
        sub.setText("Modern Xposed / Android 环境变量测试工具\n1.0.0-dev · core 0.001");
        sub.setTextSize(14f);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub, matchWrap());

        status = new TextView(this);
        status.setTextSize(15f);
        status.setText("正在连接 Xposed Service…");
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(status, matchWrap());

        Button scope = button("申请测试作用域（桃源 + 海岛）");
        scope.setOnClickListener(v -> requestScope());
        root.addView(scope, matchWrap());

        Button enable = button("启用两个测试包");
        enable.setOnClickListener(v -> setEnabledBoth(true));
        root.addView(enable, matchWrap());

        Button randomHxy = button("🎲 桃源有良田：生成下一套身份");
        randomHxy.setOnClickListener(v -> bumpGeneration(HXY));
        root.addView(randomHxy, matchWrap());

        Button randomIsland = button("🎲 海岛好时光：生成下一套身份");
        randomIsland.setOnClickListener(v -> bumpGeneration(HDHSG));
        root.addView(randomIsland, matchWrap());

        Button randomAll = button("🎲 两个应用全部生成下一套身份");
        randomAll.setOnClickListener(v -> {
            bumpGenerationInternal(HXY);
            bumpGenerationInternal(HDHSG);
            toast("已更新身份代次；强制停止目标游戏后重新启动");
        });
        root.addView(randomAll, matchWrap());

        TextView help = new TextView(this);
        help.setText("\n工作方式\n"
                + "• 每个目标应用在自己的私有目录保存安装身份。\n"
                + "• 清除目标游戏数据后，该身份会被系统一并删除，下次启动自动生成新身份。\n"
                + "• OAID/deviceId 使用 UUID 格式；Android ID 保持 16 位十六进制格式。\n"
                + "• 当前首批适配 com.zygote.* 商业 SDK。\n\n"
                + "测试日志关键词：DNLAPPENV");
        help.setTextSize(14f);
        root.addView(help, matchWrap());

        setContentView(scroll);
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
        runOnUiThread(this::renderStatus);
    }

    private void renderStatus() {
        if (service == null) {
            status.setText("框架状态：未连接\n请确认模块已在支持 Modern Xposed API 的框架中启用。");
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("框架状态：已连接 ✅\n");
            sb.append("框架：").append(service.getFrameworkName())
                    .append(" ").append(service.getFrameworkVersion()).append('\n');
            sb.append("API：").append(service.getApiVersion()).append('\n');
            sb.append("Scope：").append(service.getScope()).append('\n');
            long props = service.getFrameworkProperties();
            sb.append("Remote：")
                    .append((props & XposedService.PROP_CAP_REMOTE) != 0 ? "支持 ✅" : "不支持 ❌");
            status.setText(sb.toString());
        } catch (Throwable t) {
            status.setText("读取框架状态失败：" + t);
        }
    }

    private void requestScope() {
        if (!requireService()) return;
        List<String> packages = Arrays.asList(HXY, HDHSG);
        try {
            service.requestScope(packages, new XposedService.OnScopeEventListener() {
                @Override
                public void onScopeRequestApproved(List<String> approved) {
                    runOnUiThread(() -> {
                        toast("作用域已批准：" + approved);
                        renderStatus();
                    });
                }

                @Override
                public void onScopeRequestFailed(String message) {
                    runOnUiThread(() -> toast("作用域申请失败：" + message));
                }
            });
        } catch (Throwable t) {
            toast("作用域请求异常：" + t.getMessage());
        }
    }

    private void setEnabledBoth(boolean enabled) {
        if (!requireService()) return;
        try {
            SharedPreferences p = service.getRemotePreferences(PREF_GROUP);
            p.edit()
                    .putBoolean(HXY + ".enabled", enabled)
                    .putBoolean(HDHSG + ".enabled", enabled)
                    .apply();
            toast(enabled ? "两个测试包已启用" : "两个测试包已停用");
        } catch (Throwable t) {
            toast("写入配置失败：" + t.getMessage());
        }
    }

    private void bumpGeneration(String pkg) {
        if (bumpGenerationInternal(pkg)) {
            toast("已生成下一身份代次；强制停止目标游戏后重新启动");
        }
    }

    private boolean bumpGenerationInternal(String pkg) {
        if (!requireService()) return false;
        try {
            SharedPreferences p = service.getRemotePreferences(PREF_GROUP);
            String key = pkg + ".generation";
            long next = p.getLong(key, 0L) + 1L;
            p.edit().putBoolean(pkg + ".enabled", true).putLong(key, next).commit();
            return true;
        } catch (Throwable t) {
            toast("更新身份代次失败：" + t.getMessage());
            return false;
        }
    }

    private boolean requireService() {
        if (service == null) {
            toast("Xposed Service 尚未连接");
            return false;
        }
        return true;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
