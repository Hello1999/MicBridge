# MicBridge 真机测试计划

> 文档性质：测试计划与验收模板，不是测试结果。
>
> 当前双机 P0 与端到端状态：`NOT_RUN`。Motorola 真机部署、两次 Root boot guard 重启检查、最终 OEM 自然开机启动，以及 iPhone 快捷指令静态结构/iCloud 同步已有本轮 `PASS` 证据，但这些子项不能替代 ChatGPT Live 声学、锁屏/熄屏、Doze、Action Button、触感或故障注入。只有对应条目在目标 Android 与 iPhone 上实际执行并保存证据后，才可分别改为 `PASS`。

## 已完成的发布基线（非真机验收）

以下最终构建证据来自当前源码，APK 产物时间为 `2026-09-04T03:18:22Z`：

- `./gradlew testDebugUnitTest lintDebug assembleDebug`：`PASS`；JVM 172/172，0 failure、0 error、0 skipped。
- Lint：0 errors、28 warnings、1 hint；warnings 为 21 `UseKtx`、3 `NewerVersionAvailable`、2 `ApplySharedPref`、1 `AndroidGradlePluginVersion`、1 `GradleDependency`，hint 为 1 `AutoboxingStateCreation`。
- 最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，30,704,855 bytes，SHA-256 `403072a677facd8ef730a647b77bed255f0cde17944457b601d86911bd82ca65`；设备 `base.apk` 已核对一致。
- `apksigner verify --verbose`：`PASS`，v2=true、Android Debug signer、1 signer；`aapt2` permissions 确认 APK 不含 `RECORD_AUDIO`；生产 APK 对 `cmd appops set` 的二进制扫描命中 0。
- 当前源码在 API 36.1 / Android 16、Google Play ARM64 AVD 上安装成功，仪器测试 13/13 `PASS`；清除数据后冷启动的 `connectedDevice` 前台服务正常运行，AVD 无 `su`，端口 `8787` 未监听。
- API 37 / Android 17 Pixel_10_Pro AVD 的 13/13 仪器测试、安装、冷启动和无 Root 时 fail-closed 检查来自较早源码快照，未对当前最终源码重跑。该历史结果不能作为上述最终 APK 的模拟器验证，也不能证明 Root 控制或真实 HTTP 成功。

根目录 `SHA256SUMS.txt` 仍只认证已发布的较早 `v0.2.0` 附件（30,326,289 bytes，SHA-256 `929032041DDB9EDB63983F801936E89A32D7AADF626E150FB76A5C5A9C09C6DD`），不认证上述 Motorola 候选 APK。当前修改若形成新发布，必须使用新版本号、独立附件和匹配 checksum，不得静默替换同名 `v0.2.0` 附件。

本轮已完成的目标设备子项为：最终 APK 安装到 Motorola XT2153-1（Android 13 / API 33），且设备 `base.apk` 与本地产物 SHA-256 完全一致；Root boot guard 两次真实重启均维持安全初态；最终一次 `boot_count` 230 → 231 后，users 0/10/11/900–904 全部读回 BLOCKED，Motorola OEM op525 仅放行 user 0 的 MicBridge UID 10432，`BootReceiver` 自然启动 FGS PID 4701，`8787` 监听且 `/healthz` 返回 `{"ok":true}`，未发现相关崩溃或 AVC；`MicBridge 麦克风切换` 已完成单次 POST、响应核验、1/2/3 分支的静态结构验证并通过 iCloud 同步。ChatGPT Live 声学、物理锁屏/熄屏、Doze、Action Button 实际运行、1/2/3 次触感、长期运行与故障注入仍为 `NOT_RUN`。下方环境模板仍须按正式证据填写；静态结构或开机 PASS 不得复用为未执行项目的结论。

## 1. 目标与发布闸门

本计划验证以下用户目标是否在指定设备组合上成立：

1. Android 在口袋、锁屏、熄屏和后台状态下运行 ChatGPT Live，并使用 Android 自身麦克风。
2. iPhone Action Button 触发的快捷指令仅发送一次 `POST /v1/mic/toggle`。
3. Android 是控制状态的唯一来源；快捷指令不保存开关状态。
4. Android 返回经控制器读回确认的状态后，快捷指令才主动调用 1 次或 2 次“振动设备”；任何仍可解析但无法确认的结果主动调用 3 次。这里验收的是快捷指令动作调用数，Action Button 自身与 iOS 通知的系统触感必须另记；失败通知默认关闭。
5. 配置的硬安全窗口默认不超过 30 秒；实现会预留 Root BLOCK 重试预算，因此实际 `auto_block_at` 更早，到点自动尝试屏蔽。
6. MicBridge 不录音、不保存音频，交付 APK 不包含 `RECORD_AUDIO` 权限。

必须先完成 P0 设备可行性闸门。若没有任何控制器能够在锁屏、熄屏条件下稳定完成 BLOCK/OPEN、提供可信读回，并让既有 ChatGPT Live 会话恢复收音，则停止后续完整实现，并将结论记为 `BLOCKED_BY_DEVICE`。

## 2. 术语和结果状态

### 2.1 三层验证含义

- **命令执行结果**：API 或 Root 命令是否返回、是否超时、退出码为何。它不能单独证明麦克风已改变。
- **控制面读回**：控制器在写入后，从对应 Android 状态源重新读取到的值。
- **声学验证**：人工确认 ChatGPT Live 在 BLOCKED 时没有听到测试语句，并在 OPEN 后无需重建会话即可听到新语句。

