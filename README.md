# MicBridge

MicBridge 让 iPhone Action Button 通过 Apple 快捷指令向 Android 私有局域网发送一次 `POST /v1/mic/toggle`，由 Android 作为唯一状态源控制 ChatGPT 的麦克风访问。Android 应用本身不录音、不存音频，也没有 `RECORD_AUDIO` 权限。

当前默认候选链（是否适用于目标设备仍须真机校准）：

```text
iPhone Action Button
  → Apple 快捷指令（仅 POST /v1/mic/toggle）
  → 可完整监控的可信局域网 HTTP（API 31–35：同一 Wi‑Fi；API 36+：可加 Android 自建热点）
  → Android connectedDevice 前台服务
  → AudioManager 主控 + Root sensor_privacy gate + ChatGPT AppOps 只读 veto（默认选项）
  → HTTP toggle 锁存 OPEN；再次按 Action Button 才切回 BLOCKED
  → 成功切换后由 Android 播放 30% 媒体音量的上扬/下行电子提示音
```

## 界面更新（2026-09-12）

界面现分为“控制 / 连接 / 设置”，提供独立的校准引导、诊断及高级控制页面，并支持深色主题、窄屏和大字体。详情与本次验证见 [UI 改版说明](docs/UI_REDESIGN_ZH.md)。本次 Debug 构建通过 246 项 JVM 测试、13 项 API 36.1 仪器测试及 Lint；尚未部署真机。更新 UI 会按既有构建身份规则使旧声学校准失效，需要重新校准。

## 控制实现与历史验证记录

- Android Studio 工程、前台服务、Compose 设置/状态 UI、两个发布控制器、AppOps 只读 veto、HTTP API、持久幂等账本、审计日志和自动测试均在本仓库。
- 默认包名是 `com.openai.chatgpt`，安装后必须用目标设备确认实际包名。
- UI 的选择顺序服从项目要求：发布版默认先测 `audio_manager`；失败后才由用户手动选择 `root_sensor_privacy`，运行时绝不自动换控制器。发布版没有可选的 `root_appops` 写入控制器。注意这些选项 ID 不等于单一门控：默认选项实际由 AudioManager 主控、Root `sensor_privacy` gate 和 ChatGPT `RECORD_AUDIO` AppOps 只读 veto 组成。
- 此前控制实现构建执行 `./gradlew testDebugUnitTest lintDebug assembleDebug` 已通过：JVM 172/172，0 failure、0 error、0 skipped；Lint 成功。该历史 Debug APK 为 30,704,855 bytes，SHA-256 `403072a677facd8ef730a647b77bed255f0cde17944457b601d86911bd82ca65`，使用 Android Debug 签名，仅用于目标设备验收。以下真机记录属于该历史构建，不代表本次 UI 改版的真机验收。
- 最终 APK 已部署到 Motorola XT2153-1（Android 13 / API 33），设备 `base.apk` 的 SHA-256 与本地产物完全一致。Root boot guard 已通过两次真实重启检查；最终一次 `boot_count` 由 230 变为 231，所有已发现 user/profile 的麦克风隐私状态均为 BLOCKED，Motorola OEM 自启动许可持久化后由 `BootReceiver` 自然启动前台服务，端口 `8787` 监听且 `/healthz` 返回 `{"ok":true}`，未发现相关崩溃或 AVC。iPhone 快捷指令的单次 POST、响应校验和 1/2/3 次分支静态结构已验证并通过 iCloud 同步。
- 当前源码已在本机新建的 API 36.1 / Android 16 AVD 上完成 13/13 仪器测试；清除应用数据后冷启动时前台服务正常运行，AVD 无 `su`，端口 `8787` 按 fail-closed 设计保持关闭。API 37 模拟器的 13/13、安装及无 Root fail-closed 结果仍来自较早源码快照，尚未对当前源码重跑。ChatGPT Voice/Live 声学校准、Android 物理锁屏/熄屏与 Doze、iPhone Action Button 实际运行、1/2/3 次触感以及故障注入仍为 `NOT_RUN`。详见 [测试矩阵](docs/DEVICE_TEST_MATRIX.md) 与 [真机测试计划](docs/REAL_DEVICE_TEST_PLAN_ZH.md)。

