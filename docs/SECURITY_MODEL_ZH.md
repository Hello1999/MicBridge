# MicBridge 安全模型

> 文档性质：设计约束、威胁边界与剩余风险，不是安全认证或测试通过声明。
>
> 当前状态：Android 实现、自动测试和 API 37 模拟器验证已有可执行记录；目标 Root Android 与 iPhone 的真机验证尚未完成。本文件中的“必须”是验收要求，不代表模拟器结果已经满足 Root、锁屏/熄屏、Action Button 或 ChatGPT Live 端到端要求。

## 1. 系统与资产

MicBridge 的数据流为：

```text
iPhone Action Button
  → Apple 快捷指令
  → 当前筛选出的一个或多个私有局域网地址上的 HTTP 请求
  → Android MicBridge 前台服务
  → 经校准的麦克风控制器
  → Android 音频/隐私门控
  → ChatGPT Live 自行采集音频
```

需要保护的资产包括：

- **安全状态完整性**：不能把未确认状态报告为 OPEN 或 BLOCKED。
- **控制权**：未经授权的局域网客户端不能改变麦克风门控。
- **可用性**：合法 iPhone 请求不应被慢连接或畸形请求长期阻断。
- **控制凭据**：bearer token 不应进入日志、备份、通知或非必要剪贴板历史。
- **隐私**：MicBridge 不读取、录制、保存或转发音频。

## 2. 安全目标

MVP 的安全目标是：

1. 所有 `/v1/*` 请求在读取或改变状态前完成 token 验证；`/healthz` 只暴露固定健康信息。
2. 所有修改请求都有符合 `[A-Za-z0-9._-]{16,128}` 的 opaque request ID，请求重试不会重复产生 toggle 副作用。
3. 所有成功状态来自控制器写入后的新鲜读回，而不是缓存、命令退出码或 iPhone 的本地推断。
4. Action Button 的 toggle OPEN 是锁存状态，直到下一次 toggle；校准和诊断 `/open` 仍使用有上限的租约。
5. 服务启动、恢复、切换网络、轮换凭据和用户正常停止服务时优先 BLOCK。
6. 无法确认时进入 `ERROR_UNVERIFIED`，拒绝继续 OPEN，并向 Android 与 iPhone 明确报告失败。

## 3. 明确不保证的事项

当前明文 HTTP MVP 不保证：

- 在不可信或可被监听的共享 Wi-Fi 上提供机密性、响应真实性或抗中间人攻击。
- bearer token 能证明请求来自某一台物理 iPhone；它只证明请求方持有凭据。
- 在 Android 被 Settings Force Stop、应用卸载、设备掉电、Root/系统被攻破后，普通 APK 仍能在 30 秒内执行代码。
- 实时证明 ChatGPT Live 正在运行、当前仍持有录音流或确实收到非静音波形。
- 覆盖电话尤其是紧急呼叫对麦克风隐私策略的系统级例外。
- 在未经真机校准的 ROM、固件、profile 或 ChatGPT 版本上可靠工作。

当前两个发布控制器都会启动独立于 APK 进程的 Root watcher；Action Button 使用无截止时间 watcher，校准/诊断使用短寿命 watcher。默认 `audio_manager` 与 `root_sensor_privacy` 的进程外 fail-safe 都落到全局 sensor privacy BLOCK。lease 绑定应用 PID/starttime 和不可变 generation，常驻 Root supervisor 绑定自己的 PID/starttime，并核对 watcher PID、命令行、状态文件与用户上下文；定时 lease 还核对单调截止时间。活动 OPEN 时 supervisor 约每 200 ms 检查轻量状态，应用另每 250 ms 取得一次新鲜 Root 健康证明；异常即撤销 HTTP、BLOCK/readback 并终止 lease。

## 4. 信任边界与攻击者

### 4.1 信任边界

- iPhone 快捷指令及其中保存的 token 被视为受用户控制。
- Android App 私有存储在未 Root 的应用沙箱边界内受信任；设备已 Root 时，其他 Root 进程可以越过该边界。
- API 36+ 上经 `TetheringManager` 成功监控的专用 Android 热点，其无线加密和强密码是明文 HTTP MVP 的外层机密性边界。
- 共享 Wi-Fi、VPN、蜂窝接口、USB 网络和任何未知接口均不自动受信任。
- ChatGPT 是独立应用。MicBridge 只能控制 Android 门控，不能从 ChatGPT 获得可信的会话状态证明。

### 4.2 考虑的攻击者和故障

- 同一局域网中的被动监听者和主动中间人。
- 知道热点密码的其他客户端。
- 获得 token 但未控制 iPhone 的请求方。
- 发送慢连接、超大请求或畸形 HTTP 的局域网客户端。
- Android 上的普通恶意应用、具有 Root 的进程或能够操作系统隐私开关的人。
- OEM 后台限制、系统进程回收、Root Shell 死亡、权限撤销和网络接口变化。
- 用户误触、多次运行快捷指令、iOS 或网络层自动重试。

