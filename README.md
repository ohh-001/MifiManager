# MifiManager – 随身WiFi管理器

MifiManager 是一个为 Android 设备（特别是多亲1s+ 等具有双卡功能且已 root 的手机）设计的系统级应用。它将你的手机变成一个功能强大的便携式 WiFi 路由器，并提供类似企业级路由器的 Web 后台管理界面。通过热点连接后，你可以在浏览器中轻松配置 WiFi、切换主副卡、管理连接设备、设置黑名单等。

## ✨ 功能特点

- **简洁桌面启动器**：应用图标点击即可打开主界面，提供快速操作按钮。
- **Web 后台管理**：连接手机热点后，通过浏览器访问 `http://192.168.43.1:8080` 进入后台（IP 可能因热点设置而异）。
- **首次设置**：第一次访问后台强制要求设置管理员密码（加密存储）。
- **WiFi 设置**：修改热点 SSID、密码、加密方式（开放 / WPA2-PSK）。保存后设备自动重启使配置生效。
- **SIM 卡切换**：在主副卡之间切换默认数据卡（需设备硬件支持及 root）。
- **设备管理**：实时查看在线客户端（IP 和 MAC），支持一键拉黑。
- **黑名单管理**：查看已拉黑的设备，并可随时移除。
- **管理员密码修改**：允许修改登录密码。
- **关机重启**：通过后台直接执行关机或重启操作（需 root）。
- **日志抓取**：内置日志记录功能，将 logcat 输出保存到 `/sdcard/Android/data/com.qin.github.MifiManager/files/logs/`，方便调试。

## 📱 适用设备

- 已 root 的 Android 4.4+ 设备（测试机型：多亲1s+，Android 4.4.4）
- 需要双卡双待或随身 WiFi 场景

## 🚀 快速开始

### 1. 编译 APK

推荐使用 Android Studio 打开项目，直接编译生成 APK。

- 克隆本仓库或下载源码。
- 在 Android Studio 中打开项目（选择 `MifiManager` 目录）。
- 等待 Gradle 同步完成，点击 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
- 生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 2. 安装为系统应用（推荐）

为了获得完整 root 权限，建议将应用安装到 `/system/priv-app/` 目录。

```bash
# 连接手机并获取 root 权限
adb root
adb remount

# 创建应用目录并推送 APK
adb shell mkdir -p /system/priv-app/MifiManager
adb push app-debug.apk /system/priv-app/MifiManager/MifiManager.apk

# 设置权限
adb shell chmod 644 /system/priv-app/MifiManager/MifiManager.apk

# 重启设备
adb reboot
重启后，应用将作为系统应用运行，所有功能（包括关机、修改热点等）均可正常使用。

3. 使用 Web 后台
在手机上开启 便携式热点（设置 → 热点与网络共享 → 便携式Wi-Fi热点）。

用另一台设备连接该热点。

在浏览器中访问 http://192.168.43.1:8080（如果热点 IP 不同，请根据实际网关调整，如 192.168.1.1）。

首次访问会要求设置管理员密码。

登录后即可看到功能菜单。

🛠️ 开发说明
项目结构
text
MifiManager/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/qin/github/MifiManager/
│   │   │   │   ├── MainActivity.java          # 桌面启动器
│   │   │   │   ├── WebServerService.java      # Web 后台服务（核心）
│   │   │   │   └── ...
│   │   │   ├── res/                           # 资源文件
│   │   │   └── AndroidManifest.xml
│   └── build.gradle
└── README.md
主要依赖
NanoHTTPD – 轻量级 Java Web 服务器，用于提供后台页面和 API。

编译要求
Android Studio 4.0+

Gradle 7.5

Android SDK API 28（兼容 API 19）

🤝 贡献
欢迎提交 Issue 或 Pull Request。如果你有好的想法或发现 bug，请告知我们。

📄 许可证
本项目采用 MIT 许可证。你可以自由使用、修改和分发代码，但需保留原版权声明。

注意：部分功能（如切换 SIM 卡、修改热点配置）依赖 root 权限，请确保你的设备已正确 root（如 Magisk）。不同厂商设备的命令可能有所差异，欢迎提交适配方案。