## 控制器选择与校准顺序

默认 `audio_manager` 选项使用 `AudioManager.setMicrophoneMute()` 执行主写入，通过 Root `dumpsys sensor_privacy` 检查系统麦克风门，并在 OPEN 前后只读查询目标 ChatGPT 包的 `RECORD_AUDIO` AppOps。只有 AudioManager 与 sensor privacy 都读为 OPEN、且 AppOps 没有显式否决时才报告 OPEN；主层 BLOCK 无法确认时才尝试用 sensor privacy 门安全屏蔽。因此默认选项需要 MicBridge 获得 Root，不能描述成纯普通 API 方案。AudioManager 对不同 ROM 的实际效果仍可能不同；若该组合未通过目标设备测试，才手动选择 Root `sensor_privacy` 作为主控第二候选。

选中 `root_sensor_privacy` 时，实际组合是 sensor privacy 主控、AudioManager OPEN gate 和同一个 ChatGPT AppOps 只读 veto。Shell 退出码不足以证明状态已改变，两个发布组合都必须分别校准。

两个发布选项的 AppOps 层都**始终只读、不写入**：`ignore`、`deny`、`errored` 会显式否决 OPEN；查询/解析为 UNKNOWN 同样拒绝。`foreground`（包括 Android 13 设备可读到的 `RECORD_AUDIO: foreground`）依赖 UID 当时的进程态，不是显式否决，因此与 `allow`、`default` 一样只允许继续其他检查；但这种“条件性非显式否决”不能证明 ChatGPT 当前或锁屏/熄屏时可录音，必须依靠同一条 Live 会话的解锁、锁屏、熄屏声学校准。该解释适用于所有系统版本，不是 API 33 专用放行。若主控 OPEN 后二次查询变为显式否决/UNKNOWN，应用会通过所选控制器回滚 BLOCK。正常发布流程不会执行 `cmd appops set`，也不会把 AppOps 作为主控或静默回退。

若从曾包含 `root_appops` 的开发版升级，设置会自动迁移到 `root_sensor_privacy`。遗留 AppOps 元数据只进入非放宽式安全收尾：先以全局 sensor privacy 新鲜读回确认 BLOCKED，再读取并原样保留当前 AppOps 值，最后清除 MicBridge 的旧元数据与校准；无法确认任一步时保持 HTTP 关闭。旧 `root_appops` lease/开机脚本也只会执行全局 sensor privacy BLOCK，不会写入或“恢复” AppOps，因为系统无法证明当前同值策略的最后写入者。

最终选择只依据同一条 ChatGPT Live 会话上的声学校准结果：分别在 Android 解锁亮屏、锁屏亮屏、锁屏熄屏时确认 BLOCK 后听不到测试语句，并确认 OPEN 后无需退出或重建该会话即可恢复收音。两个发布候选在目标 Root Android 上完成这些步骤前，都不能宣称合格。

每轮可提交的校准还必须在**同一次服务会话**中完成 UI 的三个编号动作与最终提交（即四段安全边界）：① 建立已读回确认、双 Alarm 和 Root watcher 均已布防的临时 OPEN；② 进入 Root-only 隔离状态，新鲜确认 `sensor_privacy=BLOCKED` 、`AudioManager=OPEN`、AppOps 已读回为非显式否决 mode（只读且非 UNKNOWN）且 Root guard 健康；③ 用户必须在该 split 状态尚存在时确认 ChatGPT 无收音，应用重新读回上述条件后立即执行完整 BLOCK 并清理租约；④ 仅在最终 BLOCKED/租约清理仍可确认时点击“记录校准通过”提交。任一步失败、越序、重新开始或状态变化都会将本轮证据清零；新一轮绝不复用旧轮的 OPEN/隔离/BLOCK 证据。

## 已裁决的实现取舍

