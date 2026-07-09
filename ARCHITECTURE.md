# LocalSend Receiver for Android 4.2.2

适用于车载 Android 4.2.2 (API 17) 横屏设备的 LocalSend v2 协议接收端。
同时兼容 Android TV（D-pad 导航）和现代手机。

- 仅 HTTP 明文模式，仅接收文件
- 可被其他 LocalSend 设备发现（UDP Multicast + HTTP Register）
- 传输前弹窗确认，文件保存到 `/sdcard/Download/LocalSend/`
- 支持中文文件名
- 右侧面板内置文件管理器（点击打开、长按删除）
- Release APK 约 782 KB（单 DEX）

## 项目结构

```
localsend-receiver/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/localsend/receiver/
│   │   ├── App.kt                              # Application，初始化 DeviceConfig
│   │   ├── ui/MainActivity.kt                  # 横屏主界面，日志/接收历史/确认弹窗
│   │   ├── service/
│   │   │   └── LocalSendService.kt             # Foreground Service，编排 HTTP + Multicast
│   │   ├── network/
│   │   │   ├── LocalSendHttpServer.kt          # NanoHTTPD，实现 v2 协议 5 个端点
│   │   │   ├── MulticastDiscoveryService.kt    # UDP Multicast 发现 (224.0.0.167:53317)
│   │   │   ├── HttpDiscoveryResponder.kt       # HTTP 注册回调（发现后向发送方注册自己）
│   │   │   ├── FileReceiver.kt                # 流式保存 + 进度回调 + 取消支持
│   │   │   ├── SessionManager.kt              # 传输会话 (sessionId → files → tokens)
│   │   │   ├── ServerEventListener.kt         # 服务端事件回调接口
│   │   │   ├── DiscoveryListener.kt           # 发现事件回调接口
│   │   │   └── dto/ProtocolDto.kt             # LocalSend v2 协议 DTO
│   │   └── util/
│   │       ├── DeviceConfig.kt                # 设备别名/fingerprint/端口 (SharedPreferences)
│   │       └── NetworkUtils.kt                # WiFi IP / MulticastLock / WifiLock
│   └── res/
│       ├── layout/activity_main.xml           # 横屏双栏布局
│       ├── xml/file_paths.xml                 # FileProvider 路径配置
│       └── values/ (strings / colors / styles)
├── build.gradle.kts
└── settings.gradle.kts
```

## 架构

```
┌─────────────┐                    ┌──────────────────────────────────────┐
│   手机端     │   WiFi / LAN      │    车机 (Android 4.2.2 横屏)         │
│  LocalSend  │◄──────────────────►│                                      │
│   发送端     │                    │  ┌─────────────────────────────────┐ │
└─────────────┘                    │  │      MainActivity (横屏UI)       │ │
                                   │  │  ┌──────────┐ ┌──────────────┐  │ │
                                   │  │  │ 状态/日志 │ │ 接收文件历史  │  │ │
                                   │  │  └──────────┘ └──────────────┘  │ │
                                   │  └────────────┬────────────────────┘ │
                                   │               │ Static + WeakRef     │
                                   │  ┌────────────▼────────────────────┐ │
                                   │  │     LocalSendService (前台)      │ │
                                   │  │                                  │ │
                                   │  │  ┌──────────────────────────┐   │ │
                                   │  │  │  MulticastDiscoverySvc   │   │ │
                                   │  │  │  UDP 224.0.0.167:53317   │   │ │
                                   │  │  │  ┌─────────────────┐     │   │ │
                                   │  │  │  │HttpDiscoveryResp│     │   │ │
                                   │  │  │  │POST /register   │     │   │ │
                                   │  │  │  └─────────────────┘     │   │ │
                                   │  │  └──────────────────────────┘   │ │
                                   │  │                                  │ │
                                   │  │  ┌──────────────────────────┐   │ │
                                   │  │  │  LocalSendHttpServer     │   │ │
                                   │  │  │  NanoHTTPD :53317        │   │ │
                                   │  │  │                          │   │ │
                                   │  │  │  GET  /v2/info           │   │ │
                                   │  │  │  POST /v2/register       │   │ │
                                   │  │  │  POST /v2/prepare-upload │   │ │
                                   │  │  │  POST /v2/upload         │   │ │
                                   │  │  │  POST /v2/cancel         │   │ │
                                   │  │  └────────────┬─────────────┘   │ │
                                   │  │               │                  │ │
                                   │  │  ┌────────────▼─────────────┐   │ │
                                   │  │  │    SessionManager        │   │ │
                                   │  │  │  session → files → tokens │   │ │
                                   │  │  └────────────┬─────────────┘   │ │
                                   │  │               │                  │ │
                                   │  │  ┌────────────▼─────────────┐   │ │
                                   │  │  │     FileReceiver         │   │ │
                                   │  │  │  → /sdcard/Download/     │   │ │
                                   │  │  │    LocalSend/             │   │ │
                                   │  │  └──────────────────────────┘   │ │
                                   │  └──────────────────────────────────┘ │
                                   └──────────────────────────────────────┘
```

