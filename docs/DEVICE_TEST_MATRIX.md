# MicBridge 设备测试矩阵

> 当前结论：frozen 源码的最终构建、自动测试、签名/权限检查与 API 37 模拟器的 fail-closed 验证在下述范围内为 `PASS`；双机真机验收为 `NOT_RUN — TARGET_PHYSICAL_DEVICES_NOT_CONNECTED`。
>
> 2026-09-03 构建主机上的 `adb devices -l` 只列出 `emulator-5554`，没有目标 Root Android；iPhone 也未连接或提供测试记录。AVD 没有 `su`，没有可用的 ChatGPT 包。所有 Root、物理锁屏/熄屏、Doze、重启、Action Button 与 ChatGPT Live 声学项目继续保持 `NOT_RUN`；AVD 的 `mWakefulness=Asleep` 结果不冒充真机 PASS。

## 构建主机

| 字段 | 实际值 |
| --- | --- |
| 日期/时区 | 2026-09-03 / Asia/Tokyo |
| OS | Windows 10.0.26200 |
| Android SDK | 由本机 `local.properties` 指定；绝对路径不纳入公开仓库 |
| Platform tools | 37.0.0 |
| 已安装平台 | API 31、33、34、35、36、36.1、37 |
| JDK | Android Studio JBR 21.0.10 / Oracle JDK 21.0.11 |
| ADB 结果 | `emulator-5554 device`；Pixel_10_Pro AVD，设备报告型号 `sdk_gphone16k_x86_64`，API 37 / Android 17；无目标物理 Android |
| 最终产物时间（UTC） | `2026-09-03T01:46:40.0189331Z` |

## 自动构建与模拟器验证

| 项目 | 状态 | 实际证据与范围 |
| --- | --- | --- |
| Frozen build 命令 | PASS | `clean testDebugUnitTest lintDebug assembleDebug --rerun-tasks` 成功 |
| JVM 单元测试 | PASS | 152/152；0 failure、0 error、0 skipped |
| Android Lint | PASS | 0 errors、28 warnings、1 hint：21 `UseKtx`、3 `NewerVersionAvailable`、2 `ApplySharedPref`、1 `AndroidGradlePluginVersion`、1 `GradleDependency`、1 `AutoboxingStateCreation` hint |
| 最终 Debug APK | PASS | `app/build/outputs/apk/debug/app-debug.apk`；30,326,289 bytes；SHA-256 `929032041DDB9EDB63983F801936E89A32D7AADF626E150FB76A5C5A9C09C6DD` |
| APK 签名 | PASS | `apksigner verify --verbose`：v2=true、Android Debug signer、1 signer |
| APK 录音/AppOps 写能力检查 | PASS | `aapt2` permissions 确认不含 `RECORD_AUDIO`；生产 APK 对 `cmd appops set` 的二进制扫描命中 0 |
| API 37 仪器契约测试 | PASS | Pixel_10_Pro AVD / Android 17：13/13；0 failure、0 error、0 skipped |
| API 37 模拟器安装 | PASS | 最终 APK 执行 `adb install -r` 返回 `Success` |
| API 37 模拟器冷启动与 FGS | PASS | 清除旧应用数据后冷启动通过；`connectedDevice` 前台服务运行；AVD 无 `su`，高优通知明确显示“状态无法确认 / 初始化失败” |
| 缺少 Root 时拒绝 listener | PASS | `ss -ltn` 确认端口 `8787` 未监听；没有把启动失败伪报为 BLOCKED 或开放 HTTP |
| HTTP 解析/认证/路由/幂等/socket 生命周期 | PASS | JVM 自动测试覆盖；由于最终 AVD 无 `su` 且安全初始化不成立，没有伪造真实 listener `/healthz`、status 或 toggle 冒烟结果 |
| AVD 熄屏 fail-closed | PASS | `mWakefulness=Asleep` 时 `connectedDevice` FGS 仍在运行且端口继续关闭；PASS 仅限模拟器 FGS/fail-closed 契约，不是真机锁屏、熄屏或 Doze 验收 |

上述 `PASS` 不得外推到物理设备、Root shell、AppOps/sensor privacy 控制效果、前台服务真机熄屏存活、系统 Alarm/Root helper 到期动作、局域网双机通信或 ChatGPT Live 声学行为。

## 目标设备信息

