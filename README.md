# TvHelperApp —— 电视助手（仿写实现）

一个纯 Kotlin + Jetpack Compose 的 Android 应用，用于在同一局域网内连接小米电视、遥控电视、打开电视上已安装的应用，以及把手机里的 APK 推送到电视安装。

本工程是对「小米电视助手 v2.7.6」的**协议级仿写**：不依赖小米账号、不依赖原版 APK，直接按逆向还原出的私有协议与电视通信。当前版本 **1.6.0**（versionCode 13），已在小米电视真机端到端联调通过。

## 功能一览

- **连接**：mDNS 扫描 + 手动输入 IP，记住上次设备；支持自动连接（启动/回前台直连，不跳连接页）
- **遥控**：方向键 / OK / 返回 / 主页 / 菜单 / 音量± / 静音 / 电源，支持长按连发（标准 repeat 模式）；触控板（部分固件不开放）
- **应用管理**：枚举已安装应用（含真实图标懒加载 + 磁盘缓存）、打开、固定到首页宫格、长按弹出底部动作面板（打开/固定/上移/下移/卸载）
- **APK 安装**：手机起 HTTP 服务托管 APK，电视端拉取并弹官方确认框
- **保活**：前台服务常驻通知 + 断线自动重连（退避 2s/5s/10s）+ 回前台自动重连
- **设置**：深浅色主题切换（跟随系统/浅色/深色）、自动连接开关

---

## 一、功能与协议映射

| 功能 | 依赖通道 | 用到的协议要点 |
| --- | --- | --- |
| 电视发现 | mDNS/UDP 5353 | 浏览 `_rc._tcp.local.`（30612）与 `_milink._tcp.local.`（TXT 带 `tcp=6093`/`udp=6094`/`mac`） |
| 手动输入 IP 连接 | TCP 探测 | 并行探测 30612 / 6093 / 6088，任一开放即认为「像电视」 |
| 遥控按键 | RC / TCP 30612 | `SendKey`（type 4）：TLV 携带 metaState/action/keyCode/**scanCode**/repeatCount/downTime/eventTime/deviceId/source，时间戳用 uptimeMillis |
| 触控板 | RC / TCP 30612 | `SendTouch`（type 6）：每指 13 个 TLV 字段 |
| 打开电视上的 App | UDT / TCP 6093 (ctrl) | `openApp`，参数 `[时间戳, 包名]`，`status == 0` 为成功 |
| 枚举已安装 App | UDT / TCP 6093 (data) | `getInstalledPackages`（大返回走 data 连接，分页）；返回 `PkgInfos[].appInfo` 内嵌 JSON |
| 应用图标 | UDT / TCP 6093 (data) | `getPackageInfo(pkg, filter=3, [31,63])`，PNG 字节走 data 段 |
| 卸载应用 | UDT / TCP 6093 (ctrl) | `uninstallApp` |
| 安装本地 APK | UDT + 手机侧 HTTP | 手机起 `ServerSocket` 托管 APK → `installUrlApp(url)` → 电视自行拉取并弹官方确认框 |

### 为什么不用小米账号

逆向确认：`openApp` / `getInstalledPackages` / `installUrlApp` / `getPackageInfo` 走的是 6 参 `postCall`（`z11 = false`），**不触发安全芯片签名**，因此整条链路无需登录小米账号。

---

## 二、协议要点（实现依据）

### 2.1 RC（AirKan RemoteControl，TCP 30612）

统一帧结构，全部大端：

```
[type 1B][payloadLen 2B BE][payload ≤ 1500B]
```

关键 type：`2` 心跳（payload `{2,0,0}`，10s 间隔）、`3` 播放控制、`4` 按键、`5` 版本/密钥协商、`6` 触摸、`61 (0x3D)` 连接就绪。

**SendKey payload 布局**：

```
[subType=01][msgId 4B BE][dataLen 2B BE][TLV…]
```

