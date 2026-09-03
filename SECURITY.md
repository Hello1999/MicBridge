# Security Policy

MicBridge can execute Root commands and change the Android system microphone gate. Treat every build as security-sensitive and keep the microphone blocked whenever a result cannot be verified.

## Supported versions

`v0.2.0` is a device-test prerelease. Security fixes are currently made only on the latest `main` branch and latest prerelease. No version is considered production-validated until the physical-device matrix is complete.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting for this repository:

https://github.com/Hello1999/MicBridge/security/advisories/new

Do not put any of the following in a public issue:

- MicBridge bearer tokens or complete request headers;
- real device serials, stable device hashes, local IP addresses or build fingerprints;
- unredacted `docs/device-evidence` output;
- Root-manager logs that may contain private paths or identifiers.

Include the MicBridge commit, Android version, Root solution, controller ID, reproduction steps and a redacted log excerpt. Do not attach recorded audio.

If a token may have leaked, block the microphone, stop the service, use **重新生成** in the MicBridge UI, update the iPhone Shortcut, and do not reuse the old token.

## Scope

Reports about authentication bypass, request replay, fail-open behavior, lease/watchdog failure, unintended network exposure, unsafe Root command construction, or false verification are especially important. General ChatGPT service behavior and OEM limitations outside MicBridge's control are out of scope unless they create a MicBridge security invariant violation.
