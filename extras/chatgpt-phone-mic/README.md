# ChatGPT 手机麦克风（LSPosed 模块）

连着蓝牙耳机时，让 ChatGPT 从手机内置麦克风收音，声音仍然从耳机（A2DP）播放。
只作用于 `com.openai.chatgpt` 进程。不申请录音权限，不读取或保存音频，也不包含网络代码。

## 原理

ChatGPT Live 会调用 `startBluetoothSco()`。SCO 一建立，系统就把通话录音强制切到耳机麦克风，
给 `AudioRecord` 指定的首选设备也会被覆盖。所以模块在 ChatGPT 内部做了这些处理：

| 调用 | 处理 |
| --- | --- |
| `AudioRecord.startRecording` / `setPreferredDevice` | 首选设备改为内置麦克风（优先 `bottom`） |
| `startBluetoothSco()`、`setBluetoothScoOn(true)` | 拦截 |
| `setMode(MODE_IN_COMMUNICATION)` | 拦截。通话模式下系统不走 A2DP |
| `setCommunicationDevice(...)` | 全部拦截。SCO 返回 false，其他设备返回 true，避免 ChatGPT 反复重试 |
| `setSpeakerphoneOn(true)` | 拦截 |

静音仍由 MicBridge 的系统麦克风静音负责，模块不参与。

## 构建与安装

```bash
./gradlew -p extras/chatgpt-phone-mic assembleDebug
```

```bash
adb install -r extras/chatgpt-phone-mic/build/outputs/apk/debug/ChatGPTPhoneMic-debug.apk
```

在 LSPosed 里启用模块，作用域只勾 ChatGPT。然后强制停止 ChatGPT 再打开。
耳机的“通话音频”保持开启。如果签名和已安装的版本不同，要先卸载旧版，再在 LSPosed 里重新启用。

## 验证

```bash
adb logcat -s MicBridgeCapture
```

应该看到 `startBluetoothSco() blocked`，`routed=` 始终是 `type:15`，不会出现 `type:7`。

```bash
adb shell dumpsys media.audio_policy
```

`Inputs` 中 ChatGPT（uid 10303）的录音设备应为 `AUDIO_DEVICE_IN_BUILTIN_MIC`，`Phone state` 应为 `AUDIO_MODE_NORMAL`。

## 2026-09-26 实机记录（XT2153-1，Android 13，X6 蓝牙耳机）

- 0.1.0：录音开始时在内置麦克风上，0.5 秒后 SCO 建立，被切到 `type:7`，ChatGPT 录不到声音。
- 0.2.0：拦截 SCO 和通话模式后收音正常，但 ChatGPT 连续两次 SCO 失败后调用 `setCommunicationDevice(扬声器)`，声音改从手机外放。
- 0.3.0：再拦截通话设备和免提。用户确认声音从耳机播放，按住时 ChatGPT 能听到，松开听不到。
  dumpsys 显示录音在 `BUILTIN_MIC`，当前通话设备为 `bt_a2dp X6`。
- ChatGPT 放弃 SCO 前大约要等 8 秒，Live 刚打开时声音可能要晚几秒才出来。