header 里那 4 个「保留字节」实为**包级自增 msgId**。TLV 子码：`01` metaState(i32) · `02` action · `03` keyCode · `04` scanCode · `05` repeatCount · `06` flags · `07` downTime(i64) · `08` eventTime(i64) · `0A` deviceId · `0B` source · `09` characters · `0C` extra。

**按键帧三条铁律**（v1.6.0 真机踩坑得出，违反会导致音量无效甚至电视输入系统卡死）：

1. `downTime`/`eventTime` 必须用 `SystemClock.uptimeMillis()`，**绝不能用 `currentTimeMillis()`**（wall clock 巨值会让电视端输入队列时间判断异常）
2. `scanCode` 必须填真实 Linux scancode（音量 115/114、方向 103/108/105/106、OK 352、电源 116、返回 158、主页 102 等），电视端部分处理依赖它
3. 长按连发必须走**标准 repeat 模式**：按下发 1 帧 DOWN，之后每 180ms 发递增 `repeatCount` 的 DOWN，松手补一次 UP。**绝不能每次发完整 DOWN+UP 对**——会洪泛电视端输入队列，导致连物理遥控器都失灵

**SendTouch** 的 TLV 子码：`04` downTime(i64)、`05` eventTime(i64)、`02` action、`0C` pointerCount，每指 `0D` index / `0E` id / `0F` toolType / `10` orientation(f32) / `11` pressure / `12` size / `13`-`16` 几何量 / `1B` x(f32) / `17` y(f32)，尾部 `01` metaState / `08` buttonState / `09` xPrecision / `0A` yPrecision / `06` deviceId / `0B` edgeFlags / `07` source / `03` flags。

> `09` 在按键里是 characters、在触摸里是 xPrecision；`0C` 在按键里是 extra、在触摸里是 pointerCount——同名子码语义随 type 变化，不能共用解析器。

**版本协商（type 5）**：请求 `code=1` + TLV(`01`+version i32、`02`+u16+版本文字、`04`+u16+extra JSON)；响应 `code=2` + TLV(`01`+version、`03`+1B 结果、`04`+u16+extra)。当电视版本 > `0x01000200` 时，响应 extra 里下发 RSA 加密的 `secretKey`，派生 AES/ECB/PKCS5 会话密钥加密后续帧。**解密需手机本地授权文件里的 RSA 私钥**（小米账号 AirKan 授权下发，本 App 不实现该链路，遇到会在首页显式警告）。

### 2.2 UDT（milink，TCP 6093）

ctrl + data 双连接（本地绑定 1025）。消息层帧结构：

```
[ctrlLen u32BE][dataLen u32BE][tailLen u32BE][ctrl JSON][data bytes][tail JSON]
```

**会话握手**（逆向 `o6.b` 得出，缺这步所有加密请求被电视 RST）：TCP 建连后立即发送

```
payload = {0,0,0,0,5, ctrl?0:1, appId BE×2}   # 10 字节，appId=1025=0x0401
外层头 src/dst = 0
```

**UDTPacket 16 字节分帧头**（所有帧套在外面）：

```
[0]=0x55  [1]=0x10|(more<<3)  [7]=0x10
[8..11]=片长 BE  [12..13]=srcAppId  [14..15]=dstAppId
ctrl 单片上限 102400；data 分片 1440
```

读端遇到 type=0 的控制帧直接吞掉，不进消息层。

**ctrl JSON 要点**：

- 外层键名是 **`action`**，不是 `type`；`control.type == 0` 表示方法调用
- `requestId` 形如 `<appVersionCode>:<uuid>`
- `method{methodName, methodCallID, invokerID, verifyCode, methodCall{args, useDataChannelReturn}}`，另有自增 `id`
- tail 为 `{requestId, device_id, encrypt}`

响应为 `method.methodReturn{status, result, error_msg}`；大响应走 `method.dataChannel{page_no, total, page_size, total_size, md5}` 分页，`page_no` 从 2 开始。

