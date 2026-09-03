[CmdletBinding()]
param(
    [string]$Serial,
    [string]$ApkPath,
    [string]$TargetPackage = 'com.openai.chatgpt',
    [switch]$Install,
    [string]$EvidenceDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw 'adb is not available on PATH.'
}
if ($TargetPackage -notmatch '^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$') {
    throw 'TargetPackage has an invalid format.'
}

function Invoke-HostAdb {
    param(
        [Parameter(Mandatory)] [string[]]$Arguments,
        [switch]$AllowFailure
    )
    $output = (& adb @Arguments 2>&1 | Out-String).Trim()
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        $displayArguments = $Arguments
        $displayOutput = $output
        if ($Arguments.Count -ge 2 -and $Arguments[0] -eq '-s') {
            $displayArguments = @('-s', '<device>')
            if ($Arguments.Count -gt 2) {
                $displayArguments += $Arguments[2..($Arguments.Count - 1)]
            }
            $displayOutput = $displayOutput.Replace($Arguments[1], '<device>')
        }
        throw "adb $($displayArguments -join ' ') failed with exit code $exitCode`n$displayOutput"
    }
    [pscustomobject]@{ Output = $output; ExitCode = $exitCode }
}

function Invoke-DeviceAdb {
    param(
        [Parameter(Mandatory)] [string[]]$Arguments,
        [switch]$AllowFailure
    )
    $result = Invoke-HostAdb `
        -Arguments (@('-s', $script:SelectedSerial) + $Arguments) `
        -AllowFailure
    $safeOutput = $result.Output.Replace($script:SelectedSerial, '<device>')
    if (-not $AllowFailure -and $result.ExitCode -ne 0) {
        throw "adb -s <device> $($Arguments -join ' ') failed with exit code $($result.ExitCode)`n$safeOutput"
    }
    [pscustomobject]@{ Output = $safeOutput; ExitCode = $result.ExitCode }
}

function Get-Property {
    param([Parameter(Mandatory)] [string]$Name)
    (Invoke-DeviceAdb -Arguments @('shell', 'getprop', $Name)).Output.Trim()
}

function Get-ShortHash {
    param([Parameter(Mandatory)] [string]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    $digest = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    (-join ($digest | ForEach-Object { $_.ToString('x2') })).Substring(0, 12)
}

$adbInventory = (Invoke-HostAdb -Arguments @('devices', '-l')).Output
$connected = @()
foreach ($line in ($adbInventory -split "`r?`n")) {
    if ($line -match '^(\S+)\s+device\b(.*)$') {
        $connected += [pscustomobject]@{
            Serial = $Matches[1]
            Details = $Matches[2].Trim()
        }
    }
}

if ($Serial) {
    $selected = @($connected | Where-Object { $_.Serial -eq $Serial })
    if ($selected.Count -ne 1) {
        throw 'The specified device is not exactly one connected adb device.'
    }
} else {
    $selected = @(
        $connected | Where-Object {
            $_.Serial -notmatch '^emulator-' -and $_.Details -notmatch '\bmodel:sdk_'
        }
    )
    if ($selected.Count -ne 1) {
        throw "Exactly one physical Android adb device is required; candidates: $($selected.Count)."
    }
}

$script:SelectedSerial = $selected[0].Serial
$qemu = Get-Property -Name 'ro.kernel.qemu'
if ($qemu -eq '1' -or $script:SelectedSerial -match '^emulator-') {
    throw 'Refusing to generate physical-device evidence on an emulator.'
}

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$actualApkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedApk).Hash
$shaFile = Join-Path $PSScriptRoot '..\SHA256SUMS.txt'
if (-not (Test-Path -LiteralPath $shaFile -PathType Leaf)) {
    throw 'SHA256SUMS.txt is missing; the frozen APK cannot be authenticated.'
}
$shaFields = (Get-Content -Raw -LiteralPath $shaFile).Trim() -split '\s+'
if ($shaFields.Count -lt 1 -or $shaFields[0] -notmatch '^[A-Fa-f0-9]{64}$') {
    throw 'SHA256SUMS.txt does not begin with a valid SHA-256 value.'
}
$expectedApkHash = $shaFields[0].ToUpperInvariant()
if ($actualApkHash -ne $expectedApkHash) {
    throw "APK SHA-256 does not match SHA256SUMS.txt: $actualApkHash"
}