具有 Root、物理解锁控制权或能够修改 MicBridge APK/快捷指令的一方可绕过本模型的大部分保护，属于高权限边界外攻击者。

## 5. 推荐部署拓扑

### 5.1 API 36+ 有条件支持：专用 Android 私人热点

推荐配置：

- Android 创建 WPA2/WPA3 私人热点，使用长且唯一的随机密码。
- 仅测试/控制用 iPhone 加入；不与访客或其他设备共享密码。
- Android 同时使用蜂窝网络运行 ChatGPT Live。
- Android 必须为 API 36+，且 `TetheringManager` 回调成功注册并明确报告下游接口。注册失败、回调不可用或无下游接口时，MicBridge 保持 BLOCKED 且不监听热点地址。
- 每次热点、系统或固件更新后，重新确认网关地址、客户端互通和熄屏行为。

无线加密减少链路上的被动监听风险，但不把 bearer token 变成设备身份，也不能防御已经掌握热点密码和 token 的客户端。

API 31–35 没有本实现可依赖的公开下游热点接口回调；不会仅凭熟悉的私网 IP 推断“这是 Android 热点”。因此 API 31–35 的 Android 自建热点明确不支持，应使用下节的同一可信 Wi‑Fi。

### 5.2 有条件支持：可信共享 Wi-Fi

共享 Wi-Fi 必须同时满足：

- 网络由用户信任和管理；
- 未启用客户端隔离；
- 没有不受信任客户端；
- 用户接受明文 HTTP 的剩余风险。

这是 API 31–35 唯一支持的局域网拓扑，也可用于 API 36+。只有 `ConnectivityManager` 回调完整跟踪的 Wi‑Fi `Network` 及其 `LinkProperties` 上地址才会被绑定。

酒店、公司访客网、机场、咖啡店及其他公共 Wi-Fi 不在 MVP 支持范围内。若必须使用这类网络，应升级为 HTTPS 或可信 VPN，而不是仅增加 token 长度。

### 5.3 未来加固

可选加固路径：

- Android 本地 HTTPS 服务；在 iPhone 安装并显式信任私有 CA，证书必须匹配稳定主机名或 IP。
- 使用 mDNS/Bonjour 提供稳定局域网名称，并与证书名称一起验证。
- 若快捷指令环境能够安全计算和验证，则使用带时间戳与 nonce 的请求/响应 HMAC。
- 使用受信任 VPN 隧道隔离不可信局域网。

在完成对应实现和 iPhone 真机验证前，不得声称当前 MVP 具备这些能力。

## 6. Bearer token 与客户端身份

### 6.1 token 要求

- 首次运行使用 `SecureRandom` 生成至少 32 字节随机值，Base64URL 无填充编码。
- 只通过 `X-MicBridge-Token` 请求头传输；不得放入 URL、查询参数或日志。
- 使用恒定时间字节比较；认证失败统一返回 401，不读取或泄露当前状态。
- token 存入应用私有存储，明确排除 Android Auto Backup/设备迁移备份。
- UI 默认遮罩；复制时使用系统的敏感剪贴板标记，并提醒用户清除不再使用的剪贴内容。
- 轮换 token 时，在使旧 token 失效和重新开放网络控制前先确认 BLOCKED。
- 日志中不得输出完整 token、完整请求头或可恢复 token 的哈希。

Root 设备上，其他 Root 进程仍可读取或截获 token。Android Keystore 可以提高静态窃取成本，但不能抵御已控制运行中设备的 Root 攻击者。

### 6.2 身份语义

bearer token 表示“持有者身份”，不表示“这台 iPhone 的硬件身份”。以下信息不得当作强身份：

- 源 IP：DHCP 会变化，也可能被同网主机占用。
- MAC 地址：服务端通常不可稳定获得，且系统存在随机化。
- User-Agent 或自定义普通头：均可伪造。

可为唯一配对创建独立 token 和可读名称，用于撤销及审计，但文案应称“配对凭据”，不能称为不可伪造的设备绑定。

## 7. 明文 HTTP 的风险

在没有 TLS 的网络上，能够观察或篡改流量的攻击者可以：

- 读取 token，随后使用新的 request ID 发起任意 OPEN/BLOCK/toggle。
- 重放已捕获请求；持久 request ledger 只能阻止已见 request ID 的重复副作用。
- 修改或伪造响应，让快捷指令主动调用错误的 1 次或 2 次“振动设备”。
- 丢弃请求，制造拒绝服务。

因此 request ID 不是认证机制，`MessageDigest.isEqual` 也不能修复链路缺少机密性与真实性的问题。协议中的 1/2/3 只指快捷指令主动执行“振动设备”的次数；Action Button 自身或 iOS 通知产生的系统触感不在该计数内。失败通知默认关闭，以免引入额外系统触感。当前自定义触觉反馈仅在专用、可信链路的威胁假设下代表 Android 返回的控制面状态。