`verified=true` 只能在本次操作获得新鲜且匹配的控制面读回时使用。声学验证属于按设备、系统构建和 ChatGPT 版本保存的校准证据，不是每次请求都实时测量。

### 2.2 测试状态

- `NOT_RUN`：尚未执行。
- `PASS`：按步骤执行，全部观测符合通过条件，证据齐全。
- `FAIL`：已执行，至少一个通过条件不满足。
- `BLOCKED`：受环境、权限或设备限制无法完成。
- `NOT_SUPPORTED`：产品明确声明该场景不支持；不得计入总体通过。

任何空白、口头推测或“代码看起来正确”都不得标为 `PASS`。

## 3. 测试环境记录

连接目标 Android 后，建议先运行只读预检脚本。它会拒绝模拟器、核对 frozen APK 的 SHA-256、验证 `su -c id`，并读取设备、ChatGPT 版本、`RECORD_AUDIO` 授权及当前控制面状态；只有显式传入 `-Install` 才会安装 APK。脚本不会切换麦克风状态，也不会把原始 adb 序列号写入证据：

```powershell
.\tools\android_device_preflight.ps1 `
  -Install `
  -EvidenceDirectory .\docs\device-evidence
```

若连接了多台设备，另传 `-Serial <目标序列号>`。生成的 JSON 只是环境与只读状态快照，其中声学测试和 iPhone Action Button 测试固定为 `NOT_RUN`；它不能替代后续人工验收、截图/录像与签字记录。失败时脚本会先保留带失败字段的快照再返回非零退出码，因此只有退出码为 0 才表示预检闸门通过；`*_capture_succeeded` 只表示对应只读命令成功并捕获到非空文本，不代表其语义已完成验收。

每个设备组合单独复制并填写一份记录：

| 字段 | 实际值 |
| --- | --- |
| 测试记录 ID | 待填写 |
| 测试日期、时区 | 待填写 |
| 测试人员 | 待填写 |
| Android 品牌、型号 | 待填写 |
| Android 版本、API Level | 待填写 |
| Build fingerprint、构建号、安全补丁 | 待填写 |
| 当前 Android user/profile ID | 待填写 |
| Root 方案与版本 | 待填写；无 Root 写 `none` |
| MicBridge Git commit、versionCode | 待填写 |
| APK SHA-256 | 待填写 |
| ChatGPT 包名、版本名、versionCode | 待填写 |
| ChatGPT 的 Background Conversations 设置 | 待填写 |
| 录音输入路由 | Android 内置麦克风；蓝牙/USB/有线音频附件已断开 |
| iPhone 型号、iOS 版本 | 待填写 |
| 快捷指令版本/截图编号 | 待填写 |
| 网络拓扑 | API 31–35：可信同一 Wi‑Fi；API 36+：同一 Wi‑Fi / 已成功注册 TetheringManager 监控的 Android 热点 |
| SSID、加密类型 | 仅记录测试代号，不记录真实密码 |
| 发布控制器 | AudioManager 组合 / sensor_privacy 组合（AppOps 在两者中均只读 veto） |
| 自动屏蔽配置 | 默认应为 30 秒 |
| 精确闹钟、通知、电池优化状态 | 待填写 |

固件更新、Root 方案更新、ChatGPT 更新、MicBridge 控制器实现变化或应用移入不同 profile 后，原有声学校准失效，必须重新执行 P0。

## 4. 证据要求

每个失败或关键通过项至少保留：

- MicBridge UI 中最近 20 条审计记录的截图，或经授权的 ADB/Root 提取证据。应用私有 SQLite 最多保留 100 条，当前没有应用内日志导出功能；不要填写并不存在的“导出文件”。记录字段只有时间、来源、请求 ID 短摘要、结果、成功标志、控制器、耗时和错误码。
- 对应时段的 `adb logcat` 和必要的 `dumpsys` 原始输出。
- iPhone 快捷指令起止时间和最终分支；端到端延迟必须从 iPhone 侧计时。
- 人工声学记录，写明使用的唯一测试语句、ChatGPT 是否转写/响应，以及 OPEN 后是否重建过会话。
- 锁屏、熄屏和 Doze 条目的物理状态说明。允许使用不含私人对话的屏幕录像或外部录像作为辅助证据，但不是强制项。

不要在证据中保存真实 token、热点密码、完整私人 IP、私人对话或非测试音频。

## 5. 测试前准备

1. 使用专门的无敏感信息测试会话和固定测试语句，例如“MicBridge 测试标记 A-17”。每轮更换编号，避免把旧响应误判为本轮结果。
2. 断开蓝牙耳机、USB 音频设备和有线耳机/麦克风，记录 Android 音频输入路由，用近距离遮挡/轻触测试确认 ChatGPT Live 当前确实使用 Android 内置麦克风。该步骤未有证据时，不得将结果记为“Android 自身麦克风”验收通过。
3. 开启 ChatGPT 的后台语音会话能力，并先确认锁屏前后会话本身能够持续。
4. 按 Android API 级别选择拓扑：API 31–35 只允许两台手机连入同一个明确可信、未启用客户端隔离的 Wi‑Fi；Android 自建热点应记为 `NOT_SUPPORTED`。API 36+ 可首选 Android 私人热点，但必须先确认 `TetheringManager` 回调成功注册并能跟踪下游接口；否则热点路径为 `FAIL/NOT_SUPPORTED`，只测同一可信 Wi‑Fi。
5. 从 Android UI 核对全部监听 IPv4 和端口。当前实现只绑定 `ConnectivityManager` 已跟踪的 Wi‑Fi 地址，以及 API 36+ 上 `TetheringManager` 明确报告的下游热点接口地址；然后再应用私网 IPv4 与接口前缀过滤。若同时出现多个受监控的合格地址，关闭不需要的网络接口并从每个网段检查暴露范围。不得仅因没有显示 `0.0.0.0` 就认为网络配置正确。
6. 确认 iPhone 已授予快捷指令本地网络访问，并在解锁状态手动运行一次。
7. 确认 Android 通知权限、精确闹钟能力、电池优化状态，并记录实际值。
8. 在开始任何故障注入前，确认 Android 系统隐私开关可由测试人员手动屏蔽，作为恢复手段。

