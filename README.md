# LocalSend Receiver

极简 LocalSend 接收端，专为 **Android 4.2.2 (API 17)** 车机设计，同时兼容 Android TV 和现代手机。

## 特性

- 仅接收文件，不支持发送
- 可被其他 LocalSend 设备自动发现（UDP Multicast + HTTP Register）
- 支持中文文件名
- 传输前弹窗确认
- 文件保存到 `/sdcard/Download/LocalSend/`
- 内置文件管理器（点击打开、长按删除）
- Android TV 遥控器 D-pad 焦点导航
- 横屏双栏布局（左侧状态日志、右侧文件列表）
- Release APK 约 780 KB（单 DEX，无额外依赖）

## 截图

```
┌────────────────────────────┬───────────────────────────┐
│  LocalSend Receiver        │  Files                    │
│  Ready - 192.168.1.100:53317│  Download/LocalSend/      │
│                            │                           │
│  Activity Log              │  📦 app-release.apk       │
│  [21:30] Device discovered │      782 KB · 07-09 21:30 │
│  [21:30] File received     │                           │
│  [21:31] File received     │  🖼️ photo.jpg             │
│                            │      2.3 MB · 07-09 21:31 │
│                            │                           │
└────────────────────────────┴───────────────────────────┘
```

## 安装

### 从 Release 下载

前往 [Releases](https://github.com/nisec-eric/localsend-receiver/releases) 下载最新 APK。

### 从源码构建

```bash
git clone https://github.com/nisec-eric/localsend-receiver.git
cd localsend-receiver
./gradlew assembleRelease
# 输出: app/build/outputs/apk/release/app-release.apk
```

需要 JDK 17、Android SDK 34。

### 安装到设备

```bash
adb install app-release.apk
```

## 使用

1. 将 APK 安装到车机/TV/手机
2. 确保设备和发送方在同一 WiFi 局域网
3. 打开 app → 自动启动服务，显示 IP 地址
4. 在另一台设备的 LocalSend 中选择文件发送
5. 自动发现设备名 "Car-{型号}"
6. 接收端弹窗确认 → 文件保存到 `Download/LocalSend/`
7. 右侧文件列表实时刷新，点击打开，长按删除

## 支持的设备

| 平台 | 状态 |
|------|------|
| Android 4.2.2 车机 (API 17) | 完全支持 |
| Android TV (API 28+) | 完全支持（D-pad 导航） |
| Android 手机 (API 17+) | 完全支持 |

## 技术栈

- Kotlin 1.9.22
- NanoHTTPD 2.3.1（HTTP 服务器）
- Gson 2.8.9（JSON 解析）
- minSdk 17, targetSdk 28, compileSdk 34
- 单 DEX 文件，无 coreLibraryDesugaring

## 协议

LocalSend v2，仅 HTTP 明文模式。5 个 REST 端点：

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/localsend/v2/info` | GET | 设备信息 |
| `/api/localsend/v2/register` | POST | 设备注册 |
| `/api/localsend/v2/prepare-upload` | POST | 准备上传 |
| `/api/localsend/v2/upload` | POST | 文件上传 |
| `/api/localsend/v2/cancel` | POST | 取消传输 |

## 开发者文档

- [ARCHITECTURE.md](ARCHITECTURE.md) — 详细架构、协议流程、踩坑记录
- [AGENTS.md](AGENTS.md) — AI Agent 构建指南

## CI/CD

GitHub Actions 自动构建：push 到 main 触发构建，打 `v*` tag 自动发布 Release。

```bash
# 发布新版本
git tag v3.0.0
git push origin v3.0.0
```

## License

MIT