## 8. 网络暴露控制

### 8.1 绑定规则

- 不监听 `0.0.0.0`。
- 同一 Wi‑Fi 地址只来自已注册 `ConnectivityManager` 回调中可确认为 Wi‑Fi、非 VPN、未 suspend 的 `Network`。API 36+ 热点地址只来自 `TetheringManager` 回调明确报告的下游接口。两类地址再经 site-local、非 loopback、非 link-local 与接口前缀筛选；`lo`、`rmnet`、`ccmni`、`pdp`、`tun`、`tap`、`wg`、`ipsec`、`dummy` 前缀被排除。
- ServerSocket 会绑定所有受监控的合格地址与配置端口，不要求地址唯一，也没有用户指定“唯一可信接口”的实现。设备同时存在多个受监控的 Wi‑Fi/热点接口时，API 可能同时暴露在多个 LAN。
- 没有筛选结果时保持 BLOCKED，拒绝启动远程 OPEN。OPEN 时收到任一网络出现、丢失或链路属性变化回调，会先撤销远程 OPEN 权限、关闭全部旧 listener、串行 BLOCK，再重新枚举和绑定；即使筛选后的 IPv4 集合没有变化，也不会沿用旧 OPEN 权限。
- 平台回调与接口名前缀过滤仍不是强身份证明。使用者必须关闭不需要的网络接口，逐一核对 UI 的 `addresses`，并用外部探测确认蜂窝/VPN/其他非预期路径不可达。
- Root 部署可选择增加仅允许选定接口/子网的防火墙规则，但必须有可恢复的卸载与回滚步骤。

若 targetSdk 37 或更高，接受局域网入站 TCP 需要按平台要求声明并请求 `ACCESS_LOCAL_NETWORK`。权限拒绝时服务必须保持 BLOCKED 并报告网络能力不可用。

### 8.2 网络丢失语义

MicBridge 能观察已注册的本机 Wi‑Fi/热点网络身份变化，不是“iPhone 是否仍在线”。OPEN 时任何可观察的网络出现、丢失或链路属性变化都会 fail closed；由于每个 HTTP 响应后连接关闭且没有 heartbeat，iPhone 离开当前 Wi‑Fi/热点但 Android 侧身份与 IP 都保持不变时无法检测。此时 Action Button 的持续 OPEN 会保持到下一次 toggle 或其他安全边界触发。

### 8.3 地址稳定性

IP 变化会让快捷指令中的旧 URL 失效。当前服务会在网络事件后重新枚举并绑定全部受监控的合格地址，但不会更新 iPhone 快捷指令。可在未来使用 mDNS 降低人工维护；当前用户必须在热点重启或 Wi‑Fi 切换后核对地址，并留意新出现的第二个合格地址扩大了暴露面。

## 9. HTTP 服务防护

无论使用手写 ServerSocket 还是成熟库，至少满足：

- 仅支持精确列出的路径与 GET/POST 方法；路径先规范化，拒绝绝对 URI 和歧义编码。
- 请求行最多 2 KiB，请求头总量最多 8 KiB，连接读取有总截止时间。
- API 不使用请求体。为兼容 Apple 快捷指令，只接受零字节，或 `Content-Type: application/json` 且内容严格为 `{}` 的最多 64 字节请求体；拒绝其他正文与无效 `Content-Length`。
- 拒绝所有 `Transfer-Encoding`、重复 token、重复 request ID、重复 Host 和冲突 Content-Length。
- 认证和输入校验必须在任何麦克风状态读取或副作用之前完成。
- 最大并发连接数为 4，读取超时为 3 秒；当前没有独立的按来源或认证失败速率限制，同网攻击者仍可能反复占用有限并发槽。
- 每次响应后关闭连接，不支持 upgrade、代理转发或长连接。
- 所有 JSON 采用固定 schema，错误信息不包含命令原文、内部路径、Root 输出或设备敏感信息。
- `/healthz` 只返回固定健康状态，不返回控制器、IP、版本、麦克风状态或是否 Root。
- 只有已认证、通过协议/request ID 校验并受理为修改操作的响应，写回失败才证明“可能已经有副作用但遥端无法确认”，因此触发撤销 listener、BLOCK/readback 与安全重绑；受理后的内部异常先尽量写出 HTTP 200 + `ok=false` 信封，即使写出成功也触发同一安全边界。未认证请求、HTTP 解析错误、健康检查、状态查询和尚未受理的修改请求，其响应写失败只关闭连接，不得借此造成全局 BLOCK/rebind 拒绝服务。

使用精简手写 HTTP 解析器会增加请求走私、Slowloris 和边界解析风险；若体积不是硬约束，应优先选择维护良好的嵌入式实现，并仍保留上述限制。

## 10. 请求 ID、去重与崩溃一致性

### 10.1 安全语义