## 6. P0：控制器可行性闸门

发布 UI 选项顺序固定为：先测试默认 `audio_manager`；只有它失败后才由用户手动选择 `root_sensor_privacy`。`root_appops` 不再是可选或实验控制器，测试失败也不得触发运行时自动切换。默认选项实际是 AudioManager 主控、Root sensor privacy gate 加 ChatGPT AppOps 只读 veto；第二选项是 sensor privacy 主控、AudioManager OPEN gate 加同一只读 veto。两个组合中的 AppOps 都不得写入。最终采用哪个组合，只看下面同一条 ChatGPT Live 会话在解锁、锁屏和熄屏状态的声学结果，不能依据模拟器、Root 命令退出码或代码推断提前决定。

### 6.1 通用步骤

按上述顺序对候选控制器分别执行；每个候选的 BLOCK 与 OPEN 必须始终使用同一条 ChatGPT Live 会话：

1. 启动 ChatGPT Live，使用标记语句确认当前能够收音。
2. 记录控制器写入前的原始状态。
3. 请求 BLOCK，保存命令结果和控制器专属读回。
4. 在 5 秒内说出新的唯一标记语句三次；确认 ChatGPT 没有转写、引用或响应这些内容。
5. 请求 OPEN，保存命令结果和控制器专属读回。
6. 不退出、不重建 ChatGPT Live，会话中说出另一个标记语句；确认能够恢复收音。
7. 分别在 Android 解锁亮屏、锁屏亮屏、锁屏熄屏状态执行 20 个完整 BLOCK→OPEN→BLOCK 周期。
8. 任一写入成功但读回不匹配时，API 必须返回 UNKNOWN/失败；响应可解析时，快捷指令必须主动调用 3 次“振动设备”，系统触感另计。

P0 通过要求：三种屏幕状态均完成 20 个周期，零次错误状态反馈；BLOCK 的声学结果和 OPEN 后会话恢复均通过。某控制器失败不代表项目立即失败，但它不得被自动选用。

### 6.1.1 UI 三个编号动作 + 最终提交（四段安全边界）

上述 20 周期是人工真机验收，UI 不会自动计数；测试人员必须先保留证据，再勾选对应声学声明。只有下列严格序列在**同一次前台服务会话**中完成，已保存校准才可标记有效：

UI 以向导显示四步：当前步骤由已发布快照推导（临时 OPEN 带 `auto_block_at` 且 Root watcher 已布防 → 第 2 步；显式隔离标志 → 第 3 步；新鲜 BLOCKED 且已勾选隔离确认 → 第 4 步），非当前步骤的按钮置灰。这只是引导；服务对每一步仍独立复核。

1. 点击“1. 临时开放用于测试”。此动作先清空本服务会话中旧轮的 OPEN/隔离/BLOCK 证据；只有新鲜确认 OPEN，且双精确 Alarm 与 Root watcher 都已布防，本轮才进入 OPEN 阶段。
2. 点击“2. 仅用 Root 屏蔽（隔离校验）”。应用必须新鲜读回 `sensor_privacy=BLOCKED`、`AudioManager=OPEN`、AppOps 为已确认非显式否决 mode（只读且非 UNKNOWN），且租约/Root guard 仍健康。隔离期间合并公开状态故意为 UNKNOWN；这是校准专用 split，不是可对外声称的 BLOCKED。
3. 在 split 状态尚存在时，说出本轮唯一测试语句，确认 ChatGPT 没有听到，勾选隔离确认后立即点击“3. 确认无收音并立即完整屏蔽”。点击时应用会再次读回 split 四项条件；只有它们仍匹配才接受人工确认，随后立即执行完整 BLOCK 并在确认两个门均已屏蔽后清理租约。
4. 仅在状态为新鲜 BLOCKED、租约已清理，且本轮解锁/锁屏/熄屏 BLOCK 与原会话 OPEN 恢复证据都已人工确认时，点击“记录校准通过”。提交时会再执行一次完整 BLOCK 并检查安全边界。

任一步失败或越序、新的临时 OPEN 尝试、普通“立即屏蔽”、服务重建、split/AppOps/user/guard/租约变化，以及最终 BLOCK/租约清理不可验，都必须使本轮序列作废并优先完整 BLOCK。不得用上轮成功的任一阶段补全本轮证据。

### 6.2 AudioManager

本节测试的是默认 `audio_manager` **组合**，不是孤立的 AudioManager API。先向 MicBridge 授予 Root，使其能够执行 `am get-current-user` 与 `dumpsys sensor_privacy`；其每次 OPEN 还必须布防映射到全局 sensor privacy 的 Root 租约 watcher。需要分别记录：