- Root 调用采用每次独立、带进程级超时和输出上限的 `su -c`，不维护长期交互 Shell；到期 helper 与开机脚本再用同一个 `flock`/租约标记协调。这样减少长期 Root Shell 状态漂移，但必须在目标 Root 管理器上验证 `su`、`timeout`、`flock`、`nohup`、`awk` 和 kernel wake-lock 接口。
- 安全配置使用同步 `SharedPreferences.commit()` 和 device-protected storage，而不是异步 DataStore；OPEN 前每一项关键持久化都必须已经提交成功。
- HTTP 不绑定规格草案中的 `0.0.0.0`，而只绑定能建立完整网络身份监控的私网 IPv4：API 31–35 只接受 `ConnectivityManager` 可识别的同一 Wi‑Fi；Android 自建热点只在 API 36+ 且 `TetheringManager` 回调成功注册后接受。仅看到熟悉的私网 IP 不足以将接口当作热点。无任何可监控地址，或基础 `ConnectivityManager` 监控注册失败时，保持 BLOCKED 且不监听；单独 `TetheringManager` 注册失败只禁用热点路径，仍可使用受监控的同一 Wi‑Fi。业务失败统一使用 HTTP 200 + `ok=false`，让快捷指令有机会主动调用 3 次“振动设备”，认证与协议错误仍为 4xx。
- iPhone 使用的 `/v1/mic/toggle` 采用持续开放，不设置 `auto_block_at`；再次按 Action Button 才执行 BLOCK。5–30 秒时限仅用于声学校准和诊断 `/open`。持久请求账本不采用短期 LRU，因为这会让迟到的真实网络重试再次 toggle。

## 安全语义

- 服务创建后立即显示 `UNKNOWN/正在执行启动屏蔽` 的前台通知；只有读回 `BLOCKED` 后才监听 HTTP。
- 所有状态修改串行执行；toggle 起点来自 Android 当次读回，不来自 iPhone 或按压次数。
- `UNKNOWN + toggle` 只尝试 `BLOCKED`，不会猜测为开放。
- 校准和诊断 `/open` 前必须同时成功安排 RTC `setAlarmClock` 与单调时钟 `setExactAndAllowWhileIdle`。Action Button 的持续 OPEN 不安排到期 Alarm，但必须启动无截止时间的 Root watcher；监督器、应用进程、网络或权限边界失效仍会故障安全 BLOCK。
- 两个发布控制器都必须部署 `/data/adb/service.d/micbridge-failsafe.sh`，并为每次 OPEN 启动独立 Root watcher；默认 `audio_manager` 的 Root 到期目标映射为全局 `sensor_privacy` BLOCK。watcher 必须成功取得并读回带超时的 kernel `/sys/power/wake_lock`；不可写、取得失败或无法确认时拒绝 OPEN。一次成功 OPEN 的 `lease_root_watchdog_armed` 因而必须为 `true`。
- Root 保护不是只做一次布防握手。lease 绑定应用 PID 与 `/proc/<pid>/stat` starttime，常驻 generation supervisor 自身也记录并核对 PID/starttime；它还核对短寿命 watcher 的 PID、命令行和状态文件。活动 lease 期间 supervisor 以约 200 ms 的轻量 procfs/文件检查监督，应用进程另以 250 ms 周期取得一次新的 Root 健康证明；应用、watcher、supervisor、generation、用户上下文或截止时间任一无法确认，就撤销 HTTP 并执行全局 BLOCK/readback，而不是静默重启后继续 OPEN。`lease_root_watchdog_armed` 字段本身仍只表示初始布防成功。
- 独立 watcher、常驻 supervisor、双系统 Alarm 与应用内健康检查仍须在目标 Root 管理器、OEM SELinux、锁屏/Doze 和 Force Stop 条件下真机验证。代码结构不能替代这项证据，也不能据此形式化承诺 Settings Force Stop 后一定按时屏蔽。
- 所有 BLOCK/回滚路径都先写入屏蔽并新鲜读回，只有确认 `BLOCKED` 后才撤销仍有效的租约；失败时保留后备动作。
- AppOps 只读 veto 绑定到固定的目标包与当前 Android user ID；用户/profile 不匹配或读回不明时拒绝 OPEN。任何已注册的 Wi‑Fi/热点网络身份回调（网络出现、丢失或链路属性变化）都会先撤销远程 OPEN gate、关闭旧 listener 并确认 BLOCKED，随后才重新枚举地址和监听。API 31–35 没有本实现可依赖的公开下游热点回调，所以 Android 自建热点不在该版本范围内。HTTP 无 heartbeat，因此仅“iPhone 离开热点但 Android 网络/IP 保持不变”无法被识别，持续 OPEN 会保持到下一次按钮切换或其他安全边界触发。
- `LOCKED_BOOT_COMPLETED` 使用 device-protected 的最小非秘密配置，只执行 BLOCK 且不开放 HTTP；常规启动仍在读回 `BLOCKED` 前保持端口关闭。
- 成功 JSON 将“命令执行”“控制面读回”“按构建/控制器/ChatGPT 版本保存的声学校准”分开。只有读回与校准同时有效时 `verified=true`。
- 设置中的正常停止先确认 `BLOCKED`；确认失败时拒绝正常停止并保留错误通知。
- 同一 `request_id` 先写入 SQLite，再执行任何副作用。重复请求不再次 toggle；响应报告当前新鲜状态，并额外返回 `replayed` 与 `original_outcome`。
- `request_id` 在应用数据的整个生命周期内全局保留，不按时间或条数淘汰；令牌轮换后重用旧 ID 也只会得到冲突，不会再次切换。客户端 ID 不得以内部标记前缀 `cancel-`、`expired-`、`removed-` 开头。代价是账本会随控制次数增长；清除应用数据或卸载会同时删除账本和令牌，因此必须重建快捷指令配置。

