## Brokenithm-Android

A version of Brokenithm, inspired by the [Brokenithm-iOS](https://github.com/esterTion/Brokenithm-iOS) project.
Supports UDP and TCP connection to host, UDP for Wireless connection and TCP for `adb reverse` port forward (over USB for lower latency?).

The Windows server is in [another repository](https://github.com/tindy2013/Brokenithm-Android-Server).

---

## 相较于原项目的更新

本项目在 [tindy2013/Brokenithm-Android](https://github.com/tindy2013/Brokenithm-Android) 的基础上，针对**延迟、性能、稳定性与可构建性**做了深度优化。所有修改均**严格保留原有二进制协议**，服务端无需任何改动即可兼容。

### 性能优化

- **消除 HashSet 装箱（位图化）**
  - 将 32 键触摸状态由 `HashSet<Int>` 改为 `Long` 位图，彻底消除每帧的装箱/拆箱与集合遍历开销。
  - 所有 `contains` / `add` / `isEmpty` 操作替换为位运算 `and` / `or` / `== 0L`。

- **对象池化（消除每毫秒 GC）**
  - 复用 `IoBuffer`、`ByteArray(48)` 发送缓冲、`ByteArray(24)` 卡片缓冲、`ByteArray(12)` Ping 缓冲以及 `Paint` 绘制对象。
  - 发包线程不再产生任何堆分配，显著降低 GC 造成的卡顿与延迟抖动。

- **发包定时精度优化**
  - 原 `Thread.sleep(1)` 在 Linux/Android 调度下实际精度仅约 10~15 ms，远低于目标 1 kHz。
  - 引入混合等待策略：`Thread.sleep(大部分)` + `System.nanoTime()` 自旋补足，将发包周期稳定控制在约 1 ms。

- **触控采样延迟优化**
  - 在触摸监听器中主动调用 `requestUnbufferedDispatch(event)`（Android 8.0+），减少系统对 `MotionEvent` 的批处理缓冲，降低触控到响应的端到端延迟。

### Bug 修复

- **修复 TCP 多发 Bug**
  - 原代码使用 `OutputStream.write(buf)` 写入整个固定长度数组，导致实际有效载荷后携带未清零的垃圾数据。
  - 已改为按实际有效长度写入：`write(buf, 0, buffer.length + 1)`。

- **依赖与仓库修复**
  - JCenter 已关闭，原 `net.cachapa.expandablelayout` 无法解析。
  - 已迁移仓库源至 `mavenCentral` + `jitpack.io`，并将依赖坐标修正为 `com.github.cachapa:ExpandableLayout:2.9.2`。

### 体验增强

- **游戏模式标识**
  - `AndroidManifest.xml` 新增 `android:isGame="true"` 与 `android:appCategory="game"`，在支持的系统上可被识别为游戏并启用相应调度策略。
  - 开启 `android:hardwareAccelerated="true"`，强制使用硬件加速渲染 LED 回显。

### 构建说明

- 本项目使用 Gradle 6.7.1 + Android Gradle Plugin 4.2.2，**必须使用 Java 11（或 ≤15）**，不可使用 Java 17+。
- 首次构建前请确保本地已配置 `local.properties`，并写入正确的 `sdk.dir`。
- 若本地 Android SDK 存在 `build-tools/36.1.0` 或 `platforms/android-36.1` 等 `36.1` 版本组件，旧版 AGP 会因 XML 解析 `api-level=36.1` 而崩溃，建议临时将这些目录重命名或移除（项目仅依赖 API 30，不影响编译）。

### 协议兼容性

- 包头 `'INP'` / `'IPT'`、包长度字段、`slider[32]` + `air[6]` + 功能键布局、`CRD` 卡片包、`PIN` Ping 包均**保持不变**。
- [Brokenithm-Android-Server](https://github.com/tindy2013/Brokenithm-Android-Server) 无需更新即可直接联机使用。