## LocalSend v2 协议交互流程

### 1. 设备发现

**方式A — UDP Multicast（主要）**

```
手机                              车机
  │  UDP → 224.0.0.167:53317       │
  │  {announce:true, alias:".."}    │
  │────────────────────────────────>│
  │                                 │
  │  HTTP POST /v2/register        │  ← HttpDiscoveryResponder
  │  {alias:"Car", version:"2.1"}  │
  │<────────────────────────────────│
  │                                 │
  │  UDP response (announce:false)  │  ← 备用通道
  │<────────────────────────────────│
```

**方式B — HTTP 子网扫描（Fallback）**

手机遍历子网每个 IP，`POST /v2/register` → 车机回复设备信息。

### 2. 文件传输

```
手机 (Sender)                     车机 (Receiver)
  │                                 │
  │  POST /v2/prepare-upload        │
  │  {info:{...}, files:{...}}      │
  │────────────────────────────────>│
  │                                 │ ← 弹窗确认 (CountDownLatch 阻塞 HTTP 线程, 60s 超时)
  │  200 OK                         │
  │  {sessionId, files:{id:token}}  │
  │<────────────────────────────────│
  │                                 │
  │  POST /v2/upload                │
  │  ?sessionId=X&fileId=Y&token=Z  │
  │  Content-Length: <file_size>     │
  │  (binary body)                  │
  │────────────────────────────────>│
  │                                 │ ← 精确读取 Content-Length 字节，流式保存
  │  200 OK {}                      │
  │<────────────────────────────────│
```

### 3. 取消传输

```
手机                              车机
  │  POST /v2/cancel?sessionId=X   │
  │────────────────────────────────>│
  │                                 │ ← 清理会话，删除临时文件
```

### 4. 设备信息查询

```
手机                              车机
  │  GET /v2/info                   │
  │────────────────────────────────>│
  │                                 │
  │  200 OK                         │
  │  {alias, version, fingerprint,  │
  │   deviceType:"HEADLESS", ...}   │
  │<────────────────────────────────│
```

## Activity-Service 连接

```
MainActivity.onStart()
  ├─ registerReceiver(statusReceiver)
  ├─ startForegroundService(ACTION_START)   ← API 26+: startForegroundService
  └─ LocalSendService.registerActivity(this)
       └─ Service.attachActivity(activity)  ← WeakReference 防泄漏

LocalSendService.onCreate()
  ├─ instance = this                        ← 静态引用
  └─ pendingActivity?.get()?.let { attachActivity(it) }  ← 处理竞态

MainActivity.onStop()
  ├─ LocalSendService.unregisterActivity()
  └─ unregisterReceiver(statusReceiver)

MainActivity.onBackPressed()
  ├─ startService(ACTION_STOP)
  ├─ stopSelf()                             ← 防止僵尸服务
  └─ finish()
```

`registerActivity` / `unregisterActivity` 使用静态变量 `pendingActivity` 桥接，
解决 `startForegroundService()` 异步创建 Service 时 Activity 引用丢失的竞态问题。

## 关键技术决策