“30 秒”现在只约束声学校准和诊断 `/open`。iPhone Action Button 的 `/v1/mic/toggle` 成功开放时返回 `auto_block_at=null`、不布防双 Alarm，并由无截止时间的 Root watcher 持续监督；正常情况下只有下一次 toggle 才屏蔽。服务重启、应用/Root 监督器失效、权限撤销或受监控网络变化仍会故障安全 BLOCK。

“可靠模式”默认开启。服务启动后持有一把最长 1 小时的 Partial WakeLock，并每 45 分钟释放后续取一次，因此在服务持续运行时实际接近连续持有；这会增加耗电。关闭该选项会降低后台存活保障，且变更应在重启服务后按锁屏/熄屏计划重新验证。

审计数据保存在应用私有 SQLite 中，最多 100 条，UI 只显示最近 20 条。目前没有应用内“导出日志”功能；取证只能使用 UI 截图，或在已授权的 ADB/Root 测试流程中提取数据库/相关系统输出。

## 环境与构建

已选择并固定：

- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- Kotlin / Compose compiler plugin 2.3.21
- Compose BOM 2026.08.00
- `compileSdk=37`、`targetSdk=37`、`minSdk=31`
- JDK 17 或更高（本机使用 Android Studio JBR 21）

Windows PowerShell：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

macOS/Linux：

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

若构建机通过本地代理访问 Maven，而 Gradle 未自动读取 `HTTP_PROXY/HTTPS_PROXY`，可仅在当前终端设置：

```powershell
$env:GRADLE_OPTS='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897'
```

请按实际代理修改或省略；仓库不固定个人代理地址。Debug APK 产物位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

仓库根目录的 `SHA256SUMS.txt` 只认证已经发布的 `v0.2.0` 附件（30,326,289 bytes，SHA-256 `929032041DDB9EDB63983F801936E89A32D7AADF626E150FB76A5C5A9C09C6DD`）；该附件对应较早源码快照，不是本轮已部署到 Motorola 的 30,704,855-byte 候选 APK。不得用旧 checksum 认证当前候选，也不得静默替换同名发布附件；当前修改若形成新发布，必须使用新版本号、独立附件和匹配 checksum。另一台电脑从源码构建时还会生成自己的 Android Debug 签名，因此 APK 的签名和 SHA-256 通常也不同，且可能无法通过 `adb install -r` 覆盖另一把 Debug key 安装的版本。

## 安装与首次设置

