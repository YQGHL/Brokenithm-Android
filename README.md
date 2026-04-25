## Brokenithm-Android

A version of Brokenithm, inspired by the [Brokenithm-iOS](https://github.com/esterTion/Brokenithm-iOS) project.
Supports UDP and TCP connection to host, UDP for Wireless connection and TCP for `adb reverse` port forward (over USB for lower latency?).

The Windows server is in [another repository](https://github.com/YQGHL/Brokenithm-Android-Server).

---

## 相较于原项目的更新

本项目在 [tindy2013/Brokenithm-Android](https://github.com/tindy2013/Brokenithm-Android) 的基础上，针对**延迟、性能、稳定性、可维护性与工程现代化**做了深度优化。所有修改均**严格保留原有二进制协议**，服务端无需任何改动即可兼容。

---

## 架构重构：1209 行 God Object → 6 个独立管理器

原始 `MainActivity.kt` 高达 **1209 行**，同时处理网络、触控、传感器、LED、NFC、振动、设置等全部职责。本项目将其拆分为 6 个单一职责的管理器类：

| 管理器 | 文件 | 职责 | 行数 |
|--------|------|------|------|
| `NetworkManager` | `network/NetworkManager.kt` | UDP/TCP 套接字生命周期、包构造/解析、ping 管理、连接状态机 | ~600 |
| `TouchController` | `input/TouchController.kt` | 多点触控事件→32 键位图编码、触控尺寸检测 | ~240 |
| `SensorController` | `sensor/SensorController.kt` | 陀螺仪/加速度计→空中高度计算 | ~140 |
| `LEDRenderer` | `ui/LEDRenderer.kt` | Canvas 绘制 LED 灯条、位图池化 | ~90 |
| `NFCManager` | `nfc/NFCManager.kt` | Aime/FeliCa 读卡生命周期 | ~170 |
| `VibrationController` | `haptic/VibrationController.kt` | 触觉反馈队列 | ~70 |

`MainActivity.kt` 从 **1209 行缩减至 395 行**，转变为纯编排器（Orchestrator），仅负责生命周期管理与 UI 事件转发。

---

## 并发与性能优化

### 1. 消除忙等自旋 → `LockSupport.parkNanos()`
- **原始问题**：发包线程使用 `Thread.sleep(大部分) + System.nanoTime() 自旋` 来维持 ~1ms 精度，导致单核 CPU 使用率接近 100%。
- **优化**：将混合等待策略替换为 `LockSupport.parkNanos(剩余纳秒)`，通过 `Unsafe.park()` 委托给 OS 高分辨率定时器，在保持亚毫秒精度的同时将 CPU 占用降至接近空闲。

### 2. 原始线程 → `lifecycleScope.launch` 结构化并发
- **原始问题**：`sendConnect`、`sendDisconnect`、`sendFunctionKey`、NFC 读卡均使用 `thread { }` 创建裸线程，无生命周期管理，存在泄漏风险。
- **优化**：全部替换为 `lifecycleScope.launch(Dispatchers.IO) { }`（NFC 使用传入的 `CoroutineScope`）。Activity 销毁时自动取消所有协程，避免后台泄漏。

### 3. 振动队列 → `Channel<Long>` 背压
- **原始问题**：使用 `ArrayDeque<Long>` + 10ms 轮询线程检测新振动事件，持续消耗 CPU。
- **优化**：使用 `Channel<Long>(Channel.BUFFERED)` + 挂起消费者协程。`trigger()` 通过 `offer()` 入队，消费者通过 `for (length in channel)` 阻塞等待，彻底消除轮询开销。

### 4. 结构化日志
- **原始问题**：全项目使用 `e.printStackTrace()`，日志无上下文、无法在生产环境收集。
- **优化**：全部替换为 `Log.e(TAG, "描述性上下文", e)`，共 16 处，覆盖网络发送/接收、NFC 读卡、连接管理等全部异常路径。

### 5. 对象池化与协议字节级兼容（继承自上游优化）
- `IoBuffer`、`ByteArray(48)` 发送缓冲、`ByteArray(24)` 卡片缓冲、`ByteArray(12)` Ping 缓冲、`Paint` 绘制对象全部复用。
- `constructBuffer()` 产出完全相同的字节数组，服务器端零变更即可兼容。

---

## 依赖与工程现代化

| 组件 | 升级前 | 升级后 |
|------|--------|--------|
| Kotlin | 1.5.21 | **2.0.21** |
| Android Gradle Plugin | 4.2.2 | **8.7.3** |
| Gradle | 6.7.1 | **8.10.2** |
| Kotlinx Coroutines | 1.4.2 | **1.9.0** |
| compileSdk / targetSdk | 30 | **35** |
| buildToolsVersion | 30.0.3 | *(AGP 8.x 自动管理，已移除)* |
| JDK 要求 | 11 | **21** *(AGP 8.7.x 需要)* |

- 新增 `namespace 'com.github.brokenithm'` 以适配 AGP 8.x。
- `AndroidManifest.xml` 中 `MainActivity` 添加 `android:exported="true"` 以适配 targetSdk 35 (Android 12+ 要求)。

---

## 协议兼容性

- 包头 `'INP'` / `'IPT'`、包长度字段、`slider[32]` + `air[6]` + 功能键布局、`CRD` 卡片包、`PIN` Ping 包均**保持不变**。
- [Brokenithm-Android-Server](https://github.com/YQGHL/Brokenithm-Android-Server) 无需更新即可直接联机使用。

---

## 构建说明

- 本项目使用 **Gradle 8.10.2 + Android Gradle Plugin 8.7.3 + Kotlin 2.0.21**。
- **必须使用 JDK 21**，不可使用 JDK 11（AGP 8.7.x 硬性要求）。
- 首次构建前请确保本地已配置 `local.properties`，并写入正确的 `sdk.dir`。

```bash
# Linux / macOS
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug

# Windows (PowerShell)
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat assembleDebug
```

---

# License

Same as the original project.
