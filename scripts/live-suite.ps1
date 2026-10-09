# Runs safe, reversible commands on the connected phone and summarizes each run.
#   .\scripts\live-suite.ps1 [-Only "유튜브"]  (substring filter on the goal)
param([string]$Only = '', [switch]$New)
$ErrorActionPreference = 'Continue'
$adb = 'E:\AI\Apps\android-sdk\platform-tools\adb.exe'
if (-not $env:ANDROID_SERIAL) {
    $devices = @(& $adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($devices.Count -ne 1) { throw 'Set ANDROID_SERIAL explicitly when multiple devices are connected.' }
    $env:ANDROID_SERIAL = $devices[0]
}
$pkg = 'dev.localphone.agent'

# Nothing here sends, deletes, buys, changes a setting value or starts real navigation.
$goals = @(
    '켰어',
    '카카오톡 열어줘',
    '유튜브에서 고양이 검색해줘',
    '네이버 지도에서 카페 검색해줘',
    '설정에서 배터리 화면 열어줘',
    '시계 앱에서 타이머 화면 열어줘',
    'ClipStream 열어줘',
    '음악 재생해줘',
    '음악 일시정지해줘',
    # added in iteration 5 to avoid tuning for a fixed list
    '크롬에서 오늘 날씨 검색해줘',
    'Play 스토어에서 카카오맵 검색해줘',
    '설정에서 블루투스 화면 열어줘',
    '갤러리 열어줘',
    '시계 앱에서 알람 화면 보여줘'
)
# Iteration 8+: commands never used while tuning, to check that fixes generalize.
$newGoals = @(
    '네이버 지도에서 근처 주유소 찾아줘',
    '설정에서 디스플레이 화면 열어줘',
    '설정에서 와이파이 화면 열어줘',
    '시계 앱에서 스톱워치 화면 열어줘',
    '계산기 열어줘',
    '캘린더 열어줘',
    '알람 화면 열어줘',
    '유튜브에서 아이유 노래 검색해줘',
    '크롬에서 환율 검색해줘',
    '설정에서 소리 및 진동 화면 열어줘'
)
if ($New) { $goals = $newGoals }
$goals = $goals | Where-Object { $_ -like "*$Only*" }

& $adb shell am start -n "$pkg/.SettingsActivity" --ez test_mode true | Out-Null
Start-Sleep 2
$summary = @()
foreach ($goal in $goals) {
    & $adb shell input keyevent KEYCODE_HOME
    Start-Sleep 2
    & $adb logcat -c
    $t0 = Get-Date
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($goal))
    & $adb shell am start -n "$pkg/.VoiceActivity" --es goal_b64 $b64 | Out-Null
    $deadline = (Get-Date).AddSeconds(150)
    do { Start-Sleep 3; $log = @(& $adb logcat -d -s AgentStep | Where-Object { $_ -match 'AgentStep' }) } until (@($log | Where-Object { $_ -match 'RESULT' }).Count -gt 0 -or (Get-Date) -gt $deadline)
    $secs = [int]((Get-Date) - $t0).TotalSeconds
    $result = ($log | Where-Object { $_ -match 'RESULT' } | Select-Object -Last 1)
    $result = if ($result) { $result.Substring($result.IndexOf('RESULT')) } else { 'TIMEOUT' }
    "`n### $goal  ($secs s)"
    $log | Where-Object { $_ -notmatch 'RESULT' } | ForEach-Object { '  ' + $_.Substring($_.IndexOf('AgentStep') + 11) }
    "  => $result"
    $summary += [pscustomobject]@{ goal = $goal; secs = $secs; steps = @($log | Where-Object { $_ -match '\] ' }).Count; result = $result }
    Start-Sleep 4 # let TTS finish
}
& $adb shell input keyevent KEYCODE_HOME
"`n===== SUMMARY"
$summary | Format-Table -AutoSize | Out-String -Width 200
