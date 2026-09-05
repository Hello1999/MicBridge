# 预制 iPhone 快捷指令文件

`MicBridgeWorker.shortcut` 由 [`build_micbridge_shortcut.py`](build_micbridge_shortcut.py) 生成，对应
[docs/IPHONE_SHORTCUT_ZH.md](../../docs/IPHONE_SHORTCUT_ZH.md) 第 3 节的 `MicBridgeWorker`。

## 状态：未签名、未真机验证

必须先读完这两条限制：

1. **文件未签名。** Apple 从 iOS 15 起对 `.shortcut` 文件使用签名容器。本文件是未签名的 plist，
   导入可能需要在 iPhone 上打开「设置 → 快捷指令 → 允许不受信任的快捷指令」（该开关要先手动运行过
   一次任意快捷指令才会出现），并且**部分 iOS 版本可能直接拒绝导入**。如果你有 Mac，可先签名：

   ```bash
   shortcuts sign -m anyone -i MicBridgeWorker.shortcut -o MicBridgeWorker-signed.shortcut
   ```

   本仓库的构建主机是 Windows，无法执行签名。
2. **未在 iPhone 上运行过。** 动作标识符与参数结构按 Shortcuts plist 格式手工构造，本机没有
   iOS 设备可以验证导入结果。导入后必须逐个动作对照下面的清单核对，并按
   docs/IPHONE_SHORTCUT_ZH.md 第 7 节做真机验证。导入成功不等于行为正确。

如果导入失败或动作显示异常，回退到 docs/IPHONE_SHORTCUT_ZH.md 的手工搭建步骤——那条路径是有效的
基准，不依赖本文件。

## 导入后必须做的两件事

1. **填入令牌。** 文件里的 `X-MicBridge-Token` 是占位符 `REPLACE_WITH_MICBRIDGE_TOKEN`。
   在「获取 URL 内容」动作里把它替换成 Android MicBridge 设置页显示的当前令牌。占位符是有意的：
   令牌是敏感值，不应写进会被复制、备份或分享的文件。
2. **核对地址。** URL 动作默认写入 `http://192.168.5.16:8787/v1/mic/toggle`。以 Android UI 当前
   显示的实际监听地址为准，不要沿用示例。地址变化时可以重新生成：

   ```bash
   python build_micbridge_shortcut.py --url http://<ANDROID_IP>:8787/v1/mic/toggle --out MicBridgeWorker.shortcut
   ```

## 动作结构（47 个动作）

| 段 | 动作 | 作用 |
|---|---|---|
| 1 | 当前日期 → 格式化日期 `yyyyMMddHHmmssSSS` → 随机数 → 文本 → 设定 `RequestId` | 每次运行生成一个唯一请求 ID，全程复用 |
| 2 | URL → 获取 URL 内容（POST + 三个请求头）→ 设定 `Response` | 本次运行唯一的 HTTP 控制请求 |
| 3 | 四个「获取词典值」→ `ResponseOk`/`ResponseVerified`/`ResponseState`/`ResponseRequestId` | 严格取字段，不做存在性推断 |
| 4 | 文本 → `ResultKey`；四个文本 → `ExpectedOpenA/B`、`ExpectedBlockedA/B` | 把四项判据拼成一个可比较的复合键 |
| 5 | `PulseCount = 3`；四个平铺的「如果」把它改成 2 或 1 | 保守默认 3，只有全部判据满足才降到 1/2 |
| 6 | 重复 `PulseCount` 次 → 振动设备 | 恰好主动调用 1/2/3 次 |

## 与文档第 3 节的一处实现差异

文档描述的是四层嵌套「如果」。本文件改用**一个复合键 + 四个平铺的「如果」**，判据完全相同：

```text
ResultKey       = ResponseRequestId | ResponseOk | ResponseVerified | ResponseState
ExpectedOpenA   = RequestId | true  | true  | open
ExpectedOpenB   = RequestId | 1     | 1     | open
ExpectedBlockedA= RequestId | true  | true  | blocked
ExpectedBlockedB= RequestId | 1     | 1     | blocked
```

只有请求 ID 回显一致、`ok` 与 `verified` 均为真、且 `mic_access` 恰为 `open`/`blocked` 时，复合键
才会命中某个期望值。请求 ID 不匹配、任一字段缺失、`unknown`、`ok=false` 或 `verified=false` 都
命不中，`PulseCount` 保持 3。

改用平铺结构有两个原因：嵌套条件在 plist 里靠 `GroupingIdentifier` 配对，手写时更容易出错；平铺
结构在快捷指令编辑器里也更容易逐条肉眼核对。

`A`/`B` 两个变体是因为 Shortcuts 把 JSON 布尔值渲染成文本时，不同 iOS 版本可能得到 `true`/`false`
或 `1`/`0`。服务端始终发送标准 JSON 布尔（见 `Json.kt`），两种渲染都接受可以避免版本差异导致
成功响应被误判成 3 次振动。

## 未包含的内容

- 不包含 `MicBridgeFail` / `MicBridgeAction`（docs 第 4.2 节的 `x-callback-url` 方案）。该方案在文档中
  就标注为实验性、未验证，需要时可以另外生成。
- 不包含失败通知。按文档 3.4 节，默认配置到「重复」结束即停止，以免通知的系统声音/触感破坏可数的
  1/2/3 边界。
- 不写入任何麦克风状态。iPhone 端不保存、不推测、不反转状态。