- `setMicrophoneMute(true/false)` 调用是否返回或抛错。
- 紧随写入后的 `isMicrophoneMute()` 值。
- Android 系统麦克风隐私开关分别为开/关时，AudioManager 读回是否仍能表示实际有效状态。
- OPEN 前后 Root sensor privacy gate 的独立读回；显式 OPEN 只有在授权与租约有效时才会主动打开此前为 BLOCKED 的备用 gate。gate 写入或读回失败时必须执行安全回滚并以新鲜读回确认 BLOCKED；若回滚结果也无法确认，则保持 ERROR/UNKNOWN，绝不报告 OPEN。
- 普通电话、录音机及其他应用是否受影响。

特别通过条件：不能把 `isMicrophoneMute()==false` 自动解释为 ChatGPT 一定可收音。若另一个系统门控仍在屏蔽而 API 返回 OPEN，则此控制器的 OPEN 验证能力不合格。

### 6.3 Root sensor privacy

需要分别记录：

- Root 授权状态、Shell 建立耗时与超时行为。
- 实际使用的 Android user ID；不得固定假设为 `0`。
- `cmd sensor_privacy enable/disable <userId> microphone` 的退出码、脱敏 stderr。
- 控制器使用的独立读回命令及其完整原始输出。
- 锁屏时命令返回 `0` 但状态没有改变的情况是否被正确识别为失败。
- 软件与硬件隐私开关组合状态是否被正确解释。
- 每次 OPEN 前是否布防对应开机保护与短寿命 Root watcher，并确认带超时的 kernel `/sys/power/wake_lock`。两个发布控制器都适用，且进程外 BLOCK 目标都为全局 sensor privacy；默认 `audio_manager` 同样映射到该目标。

若目标 ROM 没有稳定且可解析的独立读回来源，该控制器只能标记 `stateReadable=false`，不得进入生产选择列表。

### 6.4 默认/第二选项的 ChatGPT AppOps 只读 veto

对 `audio_manager` 与 `root_sensor_privacy` 分别验证：

1. 动态确认设置中的 ChatGPT 包名、当前 user/profile 与实际安装目标一致。
2. 分别构造 UID/package mode 的 `allow`、`default`、`foreground`，确认它们只允许继续检查，不单独产生成功；其中 `foreground` 是依赖 UID 实时进程态的条件性非显式否决。
3. 分别构造 `ignore`、`deny`、`errored`，确认 OPEN 前返回 `APPOPS_EXPLICIT_VETO` 且主控制器没有执行 OPEN。
4. 分别构造命令超时、解析不明、无 mode 或 user 不匹配，确认均返回 `APPOPS_VETO_UNKNOWN` 并拒绝 OPEN。另外确认 `foreground` 仅代表 AppOps 未给出显式否决，不得从 ChatGPT 当时恰好在前台推断它当前或锁屏/熄屏时可录音。
5. 在主控制器 OPEN 后、第二次 AppOps 读回前改变为否决或 UNKNOWN，确认经所选控制器回滚 BLOCK。
6. 采集 Root 命令审计，确认这两个选项只执行 `cmd appops get`，绝不执行 `cmd appops set`。

`allow`、`default` 与条件性 `foreground` 只能证明“这一层没有发现 AppOps 显式否决”，不能替代控制器读回或 ChatGPT Live 声学校准。特别是 `foreground` 不证明当前、锁屏或熄屏可录音；只有在同一条 Live 会话中完成解锁、锁屏、熄屏的 BLOCK/OPEN 前后对照，才能形成设备兼容性证据。

### 6.5 遗留 `root_appops` 迁移与非放宽式收尾

发布版不得把 AppOps 作为写入主控。对曾安装开发版或通过测试夹具制造的遗留状态，必须验证：

1. 保存的控制器 ID 为 `root_appops` 时，加载设置会自动迁移到 `root_sensor_privacy`；发布 UI 仍只显示两个控制器，且工厂不会构造 AppOps 写入链。
2. 正常 `/open`、`/block`、`/toggle`、计时到期、开机和网络 fail-close 的 Root 审计只允许 `cmd appops get` 读回；不得出现 `cmd appops set`。
3. 对遗留 lease、watcher 或 `service.d` generation 注入 `root_appops` ID，确认安全动作只屏蔽所有已发现 Android user 的全局 sensor privacy，并做独立读回；不得写入 AppOps。
4. 分别把当前 AppOps mode 设为 `allow`、`default`、`foreground`、`ignore`、`deny`、`errored`，再执行安全移除。流程必须先新鲜确认全局 sensor privacy 为 BLOCKED，读取当前 package/user mode 后**原样保留**该值，只清理 MicBridge 的旧 ownership/original 元数据与旧校准。
5. 制造全局屏蔽失败、AppOps 读回失败、package/user 不匹配或元数据残缺，确认 HTTP 保持关闭、元数据不被误清、AppOps 不被放宽。
6. 升级、清理和卸载测试结束后，用测试前记录人工恢复测试夹具自己改动的 AppOps；这一步是测试环境复原，不是 MicBridge 发布行为。

此节只能记录迁移与“不写 AppOps”的证据，不能把遗留开发功能重新认证为控制器。目标 Root/ROM 未实际执行前保持 `NOT_RUN`。

### 6.6 iPhone 与网络 P0

