# iPhone Action Button 与 Apple 快捷指令配置

本文说明如何只用 iPhone 的 Action Button 和 Apple「快捷指令」，向 Android MicBridge 发送一次切换请求。iPhone 不保存、推测或反转麦克风状态；每次按键的实际结果只以 Android 返回的、已经验证的状态为准。

> 重要限制：纯快捷指令可以对**已经收到并成功解析的 JSON**严格主动调用 1/2/3 次“振动设备”，但这不等于用户身体感知到的总触感次数：Action Button 自身可能给出系统确认触感，通知也可能另带系统触感。「获取 URL 内容」如果在 DNS、路由、连接、超时、本地网络权限或 ATS 层直接报错，通常会立即终止当前快捷指令，后面的“三次自定义振动动作”可能根本不会执行。本文提供一个可测试的 `x-callback-url` 错误回调方案，以及不承诺错误触感的基础降级方案。目标 iPhone 必须真机验证，不能把网络失败时的静默当成“已屏蔽”。

## 1. 最终行为

Action Button 绑定的流程只允许产生下面这一种 HTTP 控制请求：

```http
POST /v1/mic/toggle HTTP/1.1
X-MicBridge-Token: <Android 显示的令牌>
X-Request-Id: <本次运行新生成的唯一请求标识>
Accept: application/json
```

不得在 iPhone 端增加 `/open`、`/block` 或 `/status` 请求，也不得用文件、备忘录、Data Jar、iCloud 或其他方式保存麦克风状态。

每次快捷指令运行时生成一个新的唯一请求标识，并立即保存为临时变量 `RequestId`。本次运行中的请求头和响应校验必须复用这个**同一个**变量；快捷指令结束后不保留该变量。MicBridge 接受 16–128 位的字母、数字、点、下划线与连字符，不强制要求 RFC UUID，也不依赖第三方快捷指令动作；但 `cancel-`、`expired-`、`removed-` 是 Android 内部保留前缀，客户端生成值不得以它们开头。

下表中的次数只统计 `MicBridgeWorker` 主动调用 Apple“振动设备”动作的次数，不统计 Action Button、快捷指令启动/结束提示或通知产生的系统触感：

| Android 返回结果 | 快捷指令主动调用“振动设备” |
|---|---:|
| `ok=true`、`verified=true`、`mic_access="open"`，且响应 `request_id` 与本次请求完全相同 | 调用 1 次 |
| `ok=true`、`verified=true`、`mic_access="blocked"`，且响应 `request_id` 与本次请求完全相同 | 调用 2 次 |
| 其他可解析结果，包括 `unknown`、`verified=false`、`ok=false`、字段缺失或请求 ID 不匹配 | 调用 3 次 |
| 网络层直接中止，且错误回调未能运行 | 无自定义触感；以系统错误提示为准，状态未知 |

“命令发出成功”不等于“麦克风已经屏蔽”。只有 Android 明确返回 `blocked` 且 `verified=true` 才能让快捷指令主动调用两次“振动设备”。

## 2. 准备信息

在 Android 的 MicBridge 设置页取得：

- Android UI 中当前实际监听的局域网地址，例如 `192.168.43.1`；不要凭示例地址或熟悉的热点网关猜测。
- HTTP 端口，默认 `8787`。
- MicBridge 令牌。令牌是敏感信息，不要截图、共享快捷指令或上传到 iCloud 公共链接。

最终 URL 形如：

```text
http://192.168.43.1:8787/v1/mic/toggle
```

iPhone 和 Android 必须位于同一受监控的私有局域网。Android API 31–35 只支持两台设备连接同一个可信 Wi‑Fi；该版本范围不支持 Android 自建热点。API 36+ 可以优先让 Android 开热点、iPhone 加入，但只有 MicBridge 成功注册 `TetheringManager` 监控并在 UI 显示热点监听地址时才可配置。访客网络、客户端隔离和部分厂商热点会阻止设备互访。

## 3. 创建主快捷指令 `MicBridgeWorker`

英文短名称是为了让后面的 URL 回调无需处理中文转义；可以换名，但 URL 中的名字必须完全一致。

### 3.1 每次运行只生成一个请求 ID（不依赖第三方 App）

