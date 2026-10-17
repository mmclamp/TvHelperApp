# TvHelperApp —— 电视助手

一个纯 Kotlin + Jetpack Compose 的 Android 应用，用于在同一局域网内连接小米电视、遥控电视、管理电视上已安装的应用，以及把手机里的 APK 推送到电视安装。

本工程是对「小米电视助手 v2.7.6」的协议级仿写：不依赖小米账号、不依赖原版 APK，直接按逆向还原出的私有协议与电视通信。已在小米电视真机端到端联调通过。

## 功能

- **连接**：mDNS 扫描 / 手动输入 IP，记住上次设备，支持启动与回前台自动连接
- **遥控**：方向键 / OK / 返回 / 主页 / 菜单 / 电源，长按连发；音量调节与静音
- **应用管理**：枚举电视上已安装应用（真实图标）、打开、固定到首页、卸载
- **APK 安装**：手机起 HTTP 服务托管 APK，电视端拉取并弹官方确认框
- **保活**：前台服务常驻通知 + 断线自动重连 + 电池优化白名单引导
- **个性化**：深浅色主题、强调色（6 色板）

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- 协议层为纯 Kotlin 实现，无 Android 依赖，配套 38 项 JVM 字节级单测
- DataStore 本地持久化；手写依赖容器；前台服务保活

## 构建

环境要求：JDK 17、Android SDK（compileSdk 34）。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

产物：`app/build/outputs/apk/{debug/app-debug.apk, release/app-release.apk}`。

## 目录

```
app/src/main/java/com/tvhelper/app/
├── protocol/   # RC / UDT 协议编解码（可 JVM 单测）
├── net/        # 连接层（RC、UDT、mDNS、IP 探测）
├── apkserver/  # APK 托管 HTTP 服务
├── data/       # 数据仓库与本地存储
├── service/    # 前台保活服务
├── vm/         # ViewModel
└── ui/         # Compose 界面
```

## 已知限制

- 电视固件开启加密会话时按键可能无效（App 内会显式提示）
- 电视端安装 APK 时一定弹系统确认框，无法绕过
- 部分固件不开放触控注入，此时方向键仍可用