1. iPhone 解锁时手动运行一次快捷指令，确认本地网络授权。
2. iPhone 锁屏，Android 解锁，连续调用 10 次。
3. 两台手机均锁屏、Android 熄屏，静置 10 分钟后连续调用 10 次。
4. 所有 API 级别都至少测一次可信同一 Wi‑Fi，记录 AP 客户端隔离和地址变化。API 31–35 额外确认 Android 自建热点保持 BLOCKED/不监听，记为设计上 `NOT_SUPPORTED`；API 36+ 还必须测一次回调注册成功的 Android 热点。
5. API 36+ 关闭并重新开启热点，确认回调触发安全撤销且快捷指令中的地址是否仍有效。地址变时必须明确失败，不得连接到另一台设备。API 31–35 在同一 Wi‑Fi 上执行等价的 Wi‑Fi 断开/重连。

快捷指令每次新的人工运行应生成新的 opaque request ID；允许格式是 16–128 位 `[A-Za-z0-9._-]`，不要求 UUID，且不得以保留的 `cancel-`、`expired-`、`removed-` 开头。同一次运行中的网络层重试与响应校验必须复用完全相同的 request ID。测试时还要把 Action Button 的系统确认触感、快捷指令主动调用的“振动设备”次数、通知声音/系统触感分栏记录；默认快捷指令不添加失败通知，不能用身体感知的总振动次数代替动作计数证据。

## 7. ChatGPT Live 声学与会话测试

### 7.1 判定协议

一次有效 BLOCK 声学样本应满足：

- BLOCK 前的标记语句被听到，用于证明会话当时可用。
- BLOCK 后说出的新标记语句未出现在转写或响应中。
- OPEN 后的新标记语句被听到。
- 整个过程没有退出、重启或重建 Live 会话。

单独的“ChatGPT 没有回答”不足以证明静音，因为网络延迟、模型行为和会话限制也可能造成沉默。必须使用前后对照、控制面读回和时间对齐证据。

### 7.2 必测场景

- Android 前台、解锁、亮屏。
- Android 后台、解锁、亮屏。
- Android 锁屏、屏幕亮。
- Android 锁屏、熄屏 10 分钟后。
- 30 秒完整 BLOCK 区间后恢复。
- 连续 50 次人工交替切换。
- 普通来电前、通话中、结束后；只记录行为，不尝试覆盖紧急呼叫策略。

若 ChatGPT 因自身会话上限、网络或后台策略结束会话，该轮应标为 `BLOCKED` 或重测，不可把它记作 MicBridge BLOCK 成功。

## 8. 锁屏、熄屏与 Doze

### 8.1 自然条件

1. Android 与 iPhone 电量充足且不接 USB。
2. Android 启动服务和 ChatGPT Live 后锁屏熄屏。
3. 分别静置 1、5、10 分钟后运行快捷指令。
4. 每个时间点执行 BLOCK、OPEN 和状态确认，记录端到端延迟。

### 8.2 强制 Doze（开发测试）

在目标 ROM 支持时，可用 ADB 辅助进入 idle。执行前记录当前状态，执行后必须恢复：

```text
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle
adb shell dumpsys deviceidle unforce
```

强制 Doze 不能替代自然熄屏测试。若 OEM 修改了命令或状态机，记录原始输出并标记 `BLOCKED`，不要改写为通过。

通过条件：服务仍可接收请求；控制器能在目标屏幕状态下写入并读回；触觉反馈与新鲜读回一致。“可靠模式”默认开启，服务会取得最长 1 小时的 Partial WakeLock 并每 45 分钟释放后重新取得，长期运行时实际接近连续持有。必须分别记录可靠模式开/关（变更后重启服务）的结果、电量变化和锁持有情况，不能把它描述成仅 OPEN 期间的短锁。

## 9. Action Button 持续开放与校准租约

每个 OPEN 应有唯一 lease ID。Action Button toggle 应先持久化持续 lease、启动 Root watcher，再执行 OPEN、读回并提交；校准/诊断 `/open` 继续预设进程外后备定时。

Action Button 成功 OPEN 时 `auto_block_at` 必须为 `null`、`lease_exact_alarm_armed=false`、`lease_root_watchdog_armed=true`。5–30 秒设置只约束校准和诊断 `/open`。

### 9.1 正常路径

1. 从 BLOCKED 发起 toggle，确认响应为 OPEN 且没有自动截止。
2. 等待超过校准设置的 30 秒，并持续核对控制读回仍为 OPEN。
3. 再发送一次不同 request ID 的 toggle，确认立即 BLOCKED 并撤销 watcher。
4. 重复 20 次。

通过条件：等待期内没有 timer/Alarm 自动 BLOCK，第二次 toggle 才屏蔽；同时验证服务重启、watcher/supervisor 失效、权限撤销和受监控网络变化仍会 fail closed。校准/诊断路径另行保留原有到期测试。

活动 OPEN 期间还必须采证两层持续健康监督：Root supervisor 约每 200 ms 核对 procfs/lease/watcher 轻量状态并约每秒复查 user/profile，应用进程每 250 ms 请求一次新鲜 Root 健康证明（Root 命令在 coordinator 锁外执行，完成后核对 lease 身份未变）。lease 中的应用 PID/starttime、supervisor 的 PID/starttime、boot generation、watcher PID/命令行/状态任一不匹配，都应撤销 HTTP、执行全局 BLOCK/readback 并结束该 lease。`lease_root_watchdog_armed=true` 只证明初始布防，不能替代这些故障注入证据。

### 9.2 重置、取消和竞态