1. 新建快捷指令，命名为 `MicBridgeWorker`。
2. 添加 Apple 自带的“当前日期”，再添加“格式化日期”，自定义格式填 `yyyyMMddHHmmssSSS`。
3. 添加“随机数”，下限 `100000000`、上限 `999999999`。
4. 添加“文本”，依次插入格式化日期、一个连字符和随机数，例如 `20260903153045123-482901735`。
5. 添加“设定变量”，变量名填 `RequestId`，值选择上一项文本。
6. 后续只选择变量 `RequestId`，不要重新生成日期或随机数。

如果目标 iOS 确实提供由 Apple「快捷指令」内置的“生成 UUID”动作，也可以用它替代第 2–4 步；不要安装 Actions、Toolbox Pro 等第三方 App。无论采用哪种方式，都必须把结果一次性存入 `RequestId` 并在本次运行全程复用。

### 3.2 发送唯一的 HTTP 请求

1. 添加“URL”动作，填写 `http://<ANDROID_IP>:8787/v1/mic/toggle`。
2. 添加“获取 URL 内容”，输入选择上一项 URL。
3. 展开高级选项并设置：
   - 方法：`POST`
   - 请求头 `X-MicBridge-Token`：粘贴 Android 显示的令牌
   - 请求头 `X-Request-Id`：插入魔法变量 `RequestId`
   - 请求头 `Accept`：`application/json`
4. 请求不需要业务字段。若当前 iOS 界面允许无正文就保持无正文；若它强制要求正文类型，选 `JSON` 但不要添加字段。服务端兼容零字节或空对象 `{}`，拒绝任何其他业务正文。不要在正文里复制请求 ID。
5. 将“获取 URL 内容”的结果设为变量 `Response`。

不要启用或手工制作第二次 HTTP 调用。若未来增加自动重试，同一次快捷指令运行的所有重试也必须复用 `RequestId`，否则可能连续切换两次。

### 3.3 严格解析响应

从 `Response` 分别添加“获取字典值”动作并保存下列四个值：

- 键 `ok` → `ResponseOk`
- 键 `verified` → `ResponseVerified`
- 键 `mic_access` → `ResponseState`
- 键 `request_id` → `ResponseRequestId`

先添加“数字”动作，值设为 `3`，再“设定变量” `PulseCount`。它是保守默认值。用嵌套的“如果”动作只在所有条件都满足时覆盖它：

```text
PulseCount = 3

如果 ResponseRequestId 完全等于 RequestId
  如果 ResponseOk 为 true
    如果 ResponseVerified 为 true
      如果 ResponseState 完全等于 open
        PulseCount = 1
      否则，如果 ResponseState 完全等于 blocked
        PulseCount = 2
      结束如果
    结束如果
  结束如果
结束如果
```

比较 `open` 和 `blocked` 时使用小写、完整相等，不要使用“包含”。比较 `ok` 和 `verified` 时使用布尔值“为真”；不要只判断字段是否存在。响应 ID 不匹配、任何字段缺失或值未知时都保留 `PulseCount=3`。

HTTP 200 本身不是成功；必须继续检查 `ok`、`verified`、`mic_access` 与回显请求 ID。Android 对已经通过认证和协议校验并受理的失败（包括 `REQUEST_ID_CONFLICT`、控制/校准失败和内部异常）有意返回 HTTP 200 + `ok=false`，使默认流程能够主动调用 3 次“振动设备”。认证、请求 ID 格式、方法、路径或 HTTP 协议错误仍是 4xx。部分 iOS 版本会在 JSON 格式错误或 HTTP 4xx/5xx 时直接终止动作，而不是返回可供分支处理的字典。因此，上面的默认值只保护“工作流仍继续执行”的失败，不等同于捕获所有网络和解析异常。

### 3.4 只执行一个自定义触感分支

添加“重复”动作，重复次数选择变量 `PulseCount`，在循环内只放一个“振动设备”动作。这样每次成功解析后恰好**主动调用**该动作 1、2 或 3 次，不会先调用一次再追加另一种状态。验收时查看快捷指令动作执行或屏幕录制，并把系统触感另记，不能仅凭身体感觉把两类触感相加。

Apple 没有公开承诺连续触感的最小可靠间隔，而且 Action Button 自身也可能产生系统触感。先用直接重复测试。如果手机壳或口袋中无法分辨次数，可在循环内的“振动设备”后增加“等待 1 秒”；这会拉开触感，但会延长完成时间。不要把 `0.15` 秒写成系统保证值。

