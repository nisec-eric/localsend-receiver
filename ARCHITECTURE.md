# LocalSend Receiver for Android 4.2.2

适用于车载 Android 4.2.2 (API 17) 横屏设备的 LocalSend v2 协议接收端。
仅支持 HTTP 明文模式，仅接收文件，可被其他 LocalSend 设备发现。

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
│   │   │   ├── LocalSendHttpServer.kt          # NanoHTTPD HTTP 服务端，实现 v2 协议 5 个端点
│   │   │   ├── MulticastDiscoveryService.kt    # UDP Multicast 发现监听 (224.0.0.167:53317)
│   │   │   ├── HttpDiscoveryResponder.kt       # HTTP 注册响应（发现后的回调注册）
│   │   │   ├── FileReceiver.kt                # 文件流式保存 + 进度回调 + 取消支持
│   │   │   ├── SessionManager.kt              # 传输会话管理 (sessionId → files → tokens)
│   │   │   ├── ServerEventListener.kt         # 服务端事件回调接口
│   │   │   ├── DiscoveryListener.kt           # 发现事件回调接口
│   │   │   └── dto/ProtocolDto.kt             # LocalSend v2 协议 DTO (Gson 序列化)
│   │   ├── receiver/
│   │   │   └── WifiStateReceiver.kt           # WiFi 连接变化时自动重启服务
│   │   └── util/
│   │       ├── DeviceConfig.kt                # 设备别名/fingerprint/端口配置 (SharedPreferences 持久化)
│   │       └── NetworkUtils.kt                # WiFi IP 获取 / MulticastLock / WifiLock
│   └── res/
│       ├── layout/activity_main.xml           # 横屏双栏布局 (左:状态+日志 右:接收历史)
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
                                   │               │ WeakReference       │
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
```
手机遍历子网每个IP，POST /v2/register → 车机回复设备信息
```

### 2. 文件传输

```
手机 (Sender)                     车机 (Receiver)
  │                                 │
  │  POST /v2/prepare-upload        │
  │  {info:{...}, files:{...}}      │
  │────────────────────────────────>│
  │                                 │ ← 弹窗确认 (CountDownLatch 阻塞)
  │  200 OK                         │
  │  {sessionId, files:{id:token}}  │
  │<────────────────────────────────│
  │                                 │
  │  POST /v2/upload                │
  │  ?sessionId=X&fileId=Y&token=Z  │
  │  (binary body)                  │
  │────────────────────────────────>│
  │                                 │ ← 流式保存到 Download/LocalSend/
  │  200 OK                         │
  │<────────────────────────────────│
```

### 3. 取消传输

```
手机                              车机
  │  POST /v2/cancel?sessionId=X   │
  │────────────────────────────────>│
  │                                 │ ← 清理会话，删除临时文件
```

## 关键技术决策

| 决策 | 原因 |
|---|---|
| **原生 Kotlin + Activity** (非 Flutter) | Flutter 最低 API 21，无法运行在 Android 4.2.2 |
| **NanoHTTPD** (非 OkHttp Server) | 轻量嵌入式 HTTP 服务器，支持 API 9+，仅 100KB |
| **HTTP 明文模式** (非 HTTPS) | Android 4.2.2 原生不支持 TLS 1.2；HTTP 模式下 fingerprint 用随机字符串 |
| **仅实现 v2 协议** | v3 有 nonce 交换仍不稳定；v2 是当前版本向后兼容的端点 |
| **WeakReference 持有 Activity** | 防 Service 泄漏 Activity 导致内存无法回收 |
| **START_NOT_STICKY** | 车机内存小，Service 被杀后不自动重启 |
| **JsonObject 手动解析 Multicast** | ProGuard/R8 会剥离 Gson TypeToken 泛型签名 |
| **日志/历史列表上限** | log 50 条 / history 100 条，防止 LinearLayout 子 View 无限增长 OOM |

## 构建

```bash
# Debug
./gradlew assembleDebug

# Release (已含签名配置)
./gradlew assembleRelease

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk     (~2.5 MB)
# app/build/outputs/apk/release/app-release.apk  (~772 KB)
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
6. 按返回键退出 → 停止服务，释放端口

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
| Gson | 2.10.1 | JSON 序列化 |
| AndroidX AppCompat | 1.4.2 | 兼容旧版主题 |
| AndroidX Core | 1.5.0 | 兼容旧版 API |

## 已知限制

- 仅 HTTP 明文，无加密（局域网内使用，安全风险可控）
- 仅接收端（不能主动发送文件）
- 不支持 WebRTC 中继（仅局域网直连）
- NanoHTTPD 每请求一个线程（车机场景并发极低，可接受）
- `WifiInfo.getConnectionInfo()` 在高版本 API 已废弃，但 API 17 可用