- 在已有有效 OPEN 租约期间发送新的、不同 request ID 的 `/open`：它必须只断言当前 OPEN，返回同一个 `auto_block_at`，不得产生新 lease、续租或延长原截止时间。
- 相同 request ID 重试不得延长 lease。
- 主动 BLOCK 后，旧 Alarm 即使到达也不能破坏状态机。
- 原 lease 的 Alarm 到期后必须正常 BLOCK；不得因为重复 `/open` 而被替换或失效。BLOCKED 后新发起的 OPEN 才能创建新 lease，接收器必须核对当前 lease ID。
- 修改系统墙钟、时区后，单调 exact Alarm 仍应在响应的 `auto_block_at` 对应单调边界触发；深度休眠下 RTC AlarmClock 也应在同一授权截止点触发。两种 PendingIntent 均须采证，且默认配置下不应等到 30 秒硬截止才开始。
- 无精确闹钟能力、Alarm 创建失败或持久化失败时，OPEN 必须失败或按产品声明进入明确降级；不得仍返回完全验证的 OPEN。

### 9.3 App 前台切换与应用内测试切换

| 编号 | 操作 | 期望 | 结果 |
|---|---|---|---|
| U-01 | OPEN 状态下打开 MicBridge 页面、按 Home 再返回 | 状态保持 OPEN，HTTP 监听地址不变，审计只多出 `http-status`/无新 BLOCK 记录 | `NOT_RUN` |
| U-02 | 从页面上的“电池设置/闹钟设置/应用详情”返回 | 执行一次先 BLOCK 再重扫（信任边界重置），状态区说明原因 | `NOT_RUN` |
| U-03 | 点击“测试：开放”“测试：静音”各 20 次 | 审计来源为 `local-ui`，行为与 iPhone toggle 完全一致；校准失效时同样返回 `ACOUSTIC_CALIBRATION_REQUIRED` | `NOT_RUN` |
| U-04 | 更新 ChatGPT 后打开页面 | 状态区显示“快捷指令会失败（3 次振动）：ChatGPT 已更新，需要重新校准”，就绪检查该项为 ✗ | `NOT_RUN` |
| U-05 | OPEN 期间连续 toggle | 250 ms 健康核验不得使 `latency_ms` 出现整次 Root 命令量级的抖动；记录审计 `root_round_trips` | `NOT_RUN` |

## 10. 生命周期与故障注入

三类停止行为必须分开记录，不能统称“杀服务”。

| 测试 | 操作 | 两个发布选项共有的双 Alarm 预期 | 两个发布选项共有的 Root watcher/supervisor/boot guard 预期 | 初始状态 |
| --- | --- | --- | --- | --- |
| L-01 正常停止 | MicBridge UI/通知中的停止 | 先确认 BLOCKED，再停止 | 同左 | `NOT_RUN` |
| L-02 进程 kill | 在 OPEN 时终止应用进程 | PendingIntent 后备在 `auto_block_at` 执行；服务重建先 BLOCK | supervisor 应从应用 PID/starttime 失效检测到故障并全局 BLOCK；watcher 仍补充到期动作；需实测 | `NOT_RUN` |
| L-03 活动应用“停止” | Android 13+ Task Manager Stop，或测试等价命令 | 不依赖回调；已设 Alarm 应成为后备 | supervisor/watcher 应独立补充 BLOCK；需实测 | `NOT_RUN` |
| L-04 Force Stop | Settings Force stop / `am force-stop` | APK/Alarm 为已知能力边界，不能单独宣称保证 | PID/starttime supervisor 与 watcher 独立于 APK，但只有目标 Root/ROM 实测才能判定，不能预先宣称保证 | `NOT_RUN` |
| L-05 重启 | OPEN 时重启 Android | 重启后不恢复 OPEN；服务开放端口前先确认 BLOCKED | `service.d` 开机路径应尽早 BLOCK；需实测 | `NOT_RUN` |
| L-06 Root watcher/supervisor 死亡 | OPEN 时撤销 Root，或依次终止 watcher、generation child/supervisor/launcher | 应用存活时 250 ms 新鲜健康检查撤权并 BLOCK；不得自动切换控制器；否则 ERROR_UNVERIFIED | supervisor 检测 watcher 异常后应直接 BLOCK 并封闭 lease，不能用原 OPEN 授权静默重启；再验证应用与 Root 层同时丢失时双 Alarm 的独立后备 | `NOT_RUN` |
| L-07 通知权限撤销 | 服务运行前/运行中撤销 | UI 显示安全能力降级；按策略拒绝 OPEN | 同左 | `NOT_RUN` |
| L-08 精确闹钟能力撤销 | OPEN 前及 OPEN 中撤销 | 不得继续声称严格租约保证 | watcher/supervisor 可能独立存活且持续监督，但只能记录真机结果；OPEN 前仍必须先成功布防双 Alarm | `NOT_RUN` |

L-02 应在以下崩溃窗口分别注入：

- 写 request ledger 前；
- `IN_PROGRESS` 已持久化、控制器写入前；
- 控制器已 OPEN、读回前；
- 读回成功、完成结果提交前；
- 完成结果已提交、HTTP 响应发出前。

恢复后的首要动作均应为 BLOCK。无法确认时状态为 ERROR_UNVERIFIED，且不得开放 HTTP toggle。

## 11. 网络丢失与接口切换

### 11.1 可检测的网络丢失

在 OPEN 时依次测试（只执行当前 API 级别支持的项）：