1. 连接目标 Android，确认 `adb devices -l` 显示 `device`。建议先运行 `.\tools\android_device_preflight.ps1`；它默认只读并拒绝模拟器，传入 `-Install -EvidenceDirectory .\docs\device-evidence` 后才会安装 frozen APK 并保存不含原始序列号的环境快照。该快照不等于声学或 iPhone 验收结果。
2. 安装 Debug APK（若上一步没有使用 `-Install`）：

   ```powershell
   adb install -r .\app\build\outputs\apk\debug\app-debug.apk
   ```

3. 打开 MicBridge，授予通知权限。Android 17 还必须授予“本地网络”权限；拒绝时应用保持屏蔽且不监听端口。
4. 保持默认 `audio_manager` 组合选项并启动服务，在 Root 管理器中向 MicBridge 授权 Root；默认组合确认 OPEN 需要读取 Root `sensor_privacy` 状态。服务运行时不能更换控制器或包名。
5. 先在同一 ChatGPT Live 会话中完成 AudioManager 的解锁、锁屏、熄屏 P0 测试，并按 UI 的三个编号动作加最终提交完成“临时 OPEN → Root-only 隔离 → 在 split 状态确认无收音并立即完整 BLOCK → 提交”。只有失败后才停止服务，手动改选 Root `sensor_privacy` 并从第一个动作重新测试；这两种候选需要分别校准，不能运行时静默切换或复用旧证据。
6. 对两个发布选项分别安装/刷新 Root 开机保护，并核对 `boot-status`、所选控制器读回、supervisor 的 PID/starttime 身份，以及每次 OPEN 的 watcher 是否成功取得有超时的 `/sys/power/wake_lock`。默认 `audio_manager` 的开机/租约 Root 保护以全局 sensor privacy 为 BLOCK 目标。任一条件不满足都应拒绝 OPEN，且必须真机重启/熄屏测试后才能记为通过。AppOps 在两个组合中均只读 veto。
7. 在系统的“精确闹钟”特殊访问页允许 MicBridge。没有此能力时所有 OPEN 都会被拒绝；Android 可能显示指向 `auto_block_at` 的安全闹钟图标，默认 30 秒硬窗口下通常约为布防后 20 秒。
8. 打开 ChatGPT Voice/Live，按照 UI 与测试计划在同一会话中完成解锁、锁屏、熄屏声学校准。每种屏幕状态下的 BLOCK 声学确认与 OPEN 后原会话恢复均完成后，再从 UI 开始一次新的“三个编号动作 + 提交”序列。点击“临时开放”会清空上轮内存证据；隔离失败、确认时 split 状态已变或最终 BLOCK/租约清理不可验都不能提交。固件、控制器、目标包或 ChatGPT 版本变化会使已保存校准失效。
9. 选择网络拓扑时先看 Android API 级别。API 31–35 只支持 Android 与 iPhone 连接同一个可信 Wi‑Fi，不接受 Android 自建热点；API 36+ 可优先用 Android 私人热点，但只有 `TetheringManager` 回调成功注册并明确报告下游接口名时才会绑定热点 IPv4。两种路径都不监听 `0.0.0.0`；无法完整监控时保持 BLOCKED 且不监听。逐一核对 UI 列出的地址，并在系统/热点更新后重测身份变化和熄屏行为。
10. 从 UI 复制 Toggle URL 与令牌，按 [iPhone 快捷指令教程](docs/IPHONE_SHORTCUT_ZH.md) 配置 Action Button。

服务运行时，令牌、端口、控制器和目标包的危险变更会被 UI 禁用或要求先“屏蔽后停止”。

## HTTP API

完整协议见 [API 说明](docs/API_ZH.md)。端点包括：

- `GET /healthz`：无认证，只返回固定 `{ "ok": true }`。
- `GET /v1/status`：需要 `X-MicBridge-Token`，执行新鲜控制器读回。
- `POST /v1/mic/toggle`
- `POST /v1/mic/open`
- `POST /v1/mic/block`

三个修改端点均要求：

```http
X-MicBridge-Token: <43 位 Base64URL 令牌>
X-Request-Id: <16–128 位字母/数字/点/下划线/连字符>
```

