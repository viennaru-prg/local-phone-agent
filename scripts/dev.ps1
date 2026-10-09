# Development helper for a phone connected over USB debugging.
#   .\scripts\dev.ps1 build                 # build debug APK
#   .\scripts\dev.ps1 install               # build + install
#   .\scripts\dev.ps1 push-model [file]     # copy a GGUF model to the phone (default: Qwen3-1.7B Q4_K_M)
#   .\scripts\dev.ps1 run "회사로 안내해줘"   # run a typed command (debug build)
#   .\scripts\dev.ps1 log                   # follow agent logs
#   .\scripts\dev.ps1 traces                # pull step traces to .\traces
#   .\scripts\dev.ps1 screen                # screenshot + UI dump to .\traces
#   .\scripts\dev.ps1 probe                 # device facts: users/profiles (Secure Folder), CPU, RAM
param([Parameter(Position = 0)][string]$cmd = 'help', [Parameter(Position = 1)][string]$arg = '')

$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = 'E:\AI\Apps\jdk-21'
$env:ANDROID_HOME = 'E:\AI\Apps\android-sdk'
$env:PYTHONUTF8 = '1'   # llama.cpp's OpenCL kernel embedding script reads UTF-8 files
$adb = 'E:\AI\Apps\android-sdk\platform-tools\adb.exe'
# Target the USB phone, never an emulator that may also be attached.
if (-not $env:ANDROID_SERIAL) {
    $env:ANDROID_SERIAL = (& $adb devices | Select-String '^(\S+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value } |
        Where-Object { $_ -notlike 'emulator-*' } | Select-Object -First 1)
}
$pkg = 'dev.localphone.agent'
$remoteModels = "/sdcard/Android/data/$pkg/files/models"
$apk = "$root\app\build\outputs\apk\debug\app-debug.apk"

function Build { Push-Location $root; & .\gradlew.bat :app:assembleDebug --console=plain -q; $ok = $LASTEXITCODE -eq 0; Pop-Location; if (-not $ok) { throw 'build failed' } }

switch ($cmd) {
    'build' { Build }
    'install' { Build; & $adb install -r -g $apk }
    'push-model' {
        $file = if ($arg) { $arg } else { 'E:\AI\Models\phone-agent\Qwen3-1.7B-Q4_K_M.gguf' }
        & $adb shell mkdir -p $remoteModels
        & $adb push $file "$remoteModels/"
    }
    'run' {
        $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($arg))
        & $adb shell am start -n "$pkg/.VoiceActivity" --es goal_b64 $b64
    }
    'eval' {
        # .\scripts\dev.ps1 eval Qwen3-1.7B-Q4_0.gguf   (decision accuracy on assets/eval-cases.json)
        $gpu = if ($env:AGENT_GPU) { $env:AGENT_GPU } else { 'true' }
        & $adb logcat -c
        & $adb shell am start -n "$pkg/.SettingsActivity" --es eval $arg --ez gpu $gpu --ez note $(if ($env:AGENT_NOTE) { $env:AGENT_NOTE } else { 'true' }) --ez think $(if ($env:AGENT_THINK) { $env:AGENT_THINK } else { 'false' }) --ei think_chars $(if ($env:AGENT_THINK_CHARS) { $env:AGENT_THINK_CHARS } else { 160 }) | Out-Null
        $deadline = (Get-Date).AddSeconds(600)
        do { Start-Sleep 3; $log = @(& $adb logcat -d -s AgentEval) } until (@($log | Where-Object { $_ -match 'SUMMARY' }).Count -gt 0 -or (Get-Date) -gt $deadline)
        $log | Where-Object { $_ -match 'AgentEval' } | ForEach-Object { $_.Substring($_.IndexOf('AgentEval') + 11) }
    }
    'log' { & $adb logcat -v time -s AgentStep AgentLlm AgentLlama AgentA11y AgentService AndroidRuntime }
    'traces' {
        New-Item -ItemType Directory -Force "$root\traces" | Out-Null
        & $adb pull "/sdcard/Android/data/$pkg/files/traces/." "$root\traces"
    }
    'screen' {
        New-Item -ItemType Directory -Force "$root\traces" | Out-Null
        $stamp = Get-Date -Format 'HHmmss'
        & $adb exec-out screencap -p > "$root\traces\screen-$stamp.png"
        & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
        & $adb pull /sdcard/ui.xml "$root\traces\ui-$stamp.xml" | Out-Null
        "saved traces\screen-$stamp.png, traces\ui-$stamp.xml"
    }
    'probe' {
        '== device'; & $adb shell getprop ro.product.model; & $adb shell getprop ro.build.version.release
        '== users / profiles'; & $adb shell pm list users
        '== cpu'; & $adb shell "cat /proc/cpuinfo | grep -m1 Features"
        '== memory'; & $adb shell "cat /proc/meminfo | head -3"
        '== on-device speech'; & $adb shell cmd package query-services -a android.speech.RecognitionService | Select-String 'packageName' | Select-Object -First 5
    }
    default { Get-Content $PSCommandPath | Select-Object -Skip 1 -First 9 }
}