---

## 三、工程结构

```
TvHelperApp/
├── app/src/main/java/com/tvhelper/app/
│   ├── MainActivity.kt              # Tab 导航（HOME/REMOTE/SETTINGS，INSTALL 走首页按钮）+ 通知权限 + ON_RESUME 自动重连
│   ├── TvHelperApp.kt               # Application，持有 AppContainer
│   ├── protocol/                    # 纯 Kotlin 协议层（无 Android 依赖，可 JVM 单测）
│   │   ├── Bytes.kt / AirkanCrypto.kt（AES 会话加密）
│   │   ├── airkan/                  # 授权文件解析（RSA）
│   │   ├── rc/{RcCodec,RcKeyCodec,RcTouchCodec,RcVersionCodec}.kt
│   │   └── udt/{UdtCodec,UdtMethods}.kt   # 含会话握手帧 + UDTPacket 分帧
│   ├── net/                         # 连接层
│   │   ├── NetUtils.kt / IpProbe.kt / MdnsDiscovery.kt
│   │   ├── AirkanAuthClient.kt      # 授权
│   │   ├── RcClient.kt              # 30612：就绪帧 → 版本请求 → 加密 → 读循环 → 10s 心跳
│   │   ├── UdtClient.kt             # 6093：双连接、握手、分帧、requestId 匹配、分页拼装、反向 RPC
│   │   └── TvApi.kt                 # UDT 业务封装（含 getPackageIcon）
│   ├── apkserver/ApkHttpServer.kt   # 极简 HTTP（GET/HEAD + Range），托管 APK 给电视下载
│   ├── data/                        # Models / SettingsStore(DataStore) / PairingStore / TvRepository(编排+图标缓存+断线重连)
│   ├── service/ConnectionService.kt # 前台保活服务（connectedDevice 类型）
│   ├── di/AppContainer.kt           # 手写单例容器
│   ├── vm/TvViewModel.kt            # UI 状态编排（长按标准 repeat 连发、自动连接）
│   └── ui/                          # Compose：theme / common / connect / home / remote / install / settings
└── app/src/test/java/.../           # 34 个 JVM 协议测试（ProtocolTest/AirkanCryptoTest/UdtPacketTest）
```

架构分层：`UI → ViewModel → Repository → (RcClient / UdtClient / ApkHttpServer / ConnectionService)`。协议层完全无 Android 依赖，能用纯 JVM 单测做字节级校验。

> 方案原稿写的是 Hilt + Ktor；实现改为**手写 `AppContainer`**（避免注解处理器）和**`ServerSocket` 自实现 HTTP**（只需 GET/HEAD + Range）。分层不变。

---

## 四、构建

环境要求：JDK 17、Android SDK（compileSdk 34）。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。`local.properties` 的 `sdk.dir` 与 `gradle.properties` 的 `org.gradle.java.home` 按本机实际路径填写。

---

## 五、线程与并发模型

Android 不允许主线程网络 I/O，所有 socket 操作内部显式切 `Dispatchers.IO`（RcClient/UdtClient 的 connect/writeRaw、TvRepository 的 connect/probeAndConnect/installApk、IpProbe 等），调用方无需关心。

并发要点（v1.5.1 踩坑）：

- `TvRepository.connect()` 用 **Mutex 串行化**——init 自动连接与 ON_RESUME 重入会并发建**双套通道**，加锁后谁先连上谁生效，自动场景 `force=false`（已连接直接返回）
- 图标加载用 `Semaphore(4)` 限流 + 去重，磁盘缓存 `cacheDir/tvicons`
- 断线自动重连带 `userDisconnected` 标志，用户主动断开不触发

## 六、连接端口

RC 端口**不是固定 30612**：优先取 mDNS `_rc._tcp` SRV 记录的端口，仅当为 0 才回落 30612（实测有电视广播 6091）。