iPhone 的产品流程只使用 `/v1/mic/toggle`；`open`、`block` 与 `status` 仅用于 Android UI、ADB/curl 诊断和自动化测试。

成功示例：

```json
{
  "ok": true,
  "mic_access": "open",
  "verified": true,
  "command_succeeded": true,
  "control_readback": true,
  "acoustic_calibration_valid": true,
  "request_id": "20260903153045123-482901735",
  "auto_block_at": "2026-09-03T06:31:15Z",
  "replayed": false
}
```

对已经通过认证和协议校验并受理的修改请求，控制器/校准失败、`REQUEST_ID_CONFLICT`，乃至受理后的内部异常，都返回 HTTP 200 与 `ok=false` JSON，使 Apple 快捷指令仍有机会解析并主动调用 3 次“振动设备”。受理前的格式错误和认证错误仍使用 4xx；纯快捷指令遇到 TCP/超时/ATS 层直接中止时，Apple 没有提供可依赖的内联异常捕获，因此“三次自定义振动动作”不能在所有网络故障上被保证。Action Button 自身的系统触感不计入 1/2/3；失败“显示通知”默认关闭，因为通知也可能附带额外系统触感。

因此，HTTP 200 不能单独触发成功触感。快捷指令仍须确认 `ok=true`、`verified=true`、`request_id` 与本次请求完全相同，再根据 `mic_access` 选择主动调用 1 次或 2 次“振动设备”；任一条件不满足都按失败或状态未知处理。严格验收的是快捷指令动作调用数，不是把 Action Button、通知等系统触感混入后的身体总感知次数。

服务器只把**已经认证、通过协议与 request ID 校验并受理为修改操作**的响应视为可能已经越过副作用边界：这类响应写回失败会撤销 listener、优先 BLOCK 并安全重绑；受理后的内部异常会先尽量写出 HTTP 200 + `ok=false` 信封，无论写出成功与否都执行同一安全边界。未认证请求、HTTP 解析/格式错误、`/healthz`、`/v1/status` 或尚未受理的修改请求，其响应写入失败只关闭该连接，不触发全局安全重置。

## Root 控制器保护与卸载

本节的开机保护和每次 OPEN watcher 适用于两个发布控制器。默认 `audio_manager` 组合把 Root fail-safe 映射到全局 sensor privacy；`root_sensor_privacy` 直接使用同一全局目标。AppOps 始终只是固定 package/user 的只读 OPEN veto，正常发布路径从不写入它。Root 保护包含：

- `/data/adb/service.d/micbridge-failsafe.sh`：开机后按已选 Root 控制器及固定 user/target 尽早尝试 BLOCK，在有界重试窗口内读回，并写入 `boot-status`。
- `/data/adb/micbridge/lease`：当前开放租约 ID。
- `/data/adb/micbridge/watch-*.sh`：每次开放的短寿命 watcher；以 `/proc/uptime` 的绝对单调时间判断“停止授权 OPEN”“硬截止”和尾窗，并用 `flock` 协调租约标记与开机脚本。启动时必须成功写入并确认带超时的 kernel `/sys/power/wake_lock`，否则本次 OPEN 被拒绝。默认 30 秒配置会先预留 10 秒重试预算，在约 20 秒点开始 BLOCK；watcher 最迟持续重试到 30 秒硬截止后的 60 秒尾窗。kernel wake lock 的超时还在硬截止后预留 90 秒，正常退出时主动释放；正常 BLOCK 被应用确认并写入取消标记后会使 watcher 提前退出。
- generation supervisor：以不可变 generation、应用 PID/starttime 和自身 PID/starttime 绑定当前租约，持续检查 watcher PID/命令行/状态、用户上下文、截止时间与 lease 标记。活动期约每 200 ms 检查轻量状态，并约每 1 秒重新发现 user/profile；应用进程还每 250 ms 调用一次独立 Root 健康核验。稳定无活动 lease 时 supervisor 以 1 秒周期轮询，且每轮都重新发现 user/profile；若空闲期发现上下文变化，会再次全局 BLOCK/readback 后才更新基线。任何活动租约身份或健康证明异常都先全局 BLOCK/readback 并封闭该 lease。

