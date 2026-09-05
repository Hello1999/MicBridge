# MicBridge 本地 HTTP API

默认端口 `8787`。服务器仅在启动屏蔽获得可信控制读回后，绑定可建立完整身份监控的局域网 IPv4，不绑定 `0.0.0.0`。API 31–35 只使用 `ConnectivityManager` 网络回调明确标识的可信同一 Wi‑Fi 地址；Android 自建热点仅在 API 36+ 且 `TetheringManager` 回调成功注册、明确报告下游接口名时支持。仅凭 site-local IP 或熟悉的网关地址不会将接口认作热点。接口名前缀 `lo`、`rmnet`、`ccmni`、`pdp`、`tun`、`tap`、`wg`、`ipsec`、`dummy` 会被排除。若受监控的多个合格接口同时存在，API 会在其所有合格地址上可达。无合格地址或基础 `ConnectivityManager` 监控注册失败时保持 BLOCKED 且不监听；只有 `TetheringManager` 注册失败时，热点路径禁用，但仍可绑定受监控的同一 Wi‑Fi。每个连接只处理一个 HTTP/1.1 请求并关闭。OPEN 时任一已注册网络出现、丢失或链路属性变化都会撤销当前 listener generation、关闭旧连接并串行确认 BLOCKED，随后才重新枚举和监听；仅 iPhone 离开热点而 Android 热点/IP 不变无法由无 heartbeat 的协议即时检测。

本文件描述协议与实现边界，不是目标设备验收声明。Root watcher/supervisor、锁屏/熄屏、网络切换、Action Button 和 ChatGPT Live 声学结果在目标 Android/iPhone 实测前都不得标记为通过。

## 通用响应字段

| 字段 | 含义 |
| --- | --- |
| `ok` | 本次结果是否同时满足控制成功、当前读回和有效声学校准 |
| `mic_access` | 当前新鲜读回：`open`、`blocked` 或 `unknown` |
| `verified` | `control_readback && acoustic_calibration_valid` 的兼容字段 |
| `command_succeeded` | 控制命令自身是否未产生已知错误；不能单独代表静音成功 |
| `control_readback` | 控制器是否读回明确状态 |
| `acoustic_calibration_valid` | 固件、控制器、目标包和 ChatGPT 版本是否仍匹配人工校准 |
| `request_id` | 修改请求中收到的 ID；iPhone 必须核对完全相等 |
| `replayed` | 是否命中持久幂等账本且没有再次执行副作用 |
| `original_outcome` | 重复请求首次执行的结果；自定义振动动作映射不得用它代替当前 `mic_access` |
| `auto_block_at` | 校准/诊断 OPEN 的 UTC 截止时间；Action Button toggle 持续开放时为 `null` |
| `lease_exact_alarm_armed` | 校准/诊断时表示双 exact Alarm 已布防；持续 toggle OPEN 时为 `false`，因为没有自动截止 |
| `lease_root_watchdog_armed` | Root watcher 是否已启动。持续 toggle OPEN 必须为 `true`，但不取得定时 wake lock，也不按时间自动 BLOCK；仍由常驻 supervisor 和应用内健康核验监督 |
| `error` | `null` 或固定 `code` 与脱敏 `message` |

> 对修改请求，HTTP 200 只表示服务器返回了可解析的业务响应，不表示控制成功。调用方必须同时确认 `ok=true`、`verified=true`，并且响应 `request_id` 与本次发送值完全相同；随后才可依据 `mic_access="open"` 或 `"blocked"` 给出成功状态。任一字段缺失、为假、未知或请求 ID 不匹配，都必须按失败/状态无法确认处理。

## 认证

除 `/healthz` 外，所有端点都需要：

```http
X-MicBridge-Token: <token>
```

修改端点还需要：

```http
X-Request-Id: <16–128 位 [A-Za-z0-9._-]>
```