request ID 是 16–128 位 `[A-Za-z0-9._-]` opaque 标识，用于避免同一次快捷指令因网络重试发生两次 toggle，不要求 UUID，也不用于证明请求新鲜或来自 iPhone。`cancel-`、`expired-`、`removed-` 是内部租约标记保留前缀，客户端 ID 命中任一前缀都必须在修改受理前拒绝。攻击者拿到 token 后仍可以生成其他新 request ID。

当前实现把 `request_id` 本身作为应用数据生命周期内的全局主键，并同时保存端点与 `token_generation`：

```text
request_id (PRIMARY KEY) → canonical_path + token_generation + result
```

同一代次、同一路径会重放而不产生新副作用；相同 request ID 用于不同路径，或在令牌轮换后再次出现，都会作为已认证业务失败返回 HTTP 200、`ok=false`、`error.code="REQUEST_ID_CONFLICT"`。账本不按墙钟、年龄或固定行数淘汰，避免系统时间跳变或高请求量让旧重试再次 toggle。代价是数据库会持续增长；清除应用数据或卸载会同时删除账本与令牌，必须视为重新配对。不同 request ID 被视为两次独立按键操作，不设时间防抖：每次都读取 Android 新鲜状态，使第二次 toggle 在当前 OPEN/UNKNOWN 时不会被延迟而能优先 BLOCK。409 不是当前协议。

### 10.2 写前日志

修改操作必须在任何控制器副作用前持久化 `IN_PROGRESS`。推荐顺序：

1. 完成认证、格式检查和去重查询。
2. 在持久 ledger 写入 `IN_PROGRESS`。
3. 读取当前控制器状态。
4. 若目标为 OPEN，先持久化并布防 lease。
5. 执行控制器写入与独立读回。
6. 持久化完整操作结果。
7. 返回 HTTP 响应。

若进程在步骤 2 至 6 之间崩溃，恢复时一律先尝试 BLOCK；相同 request ID 不得继续一次结果未知的 toggle。

### 10.3 重复响应的真实性

“不重复副作用”和“返回完全相同字节”不是同一要求。原操作为 OPEN、但重复请求发生在 `auto_block_at` 自动 BLOCK 之后时，返回陈旧 `mic_access=open` 会产生错误触觉。响应应区分：

- `original_outcome`：首次请求产生的结果；
- `mic_access`：本次响应前的新鲜读回；
- `replayed`：本次是否由 ledger 命中；
- `observed_at`：当前读回时间。

iPhone 只根据当前 `mic_access` 与本次 `verified` 决定快捷指令主动调用“振动设备”的次数。若当前状态无法读取，返回失败并在响应可解析时主动调用 3 次；Action Button/通知的系统触感另计，传输层直接中止仍可能没有自定义失败反馈。

## 11. 状态真实性

Android 是协议的唯一状态来源，但 MicBridge 不是系统麦克风状态的唯一修改者。用户、系统隐私开关、电话策略、其他应用、Root 和硬件开关都可能改变有效状态。

因此：

- `/status` 必须执行有超时的新鲜 controller-specific readback，不能只返回 `StateFlow` 中的最后命令。
- toggle 的起点来自同一互斥区内的新鲜读回；UNKNOWN 时只允许尝试 BLOCK，不猜测 OPEN。
- `previous` 表示本次操作前的观测状态，而不是持久化的“受管理状态”。
- `verified` 必须绑定读回来源、观测时间、控制器和仍有效的设备校准。
- OPEN 表示“所有当前可观测、经校准的门控均报告允许”，不表示 MicBridge 实时测得 ChatGPT 的音频波形。
- BLOCKED 只有在一个经校准、实际有效的屏蔽门控被读回确认后才可报告。
- 外部状态改变、读回超时、控制器输出不可解析或校准过期时均为 UNKNOWN。

建议 API/日志另带 `verification_level`、`readback_source`、`observed_at` 和校准版本，避免一个布尔值承载过多含义。

## 12. Fail-closed、持续 toggle 与校准租约

### 12.1 正常 APK 层级

Action Button toggle OPEN 的安全顺序是：

1. 在持久请求账本写入 `IN_PROGRESS`，生成不可变 lease ID/控制上下文。
2. 启动绑定固定 controller/user/target 的无截止时间 Root watcher；`audio_manager` 的 watcher 目标转换为全局 sensor privacy。持续模式不安排到期 Alarm，也不取得定时 kernel wake lock。
3. 确认 watcher、常驻 supervisor 和应用进程身份健康后才执行 OPEN。
4. 执行 OPEN 并做新鲜读回；成功后才向 iPhone 返回 OPEN。
5. OPEN 失败先尝试 BLOCK；只有明确读回 BLOCKED 后才撤销 Alarm/Root lease，无法确认时保留后备。
6. 下一次 toggle、主动 BLOCK 或安全边界变化时，经同一 Mutex 串行执行 BLOCK。
7. 运行中的状态读回若发现 Root 已 BLOCK，收敛内部状态并清理剩余 lease。