这些路径和后台进程在不同 Magisk、KernelSU、APatch 与 OEM SELinux 环境中的行为必须真机验证；尚未在目标设备运行的 supervisor/健康检查不得写成通过。应用内“安全移除保护”会先确认全局 `BLOCKED`，再删除 MicBridge 自己的开机脚本与配置。若升级自旧开发版，收尾流程只在全局门已确认屏蔽时读取并保留当前 AppOps 值、清除遗留元数据，绝不凭旧“ownership”标记恢复或放宽它。直接卸载 APK 可能留下 Root 脚本或旧 AppOps 值；卸载前先使用安全移除并按设备测试记录确认。

## 测试

自动测试覆盖：

- 启动先屏蔽、`BLOCKED → OPEN → BLOCKED`、`UNKNOWN + toggle → BLOCKED`。
- 屏蔽失败时不撤销现有租约、OPEN 失败回滚仍保留后备、网络 gate 与显式 UNKNOWN OPEN 拒绝。
- Mutex 串行化、同 request ID 并发去重、过期后重复响应的当前状态语义。
- 不同 request ID 的连续 toggle 仍会立即按新鲜状态执行 BLOCK、未校准拒绝 OPEN、租约未布防拒绝 OPEN、布防在持久化前失败时不产生伪租约，以及崩溃中请求恢复。
- AppOps 与 sensor privacy 输出解析、Shell 参数转义，两个发布选项的 AppOps 只读 veto 与 OPEN 后回滚，以及旧 `root_appops` 只做全局屏蔽的迁移边界。
- 令牌随机性/比较、JSON 转义。
- HTTP/1.1 CRLF、大小限制、重复关键头、Transfer-Encoding、Content-Length 与仅空 JSON 兼容等解析边界。
- 状态提示音只在读回确认的真实 `BLOCKED ↔ OPEN` 切换后选择；失败、幂等重放、状态断言和 `UNKNOWN` 收敛不播放。
- Android 仪器测试检查生产 Manifest 不含 `RECORD_AUDIO`、紧急接收器不导出、前台服务类型与 Direct Boot 最小配置。

历史源码构建证据（APK 产物时间 `2026-09-04T03:18:22Z`）：

- `./gradlew testDebugUnitTest lintDebug assembleDebug`：`PASS`；JVM 172/172，0 failure、0 error、0 skipped。
- Android Lint：0 errors、28 warnings、1 hint；warnings 为 21 `UseKtx`、3 `NewerVersionAvailable`、2 `ApplySharedPref`、1 `AndroidGradlePluginVersion`、1 `GradleDependency`，hint 为 1 `AutoboxingStateCreation`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，30,704,855 bytes，SHA-256 `403072a677facd8ef730a647b77bed255f0cde17944457b601d86911bd82ca65`。设备 `base.apk` 的 SHA-256 已核对一致。
- 当前源码在 API 36.1 / Android 16、Google Play ARM64 AVD 上安装成功，仪器测试 13/13 `PASS`；清除数据后冷启动的 `connectedDevice` 前台服务正常运行，AVD 无 `su`，`8787` 未监听，验证缺少 Root/控制读回时保持 fail-closed。
- 较早源码快照曾在 API 37 / Android 17 Pixel_10_Pro AVD 完成 13/13 仪器测试、安装、冷启动和无 Root 时 fail-closed 检查；该 AVD 结果未对当前最终源码重跑，因此只保留为历史范围证据，不能归到上述最终 APK。
- 最终 APK 已安装到 Motorola XT2153-1（Android 13 / API 33）。Root boot guard 两次真实重启检查均保持 BLOCKED；最终验收中 `boot_count` 230 → 231，generation/status、launcher/supervisor 与 fd 0 locks 正常，users 0/10/11/900–904 全部 BLOCKED。Motorola OEM op525 仅允许 user 0 的 MicBridge UID 10432 且已写盘，重启后 `BootReceiver` 自然启动 FGS PID 4701，`8787` 正常监听，`GET /healthz` 返回 `{"ok":true}`，未发现相关崩溃或 AVC。
- iPhone 快捷指令已完成静态结构验证并通过 iCloud 同步：每次运行只配置一次 `POST /v1/mic/toggle`，复用同一 request ID，校验回显 ID、`ok`、`verified` 与 `mic_access` 后进入 1/2/3 次“振动设备”分支。此项不等于 Action Button 或触感真机运行通过。