- API 36+ 关闭 Android 热点；
- Android 从当前 Wi-Fi 断开；
- DHCP 更新导致绑定地址消失；
- 从 Wi-Fi 切到蜂窝网络；
- VPN 启停但 LAN 接口仍存在。

通过条件：任一已注册网络变化回调都先撤销旧 listener 并进入 BLOCK 流程，再重新枚举地址。记录重绑后的**全部**地址；服务不得绑定 `0.0.0.0` 或命中已排除前缀的蜂窝/VPN 地址。所有地址必须来自受监控的 Wi‑Fi `Network`，或 API 36+ 上已回调确认的热点下游接口；系统上其他仅具有私网 IP 的接口不得被推测并绑定。

### 11.2 无法直接检测的客户端离开

由于 HTTP 每次响应后关闭连接且 iPhone 不发送 heartbeat，iPhone 离开当前 Wi‑Fi/热点，但 Android 侧受监控的网络身份与 IP 仍不变时，服务端不能可靠确认客户端已离开。此场景只由有界租约兜底：默认 30 秒硬窗口下通常约 20 秒开始 BLOCK。测试报告必须明确该边界，不得记录成“断网立即检测”。

## 12. 请求幂等与崩溃一致性

测试时使用可观测的控制器调用计数或 Debug 故障注入点；这些测试钩子不得进入发布行为。

| ID | 场景 | 通过条件 | 初始状态 |
| --- | --- | --- | --- |
| I-01 | 同一 request ID 串行发送 3 次 | 控制器只执行一次副作用 | `NOT_RUN` |
| I-02 | 同一 request ID 并发发送 3 次 | 仅一次进入状态转换；其他返回同一操作结果或等待其完成 | `NOT_RUN` |
| I-03 | 同一 request ID 在进程重启后重试 | 持久 ledger 命中，不再 toggle | `NOT_RUN` |
| I-04 | 同一 request ID 用于不同路径或方法 | HTTP 200、`ok=false`、`REQUEST_ID_CONFLICT`，无副作用 | `NOT_RUN` |
| I-05 | 第一个 toggle 已 OPEN 后，不同 request ID 的第二个 toggle 立即到达 | 第二个请求不被时间防抖；读取新鲜 OPEN 并执行 BLOCK，返回已验证 `blocked` | `NOT_RUN` |
| I-06 | 响应丢失后同 request ID 重试 | 不重复切换；反馈基于当前新鲜状态 | `NOT_RUN` |
| I-07 | 原结果为 OPEN，但重试发生在自动 BLOCK 后 | 不返回会导致“当前仍 OPEN”误解的陈旧状态 | `NOT_RUN` |
| I-08 | ledger 为 `IN_PROGRESS` 时重启 | 先 BLOCK；重复请求不继续未知的 toggle | `NOT_RUN` |
| I-09 | 使用 `cancel-`、`expired-`、`removed-` 开头且其余格式合法的 request ID | 受理前返回 400/`INVALID_REQUEST_ID`，无副作用 | `NOT_RUN` |
| I-10 | 已认证且已受理的修改请求执行后强制让响应写回失败 | 撤销 listener，优先 BLOCK/readback，再安全重绑 | `NOT_RUN` |
| I-11 | 分别让未认证、HTTP 解析错误、`/healthz`、`/v1/status`、尚未受理的修改响应写回失败 | 只关闭该连接，不触发全局 BLOCK/rebind | `NOT_RUN` |
| I-12 | 已认证且已受理的修改内部抛出异常，分别让 HTTP 响应可写与不可写 | 尽量返回 200/`ok=false`/ID 回显；两种情况下都撤销 listener、BLOCK/readback 并安全重绑 | `NOT_RUN` |

持久 ledger 以 request ID 作为全局主键；首次记录同时冻结 token generation、规范化方法和路径。相同 request ID 只有在这些上下文完全一致时才视为重放；方法、路径或 token generation 不一致均返回 `REQUEST_ID_CONFLICT`，且不得产生控制副作用。Request ID 只用于误重试去重，不作为认证或抵御已窃取 token 的安全机制。

## 13. 端到端性能与稳定性

1. 使用 Action Button 完成至少 50 次切换；每次在 iPhone 快捷指令起点和得到响应处分时。
2. 统计成功请求的 p50、p95、最大值；网络失败和 UNKNOWN 单独统计，不得从样本中静默删除。
3. 目标：私有局域网内 p95 小于 1 秒，且快捷指令主动振动动作数零次与 Android 新鲜读回映射不一致；系统触感作为独立观察项，不混入该判定。
4. 服务器 `latency_ms` 只用于分解 Android 处理时间，不能代替端到端指标。审计记录 `diagnostic` 中的 `root_round_trips=N` 给出该次操作的 Root 往返次数；诊断卡显示最近 20 条成功 toggle 的 p50/最大 `latency_ms`。BLOCKED→OPEN 预期约 7 次、OPEN→BLOCKED 预期约 5 次 Root 往返；显著更多说明重试或 fail-closed 回滚。
5. 稳定空闲 30 分钟并继续做较长时段耗电测试。可靠模式默认使前台服务以“1 小时超时、45 分钟续取”方式接近连续持有 Partial WakeLock；这不是泄漏判定的充分条件，但必须记录为明确耗电策略。关闭可靠模式并重启服务后再做对照。
6. 分开记录 OPEN 活动窗口与 BLOCKED 稳态的 CPU/电量：活动期预期存在 250 ms 应用健康检查、250 ms 地址检查、约 200 ms Root supervisor 轻量检查与约 1 秒 user/profile 发现；BLOCKED 稳态 supervisor 应降为 1 秒轮询，但每轮仍必须重新发现 user/profile。注入空闲期新建/切换用户，确认不晚于下一轮发现后执行全局 BLOCK/readback；若活动检查在 BLOCKED 后未降频、空闲多用户发现慢于 1 秒、Root supervisor 异常重启循环或产生持续高负载，不得通过稳定性验收。

