# MicBridge 设备测试矩阵

> 当前结论：最终源码构建、JVM 测试、签名/权限检查、Motorola 真机部署、两次 Root boot guard 重启检查、Motorola OEM 自然开机启动，以及 iPhone 快捷指令静态结构/iCloud 同步在下述限定范围内为 `PASS`；双机 P0 与端到端验收仍为 `NOT_RUN — ACOUSTIC_AND_ACTION_BUTTON_TESTS_PENDING`。
>
> API 37 / Android 17 Pixel_10_Pro AVD 的 13/13 仪器测试、安装、冷启动和无 Root 时 fail-closed 结果来自较早源码快照，未对当前最终源码重新运行，不能作为最终 APK 的模拟器验证。ChatGPT Live 声学、物理锁屏/熄屏与 Doze、Action Button 实际运行、1/2/3 次触感和故障注入继续保持 `NOT_RUN`。

## 构建主机

| 字段 | 实际值 |
| --- | --- |
| 日期/时区 | 2026-09-04 / Asia/Tokyo |
| OS | macOS 27.0（Build 26A5425a，arm64） |
| Android SDK | 由本机 `local.properties` 指定；绝对路径不纳入公开仓库 |
| Platform tools | 37.0.1 |
| JDK | Oracle JDK 25.0.2 |
| ADB 结果 | Motorola XT2153-1 通过无线调试连接，状态为 `device` |
| 最终产物时间（UTC） | `2026-09-04T03:18:22Z` |

## 自动构建与模拟器验证

| 项目 | 状态 | 实际证据与范围 |
| --- | --- | --- |
| 最终构建命令 | PASS | `./gradlew testDebugUnitTest lintDebug assembleDebug` 成功 |
| JVM 单元测试 | PASS | 172/172；0 failure、0 error、0 skipped |
| Android Lint | PASS | 0 errors、28 warnings、1 hint：21 `UseKtx`、3 `NewerVersionAvailable`、2 `ApplySharedPref`、1 `AndroidGradlePluginVersion`、1 `GradleDependency`、1 `AutoboxingStateCreation` hint |
| 最终 Debug APK | PASS | `app/build/outputs/apk/debug/app-debug.apk`；30,704,855 bytes；SHA-256 `403072a677facd8ef730a647b77bed255f0cde17944457b601d86911bd82ca65` |
| APK 签名 | PASS | `apksigner verify --verbose`：v2=true、Android Debug signer、1 signer |
| APK 录音/AppOps 写能力检查 | PASS | `aapt2` permissions 确认不含 `RECORD_AUDIO`；生产 APK 对 `cmd appops set` 的二进制扫描命中 0 |
| API 36.1 当前源码仪器契约测试 | PASS | MicBridge_API_36_1 AVD / Android 16 / Google Play ARM64：安装主 APK 与 test APK 均返回 `Success`；13/13，0 failure、0 error、0 skipped |
| API 36.1 当前源码无 Root fail-closed | PASS | 清除应用数据后冷启动；`connectedDevice` 前台服务运行；AVD 无 `su`，`ss -ltn` 确认端口 `8787` 未监听 |
| API 37 仪器契约测试（较早快照） | PASS | Pixel_10_Pro AVD / Android 17：13/13；0 failure、0 error、0 skipped；未对最终源码重跑 |
| API 37 模拟器安装（较早快照） | PASS | 当时 APK 执行 `adb install -r` 返回 `Success`；不是当前最终 APK 的安装证据 |
| API 37 模拟器冷启动与 FGS（较早快照） | PASS | 清除旧应用数据后冷启动通过；`connectedDevice` 前台服务运行；AVD 无 `su`，高优通知明确显示“状态无法确认 / 初始化失败” |
| 缺少 Root 时拒绝 listener（较早快照） | PASS | `ss -ltn` 确认端口 `8787` 未监听；没有把启动失败伪报为 BLOCKED 或开放 HTTP |
| HTTP 解析/认证/路由/幂等/socket 生命周期 | PASS | 当前 JVM 自动测试覆盖；较早 AVD 因无 `su` 且安全初始化不成立，没有伪造 listener `/healthz`、status 或 toggle 冒烟结果 |
| AVD 熄屏 fail-closed（较早快照） | PASS | `mWakefulness=Asleep` 时 `connectedDevice` FGS 仍在运行且端口继续关闭；未对最终源码重跑，且不是真机锁屏、熄屏或 Doze 验收 |

上述 API 36.1 当前源码和 API 37 历史快照的 AVD `PASS` 均不得外推到物理设备、Root shell、AppOps/sensor privacy 控制效果、前台服务真机熄屏存活、系统 Alarm/Root helper 到期动作、局域网双机通信或 ChatGPT Live 声学行为。下方真机 PASS 也只证明逐行列出的开机与安全初态，不证明尚未运行的 P0 或故障注入。

## 目标设备信息

| 字段 | 状态 | 实际值/证据 |
| --- | --- | --- |
| Android 品牌/型号/ROM | PASS | Motorola XT2153-1 / `pstar_cmcc` |
| Android API/build fingerprint | PASS | Android 13 / API 33；`motorola/pstar_cmcc/pstar:13/T1RAA33.39-11-11/518de-8eea3:user/release-keys` |
| Root 方案及版本 | PASS | Magisk 30.6；`su -c id` 返回 UID 0，SELinux context 为 `u:r:magisk:s0` |
| 当前 user/profile | PASS | 当前 user 0；已发现 users 0/10/11/900–904，最终重启后全部读回麦克风隐私 BLOCKED |
| ChatGPT 包名/版本 | PASS | `com.openai.chatgpt`；versionName `1.2026.237`，versionCode `2623716` |
| iPhone 型号/iOS | NOT_RUN | 设备已连接且快捷指令已通过 iCloud 同步；型号/iOS 未记录，Action Button 未运行 |
| 网络拓扑 | NOT_RUN | Android 与构建 Mac 的可信局域网 `/healthz` 已通过；iPhone 到 Android 的实际请求仍未运行 |

