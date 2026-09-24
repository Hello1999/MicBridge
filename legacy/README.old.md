# MicBridge

MicBridge 是 Android 的**系统麦克风开关**。在手机首页直接屏蔽或恢复麦克风，也可以用 iPhone Action Button 经可信局域网遥控同一个开关。控制作用于 Android 系统音频与隐私门，不绑定 ChatGPT 或任何应用包名；MicBridge 本身不录音、不存储音频，也不申请 `RECORD_AUDIO` 权限。

支持的 Android 12+ 设备提供系统级麦克风隐私开关，关闭后应用收到静音音频。MicBridge 将这一系统控制与状态读回、Root 保护和可选远程控制结合起来。恢复系统麦克风只表示系统门已放行，应用仍需自己的录音权限并处于可录音状态。[Android 官方说明](https://developer.android.com/training/permissions/explaining-access)

## 现在的使用方式

底部页面为“麦克风 / 连接 / 设置”：

1. 在“麦克风”页启动屏蔽，按提示授予 Root 等必要权限。
2. 按“设备验证”引导，用任意能持续录音的录音机或语音应用确认屏蔽与恢复有效。
3. 验证完成后，首页直接使用“恢复麦克风”和“立即屏蔽”。状态由服务的新鲜系统读回提供。
4. 需要 iPhone 遥控时，再到“连接”页复制地址和访问密钥，按 [快捷指令教程](docs/IPHONE_SHORTCUT_ZH.md) 配置 Action Button。

应用不再显示目标包名，不要求安装或探测 ChatGPT，也不因某个应用的权限、版本或安装状态拒绝系统麦克风控制。页面调整见 [界面说明](docs/UI_REDESIGN_ZH.md)，本次审查与真机待验项目见 [系统麦克风审查](docs/SYSTEM_MIC_REVIEW_ZH.md)。

## 控制范围与安全状态

```text
Android 首页按钮 / iPhone Action Button
  → MicBridge 前台服务的同一串行状态机
  → 全局 AudioManager + Root sensor_privacy
  → 系统状态读回 + 用户身份核验 + Root 监督
```

- `BLOCKED`：系统屏蔽已读回确认。
- `OPEN`：AudioManager 与 sensor privacy 两层均读回为允许；不代表任何应用正在录音。
- `UNKNOWN`：状态无法确认。界面保留“立即屏蔽”，不会把未知画成已安全。
- `verified=true`：本次控制面读回明确，且当前系统/控制器/构建的人工设备验证仍有效。它不是实时声学测量。

发布控制器保留两个选项：默认 `audio_manager` 使用 AudioManager 主控与 Root sensor privacy 门；若目标设备验证失败，可在停止服务后手动选择 `root_sensor_privacy` 主控与 AudioManager 门。两种选项都需要 Root，都要分别验证，运行时不会自动更换控制器。正常控制不读取或写入应用 AppOps，不修改应用录音权限。

当前 Android 用户必须与服务绑定身份一致；无法查询或发生切换时拒绝 OPEN。设备验证绑定配置代际、控制器、MicBridge 构建、Android Build ID / fingerprint 和用户身份。新版验证结构会使旧应用定向校准失效，安装新版后必须重新验证；第三方应用安装、卸载或升级不再属于校准身份。

## 首次安装与验证

1. 构建并安装 APK：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。不同电脑的 Debug 签名可能不同；不要为覆盖签名冲突而直接清除已有数据。
2. 打开 MicBridge，允许状态通知和持续后台运行。本地麦克风操作不需要局域网权限；Android 17 上需要 iPhone 遥控时，再到“连接”页允许“本地网络”。
3. 启动服务并在 Root 管理器中授权。服务先尝试屏蔽并新鲜读回；无法确认时不监听 HTTP，也不允许恢复麦克风。
4. 按设备验证提示检查精确闹钟及 Root 保护条件。临时验证需要精确闹钟权限、双 Alarm、Root watcher 与独立读回；已通过设备验证后的日常持续恢复不要求精确闹钟，仍需 Root 监督与核心保护条件。
5. 选一个有录音权限、可持续观察收音结果的应用。分别验证解锁亮屏、锁屏亮屏、锁屏熄屏时的屏蔽与原会话恢复。应用内引导依次执行临时开放、仅 Root 门屏蔽、人工确认无收音并立即完整屏蔽、最终提交；整轮必须在同一服务会话中完成。
6. 完成验证后使用首页开关。更换控制器、Android 固件、MicBridge 构建或用户身份后重新验证。

验证时的“仅 Root 门屏蔽”要求新鲜读回 `sensor_privacy=BLOCKED`、`AudioManager=OPEN` 且 Root guard 健康，用于确认独立保护门确实有效。应用不再检查测试应用的包名或 AppOps；测试应用本身必须先成功收音，否则不能以“没有声音”作为屏蔽成功证据。失败、步骤越序、保护失效或退出未完成的验证都会清理本轮证据；最终屏蔽与租约清理无法确认时不能提交。

## 本地与远程控制

首页本地切换直接调用服务，共享 HTTP toggle 的控制状态机、校准检查和 Root 保护，不需要手机向自己的局域网地址发送请求，也不依赖某一代 HTTP listener 或本地网络权限。它仍遵循服务的权限、后台保护与 fail-closed 约束；服务监控的网络身份变化仍会触发安全屏蔽。

iPhone 每次发送一次 `POST /v1/mic/toggle`，不保存开关状态。成功开放会持续到下一次切换或安全边界触发，`auto_block_at=null`。只有校准和诊断 `/open` 使用 5–30 秒窗口；默认 30 秒时预留 Root BLOCK 重试预算，通常约 20 秒开始尝试屏蔽。不能把持续 toggle 理解为会在 30 秒后自动关闭。

远程默认端口 `8787`，只绑定受完整监控的可信私网 IPv4，不监听 `0.0.0.0`：

- API 31–35：Android 和 iPhone 连接同一个可信 Wi‑Fi。
- API 36+：也支持 `TetheringManager` 监控成功的 Android 私人热点。
- 网络身份变化先撤销旧监听、确认屏蔽，再重新枚举地址。仅 iPhone 离开热点而 Android 网络不变，无法被单次 HTTP 协议即时发现。

修改请求要求 `X-MicBridge-Token` 与唯一 `X-Request-Id`。HTTP 200 不是控制成功；快捷指令必须核对 `ok=true`、`verified=true` 和回显 request ID，随后才依据 `mic_access` 给出反馈。幂等账本先落盘再操作，相同 ID 永不再次 toggle；账本在应用数据生命周期内保留。完整端点、响应、重试与 1/2/3 次振动规则见 [API 说明](docs/API_ZH.md)。

## 后台与卸载

服务启动、用户切换、受监控网络变化、保护失效和正常停止都优先执行 BLOCK。只有新鲜读回确认屏蔽后才撤销有效租约；失败保留后备动作，不把“命令执行成功”当作屏蔽证明。

两个控制器都使用 `/data/adb/service.d/micbridge-failsafe.sh`、独立 Root watcher 和 generation supervisor。持续开放使用无截止时间 watcher；临时验证还要求双系统 Alarm 和有超时的 kernel wake lock。Root 健康检查绑定 PID/starttime、用户上下文与代际；无法确认时撤销 OPEN 并尝试全局屏蔽。具体实现及 Force Stop、Doze、OEM 限制见 [安全模型](docs/SECURITY_MODEL_ZH.md)。

“保持后台运行”会增加耗电。更改后台运行设置后需重启服务，并重新检查目标设备的锁屏/熄屏行为。正常停止先屏蔽；卸载前在高级维护使用“移除保护并停止服务”，避免留下 Root 脚本。

曾使用旧 `root_appops` 开发版的设置会迁移为 `root_sensor_privacy`。旧 AppOps 仅用于维护收尾：全局屏蔽确认后读取并保留现值，清除旧元数据，不恢复或放宽权限。独立的 `extras/chatgpt-phone-mic` 是历史录音路由扩展，不属于本次系统开关。

## 构建与验证记录

固定环境：AGP 9.4.0、Gradle 9.6.0、Kotlin 2.3.21、Compose BOM 2026.08.00；`minSdk=31`、`compileSdk=37`、`targetSdk=37`，JDK 17 或更高。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows 使用 `gradlew.bat`。APK 路径：`app/build/outputs/apk/debug/app-debug.apk`。根目录 `SHA256SUMS.txt` 只对应已发布的旧 `v0.2.0` 附件，不能认证本次构建；新发布必须使用独立版本、附件与匹配校验和。

本次系统麦克风改动已通过 254 项 JVM 测试、15 项 API 36.1 / Android 16 AVD 仪器测试，均无失败、错误或跳过；Lint 0 errors / 32 warnings，APK 构建成功。最终 APK SHA-256 为 `99a815a96946b83dfbcfcda4335165789c14c9a7418377b5359de20770812eda`，已核对最终 AVD 安装包字节一致及源码构建身份。运行页面检查与真机待验范围见 [系统麦克风审查](docs/SYSTEM_MIC_REVIEW_ZH.md)；物理设备与 iPhone 端到端仍为 `NOT_RUN`。以下仅为历史记录，均不得作为本次 APK 或真机验收结论：

| 时间 / 范围 | 历史证据 |
| --- | --- |
| 2026-09-04 控制实现 | 172 项 JVM 测试、Lint、构建；APK SHA-256 `403072a677facd8ef730a647b77bed255f0cde17944457b601d86911bd82ca65` |
| 上述历史 Motorola APK | 安装哈希核对、两次 Root boot guard 重启与 OEM 自然开机启动；声学、Doze、Action Button 与故障注入未完成 |
| 2026-09-12 UI 实现 | 246 项 JVM 测试、13 项 API 36.1 仪器测试、Lint 与无 Root AVD 界面检查；APK SHA-256 `699ac8ecf409b83454ede516521dbf7202303f70b1873a4be83375bc3cf2b926` |

历史详细记录保存在 [旧设备矩阵](docs/DEVICE_TEST_MATRIX.md) 和 [旧场景测试计划](docs/REAL_DEVICE_TEST_PLAN_ZH.md)。当前控制范围的真机验收需重新执行，不复用旧 AppOps 或 ChatGPT 定向结果。

## 边界与安全报告

系统门开放不保证应用权限、会话或音频路由可用；全局屏蔽会影响使用系统麦克风的其他应用。电话、紧急呼叫、蓝牙/USB/有线音频和不同用户/profile 应单独实测，软件门控不等于物理断开传感器。

远程采用 bearer token + 明文 HTTP，仅适用于可信局域网；不能防御同网监听或篡改。日志最多保留 100 条，界面显示最近 20 条；不记录令牌或音频，没有应用内日志导出功能。

源码按 [Apache License 2.0](LICENSE) 发布。安全问题按 [SECURITY.md](SECURITY.md) 私密报告，不附带令牌、设备标识或未脱敏证据。

## 参考

- [Android 系统麦克风隐私开关](https://developer.android.com/training/permissions/explaining-access)
- [Android AudioManager](https://developer.android.com/reference/android/media/AudioManager#setMicrophoneMute(boolean))
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
- [Android exact alarms](https://developer.android.com/develop/background-work/services/alarms)
- [Android local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
