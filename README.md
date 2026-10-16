# TvHelperApp —— 电视助手（仿写实现）

一个纯 Kotlin + Jetpack Compose 的 Android 应用，用于在同一局域网内连接小米电视、遥控电视、打开电视上已安装的应用，以及把手机里的 APK 推送到电视安装。

本工程是对「小米电视助手 v2.7.6」的**协议级仿写**：不依赖小米账号、不依赖原版 APK，直接按逆向还原出的两套私有协议与电视通信。

---

## 一、功能与协议映射

| 功能 | 依赖通道 | 用到的协议要点 |
| --- | --- | --- |
| 电视发现 | mDNS/UDP 5353 | 浏览 `_rc._tcp.local.`（30612）与 `_milink._tcp.local.`（TXT 带 `tcp=6093`/`udp=6094`/`mac`） |
| 手动输入 IP 连接 | TCP 探测 | 并行探测 30612 / 6093 / 6088，任一开放即认为「像电视」 |
| 遥控按键 | RC / TCP 30612 | `SendKey`（type 4）：TLV 携带 metaState/action/keyCode/downTime/eventTime/deviceId/source |
| 触控板 | RC / TCP 30612 | `SendTouch`（type 6）：每指 13 个 TLV 字段（index/id/toolType/orientation/pressure/size/…/x/y） |
| 打开电视上的 App | UDT / TCP 6093 (ctrl) | `openApp`，参数 `[时间戳, 包名]`，`status == 0` 为成功 |
| 枚举已安装 App | UDT / TCP 6093 (data) | `getInstalledPackages`（大返回走 data 连接，可分页）；返回 `PkgInfos[].appInfo` 内嵌 JSON 字符串 |
| 首页 Pin 应用 | 本地 | DataStore 持久化包名 + label，首页 4 列宫格 |
| 卸载应用 | UDT / TCP 6093 (ctrl) | `uninstallApp` |
| 安装本地 APK | UDT + 手机侧 HTTP | 手机起 `ServerSocket` 托管 APK → `installUrlApp(url)` → 电视自行拉取并弹官方确认框 |

### 为什么不用小米账号

逆向确认：`openApp` / `getInstalledPackages` / `installUrlApp` 走的是 6 参 `postCall`（`z11 = false`），**不触发安全芯片签名**，因此整条链路无需登录小米账号。

---

## 二、协议要点（实现依据）

### 2.1 RC（AirKan RemoteControl，TCP 30612）

统一帧结构，全部大端：

```
[type 1B][payloadLen 2B BE][payload ≤ 1500B]
```

关键 type：

| type | 含义 |
| --- | --- |
| 2 | 心跳，payload `{2,0,0}` |
| 3 | 播放控制 |
| 4 | 按键 `SendKey` |
| 5 | 版本/密钥协商 |
| 6 | 触摸 `SendTouch` |
| 61 (`0x3D`) | 连接就绪 |

**SendKey payload 布局**：

```
[subType=01][msgId 4B BE][dataLen 2B BE][TLV…]
```

其中 header 里那 4 个「保留字节」实为**包级自增 msgId**。TLV 子码顺序：

`01` metaState(i32) · `02` action · `03` keyCode · `04` scanCode · `05` repeatCount · `06` flags · `07` downTime(i64) · `08` eventTime(i64) · `0A` deviceId · `0B` source · `09` characters(u16 长度 + UTF-8) · `0C` extra

**SendTouch** 的 TLV 子码对应 Netty opcode 常量：`04` downTime(i64)、`05` eventTime(i64)、`02` action、`0C` pointerCount，每指 `0D` index / `0E` id / `0F` toolType / `10` orientation(f32) / `11` pressure / `12` size / `13` toolMajor / `14` toolMinor / `15` touchMajor / `16` touchMinor / `1B` x(f32) / `17` y(f32)，尾部 `01` metaState / `08` buttonState / `09` xPrecision / `0A` yPrecision / `06` deviceId / `0B` edgeFlags / `07` source / `03` flags。