默认配置到上述“重复”结束即停止，**不添加失败通知**。这是为了避免通知按系统设置再产生声音或触感，破坏可数的 1/2/3 自定义动作边界。

只有在目标 iPhone 真机确认通知不会干扰触感判定、并愿意单独记录通知系统反馈时，才可选择启用失败通知。启用时必须在循环后另加一层“如果 `PulseCount` 等于 `3`”，并把“显示通知”动作明确放在这个 `If PulseCount = 3` 分支内部：

```text
重复 PulseCount 次
  振动设备
结束重复

如果 PulseCount 等于 3
  显示通知“MicBridge 状态未知，请检查 Android”
结束如果
```

成功的 1/2 次分支不得显示这条错误通知。通知可能按 iPhone 当前系统设置附带声音或系统触感；它不计入协议的三次“振动设备”，也不得作为默认配置或成功/失败判据。

## 4. 网络直接失败时的两种配置

### 4.1 基础降级：系统错误、无自定义触感

这是组件最少、锁屏路径最短的配置：直接把 Action Button 绑定到 `MicBridgeWorker`。

如果“获取 URL 内容”在连接前后直接报错，快捷指令可能显示系统错误并停止，三次自定义“振动设备”调用不会运行。此时必须按“状态未知”处理：

- 不要把没有自定义振动动作解释成已开放或已屏蔽。
- 不要立刻再次长按；首个请求可能已在 Android 执行，只是响应丢失。第二次运行会生成新请求 ID，可能把状态再次反转。
- 查看 Android 的 MicBridge 常驻通知或状态页。若无法查看，等待 Android 的最长开放计时到期（默认不超过 30 秒），再确认其已自动尝试屏蔽。

这是纯快捷指令的诚实降级，不承诺网络断开时一定主动调用三次“振动设备”。

### 4.2 实验方案：用 `x-callback-url` 转入三次自定义振动

Apple 的 Shortcuts URL scheme 支持 `x-error` 回调。可以用三个快捷指令尝试捕获 `MicBridgeWorker` 的中止错误：

1. 新建 `MicBridgeFail`：默认只包含“重复 3 次”→“振动设备”，不显示通知，也不发送 HTTP。若已按 3.4 的边界在目标 iPhone 真机验证，可选择在三次动作后增加失败通知，但通知反馈必须单独记录。
2. 新建 `MicBridgeAction`：添加一个“URL”动作，内容为：

   ```text
   shortcuts://x-callback-url/run-shortcut?name=MicBridgeWorker&x-error=shortcuts%3A%2F%2Frun-shortcut%3Fname%3DMicBridgeFail&x-cancel=shortcuts%3A%2F%2Frun-shortcut%3Fname%3DMicBridgeFail
   ```

3. 在 `MicBridgeAction` 中添加“打开 URL”动作。
4. 先在解锁状态手动运行 `MicBridgeAction`，确认允许打开快捷指令 URL。
5. 暂时把 Android Wi-Fi 关闭或改成一个未监听的测试地址，验证是否会进入 `MicBridgeFail`。
6. 再恢复正确地址，确认成功时只由 `MicBridgeWorker` 主动调用一次或两次“振动设备”，没有额外回调动作；系统触感另记。
7. 测试通过后，将 Action Button 绑定到 `MicBridgeAction`，而不是 Worker。

此方案仍然不是保证：URL scheme 的再次调度、锁屏运行和具体错误是否触发 `x-error` 会受 iOS 版本与系统状态影响。尤其要在屏幕熄灭、设备锁定、重启后首次运行等场景真机测试。若测试中出现需要解锁、打开 App、错误回调不运行或重复触感，删除这层回调并使用 4.1 的无反馈降级；不要把未验证的 `x-callback-url` 写成已解决网络失败反馈。

无论采用哪种配置，iPhone 发出的唯一 HTTP 控制请求仍然只是 `POST /v1/mic/toggle`。

## 5. 第一次授权与 Action Button 绑定

第一次必须在 iPhone 解锁且「快捷指令」App 位于前台时完成：

