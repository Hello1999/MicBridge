# iPhone 快捷指令设置

完整的中文配置、错误反馈、`x-callback-url` 降级方案和锁屏验收步骤见 [IPHONE_SHORTCUT_ZH.md](IPHONE_SHORTCUT_ZH.md)。

此入口文件用于保持实施规格中的交付路径；唯一维护版本是上述中文教程。

## 3.5 Compact variant: use `haptic_pulses` (mirror of IPHONE_SHORTCUT_ZH.md §3.5)

Android returns an integer `haptic_pulses` in every JSON envelope except `/healthz`. The server
computes it from exactly the same `ok`/`verified`/`mic_access` rule as §3.3: `1` for a verified
`open`, `2` for a verified `blocked`, `3` for everything else. It replaces the four `Get Dictionary
Value` actions, the nested `If` actions and the manual `PulseCount` assignments of §3.3–3.4 with
roughly six fewer actions per run:

1. From `Response`, use `Get Dictionary Value` for `request_id` → `ResponseRequestId`, and for
   `haptic_pulses` → `ServerPulses`.
2. Add a `Number` action set to `3`, then `Set Variable` `PulseCount`. It stays the conservative
   default.
3. If `ResponseRequestId` **is** `RequestId`: if `ServerPulses` is a number equal to `1` or `2`,
   set `PulseCount` to `ServerPulses`; end if.
4. Add `Repeat` with `PulseCount` and a single `Vibrate Device` action inside the loop.

```text
ResponseRequestId = Get Dictionary Value request_id (from Response)
ServerPulses      = Get Dictionary Value haptic_pulses (from Response)

PulseCount = 3

If ResponseRequestId is RequestId
  If ServerPulses is a number equal to 1 or 2
    PulseCount = ServerPulses
  End If
End If

Repeat PulseCount times
  Vibrate Device
End Repeat
```

Boundaries that do not change:

- **Comparing the echoed request ID stays mandatory on the iPhone.** `haptic_pulses` does not
  replace that check; it only folds the `ok`, `verified` and `mic_access` checks into one integer.
- **The server's number may be trusted only when `ResponseRequestId` exactly equals this run's
  `RequestId`.** A mismatched ID means the body is not this request's result, so `PulseCount` must
  stay `3`.
- **A missing, empty, non-numeric or out-of-range field keeps `PulseCount = 3`.** Never feed an
  unknown value straight into `Repeat`.
- Three pulses still mean "failed or state cannot be confirmed", never "safely blocked". A
  network-layer abort may still produce no custom haptic at all; handle it as in §4.
- The variant saves roughly six actions per run and therefore shortens the gap between parsing and
  haptics slightly. It changes no decision criterion and removes no HTTP request — there is still
  exactly one `POST /v1/mic/toggle`.
- **The full §3.3 logic remains the reference behaviour.** Either variant is acceptable: §3.3
  recomputes everything on the iPhone, §3.5 reuses the server's pre-computed result, and both must
  produce the same 1/2/3 for the same response. The §7 acceptance tests expect identical results
  from either variant.

If §3.5 and §3.3 ever disagree on a real device, treat §3.3 as authoritative, handle the run as
"state unknown", and record the raw response for diagnosis.