## P0 控制器闸门

| 控制器 | 解锁 BLOCK/OPEN | 锁屏亮屏 | 锁屏熄屏 | ChatGPT 会话恢复 | 结论 |
| --- | --- | --- | --- | --- | --- |
| AudioManager 主控 + Root sensor privacy gate + ChatGPT AppOps 只读 veto（默认选项） | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | 默认需 Root；helper 映射为全局 sensor privacy BLOCK；绝不写 AppOps |
| Root sensor privacy 主控 + AudioManager gate + ChatGPT AppOps 只读 veto（第二选项） | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | 仅在第一候选失败后手动选择；每次 OPEN 必须启用 Root helper；绝不写 AppOps |

## 端到端验收

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| 目标 Root Android 上最终 Debug APK 安装并启动 | PASS | Motorola XT2153-1 已安装，设备 `base.apk` 与本地产物的 SHA-256 均为 `403072a677facd8ef730a647b77bed255f0cde17944457b601d86911bd82ca65`；服务监听 `192.168.5.16:8787`，本构建未重复重启测试 |
| Android 状态电子提示音与 30% 音量恢复 | PASS | 真实 HTTP 的 `BLOCKED → OPEN` 与 `OPEN → BLOCKED` 均返回 `ok=true/verified=true`；Motorola 媒体音量 Max=15，播放时 speaker 由 15 临时变为最接近 30% 的档位 5，提示结束恢复 15；开放/屏蔽分别使用上扬/下行双音 |
| Motorola OEM 自启动许可持久化 | PASS | op525 仅允许 user 0 的 MicBridge UID 10432；user 10/11 与 900–904 均未放行；许可写盘并经最终真实重启验证 |
| iPhone 快捷指令静态结构与 iCloud 同步 | PASS | `MicBridge 麦克风切换` 仅配置一次 toggle POST；复用并核对 request ID，核对 `ok`、`verified`、`mic_access` 后进入 1/2/3 次分支；不代表已运行 |
| 遗留 `root_appops` 迁移与只读安全收尾 | NOT_RUN | 未在真机制造并验证遗留 `root_appops` 状态；发布版不写入或自动“恢复” AppOps |
| BLOCKED 时 ChatGPT 听不到唯一测试短语 | NOT_RUN | 需人工声学确认 |
| OPEN 后同一会话恢复收音 | NOT_RUN | 需人工声学确认 |
| Android 锁屏/熄屏 10 分钟 | NOT_RUN | 需真机 |
| iPhone 锁屏 Action Button | NOT_RUN | 需真机 |
| 1/2/3 次触感映射 | NOT_RUN | 需真机 |
| 50 次交替、零错位 | NOT_RUN | 需两台真机 |
| 同一 opaque ID 三次只有一次副作用 | NOT_RUN | JVM/AVD 的幂等契约已通过；双机真机仍未运行 |
| 默认 30 秒硬窗口/`auto_block_at`/双 Alarm/Root helper | NOT_RUN | 两个选项都要求 helper；默认预留 10 秒预算，通常约 20 秒开始 BLOCK，有硬截止后 60 秒执行尾窗与 90 秒 wake-lock 裕量；仍需真机采证 |
| 默认/第二选项 AppOps 只读 veto | NOT_RUN | `ignore/deny/errored/UNKNOWN` 拒绝 OPEN；`allow/default` 与条件性 `foreground` 只表示本层未发现显式否决，不证明当前或锁屏/熄屏可录音；需同一 Live 会话三种屏幕状态声学校准；确认无 `appops set` |
| Root helper 布防后死亡 | NOT_RUN | 源码包含 generation supervisor 约 200 ms 检查与应用内约 250 ms 健康核验；仍需真机杀死 watcher/supervisor，验证撤销 HTTP、BLOCK 与双 Alarm 兜底；`lease_root_watchdog_armed` 仅表示初始布防成功 |
| `kill -9` | NOT_RUN | 需真机 |
| Android Task Manager Stop | NOT_RUN | 需真机 |
| Settings Force Stop | NOT_RUN | 需真机 |
| 重启默认 BLOCKED | PASS | Root boot guard 已通过两次真实重启检查；最终一次 generation/status、launcher/supervisor 与 fd 0 locks 正常，users 0/10/11/900–904 均为 BLOCKED；未发现相关崩溃或 AVC |
| Android 热点/共享 Wi‑Fi | NOT_RUN | API 31–35 仅同一可信 Wi‑Fi；API 36+ 仅在回调明确报告 Wi‑Fi tether 下游接口时接受 Android 自建热点；需真机 |
| 多个合格私网地址同时绑定 | NOT_RUN | 需列出 UI 的全部监听地址，关闭不需要的私网接口并从各网段探测 |
| 可靠模式耗电 | NOT_RUN | 默认以 1 小时锁、45 分钟续取方式接近连续持有 Partial WakeLock；需真机对照 |
| 审计证据取得 | NOT_RUN | 无应用内导出；使用最近 20 条 UI 截图或经授权 ADB/Root 提取最多 100 条 SQLite 记录 |
| 来电影响 | NOT_RUN | 需真机且不得影响紧急呼叫 |

完整步骤与证据格式见 [REAL_DEVICE_TEST_PLAN_ZH.md](REAL_DEVICE_TEST_PLAN_ZH.md)。设备连接后必须把本文件复制为带设备代号的记录，填写 Git commit、上述最终 APK SHA-256、命令输出摘要、屏幕录像/截图编号与人工签字。