5–30 秒配置仅用于声学校准和诊断 `/open`；这些路径继续使用双 Alarm、单调截止时间和带超时 wake lock。Action Button `/v1/mic/toggle` 成功开放时 `auto_block_at=null`，只有下一次 toggle 正常屏蔽；进程、Root 监督器、权限或受监控网络失效仍会 fail closed。

已有有效 OPEN 租约时，新的显式 `/open` 只是状态断言：它返回原 `auto_block_at`，不得替换 lease 或延长截止时间。只有状态已经 BLOCKED 后的新 OPEN 才能创建新的租约。

RTC AlarmClock 是 Android 文档中不会被系统调整、且会使系统退出低功耗模式的最强公开调度；第二个单调时钟 Alarm 防止修改墙钟延长租约。ISO 时间只用于 UI。系统可能显示即将到期的闹钟图标。精确闹钟权限、持久化或控制器任一步不可用时，应用不得返回 OPEN。

### 12.2 生命周期边界

- `onDestroy()` 和 `onTaskRemoved()` 只能作为额外清理机会，不能作为保证。
- 正常 UI/通知“停止服务”必须先确认 BLOCKED；失败时保持错误通知并阻止普通停止，另行提供明确警告的人工恢复路径。
- 进程被系统杀死时依赖 PendingIntent Alarm 和服务重建。
- Android 13+ Task Manager Stop 不提供应用回调，但系统设计上仍可能执行已安排的 Alarm；必须真机验证。
- Settings Force Stop 会使应用无法自启动并可能取消 PendingIntent。普通 APK 对此只能披露为剩余风险。
- 应用卸载、掉电或系统/Root 故障期间，APK 无法执行补救代码。

### 12.3 Root watchdog 与更严格层级

当前 Debug 实现会部署版本化 `service.d` 开机脚本和常驻 generation supervisor。Action Button 的 watcher 无单调截止时间，保持到 lease 标记被下一次 BLOCK 替换；校准/诊断 watcher 仍按 `/proc/uptime` 截止时间运行并使用带超时的 kernel wake lock。两种 watcher 都固定 controller/user/target，以 `flock` 协调 lease/boot generation，且健康校验失败都拒绝或撤销 OPEN。

supervisor 将应用 PID/starttime、自己的 PID/starttime、不可变 generation 和 watcher PID/命令行/状态纳入活动 lease 健康检查。活动期约 200 ms 检查轻量进程/文件状态，框架 user/profile 发现约每秒一次；应用进程另以 250 ms 周期调用 Root 新鲜核验。该核验只在 coordinator 互斥锁内读取当前 lease 身份，Root 命令本身在锁外执行，完成后再在锁内核对 lease 未被替换；因此监督不会延迟到来的 toggle。若核验期间 lease 已被新的 OPEN 事务替换，过期证明不作为对新 lease 的否定，下一周期会重新核验新 lease（新 lease 在其自身 OPEN 事务内已在锁内完成过一次证明）。任何无法确认都封闭 lease 并 BLOCK，而不是用同一 OPEN 授权静默拉起新 watcher。无活动 lease 时 supervisor 以 1 秒周期运行，且每轮都重新发现 user/profile；发现空闲期上下文变化时先对新全集执行全局 BLOCK/readback，再更新基线。这是实现证据，不是目标设备通过证据；必须对目标 Root 管理器、OEM shell 工具、SELinux、锁屏、Doze、进程 kill 与 Force Stop 逐项实测。

持续监督能缩短已知故障暴露窗口，但不是形式化可用性证明：Root launcher/supervisor 自身仍可能被 Root 管理器、OOM/OEM、SELinux 或信号终止，shell `sleep` 和 sysfs wake lock 的目标内核语义也可能不同。应用仍存活时，250 ms 健康检查会将这类不确定性转为 BLOCK/ERROR；应用同时被 Force Stop 时，只剩独立 Root 层与已布防的 Android exact Alarm。所有 watcher/supervisor 死亡、Force Stop、熄屏和 Doze 场景仍为 `NOT_RUN`，不得因代码中存在 liveness 检查而标为通过。

若 Force Stop 后也要在任意深度休眠下给出形式化的 30 秒保证，还需把当前 shell watcher/supervisor 升级为带 `CLOCK_BOOTTIME_ALARM`/受支持内核唤醒源的独立 Root daemon，并满足：

- watchdog 必须由 Root/Magisk 的开机机制独立启动，不与 MicBridge APK 同进程。
- 开机默认执行 BLOCK，不从持久记录恢复 OPEN。
- APK 只能向 watchdog 发放有最大 30 秒的带 lease ID 开放租约。
- watchdog 自己使用单调时钟，到期独立 BLOCK，并在 APK 死亡或 IPC 中断时保留截止动作。
- IPC 必须限制为 MicBridge UID/SELinux 上下文或等价本地身份；不得开放任意 Root Shell 字符串执行。
- 所有控制器命令使用固定参数集合，包名、user ID 等外部值严格验证和转义。
- 模块必须提供卸载、升级、失败恢复和手动 BLOCK 文档。