## 14. 安全和权限负面测试

- 缺失、错误、已轮换的 token 均返回 401，且不泄露麦克风状态。
- 缺失/非法 request ID、保留前缀 `cancel-`/`expired-`/`removed-`、重复关键头、冲突 Content-Length、Transfer-Encoding、超大头、慢速连接均被拒绝且无状态副作用；另测试合法但非 UUID、且不使用保留前缀的 opaque ID 被接受。
- 4 个慢连接不能无限阻断合法控制；记录超时和资源释放。
- 改端口、轮换 token、切换绑定接口和停止服务前均先尝试 BLOCK。
- 核对服务器不绑定通配地址，列出它绑定的全部受监控合格地址，并确认已排除接口前缀上的蜂窝/VPN 地址不可达；API 31–35 上 Android 自建热点地址不得被绑定，API 36+ 上热点地址必须与 `TetheringManager` 报告的下游接口对齐。
- 使用构建产物检查权限，确认交付 APK 不含 `RECORD_AUDIO`。
- 日志、崩溃报告和 UI 不显示完整 token 或完整请求头。

## 15. 最终验收矩阵

| 验收项 | 要求 | 状态 | 证据链接/编号 |
| --- | --- | --- | --- |
| P0 控制器解锁可靠性 | 20 周期零错位 | `NOT_RUN` | 待填写 |
| P0 控制器锁屏可靠性 | 20 周期零错位 | `NOT_RUN` | 待填写 |
| P0 控制器熄屏可靠性 | 20 周期零错位 | `NOT_RUN` | 待填写 |
| ChatGPT BLOCK 声学结果 | 未听到测试标记 | `NOT_RUN` | 待填写 |
| ChatGPT OPEN 恢复 | 无需重建 Live | `NOT_RUN` | 待填写 |
| 双机锁屏 Shortcut | 10 分钟后可达 | `NOT_RUN` | 待填写 |
| 50 次端到端切换 | 自定义振动动作零映射错位，系统触感另记，p95 < 1 秒 | `NOT_RUN` | 待填写 |
| 默认 30 秒硬窗口 | `auto_block_at` 通常约 20 秒开始 BLOCK，绝不晚于硬截止；验证 60 秒执行尾窗、90 秒 wake-lock 裕量及正常提前取消 | `NOT_RUN` | 待填写 |
| 进程 kill 后备 | 租约内 BLOCK | `NOT_RUN` | 待填写 |
| Task Manager Stop 后备 | 不依赖回调，租约内 BLOCK | `NOT_RUN` | 待填写 |
| 打开 App / 回到前台不改变状态；系统设置返回触发重扫 | U-01/U-02 | `NOT_RUN` | 待填写 |
| 应用内测试切换与 iPhone 路径一致 | U-03 | `NOT_RUN` | 待填写 |
| Root watcher/supervisor 身份或健康异常 | 应用 250 ms 核验（锁外 Root 命令）或 Root supervisor 先撤权并 BLOCK；双 Alarm 仍独立兜底；armed 字段只证明初始布防 | `NOT_RUN` | 待填写 |
| Force Stop | 按所选安全层级验收 | `NOT_RUN` | 待填写 |
| 重启默认状态 | 不恢复 OPEN | `NOT_RUN` | 待填写 |
| 绑定接口丢失 | 立即进入 BLOCK | `NOT_RUN` | 待填写 |
| 幂等与崩溃点 | 无重复 toggle | `NOT_RUN` | 待填写 |
| 遗留 `root_appops` 迁移/收尾 | 自动迁移到 sensor privacy；只做全局 BLOCK；当前 AppOps 值保持不变并清除旧元数据 | `NOT_RUN` | 待填写 |
| 无录音权限 | APK 不含 `RECORD_AUDIO` | `PASS` | frozen APK 的 `aapt2` permissions 检查 |

## 16. 发布判定

只有同时满足下列条件才可把目标设备组合标记为“受支持”：

- 至少一个控制器通过 P0 的解锁、锁屏、熄屏和声学验证。
- 没有任何“成功响应但实际状态相反”的样本。
- OPEN 前安全租约已持久化并成功布防。
- 重试、并发和所有崩溃注入点均未造成第二次 toggle。
- 双系统 Alarm、短寿命 Root watcher、PID/starttime 绑定的常驻 supervisor 与 250 ms 应用健康检查的能力边界已按实际交付层级写清楚；没有用代码存在替代目标设备实测，也不宣称形式化 Force Stop 保证。
- 所有 `NOT_RUN`、`FAIL`、`BLOCKED` 和 `NOT_SUPPORTED` 项均保留原样并在发布说明中列出，未被措辞掩盖。

测试结束后应恢复 Doze；若测试人员为 6.4/6.5 故障注入手工改变过 AppOps，应按测试前记录人工复原，不能把这一步误写成 MicBridge 的自动恢复行为。最后手动确认系统麦克风处于 BLOCKED，并删除测试 token 和不再需要的测试日志。