| 字段 | 状态 | 实际值/证据 |
| --- | --- | --- |
| Android 品牌/型号/ROM | NOT_RUN | 设备未连接 |
| Android API/build fingerprint | NOT_RUN | 设备未连接 |
| Root 方案及版本 | NOT_RUN | 用户确认具备 Root；版本未读取 |
| 当前 user/profile | NOT_RUN | 设备未连接 |
| ChatGPT 包名/版本 | NOT_RUN | 设备未连接 |
| iPhone 型号/iOS | NOT_RUN | 未连接/未提供 |
| 网络拓扑 | NOT_RUN | 待测 Android 热点与可信 Wi‑Fi |

## P0 控制器闸门

| 控制器 | 解锁 BLOCK/OPEN | 锁屏亮屏 | 锁屏熄屏 | ChatGPT 会话恢复 | 结论 |
| --- | --- | --- | --- | --- | --- |
| AudioManager 主控 + Root sensor privacy gate + ChatGPT AppOps 只读 veto（默认选项） | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | 默认需 Root；helper 映射为全局 sensor privacy BLOCK；绝不写 AppOps |
| Root sensor privacy 主控 + AudioManager gate + ChatGPT AppOps 只读 veto（第二选项） | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | 仅在第一候选失败后手动选择；每次 OPEN 必须启用 Root helper；绝不写 AppOps |

## 端到端验收

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| 目标 Root Android 上最终 Debug APK 安装并启动 | NOT_RUN | 最终 APK 已生成并通过 AVD 安装；目标物理设备未连接 |
| 遗留 `root_appops` 迁移与只读安全收尾 | NOT_RUN | 目标 Root Android 未连接；发布版不写入或自动“恢复” AppOps |
| BLOCKED 时 ChatGPT 听不到唯一测试短语 | NOT_RUN | 需人工声学确认 |
| OPEN 后同一会话恢复收音 | NOT_RUN | 需人工声学确认 |
| Android 锁屏/熄屏 10 分钟 | NOT_RUN | 需真机 |
| iPhone 锁屏 Action Button | NOT_RUN | 需真机 |
| 1/2/3 次触感映射 | NOT_RUN | 需真机 |
| 50 次交替、零错位 | NOT_RUN | 需两台真机 |
| 同一 opaque ID 三次只有一次副作用 | NOT_RUN | JVM/AVD 的幂等契约已通过；双机真机仍未运行 |
| 默认 30 秒硬窗口/`auto_block_at`/双 Alarm/Root helper | NOT_RUN | 两个选项都要求 helper；默认预留 10 秒预算，通常约 20 秒开始 BLOCK，有硬截止后 60 秒执行尾窗与 90 秒 wake-lock 裕量；仍需真机采证 |
| 默认/第二选项 AppOps 只读 veto | NOT_RUN | `ignore/deny/errored/foreground/UNKNOWN` 拒绝 OPEN；只有 `allow/default` 表示本层未发现否决；确认无 `appops set` |
| Root helper 布防后死亡 | NOT_RUN | 源码包含 generation supervisor 约 200 ms 检查与应用内约 250 ms 健康核验；仍需真机杀死 watcher/supervisor，验证撤销 HTTP、BLOCK 与双 Alarm 兜底；`lease_root_watchdog_armed` 仅表示初始布防成功 |
| `kill -9` | NOT_RUN | 需真机 |
| Android Task Manager Stop | NOT_RUN | 需真机 |
| Settings Force Stop | NOT_RUN | 需真机 |
| 重启默认 BLOCKED | NOT_RUN | 需 Root boot guard 真机 |
| Android 热点/共享 Wi‑Fi | NOT_RUN | API 31–35 仅同一可信 Wi‑Fi；API 36+ 仅在回调明确报告 Wi‑Fi tether 下游接口时接受 Android 自建热点；需真机 |
| 多个合格私网地址同时绑定 | NOT_RUN | 需列出 UI 的全部监听地址，关闭不需要的私网接口并从各网段探测 |
| 可靠模式耗电 | NOT_RUN | 默认以 1 小时锁、45 分钟续取方式接近连续持有 Partial WakeLock；需真机对照 |
| 审计证据取得 | NOT_RUN | 无应用内导出；使用最近 20 条 UI 截图或经授权 ADB/Root 提取最多 100 条 SQLite 记录 |
| 来电影响 | NOT_RUN | 需真机且不得影响紧急呼叫 |

完整步骤与证据格式见 [REAL_DEVICE_TEST_PLAN_ZH.md](REAL_DEVICE_TEST_PLAN_ZH.md)。设备连接后必须把本文件复制为带设备代号的记录，填写 Git commit、上述最终 APK SHA-256、命令输出摘要、屏幕录像/截图编号与人工签字。