1. 启动 Android MicBridge 前台服务，确认它已完成启动时的安全屏蔽，并显示监听地址、端口和令牌。
2. 按 Android API 级别选择网络：API 31–35 让两台设备加入同一可信 Wi‑Fi；API 36+ 也可让 iPhone 加入 Android 热点，但必须确认 MicBridge UI 已列出由 `TetheringManager` 监控的热点地址。
3. 在快捷指令编辑器中手动运行 `MicBridgeWorker`。
4. iOS 首次询问访问本地网络时选择“允许”。若出现允许该快捷指令连接指定主机的提示，选择“始终允许”。
5. 打开“设置”→“隐私与安全性”→“本地网络”，确认「快捷指令」已启用。如果列表中还没有它，先回到第 3 步触发一次请求。
6. 打开 `MicBridgeWorker` 的详情/信息页→“隐私”。如果目标 iOS 版本确实显示“锁定时允许运行”或同义开关，则启用；如果没有该项，记录 iOS 版本并直接按第 7 节实测，不要把不存在的设置写成已配置。如果使用回调方案，也检查 `MicBridgeAction` 和 `MicBridgeFail` 的同类权限项。
7. 打开“设置”→“声音与触感”→“触感”，选择允许播放触感的选项；同时在“辅助功能”→“触控”中确认“振动”已打开。
8. 打开“设置”→“Action Button/操作按钮”→“快捷指令”→“选取快捷指令”，基础方案选择 `MicBridgeWorker`，回调方案选择 `MicBridgeAction`。
9. 在解锁状态长按一次 Action Button，核对 Android 实际状态、快捷指令主动振动动作数和另记的系统触感；再长按一次，核对反向切换。

如果修改了 Android IP、端口、令牌，或重新生成了令牌，应先在解锁状态手动运行一次，以处理新的每主机访问授权。不要等到手机放入口袋后才发现系统在等待许可。

## 6. HTTP、本地网络和 ATS 注意事项

当前 URL 是明文 `http://`，令牌和结果在同一局域网内不会加密。只在可信同一 Wi‑Fi，或 API 36+ 上已成功注册监控的个人 Android 热点使用；不要把 `8787` 端口映射到互联网，也不要在公共 Wi-Fi 使用。

“本地网络”权限和 App Transport Security（ATS）是两套独立机制：即使已允许本地网络，Shortcuts 仍可能拒绝某些明文 HTTP 目标。用户不能在设置中为「快捷指令」自行增加 ATS 例外。Apple 文档没有对所有 iOS 版本、数字 IP、锁屏状态下的明文 HTTP 行为作出本项目所需的端到端保证，因此必须在目标 iPhone 上实测。

如果系统明确报 ATS/不安全连接错误，不要通过把地址换成公网、关闭认证或假称请求成功来绕过。可行的产品级方向是在 Android 端提供 iPhone 信任的 HTTPS 入口；这属于后续实现范围。

排查局域网失败时依次确认：

- Android MicBridge 前台服务仍在运行且端口与文档一致。
- iPhone 当前 Wi-Fi 与 Android 地址属于同一网段。
- 路由器未开启客户端隔离；若使用 API 36+ Android 热点，MicBridge 的 `TetheringManager` 监控注册成功且 UI 仍显示该热点地址。
- VPN、安全 DNS 或企业设备策略没有阻断私网访问。
- URL 没有跳转到登录门户、HTML 页面或 HTTPS。
- `X-MicBridge-Token` 没有前后空格，`X-Request-Id` 确实来自本次唯一的 `RequestId`。

## 7. 锁屏与熄屏真机验证

目标 iOS 若提供“锁定时允许运行”，该开关也只是准备项，不是端到端可靠性的证明。至少记录下面的测试；每个场景都同时观察快捷指令主动振动动作数、另记的 iPhone 系统触感、快捷指令错误提示、Android API 审计记录、Android 实际受管理状态和 ChatGPT Live 行为。