| 通道 | 候选顺序 |
| --- | --- |
| RC | 广播端口（若有）→ 30612 → 6091 |
| UDT | 广播端口（若有）→ 6093（milink 的 TXT `tcp=` 优先于 SRV） |

全部失败才报错，错误消息里列出**实际尝试过的端口**。

## 七、已知限制

1. **加密固件**：电视版本 > `0x01000200` 需要 AES 会话密钥（小米账号 AirKan 授权下发 RSA 私钥），本 App 未实现该链路，UI 显式提示「按键可能无效」。
2. **安装确认**：电视端一定弹系统确认框，无法绕过。
3. **触控注入**：部分固件不开放 type 6，此时方向键仍可用。
4. **ColorOS/杀后台**：激进厂商可能杀掉前台服务，建议系统设置里允许自启动/后台运行；代码侧已有断线重连 + 回前台重连兜底。

## 八、排查

1. **连接失败**：看错误文案里的「已尝试端口」；确认同一 Wi-Fi、路由器未开 AP 隔离。
2. **双通道状态**：首页英雄卡徽章显示 RC/UDT/加密各自状态；RC 就绪但 UDT 未连通 → 遥控可用、应用管理不可用。
3. **按键无效/电视卡死**：优先检查时间戳是否 uptimeMillis、scanCode 是否填了、长按是否走 repeat 模式（见 2.1 三条铁律）。
4. 日志：`adb logcat -s TvRepository RcClient UdtClient ApkHttpServer ConnectionService`。

## 九、变更记录

### 1.6.0 (13)
- **修复音量键无效 + 电视输入卡死**：时间戳改 uptimeMillis、填真实 scancode、长按改标准 repeat 模式（旧实现 90ms 全量 DOWN+UP 对洪泛电视输入队列）

### 1.5.2 (12)
- connect() 加 Mutex + force 参数，修复启动时双套 RC/UDT 通道
- 断线原因诊断日志（读循环 EOF/异常、心跳失败、重连 cause）

### 1.5.0 (10)
- 首页未连接主 CTA：有上次设备直接重连，否则才跳连接页；连接中进度态
- RC 断线自动重连（退避 2s/5s/10s 最多 3 次）；回前台 ON_RESUME 自动重连
- 请求 POST_NOTIFICATIONS 权限（Android 13+ 常驻通知需要）

### 1.4.2 / 1.4.1 / 1.4.0
- 长按菜单 → ModalBottomSheet 底部动作面板；英雄卡按钮行（安装 APK / 断开连接）
- 安装入口改首页按钮；长按卸载；电源键 Canvas 自绘图标
- Git 初始化；连接成功自动刷新应用列表；设置页（主题切换 + 自动连接）；首页渐变英雄卡

### 1.3.x
- 前台保活服务、自动连接、音量 UP 帧 eventTime 偏移、图标懒加载+缓存、按压反馈
- 修复扫描页 LazyColumn 重复 key 崩溃、快捷图标重启失效
- **UDT 加密通道打通**：会话握手帧 + UDTPacket 16 字节分帧

### 1.1.0
- AirKan 授权（AirkanAuthClient/AirkanCrypto）+ RC 加密会话

### 1.0.x
- 首版：连接/发现、遥控、应用管理、Pin、APK 推送安装
- 修复 NetworkOnMainThreadException（socket 统一切 IO）；端口候选列表回落

## 十、单测

`app/src/test` 共 **34 项** JVM 测试，全部为字节级黄金校验：

- `ProtocolTest`（27）：RC 帧头/心跳/就绪、版本协商、SendKey 逐字节比对与 TLV 顺序、scanCode 映射、长按 repeat 帧序列、触摸帧、UDT 三段长度/ctrl `action` 键/tail 回填/分页解析、端口候选顺序
- `UdtPacketTest`（5）：会话握手帧、UDTPacket 16 字节头、分片 more 标志、控制帧识别
- `AirkanCryptoTest`（8）：AES 会话加密往返