ChatGPT Live 声学校准、Android 物理锁屏/熄屏与 Doze、iPhone Action Button 实际运行、1/2/3 次触感、watcher/supervisor 死亡、Force Stop 与其他故障注入仍为 `NOT_RUN`。

真机声学验证不能由不带 `RECORD_AUDIO` 权限的生产 APK 自动完成。执行顺序、证据字段与通过标准见 [真机测试计划](docs/REAL_DEVICE_TEST_PLAN_ZH.md)。

## 已知边界

- OpenAI 官方文档说明 ChatGPT Voice/Live 的使用方式，但没有承诺 Android 外部系统门控后一定保持会话并恢复录音；这项行为只接受目标设备的声学实测。
- `verified=true` 是“当前控制器新鲜读回 + 当前构建/版本的人工校准有效”，不是 MicBridge 实时监听音频所得的证明。
- 一次性 HTTP 请求无法检测 iPhone 随后离开热点；最大开放租约负责兜底。
- Bearer token + 明文 HTTP 不能抵御同一不可信局域网中的监听或篡改。只支持可完整监控的可信同一 Wi‑Fi，以及 API 36+ 上回调注册成功的专用 Android 私人热点。
- Motorola OEM 自启动许可写盘与一次最终自然重启启动已经通过，但 Android Settings Force Stop、省电策略、watcher/supervisor 死亡及其他故障注入仍须逐项实测。两个发布选项都有独立 Root watcher、常驻 supervisor 和 250 ms 应用内健康检查；不能用本轮开机 PASS 外推为这些场景或 Force Stop 的形式化保证。
- 用户在 OPEN 期间撤销精确闹钟特殊权限会使 Android 终止进程并删除系统闹钟；Root watcher/supervisor/开机脚本仍会尝试屏蔽，但它们的实际调度、SELinux 与存活行为仍须目标设备故障注入，当前不得宣称通过。
- 电话、紧急呼叫、蓝牙/USB/有线耳机和多用户/profile 可能改变音频或 AppOps 语义；本目标只验收 Android 内置麦克风，并在来电场景记录实际影响。

详细威胁边界见 [安全模型](docs/SECURITY_MODEL_ZH.md)。

## 许可证与安全报告

源码按 [Apache License 2.0](LICENSE) 发布。安全问题请不要公开附带令牌、设备标识或真机证据；请按 [SECURITY.md](SECURITY.md) 使用 GitHub 私密漏洞报告。

## 源码结构

```text
app/src/main/java/com/jack/micbridge/
├── MainActivity.kt                  # Compose 状态、配置和校准 UI
├── data/                            # 设置、SQLite 请求账本、审计日志、模型
├── mic/                             # AppOps / AudioManager / sensor_privacy 与状态机
├── network/                         # 受监控 Wi‑Fi/热点接口上私网 IPv4 的筛选
├── receiver/                        # 开机与到期紧急屏蔽
├── safety/                          # Alarm + 独立 Root 开机/租约保护
├── server/                          # 受限 HTTP/1.1 解析器、认证和 API
└── service/                         # connectedDevice 前台服务和生命周期
```

## 参考

- [Android AudioManager](https://developer.android.com/reference/android/media/AudioManager#setMicrophoneMute(boolean))
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
- [Android exact alarms](https://developer.android.com/develop/background-work/services/alarms)
- [Android 17 local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [AOSP sensor privacy service](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/sensorprivacy/SensorPrivacyService.java)
- [AOSP AppOps documentation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/AppOps.md)
- [OpenAI Help: Voice Mode FAQ](https://help.openai.com/en/articles/20001274/)