| 场景 | 操作 | 期望 | 结果 |
|---|---|---|---|
| 解锁、快捷指令前台 | 连续切换两次；每次生成不同 request ID | 首次主动振动动作 1 次且为已验证开放；第二次即使紧接到达也必须主动振动 2 次且为已验证屏蔽 | 待真机验证 |
| iPhone 锁屏、屏幕点亮 | 长按 Action Button | 无解锁提示；请求一次；自定义动作数与 Android 返回一致，系统触感另记 | 待真机验证 |
| iPhone 锁屏、屏幕熄灭 | 长按 Action Button | 同上 | 待真机验证 |
| iPhone 熄屏 10 分钟 | 长按 Action Button | 同上，记录首包延迟 | 待真机验证 |
| iPhone 低电量模式 | 锁屏长按 | 同上或明确记录限制 | 待真机验证 |
| iPhone 重启后尚未首次解锁 | 长按 | 不预设可用；记录是否要求首次解锁 | 待真机验证 |
| iPhone 重启并首次解锁后 | 再锁屏长按 | 应恢复运行 | 待真机验证 |
| Android 锁屏/熄屏 | iPhone 长按 | 服务仍收到一次请求并返回可验证结果 | 待真机验证 |
| Wi‑Fi/受支持 Android 热点断开或地址错误 | iPhone 长按 | x-callback 方案主动调用 3 次；基础方案允许系统错误/无自定义反馈，绝不解释为成功 | 待真机验证 |
| 错误令牌 | iPhone 长按 | 不改变状态；若错误 JSON 可供解析则主动调用 3 次，否则记录系统中止 | 待真机验证 |
| `verified=false` / `unknown` | 注入或触发控制器失败 | 主动调用 3 次，绝不调用 1 次或 2 次 | 待真机验证 |
| 响应请求 ID 不匹配 | 使用测试服务返回错误 ID | 主动调用 3 次 | 待真机验证 |
| 成功执行后响应丢失 | 在响应阶段断网 | 状态标记未知；不立即重按；Android 在 `auto_block_at` 尝试屏蔽，默认 30 秒硬窗口下通常约 20 秒开始 | 待真机验证 |
| ChatGPT Live 正在会话 | 开放→屏蔽→再次开放 | 屏蔽时无输入且会话保持；再次开放后恢复收音 | 待真机验证 |

测试表每一行都要分开记录：Action Button/系统触感、快捷指令主动执行“振动设备”的次数、以及通知声音/系统触感。默认关闭失败通知；协议的一/二/三次只按第二列判定，不能以身体感知到的总次数替代证据。

建议正常网络下交替切换至少 50 次，并核对每次 Android 审计日志只有一个新的 `request_id` 和一次状态转换。Android 不对不同 request ID 做时间防抖；同一 ID 的传输重试由持久幂等账本重放，两个不同 ID 则必须被当作两次真实按键并各自依据新鲜状态执行。

## 8. 必须理解的边界

- 这是 Toggle，不是按住说话：长按 Action Button 只是触发一次状态切换。
- 配置的硬安全窗口默认最多 30 秒，但 Android 会为 Root BLOCK 预留重试预算，当前通常约 20 秒就到 `auto_block_at`；不要把它理解为保证开放满 30 秒。若第一次按下开放后等到自动屏蔽，再按一次会从 Android 当前的 `blocked` 状态重新开放；“第二次一定屏蔽”只在自动屏蔽尚未发生且第一次结果已确认时成立。
- 请求 ID 去重能防止**同一个 ID 的网络重试**重复切换，并在 Android 应用数据中跨服务/系统重启保留。若服务器已切换但响应丢失，用户再次长按会生成另一个 ID；纯快捷指令且不持久化 pending ID 的约束下，无法把下一次独立按键识别成上一次重试。
- 快捷指令主动调用三次“振动设备”表示“失败或状态无法确认”，不表示“已经安全屏蔽”。Android 会尽力 fail closed，但读回无法确认时仍必须显示未知；系统额外触感不参与这项映射。
- MicBridge 不录音。它只控制 Android 的系统级麦克风访问；是否真的阻断并恢复 ChatGPT Live 必须用目标 Android、目标 ROM 和真实会话声学验证。

## 9. Apple 官方参考

- [Use and customize the Action button on iPhone](https://support.apple.com/guide/iphone/use-and-customize-the-action-button-iphe89d61d66/ios)
- [Request your first API in Shortcuts on iPhone or iPad](https://support.apple.com/guide/shortcuts/apd58d46713f/ios)
- [Use JSON with Shortcuts](https://support.apple.com/guide/shortcuts/apdf01294032/ios)
- [Use Repeat actions in Shortcuts](https://support.apple.com/guide/shortcuts/apdc11deb2c1/ios)
- [Adjust privacy settings in Shortcuts](https://support.apple.com/guide/shortcuts/apd961a4fc65/ios)
- [If an app would like to connect to devices on your local network](https://support.apple.com/102229)
- [Use x-callback-url with Shortcuts](https://support.apple.com/guide/shortcuts/apdcd7f20a6f/ios)
- [Change iPhone sounds and vibrations](https://support.apple.com/guide/iphone/iph07c867f28/ios)