| 决策 | 原因 |
|---|---|
| 原生 Kotlin + Activity (非 Flutter) | Flutter 最低 API 21，无法运行在 Android 4.2.2 |
| NanoHTTPD 2.3.1 | 轻量嵌入式 HTTP 服务器，支持 API 9+，仅 100KB |
| HTTP 明文模式 (非 HTTPS) | Android 4.2.2 原生不支持 TLS 1.2；fingerprint 用随机字符串 |
| 仅 v2 协议 | v3 有 nonce 交换仍不稳定；v2 端点向后兼容 |
| 直接读 InputStream (非 parseBody) | NanoHTTPD parseBody 对中文 JSON 编码错误，直接读字节用 UTF-8 解码 |
| WeakReference 持有 Activity | 防 Service 泄漏 Activity |
| Static pendingActivity 桥接 | 解决 Activity 与 Service 启动时序竞态 |
| START_NOT_STICKY | 车机内存小，Service 被杀后不自动重启 |
| Theme.DeviceDefault | AppCompat 主题需要 AppCompatActivity，普通 Activity 不兼容 |
| 精确读 Content-Length 字节 | HTTP keep-alive 下 read()==-1 会阻塞超时 |
| 不关闭 session.inputStream | .use{} 会关闭底层 socket 导致响应发不出去 |
| **关闭 coreLibraryDesugaring** | desugaring 把 java.util.* 替换为 j$.util.* 放入 classes2.dex，Dalvik API 17 可能加载失败 |
| **Gson 2.8.9 (非 2.10+)** | Gson 2.10+ 内部引用 java.util.Objects (API 19+) |
| **catch(Throwable) 非 Exception** | NoClassDefFoundError/VerifyError 是 Error 子类，穿透 catch(Exception) |
| **Charset.forName("UTF-8")** | Charsets.UTF_8 指向 StandardCharsets (API 19+) |

## 构建

```bash
# Debug
./gradlew assembleDebug

# Release (已含签名配置)
./gradlew assembleRelease

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk     (~2.5 MB)
# app/build/outputs/apk/release/app-release.apk  (~782 KB)
```

### 签名密钥

- 文件: `localsend-release.jks`
- 密码: `localsend123`
- 别名: `localsend`

## 使用

1. 将 `app-release.apk` 安装到车机: `adb install app-release.apk`
2. 确保车机和手机在同一 WiFi
3. 打开 app → 自动启动 HTTP 服务 + Multicast 监听
4. 手机 LocalSend 发送文件 → 自动发现车机 (显示 "Car-{型号}")
5. 车机弹窗确认 → 文件保存到 `/sdcard/Download/LocalSend/`
6. 按返回键退出 → 停止服务并 stopSelf，释放端口

## 环境要求

| 项 | 版本 |
|---|---|
| Android minSdk | 17 (Android 4.2) |
| Android targetSdk | 28 |
| compileSdk | 34 |
| JDK | 17 |
| Gradle | 8.5 |
| AGP | 8.2.2 |
| Kotlin | 1.9.22 |

## 依赖

| 库 | 版本 | 用途 |
|---|---|---|
| NanoHTTPD | 2.3.1 | 嵌入式 HTTP 服务器 |
| Gson | 2.8.9 | JSON 序列化（不用 2.10+，避免 java.util.Objects） |
| AndroidX Core | 1.5.0 | FileProvider (API 24+ 文件打开) |
| AndroidX AppCompat | 1.4.2 | 编译时资源兼容 |
| AndroidX Multidex | 2.0.1 | API < 21 MultiDex 支持 |
| Kotlin Stdlib | 1.9.22 | Kotlin 标准库 |

## 已知限制

- 仅 HTTP 明文，无加密（局域网内使用，安全风险可控）
- 仅接收端（不能主动发送文件）
- 不支持 WebRTC 中继（仅局域网直连）
- NanoHTTPD 每请求一个线程（车机场景并发极低，可接受）
- `WifiInfo.getConnectionInfo()` 在高版本 API 已废弃，但 API 17 可用

## 踩坑记录

### NanoHTTPD parseBody 中文乱码

NanoHTTPD 的 `parseBody()` 对 `application/json` body 使用 `new String(bytes)` 转换，
平台默认编码可能是 ISO-8859-1，导致中文变乱码。

修复：绕过 `parseBody()`，直接从 `session.inputStream` 读取 `Content-Length` 字节的原始数据，
用 `String(bytes, Charsets.UTF_8)` 解码。

