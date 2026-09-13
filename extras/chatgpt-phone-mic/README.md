# ChatGPT 手机麦克风（实机验证模块）

独立 LSPosed 模块，仅在 `com.openai.chatgpt` 进程中为 `AudioRecord` 指定手机内置输入。
优先选择地址为 `bottom` 的内置麦克风。保留应用本来的播放、SCO、停止录音和麦克风权限行为。
不申请录音权限、不读取或保存音频、不包含网络或证书相关代码。

此项目单独构建，不更改或重新安装 MicBridge 主应用。

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew -p extras/chatgpt-phone-mic assembleDebug lintDebug
```

安装 `build/outputs/apk/debug/ChatGPTPhoneMic-debug.apk` 后，在 LSPosed 启用模块，
作用域仅勾选 ChatGPT，然后完全结束并重新打开 ChatGPT。保留耳机 Phone calls。
卸载模块或在 LSPosed 禁用后重启 ChatGPT，即恢复原行为。

验证不能仅看 `accepted=true` 或 preferred：

1. `adb logcat -s MicBridgeCapture` 确认模块加载、startRecording 和实际 routed 类型。
2. `dumpsys media.audio_policy` 的活跃 ChatGPT AudioRecord 必须使用内置输入，
   播放仍使用蓝牙；`dumpsys audio` 确认录音未静音。
3. 用户确认语音从耳机播放，手机附近说话可被识别。
4. 关闭 MicBridge 麦克风后必须仍阻止收音；开关、耳机断开重连、语音重开均需实测。

当前仍属实验功能。若 ChatGPT 使用原生录音绕过 Java AudioRecord，或厂商音频层忽略
录音实例的设备选择，本模块可能不起作用；日志和实机路由优先于 API 返回值。

## 2026-09-10 验证记录

- 0.1.0 debug 构建与 Lint 完成（无错误，有提示性警告），安装包哈希与本地产物一致。
- 用户在启用模块并测试后反馈“有效的”，确认本次方案可用。
- 随后的 ADB 快照已无活跃录音（`Inputs (0)`），没有取得当次会话的实际双向路由证据；
  不把该快照视为路由验收。长时间通话、蓝牙重连及 MicBridge 关闭收音的回归仍待实测。