> 注意 `09` 在按键里是 characters、在触摸里是 xPrecision，`0C` 在按键里是 extra、在触摸里是 pointerCount —— 同名子码语义随 type 变化，不能共用解析器。

**版本协商（type 5）**：请求 `code=1` + TLV(`01`+version i32、`02`+u16+版本文字、`04`+u16+extra JSON)；响应 `code=2` + TLV(`01`+version、`03`+1B 结果、`04`+u16+extra)。

当电视版本 > `0x01000200` 时，响应 extra 里会下发 RSA 加密的 `secretKey`，用它派生 AES/ECB/PKCS5 会话密钥加密后续帧。**解密需要手机本地授权文件里的 RSA 私钥（由小米账号 AirKan 授权下发），本 App 不实现该链路**，遇到这类固件会在首页显式给出警告。

### 2.2 UDT（milink，TCP 6093）

ctrl + data 双连接（本地绑定 1025）。帧结构：

```
[ctrlLen u32BE][dataLen u32BE][tailLen u32BE][ctrl JSON][data bytes][tail JSON]
```

ctrl JSON 要点：

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
│   ├── MainActivity.kt              # 四页底部导航 + Toast 消费
│   ├── TvHelperApp.kt               # Application，持有 AppContainer
│   ├── protocol/                    # 纯 Kotlin 协议层（无 Android 依赖，可 JVM 单测）
│   │   ├── Bytes.kt                 # 大端编解码工具
│   │   ├── rc/{RcCodec,RcKeyCodec,RcTouchCodec,RcVersionCodec}.kt
│   │   └── udt/{UdtCodec,UdtMethods}.kt
│   ├── net/                         # 连接层
│   │   ├── NetUtils.kt / IpProbe.kt / MdnsDiscovery.kt
│   │   ├── RcClient.kt              # 30612：就绪帧 → 版本请求 → 读循环 → 10s 心跳
│   │   ├── UdtClient.kt             # 6093：双连接、requestId 匹配、分页拼装、反向 RPC 应答
│   │   └── TvApi.kt                 # UDT 业务封装
│   ├── apkserver/ApkHttpServer.kt   # 极简 HTTP（GET/HEAD + Range），托管 APK 给电视下载
│   ├── data/                        # Models / SettingsStore(DataStore) / TvRepository(编排)
│   ├── di/AppContainer.kt           # 手写单例容器
│   ├── vm/TvViewModel.kt            # UI 状态编排（含按键长按连发 90ms）
│   └── ui/                          # Compose：theme / common / connect / home / remote / install
└── app/src/test/java/.../ProtocolTest.kt   # 17 个协议黄金测试
```

架构分层：`UI → ViewModel → Repository → (RcClient / UdtClient / ApkHttpServer)`。协议层完全无 Android 依赖，因此能用纯 JVM 单测做字节级校验。

> 方案原稿写的是 Hilt + Ktor；实现时改为**手写 `AppContainer`**（避免注解处理器，编译链更短）和**`ServerSocket` 自实现 HTTP**（只需 GET/HEAD + Range，少一个重依赖）。分层不变，后续要换回 Hilt/Ktor 不影响现有代码。

---

## 四、构建

环境要求：JDK 17、Android SDK（compileSdk 34）。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

`local.properties` 里的 `sdk.dir` 与 `gradle.properties` 里的 `org.gradle.java.home` 按本机实际路径填写。

---

## 五、线程模型（重要）

Android 不允许在主线程做网络 I/O，否则抛 `NetworkOnMainThreadException`。
`viewModelScope` 默认跑在 `Dispatchers.Main`，所以**所有 socket 操作都必须显式切到 IO**：

| 层 | 处理方式 |
| --- | --- |
| `RcClient.connect()` / `writeRaw()` | 内部 `withContext(Dispatchers.IO)`，阻塞写走 `writeDirect()` |
| `UdtClient.connect()` / `call()` | 同上，建连与写帧都在 IO |
| `TvRepository.connect()` / `probeAndConnect()` / `installApk()` | 整个方法体包在 `withContext(Dispatchers.IO)` |
| `TvViewModel.localIp` | 初始化时在 IO 上算完，用 StateFlow 回填 |
| `IpProbe` | `openPorts()` 内部已是 IO；`isOpen()` 标注为仅限 IO 线程调用 |

这样无论调用方在哪个调度器上，都不会踩主线程网络限制。

## 六、连接端口

RC 端口**不是固定 30612**。逆向 `h2.h.M()` 显示：连接端口优先取设备数据里的端口
（即 mDNS `_rc._tcp` SRV 记录的值），**仅当该值为 0 才回落 30612**。
实测有电视把 `_rc._tcp` 广播到 6091。

因此实现为「候选列表按序尝试」：

| 通道 | 候选顺序 |
| --- | --- |
| RC | 广播端口（若有）→ 30612 → 6091 |
| UDT | 广播端口（若有）→ 6093（milink 的 TXT `tcp=` 优先于 SRV） |

全部失败才报错，错误消息里会列出**实际尝试过的端口**，方便定位。

## 七、已知限制

1. **加密固件**：电视版本 > `0x01000200` 的固件要求 AES 会话密钥，密钥由小米账号 AirKan 授权下发。本 App 未实现该链路，连接后首页会提示「按键可能无效」。
2. **安装确认**：电视端一定会弹系统确认框，这是电视的安全机制，无法绕过。手机侧只负责把 APK 推过去。
3. **触控注入**：部分固件不开放 type 6，遥控页已注明；此时方向键仍可用。
4. **电视端拦截**：部分电视上的安全软件会拦截局域网 HTTP 拉取，安装页给出了 U 盘安装的替代建议。
5. **未在真机验证**：本工程通过了编译与协议层单测，但尚未在真实电视上做端到端联调。

## 八、排查

**连接失败**时按顺序看：

1. 错误文案里的「已尝试端口 …」——若 30612 / 6091 都连不上，说明是网络层问题而非协议问题：
   - 电视是否开机、是否和手机在**同一 Wi-Fi**（不是访客网络）
   - 路由器是否开了 **AP 隔离 / 设备隔离**（开了必然连不上）
   - 电视是否允许「远程控制 / 无线显示」类开关
2. 首页设备卡片显示**双通道状态**：RC 就绪但 UDT 未连通 → 遥控可用、应用管理不可用。
3. `adb logcat -s TvRepository RcClient UdtClient ApkHttpServer` 可看到每个端口的尝试结果。

## 九、变更记录

### 1.0.1
- 修复：连接电视抛 `NetworkOnMainThreadException` —— RC/UDT 客户端与仓储层的 socket 操作原先在调用线程（`viewModelScope` = 主线程）执行，现已统一切到 `Dispatchers.IO`。
- 修复：RC/UDT 端口改为候选列表按序回落，不再只认 30612；错误消息列出实际尝试的端口。
- 改进：milink 的 UDT 端口优先读 mDNS TXT `tcp=`（原实现也这么做），SRV 端口降为兜底。
- 改进：本机 IP 计算移出主线程；连接页显示每台设备的 RC/UDT 端口。

### 1.0.0
- 首个可用版本：连接/发现、遥控、应用管理、Pin、APK 推送安装。

---

## 十、单测

`ProtocolTest` 覆盖 19 项，全部为字节级黄金校验，包括：

- RC 帧头大端长度、心跳/就绪帧、payload 超 1500 拒绝
- 版本包请求布局与含/不含 `secretKey` 的响应解析
- `SendKey` 帧**逐字节**比对（`0400410100000001003A`）、TLV 顺序（deviceId/source 在 characters 之前）、msgId 自增
- 触摸帧指针几何量与 source
- UDT 三段长度前缀自洽、ctrl 用 `action` 而非 `type`、tail 回填 requestId、分页解析
- `getInstalledPackages` 解析容错（缺 label 回退包名、垃圾输入返回空）
- RC/UDT 端口候选顺序与去重