if ($Install) {
    $installResult = Invoke-DeviceAdb -Arguments @('install', '-r', $resolvedApk)
    if ($installResult.Output -notmatch '(?m)^Success$') {
        throw "The frozen APK installation could not be confirmed: $($installResult.Output)"
    }
}

$rootProbe = Invoke-DeviceAdb -Arguments @('shell', 'su', '-c', 'id') -AllowFailure
$rootVerified = $rootProbe.ExitCode -eq 0 -and $rootProbe.Output -match '\buid=0\b'
$currentUserResult = Invoke-DeviceAdb -Arguments @('shell', 'am', 'get-current-user') -AllowFailure
$currentUser = $currentUserResult.Output.Trim()
$currentUserConfirmed = $currentUserResult.ExitCode -eq 0 -and $currentUser -match '^\d+$'
$targetPath = if ($currentUserConfirmed) {
    Invoke-DeviceAdb -Arguments @('shell', 'pm', 'path', '--user', $currentUser, $TargetPackage) -AllowFailure
} else {
    [pscustomobject]@{ Output = ''; ExitCode = -1 }
}
$targetInstalled = $targetPath.ExitCode -eq 0 -and $targetPath.Output -match '^package:'
$targetDump = if ($targetInstalled) {
    (Invoke-DeviceAdb -Arguments @('shell', 'dumpsys', 'package', $TargetPackage) -AllowFailure).Output
} else {
    ''
}
$targetVersion = [regex]::Match($targetDump, '(?m)^\s*versionName=(.+)$').Groups[1].Value.Trim()
$targetVersionCode = [regex]::Match($targetDump, '(?m)^\s*versionCode=(\d+)').Groups[1].Value
$recordAudioReadback = if ($targetInstalled -and $currentUserConfirmed) {
    $permissionResult = Invoke-DeviceAdb `
        -Arguments @(
            'shell',
            'dumpsys',
            'package',
            'check-permission',
            'android.permission.RECORD_AUDIO',
            $TargetPackage,
            $currentUser
        ) `
        -AllowFailure
    $permissionValue = $permissionResult.Output.Trim()
    $permissionConfirmed = `
        $permissionResult.ExitCode -eq 0 -and $permissionValue -match '^(?:0|-1)$'
    [pscustomobject]@{
        Confirmed = $permissionConfirmed
        Granted = $permissionConfirmed -and $permissionValue -eq '0'
        RawValue = if ($permissionConfirmed) { $permissionValue } else { $null }
    }
} else {
    [pscustomobject]@{ Confirmed = $false; Granted = $false; RawValue = $null }
}
$targetRecordAudioGranted = $recordAudioReadback.Confirmed -and $recordAudioReadback.Granted

$sensorResult = Invoke-DeviceAdb -Arguments @('shell', 'dumpsys', 'sensor_privacy') -AllowFailure
$sensorDump = $sensorResult.Output
$sensorSummary = @(
    $sensorDump -split "`r?`n" |
        Where-Object { $_ -match '(?i)microphone|sensor.?privacy|toggle' } |
        Select-Object -First 80
) -join "`n"
$audioResult = Invoke-DeviceAdb -Arguments @('shell', 'dumpsys', 'audio') -AllowFailure
$audioDump = $audioResult.Output
$audioSummary = @(
    $audioDump -split "`r?`n" |
        Where-Object { $_ -match '(?i)microphone|mic mute|micmute' } |
        Select-Object -First 40
) -join "`n"
$appOpsResult = if ($rootVerified -and $currentUserConfirmed -and $targetInstalled) {
    $remoteCommand = "cmd appops get --user $currentUser $TargetPackage RECORD_AUDIO"
    Invoke-DeviceAdb -Arguments @('shell', 'su', '-c', $remoteCommand) -AllowFailure
} else {
    [pscustomobject]@{ Output = 'NOT_RUN'; ExitCode = -1 }
}
$appOpsSummary = $appOpsResult.Output

$facts = [ordered]@{
    schema = 1
    captured_at_utc = [DateTime]::UtcNow.ToString('o')
    device_id_hash = Get-ShortHash -Value $script:SelectedSerial
    manufacturer = Get-Property -Name 'ro.product.manufacturer'
    model = Get-Property -Name 'ro.product.model'
    android_release = Get-Property -Name 'ro.build.version.release'
    sdk = Get-Property -Name 'ro.build.version.sdk'
    build_fingerprint = Get-Property -Name 'ro.build.fingerprint'
    current_user = if ($currentUserConfirmed) { $currentUser } else { $null }
    current_user_confirmed = $currentUserConfirmed
    root_verified = $rootVerified
    root_probe = if ($rootVerified) { 'uid=0' } else { 'FAILED_OR_UNCONFIRMED' }
    target_package = $TargetPackage
    target_installed = $targetInstalled
    target_version_name = if ($targetVersion) { $targetVersion } else { $null }
    target_version_code = if ($targetVersionCode) { $targetVersionCode } else { $null }
    target_record_audio_grant_confirmed = $recordAudioReadback.Confirmed
    target_record_audio_granted = if ($recordAudioReadback.Confirmed) {
        $recordAudioReadback.Granted
    } else {
        $null
    }
    apk_path = $resolvedApk
    apk_sha256 = $actualApkHash
    apk_installed_by_this_run = [bool]$Install
    appops_record_audio_readback = $appOpsSummary
    appops_record_audio_readback_exit_code = $appOpsResult.ExitCode
    appops_record_audio_readback_capture_succeeded = `
        $appOpsResult.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($appOpsSummary)
    sensor_privacy_readback_excerpt = $sensorSummary
    sensor_privacy_readback_exit_code = $sensorResult.ExitCode
    sensor_privacy_readback_capture_succeeded = `
        $sensorResult.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($sensorSummary)
    audio_manager_readback_excerpt = $audioSummary
    audio_manager_readback_exit_code = $audioResult.ExitCode
    audio_manager_readback_capture_succeeded = `
        $audioResult.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($audioSummary)
    acoustic_tests = 'NOT_RUN_MANUAL_CONFIRMATION_REQUIRED'
    iphone_action_button_tests = 'NOT_RUN_IPHONE_REQUIRED'
}

$json = $facts | ConvertTo-Json -Depth 5
if ($EvidenceDirectory) {
    $evidenceRoot = if ([IO.Path]::IsPathRooted($EvidenceDirectory)) {
        [IO.Path]::GetFullPath($EvidenceDirectory)
    } else {
        [IO.Path]::GetFullPath((Join-Path (Get-Location).Path $EvidenceDirectory))
    }
    New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
    $name = 'android-preflight-{0}-{1}-{2}.json' -f `
        ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')), `
        $facts.device_id_hash, `
        ([Guid]::NewGuid().ToString('N').Substring(0, 8))
    $outputPath = Join-Path $evidenceRoot $name
    $utf8WithoutBom = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($outputPath, $json + [Environment]::NewLine, $utf8WithoutBom)
    Write-Output "Evidence: $outputPath"
}
Write-Output $json

if (-not $rootVerified) {
    throw 'Root could not be verified with su -c id; controller acceptance must not continue.'
}
if (-not $targetInstalled -or -not $targetRecordAudioGranted) {
    throw 'The target ChatGPT package or its RECORD_AUDIO grant is unconfirmed; acoustic calibration must not continue.'
}