### session.inputStream.use{} 导致 IncompleteMessage

`use{}` 会在读取完毕后关闭 stream，但 NanoHTTPD 的 session stream 就是 socket stream。
关闭它会导致后续 HTTP 响应无法发送，发送方收到 `IncompleteMessage` 错误。

修复：去掉 `.use{}`，不关闭 stream，让 NanoHTTPD 自行管理连接生命周期。

### HTTP keep-alive 下 read() 阻塞超时

`FileReceiver.saveFile()` 原来用 `while (read() != -1)` 读取文件，但 HTTP keep-alive 下
socket 不会在 body 结束后关闭，`read()` 会一直阻塞直到超时。

修复：精确读取 `Content-Length` 字节，读完后立即返回响应。

### Activity-Service 竞态导致 transfer rejected

`startForegroundService()` 是异步的。Activity 在 `onStart()` 中调用 `attachActivity()`
时 Service 可能还没创建，`activityRef` 为 null，所有传输被 auto-reject。

修复：使用 `companion object` 的静态 `pendingActivity` 变量，Activity 先存引用，
Service `onCreate()` 时再取出。

### Theme.AppCompat + Activity 崩溃

`Theme.AppCompat.NoActionBar` 需要 `AppCompatActivity` 才能正确初始化，
用在普通 `Activity` 上会在某些 OEM 设备崩溃。

修复：改用 `android:Theme.DeviceDefault.NoActionBar`。

### API 31+ PendingIntent FLAG_IMMUTABLE

Android 12 (API 31) 要求 PendingIntent 必须指定 `FLAG_IMMUTABLE` 或 `FLAG_MUTABLE`。

修复：`if (Build.VERSION.SDK_INT >= 31) FLAG_IMMUTABLE else 0`。

### 缺失权限导致各版本崩溃

| 权限 | 缺失时崩溃 |
|---|---|
| FOREGROUND_SERVICE | API 28+ startForeground() 抛 SecurityException |
| WAKE_LOCK | WifiLock.acquire() 抛 SecurityException |
| usesCleartextTraffic | API 28+ HTTP 连接被系统阻止 |

### stopAll() 缺少 stopSelf()

按返回键调用 `stopAll()` 停止了服务器和前台通知，但没有调用 `stopSelf()`，
服务变成无通知的僵尸后台服务，最终被系统杀死，下次开机显示"该软件停止运行"。

修复：`stopAll()` 后调用 `stopSelf()`，`onDestroy()` 中也调用 `stopAll()`。

### Uri.fromFile 在 API 24+ 崩溃

`Uri.fromFile()` 在 API 24+ 抛 `FileUriExposedException`。

修复：API 24+ 使用 `FileProvider.getUriForFile()`。

### coreLibraryDesugaring 导致 API 17 闪退（v3.0.0 根因）

启用 `coreLibraryDesugaring` 后，D8 把**所有** `java.util.*` 引用替换为 `j$.util.*`，
这些 desugar 类放在 `classes2.dex` 中。API 17 的 Dalvik VM 可能延迟或失败加载
secondary DEX 文件。

崩溃链条：
1. App 启动正常（只用 Android framework 类，不需要 java.util 集合）
2. 被其他设备发现 → Gson `toJson()` / `fromJson()` 第一次创建 `HashMap`
3. 需要加载 `j$.util.HashMap`（在 classes2.dex 中）
4. classes2.dex 未加载 → `NoClassDefFoundError`（extends Error，不是 Exception）
5. 穿透所有 `catch(Exception)` → 线程未捕获异常 → 闪退

这解释了为什么：
- API 28+ 没问题（ART 原生加载所有 DEX）
- 启动不崩溃（启动阶段不使用 java.util 集合）
- 被发现时崩溃（Gson 第一次需要 HashMap）

修复：**完全关闭 coreLibraryDesugaring**。源码已手动避免 API 19+：
- `Charset.forName("UTF-8")` 替代 `StandardCharsets.UTF_8`
- Gson 降级到 2.8.9（不引用 `java.util.Objects`）
- 全部 `catch(Exception)` 改为 `catch(Throwable)`
- 结果：单 DEX 文件 782KB，无 classes2.dex