watchdog 仍不能绕过某些 ROM 在锁屏时由 SensorPrivacyService 执行的认证策略；控制器本身仍须先通过 P0。

### 12.4 toggle 热路径中的证明次数

一次 BLOCKED→OPEN 的远程 toggle 在以下边界读取 Root：入口读回、租约布防（部署 watcher 并等待其取得 wake lock）、AppOps 前读、sensor privacy OPEN、跨门读回、AppOps 后读，以及互斥锁内 OPEN 读回之后的一次 PID 绑定 Root guard 证明。此前提交前还会在锁外重复同一证明，并因自身 `setMicrophoneMute` 触发的 `ACTION_MICROPHONE_MUTE_CHANGED` 再做一整轮复合读回；2026-09-05 起：

- OPEN 提交只使用锁内的那一次 guard 证明。coordinator 互斥锁在证明与提交之间始终持有，lease 不可能被替换；周期性健康核验和之后的每次状态读取仍独立重新证明。
- 提交前的过渡期漂移复核只重读 AudioManager，因为该广播只可能来自 AudioManager 门，而 Root 两门刚在互斥锁内新鲜读回。不一致仍拒绝成功响应并触发 fail-closed listener 拆除。
- 声学校准身份（PackageManager + 签名摘要）在入口、控制器 OPEN 下发前与最终提交时各新鲜评估一次，其余中间检查复用最近一次结果。
- `cancel()` 复用服务初始化时已声明的 Root 工作区所有权，不再为每次 BLOCK 额外执行一次所有权 Root 往返；撤销脚本本身仍在 Root flock 内核对所有者文件。
- 每次 coordinator 操作消耗的 Root 往返次数记入审计记录的 `diagnostic`（`root_round_trips=N`）与状态快照，供真机对比。

这些调整不改变 fail-closed 方向：任何被去掉的重复检查都在同一互斥锁内已有等价的新鲜证明。

## 13. 失败事件策略

| 事件 | 必须动作 | 无法确认时 |
| --- | --- | --- |
| 服务首次启动/重建 | 先启动安全状态通知，再尝试 BLOCK；确认后才监听 API | 不监听 toggle/open，显示 ERROR_UNVERIFIED |
| 自动租约到期 | 串行执行 BLOCK 并读回 | 高优先级告警，保持 ERROR_UNVERIFIED |
| 绑定 LAN 接口消失 | 立即 BLOCK，关闭旧 listener | 不自动改绑其他接口 |
| Root Shell 死亡/授权撤销 | 关闭远程 OPEN；在当前已配置组合内尝试安全方向 BLOCK 并读回 | 不自动切换控制器，不猜测状态 |
| token、端口、接口或控制器变更 | 先 BLOCK，再原子应用配置 | 保持旧配置或停止服务 |
| 用户正常停止服务 | BLOCK 后停止 | 普通停止失败，提示人工处理 |
| Android 重启 | 不恢复 OPEN；开机路径尽早 BLOCK | 保持端口关闭并告警 |
| 紧急呼叫/系统覆盖 | 不试图绕过系统策略 | 记录为系统例外 |

在 ERROR_UNVERIFIED 中仍可允许认证后的 `/status` 和幂等 `/block` 重试，但必须拒绝 `/open`；`/toggle` 只可执行安全方向的 BLOCK 尝试。

## 14. Root、AppOps 与用户/profile