Request ID 是符合上述字符集和长度的 opaque 标识，不要求 RFC UUID。客户端不得使用 MicBridge 内部租约标记保留的 `cancel-`、`expired-`、`removed-` 前缀；命中任一前缀都以 `INVALID_REQUEST_ID` 在受理修改前拒绝。一次新的快捷指令运行应生成新值；同一次运行中的请求、网络层重试和响应校验必须复用完全相同的值。

令牌错误统一返回 401，不执行状态读回，也不泄露控制器、设备或麦克风状态。

## 端点

### `GET /healthz`

无认证，固定返回：

```json
{"ok":true}
```

### `GET /v1/status`

认证后执行控制器新鲜读回。它不是内存缓存查询。

### `POST /v1/mic/toggle`

从 Android 当前读回切换。`UNKNOWN` 时仅尝试 `BLOCKED`。由 `BLOCKED` 切到 `OPEN` 时采用持续开放，响应中 `auto_block_at=null`；正常情况下只有下一次不同 request ID 的 toggle 才切回 `BLOCKED`。服务重启、监督器失效、权限撤销或受监控网络变化仍会故障安全屏蔽。

读回确认并提交成功结果后，Android 异步播放非语音状态提示：`BLOCKED → OPEN` 为短促上扬双音，`OPEN → BLOCKED` 为短促下行双音。播放期间媒体音量临时设为最接近 30% 的系统档位，结束后恢复原值；若用户在提示期间主动调节音量，则不覆盖用户的新值。提示音失败不改变已经提交的控制结果。失败、同 request ID 重放、状态未变化或从 `UNKNOWN` 收敛到安全状态时不播放。

### `POST /v1/mic/open`

强制尝试 OPEN。若当前已由有效 OPEN 租约保护，该请求只是状态断言，返回现有 `auto_block_at`，**不会**创建新 lease、续租或延长截止时间。若当前状态为 `unknown`，先尝试屏蔽并返回失败，不会从未知状态直接开放。仅供 Android/ADB 诊断；iPhone Action Button 不使用。

配置的 `maxOpenSeconds` 是硬安全窗口，当前两个发布选项都会预留 `min(10 秒, 配置时长的一半)` 作为 Root BLOCK 重试预算。因此默认配置 30 秒时，返回的 `auto_block_at` 通常约为布防起点后 20 秒；Root watcher 从该点开始执行 BLOCK/读回，可持续到 30 秒硬截止后的 60 秒尾窗。其 kernel wake lock 超时在硬截止后另留 90 秒裕量，正常退出时主动释放。不得把 `auto_block_at` 解释成“保证开放满 30 秒”。

发布版仅允许 `audio_manager` 与 `root_sensor_privacy`，两者都在 OPEN 前后只读查询目标 ChatGPT 包的 `RECORD_AUDIO` AppOps。UID override 与 package mode 合成后的有效 mode 为 `ignore`、`deny`、`errored` 时返回 `APPOPS_EXPLICIT_VETO`。`foreground` 的实际许可依赖 UID 当时的进程态，因此它是条件性非显式否决，与 `allow`、`default` 一样只表示这一只读层没有发现硬否决；它不能单独证明 ChatGPT 当前或锁屏/熄屏时可录音，这些状态必须由同一条 Live 会话的解锁、锁屏、熄屏声学校准证明。命令失败、解析不明或无可用 mode 仍映射为 UNKNOWN，并返回 `APPOPS_VETO_UNKNOWN`。该层始终只读，正常 API 路径绝不执行 `appops set`；若 OPEN 后复查出现显式否决/UNKNOWN，会通过所选控制器回滚 BLOCK。上述显式否决、UNKNOWN 或 OPEN 后回滚均以 HTTP 200、`ok=false` 的业务结果返回。

旧开发版保存的 `root_appops` 选择会在加载设置时自动迁移为 `root_sensor_privacy`，不能通过 UI 或 API 再次选择。遗留 lease/开机脚本只把全局 sensor privacy 关闭到 BLOCKED；安全维护流程在全局 BLOCK 新鲜读回后读取并原样保留当前 AppOps 值，再清除旧元数据。它不会根据旧记录恢复、放宽或写入 AppOps。

