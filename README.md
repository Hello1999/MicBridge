# MicBridge

用 Seeed XIAO ESP32C3 做一个实体“按键说话”按钮：**按住，手机麦克风恢复；松开，麦克风静音。**

控制对象是 Android **系统麦克风**，不针对任何应用。ChatGPT 语音（Live）、微信语音、录音机等所有应用都一样生效。

```text
ESP32C3 按钮 ──BLE 通知 [state, seq]──▶ MicBridge 前台服务 ──▶ 系统麦克风 静音 / 恢复
```

## 为什么用蓝牙 BLE，而不是 WiFi

| | BLE（采用） | WiFi |
| --- | --- | --- |
| 连接 | 手机直连按钮，不需要路由器或热点 | 按钮和手机必须在同一网络，换网就断 |
| 延迟 | 连接间隔 7.5–15 ms，抖动小 | 局域网通常很快，但受路由器和省电模式影响，抖动大 |
| 功耗 | 很低，适合电池供电 | 高，C3 在 WiFi 下持续耗电约 80 mA 以上 |
| 实现 | 一个 GATT 通知特征 | 需要配网、找 IP、处理重连 |

没有采用“BLE 键盘（HID）”方案。它会顶掉手机软键盘，而且应用在后台接收按键需要无障碍服务，比自定义 GATT 更麻烦。

## 两种静音方式

在 App 里选择。运行中不能切换，关闭“按键说话”后才能改。

1. **系统静音（默认，无需 Root）**：调用 `AudioManager.setMicrophoneMute`，全局生效、瞬时切换、没有弹窗。
   - 如果 ChatGPT 或系统自行取消了静音，服务会立刻静音回去（监听 `ACTION_MICROPHONE_MUTE_CHANGED`，并每秒核对一次）。
   - 少数机型的音频 HAL 只在通话时执行这个静音。请用 App 里的“收音测试”确认。
2. **隐私开关（Root）**：通过常驻的 `su` 执行 `cmd sensor_privacy enable|disable <user> microphone`，这是 Android 12+ 的系统级麦克风开关，所有应用都只会收到静音数据。
   - 缺点：应用在静音状态下*开始*录音时，系统会弹出“要取消屏蔽麦克风吗”的提示。例如按钮松开时打开 ChatGPT Live。

## 安全规则

| 情况 | 麦克风 |
| --- | --- |
| 服务运行、按钮未按下 | 静音 |
| 按住按钮（每 400 ms 一次心跳） | 恢复 |
| 1.5 秒收不到心跳 / 蓝牙断开 | 静音 |
| 关闭“按键说话” / 通知栏“停止” | **恢复**（手机回到正常状态） |
| App 崩溃后麦克风一直静音 | 打开 App，点“恢复麦克风（异常时使用）” |

## 使用步骤

### 1. 烧录按钮固件

- 硬件：XIAO ESP32C3，外加一个轻触按钮，接在 **D1 (GPIO3)** 和 **GND** 之间，不需要电阻。**记得装上配套的 U.FL 天线**，否则 BLE 距离很短。
- 还没接按钮时，可以把 `BUTTON_PIN` 改成 `9`，用板载 BOOT 键测试。
- 用 Arduino IDE 打开 [`firmware/esp32c3_ptt/esp32c3_ptt.ino`](firmware/esp32c3_ptt/esp32c3_ptt.ino)，开发板选 `XIAO_ESP32C3`（esp32 core 3.x），上传。
- 串口 115200 会打印 `pressed` / `released`。

### 2. 安装 App

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 使用 `gradlew.bat`。环境：JDK 17+，`minSdk 31`（Android 12）。

### 3. 配对和测试

1. 在“按钮”一栏点“搜索”，授予“附近设备”和通知权限，选择 `MicBridge-BTN`。
2. 打开“按键说话”。圆盘变红表示“已静音”。按住实体按钮或按住圆盘，圆盘变绿表示“说话中”。
3. 在“收音测试”里对着手机说话：按住时电平跳动，松开后应归零。**不归零说明这台手机不支持第一种方式，请换成“隐私开关（Root）”。**
4. 打开 ChatGPT Live 实际验证一次。

调试：

```bash
adb shell dumpsys audio | grep "mic mute"
```

```bash
adb logcat -s MicBridgePtt
```

第一条命令读取当前系统静音状态。第二条显示每次切换的耗时。

## 代码结构

```text
app/src/main/java/com/jack/micbridge/
  ble/ButtonProtocol.kt   GATT UUID 与数据包格式（与固件共用）
  ble/ButtonLink.kt       连接、订阅、自动重连
  ble/ButtonScanner.kt    按服务 UUID 搜索按钮
  mic/MicGate.kt          两种静音方式
  mic/RootShell.kt        常驻 su，减少每次切换的开销
  mic/MicLevelMeter.kt    收音测试电平（只算音量，不保存音频）
  service/PttService.kt   前台服务：按键 → 麦克风状态
  ui/PttScreen.kt         单页界面
firmware/esp32c3_ptt/     XIAO ESP32C3 固件
extras/chatgpt-phone-mic/ LSPosed 模块：连着蓝牙耳机时 ChatGPT 用手机麦克风
```

## 连着蓝牙耳机时 ChatGPT 录不到声音

蓝牙耳机只要支持通话协议（HFP），系统就把它当成带麦克风的耳机。ChatGPT Live 打开时会自己调用 `startBluetoothSco()`。SCO 链路一建立，系统就把所有通话录音（`VOICE_COMMUNICATION`）切到耳机的 SCO 麦克风，连 MicBridge 的收音测试也会被带过去。耳机麦克风录不到声音，按住按钮也就没用了。

实测无效的做法：

- 系统级“首选收音设备”（Root，`setPreferredDevicesForCapturePreset`）：通话模式下被忽略。
- 只给 ChatGPT 的 `AudioRecord` 指定手机麦克风（旧版 capturehook 0.1.0）：SCO 一建立就被系统覆盖，日志会出现 `route_changed ... routed=type:7`。
- 关掉耳机的“通话音频”：收音正常，但 ChatGPT 处于通话模式，声音改从手机听筒或扬声器播放。

有效的做法是用 LSPosed 模块 [`extras/chatgpt-phone-mic`](extras/chatgpt-phone-mic/)，只作用于 ChatGPT。它会拦截 SCO、通话模式、通话设备和免提这几类调用，让 ChatGPT 从手机麦克风收音，声音通过 A2DP 从耳机播放。耳机的“通话音频”保持开启，普通电话不受影响。

已知情况：ChatGPT 会先尝试两次 SCO，每次约 4 秒，都失败后才放弃。所以 Live 刚打开时，声音可能要晚几秒才出来。

## 从旧版升级
## 从旧版升级

旧版可能留下 Root 开机脚本 `/data/adb/service.d/micbridge-failsafe.sh` 和 watcher 进程，开机会重新打开麦克风隐私屏蔽。安装新版前：

1. 在旧版 App 的高级维护里点“移除保护并停止服务”。如果旧版已打不开，就在 Root 下删除该脚本并重启。
2. 卸载旧版，或执行 `adb shell pm clear com.jack.micbridge` 后再安装新版。
3. LSPosed 模块 `com.jack.micbridge.capturehook` 请升级到 0.3.0 或更高版本并保持启用，见上一节。0.1.0 在 SCO 建立后会失效。

源码按 [Apache License 2.0](LICENSE) 发布。