- 发布 UI 只允许两个选项：默认先测试 `audio_manager`；若失败，再由用户手动测试 `root_sensor_privacy`。运行时不得自动换控制器；`root_appops` 不再是可选、实验或发布控制器。
- 默认 `audio_manager` 不是单一 AudioManager：实际由 AudioManager 主控、Root sensor privacy gate 与目标 ChatGPT AppOps 只读 veto 组成，只有前两层都为 OPEN 且 AppOps 无显式否决才可能报告 OPEN，所以默认选项需要 Root。其 Root watcher 和开机保护把 `audio_manager` 安全目标映射为全局 sensor privacy BLOCK。
- `root_sensor_privacy` 选项由 sensor privacy 主控、AudioManager OPEN gate 与同一只读 AppOps veto 组成。两个组合必须独立进行声学校准。
- 两个发布选项对 AppOps 始终只读不写：UID override 与 package mode 合成后的有效 mode 为 `ignore`、`deny`、`errored` 即显式否决；查询/解析 UNKNOWN 也拒绝 OPEN。`foreground` 依赖 UID 实时进程态，因此只是条件性非显式否决，与 `allow`、`default` 一样只允许继续其他检查。它不能证明 ChatGPT 当前或锁屏/熄屏时可录音，必须由同一条 Live 会话的解锁、锁屏、熄屏声学校准补足证据。该 veto 在主控 OPEN 前后各读一次，后读出现显式否决或 UNKNOWN 则经所选控制器回滚 BLOCK。发布控制和 fail-safe 路径不得执行 `cmd appops set`。
- 最终选择必须依据同一 ChatGPT Live 会话中解锁亮屏、锁屏亮屏、锁屏熄屏三种状态的 BLOCK 声学结果与 OPEN 后原会话恢复结果；单元测试、模拟器读回和命令退出码均不能替代。
- UI 只在冷启动或从本 App 打开的系统设置页返回时发送 `ACTION_START`（先 BLOCK、撤销 listener 代次再重绑）；普通打开/回到前台只请求 `ACTION_REFRESH`。此前每次 onResume 都会重置信任边界，使“打开 App 查看状态”本身成为一次静音。
- 应用内“测试切换”动作（`ACTION_TOGGLE`）与 iPhone 请求使用同一 `coordinator.execute` 路径、同一账本、同一校准门、同一租约布防与 listener 代次检查，仅审计来源为 `local-ui`；它不是绕过远程门的本地开关。
- 可提交的校准必须在同一服务会话中完成 UI 的三个编号动作与最终提交，形成严格四段边界：带完整租约保护的临时 OPEN → Root-only split（`sensor_privacy=BLOCKED`、`AudioManager=OPEN`、AppOps 已读回为非显式否决 mode（只读且非 UNKNOWN）、Root guard 健康）→ 用户在 split 仍成立时确认无收音，应用新鲜复核后立即完整 BLOCK/清理租约 → 最终安全边界复核后提交。只有前后两个门独立读回为该 split，才能证明 Root fail-safe 不是 AudioManager 的镜像/别名。
- 校准序列是内存严格状态机。新的临时 OPEN 尝试会先清空旧序列；失败、越序、split 改变、guard/租约失效、最终 BLOCK 或租约清理无法确认都会作废本轮。旧轮 OPEN/隔离/BLOCK 证据不得与新轮拼接，服务重建后也不得复用。
- Root 扩大了信任边界：其他 Root 进程能读取 token、改变状态和伪造读回，因此“设备已被其他 Root 软件攻破”不在可防御范围内。
- `cmd sensor_privacy` 的成功退出码不是成功证明；必须使用独立状态读回。
- 软件 privacy toggle 可能要求设备解锁，Root shell 仍可能被服务策略拒绝。
- user ID 不得硬编码为 `0`。控制器必须绑定当前 user/profile，并在用户切换后重新校准或进入 UNKNOWN。
- AppOps 只读结果绑定选定包和当前 user/profile；目标不匹配、输出含糊或任何显式拒绝都 fail closed，且不能替代声学校准。
- 若设置来自曾支持写 AppOps 的旧开发版，`root_appops` 选择会自动迁移为 `root_sensor_privacy`。旧 lease、watcher 与开机脚本遇到该 ID 时只执行全局 sensor privacy BLOCK，不再写 AppOps。
- 遗留 AppOps 元数据不能证明当前同值 mode 的最后写入者。安全收尾必须先新鲜确认全局 sensor privacy 为 BLOCKED，再读取并**原样保留**当前 AppOps 值，只清除 MicBridge 的旧元数据/校准；读回或全局屏蔽不可信时保持 HTTP 关闭并拒绝清理。绝不能按旧 ownership 标记恢复或放宽 AppOps。

## 15. 前台服务与用户可见性

- 服务应使用符合实际网络设备交互用途的前台服务类型，并满足目标 SDK 的权限前提。
- `startForeground()` 必须先于可能耗时的 Root BLOCK；初始通知显示 `正在确认安全状态/UNKNOWN`，而不是虚假 BLOCKED。
- HTTP 端口只在 BLOCKED 已验证后开放。
- Android 13+ 若通知权限被拒，前台服务通知可能不在通知抽屉显示。产品若把常驻状态通知视为安全要求，应在权限缺失时拒绝远程 OPEN。
- “可靠模式”默认开启：前台服务启动后取得最长 1 小时的 Partial WakeLock，并每 45 分钟释放后重新取得一次。因此单次锁虽有界，服务长期运行时实际接近连续持有，而非只在 OPEN 租约期间持有；这会增加耗电。关闭后后台可靠性变化、以及设置需重启服务才影响当前持锁状态，都必须纳入真机测试。
- OEM 后台限制和电池策略属于必须真机测试的运行条件，不得仅凭 `START_STICKY` 宣称可靠。

## 16. 日志、隐私与备份

MicBridge 不申请生产 `RECORD_AUDIO`，不打开 AudioRecord，不存储或传输音频。真机声学验证由人工完成，或由不进入交付 APK 的独立测试模块辅助。

当前有界审计日志只包含：

- 时间、来源；
- request ID 的不可恢复短摘要；
- 控制器 ID、操作结果、是否成功、耗时和固定错误码。