一次 OPEN 不只检查布防标志：lease 记录应用 PID/starttime 与不可变 boot generation，supervisor 记录并核对自身 PID/starttime，并验证 watcher PID、命令行与状态文件。活动 lease 下 supervisor 约每 200 ms 查轻量状态、约每 1 秒重新发现 user/profile，应用每 250 ms 请求新的 Root 健康证明；空闲 supervisor 以 1 秒周期运行且每轮重新发现 user/profile，发现上下文变化便重新全局 BLOCK/readback。任一活动租约身份、用户上下文、截止时间或进程健康检查失败都会关闭 HTTP 并优先 BLOCK。所有这些行为仍须目标 Root/ROM 真机验证。

### `POST /v1/mic/block`

强制尝试 BLOCKED；只有新鲜读回已屏蔽后才取消当前租约，屏蔽失败时保留 Alarm/Root 后备。仅供 Android/ADB 诊断；iPhone Action Button 不使用。

## HTTP 限制

- 请求行最多 2 KiB，请求头总计最多 8 KiB，最多 64 个头。
- 读取超时 3 秒，并发处理上限 4。
- 只接受严格 CRLF；拒绝裸 LF、折叠头和重复关键头。
- 拒绝全部 `Transfer-Encoding` 与无效 `Content-Length`。为兼容 Apple 快捷指令，只接受零字节或最多 64 字节且严格为空 JSON 对象 `{}` 的正文；拒绝其他正文。
- 不支持 keep-alive、upgrade、代理绝对 URI 或公网监听。
- 已通过认证和协议校验并受理的修改请求，其业务结果一律装在 HTTP 200 JSON 中；控制/校准失败、`REQUEST_ID_CONFLICT` 和受理后的内部异常均以 `ok=false` 表示，而不是 409/500。认证、请求 ID 格式、HTTP 协议、方法和路径错误仍返回 4xx。无论状态码是否为 200，快捷指令都不得跳过 `ok`、`verified` 和回显 `request_id` 的联合校验。
- 上述 1/2/3 次是快捷指令在**收到并解析 JSON 后**主动调用“振动设备”的次数，不包括 Action Button 或通知的系统触感。TCP、路由、超时、ATS 或本地网络权限导致“获取 URL 内容”直接中止时，纯快捷指令可能没有任何自定义失败振动；该情形始终是“状态未知”，不得解释为已屏蔽。失败通知默认关闭，实验性 `x-callback-url` 也只能经目标 iPhone 真机验证，不是保证。
- 只有已认证、通过协议与 request ID 校验且已受理的修改请求，才携带“响应写入失败即 fail closed”的内部策略；因为它可能已经产生副作用，响应丢失时服务会撤销 listener、BLOCK 并安全重绑。受理后的内部异常先尽量写出 HTTP 200 + `ok=false` 信封，无论写出成功与否也执行该边界。未认证请求、HTTP 解析错误、`/healthz`、`/v1/status` 和尚未受理的修改请求，其响应写入失败只关闭连接，不触发全局 BLOCK/rebind。
- `request_id` 在应用数据生命周期内不会按年龄或行数淘汰。同 ID、同端点只重放；跨端点或令牌轮换后重用同 ID 返回 `REQUEST_ID_CONFLICT`，均不会再次执行副作用。清除应用数据或卸载会连同令牌一起删除这项保证。
- 不同 request ID 表示两次独立按键意图，不做时间防抖；第二个 toggle 必须读取 Android 当前状态，因此若第一个请求已 OPEN，紧接着的第二个请求仍会执行 BLOCK。

## curl 示例

PowerShell 7：

```powershell
$mbBase = 'http://192.168.43.1:8787'
$mbToken = '<从 Android UI 复制>'
$mbId = [guid]::NewGuid().ToString()
$mbHeaders = @{
  'X-MicBridge-Token' = $mbToken
  'X-Request-Id' = $mbId
  'Accept' = 'application/json'
}
Invoke-RestMethod -Method Post -Uri "$mbBase/v1/mic/toggle" -Headers $mbHeaders
```

不要把真实令牌写入脚本、Git、截图或测试报告。
