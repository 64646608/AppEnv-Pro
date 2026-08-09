# 应用变量 Pro

面向现代 Android / LSPosed 的应用环境变量测试工具。

## 当前版本

`1.0.0-dev / core 0.001`

第一阶段目标：

- 使用 Modern Xposed API 102；
- Android 16+ / 新版 LSPosed 兼容基线；
- 每个目标应用独立 Profile；
- 一键生成测试安装身份；
- Android ID 环境 Hook；
- OAID / deviceId 的 Zygote 商业 SDK 兼容适配；
- Remote Preferences 保存配置；
- GitHub Actions 云端自动编译 APK；
- 无云账号、无遥测、无第三方统计。

## 首批测试包

- `com.tyylt.hxy`
- `com.sm.hdhsg`

后续会逐步扩展为通用应用选择与更多环境变量。

## 构建

仓库每次 push 后由 GitHub Actions 自动执行 Debug APK 构建。构建成功后在对应 Workflow Run 的 **Artifacts** 下载 `AppEnv-Pro-debug`。

## 来源说明

本项目参考老项目 `kingsollyu/AppEnv` 的“按应用配置环境变量”设计思想，但采用现代 Android / libxposed API 重新实现核心代码。