SQLite 最多保留 100 条，应用 UI 只展示最近 20 条；当前没有应用内日志导出功能。真机证据必须使用 UI 截图，或在明确授权的 ADB/Root 流程中提取应用数据库与对应 `logcat`/`dumpsys`，不能在测试计划中虚构“导出”按钮或导出文件。

不得记录 token、完整请求头、Root 命令中的敏感值、热点密码、完整私人 IP、ChatGPT 对话或音频。token、Root 配置和审计日志应通过数据提取规则排除云备份；崩溃报告发送前同样脱敏。

## 17. 威胁与缓解摘要

| 威胁 | 主要缓解 | 剩余风险 |
| --- | --- | --- |
| 快捷指令网络重试导致双 toggle | 持久 request ledger、写前记录、Mutex | 崩溃下只能安全地做到不重复副作用，不能保证原请求一定完成 |
| 同网窃取 token | 专用 WPA2/WPA3 热点；未来 HTTPS/VPN | 明文共享 Wi-Fi 上无法消除 |
| 伪造成功响应 | 可信专用链路；未来 TLS/HMAC | 当前 HTTP 无响应真实性保证 |
| 慢连接耗尽服务 | 3 秒读取超时、大小限制、并发上限 4 | 无按来源/认证失败限速，同网攻击者仍可造成 DoS |
| 外部改变麦克风状态 | 每次操作前后新鲜读回、UNKNOWN 策略 | 响应后仍可能立即被外部改变 |
| OPEN 后 APK 崩溃 | RTC AlarmClock + 单调 exact Alarm；两个发布选项另加 Root watcher 与常驻 supervisor | supervisor/Root 工具/SELinux 的真实存活和调度仍须目标 Root 实测 |
| Force Stop 后维持 OPEN | watcher + PID/starttime 绑定的 supervisor；活动时约 200 ms Root 监督及 250 ms 应用内健康检查（Root 命令在 coordinator 锁外执行） | 应用检查会随进程停止，独立 Root 层仍需真机证明；不能声称形式化保证 |
| Root Shell 返回 0 但未执行 | 独立 readback、锁屏 P0 | 无稳定读回的 ROM 不支持 |
| 遗留 AppOps 被错误放宽 | 发布控制始终只读；旧选择迁移为 sensor privacy；全局 BLOCK 后保留当前值并只清元数据 | 升级路径与 OEM AppOps 输出仍须真机验证；不提供自动恢复原 mode |
| 监听到错误接口 | 不绑定通配地址；同一 Wi‑Fi 来自 ConnectivityManager；API 36+ 热点来自 TetheringManager；网络变化先 BLOCK | 会绑定全部受监控的合格地址，平台分类仍需真机审计，IP 变化需重配 Shortcut |
| token 从备份/剪贴板泄露 | 排除备份、敏感剪贴板、轮换 | Root/物理解锁者仍可获取 |

## 18. 安全验收条件

发布到某个设备组合前，至少证明：

- APK 权限清单不含 `RECORD_AUDIO`，且代码没有生产录音路径。
- 服务器不监听通配地址；UI 列出的每个合格私网绑定地址均已审计，所有非预期接口已关闭或从外部验证不可达。
- 未认证请求不能读取状态或触发任何控制器调用。
- 相同 request ID 在并发、响应丢失、服务重启和崩溃恢复后不会产生第二次 toggle。
- 所有成功响应都有本次新鲜读回；退出码、缓存或上次状态不能单独产生成功。
- OPEN 前 lease 与后备定时已经布防；30 秒、进程 kill 和 Task Manager Stop 均按真机计划验证。
- Force Stop 按实际交付层级标为已知限制或由独立 watchdog 真机通过，不能用普通进程 kill 的结果替代。
- 与交付 Android API 级别相符的网络路径必须分别记录：API 31–35 同一可信 Wi‑Fi；API 36+ 再增加 `TetheringManager` 监控成功的专用 Android 私人热点；同时验证 iPhone 锁屏路径。
- Root 控制组合、AppOps 只读目标、当前 user/profile、固件与 ChatGPT 版本均进入校准记录。
- 所有未执行或失败项目继续显示为 `NOT_RUN`、`FAIL`、`BLOCKED` 或 `NOT_SUPPORTED`。

## 19. 事件响应与人工恢复

出现 UNKNOWN、错误触觉或疑似 token 泄露时：

1. 在 Android 上使用系统麦克风隐私控制或 MicBridge 的本地“立即屏蔽”确认 BLOCKED。
2. 停止 MicBridge 服务并关闭热点，避免继续接受远程请求。
3. 保存脱敏诊断证据。
4. 轮换 token；若使用共享 Wi-Fi，必要时同时更换热点密码。
5. 检查 Root 授权、当前 user/profile、控制器读回和 ChatGPT 版本。
6. 重新执行 P0 校准后才恢复远程 OPEN。

任何时候都不得用“一般应该已经静音”代替可验证的 BLOCKED 状态。
