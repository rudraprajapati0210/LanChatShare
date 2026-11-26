param(
    [string]$Sender = 'emulator-5554',
    [string]$Receiver = 'emulator-5556',
    [string]$Sizes = '10,30,100,500,1000',
    [ValidateSet(262144,524288,1048576,2097152,4194304)][int]$WindowBytes = 262144,
    [ValidateSet(1,2,3,4)][int]$Streams = 1,
    [switch]$Ceiling,
    [string]$Output = (Join-Path $env:TEMP ('lcs-benchmark-' + (Get-Date -Format yyyyMMdd-HHmmss)))
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$apk = Join-Path $PSScriptRoot '../core/connectivity/build/outputs/apk/androidTest/debug/connectivity-debug-androidTest.apk'
$runner = 'dev.lanchatshare.connectivity.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'dev.lanchatshare.connectivity.TwoDeviceBenchmark'
New-Item -ItemType Directory -Path $Output -Force | Out-Null
$pins = @{}
$starts = @{}
foreach ($device in $Sender,$Receiver) {
    & $adb -s $device install -r -t $apk
    if ($LASTEXITCODE) { throw "Install failed on $device" }
    $starts[$device] = [long](& $adb -s $device shell date +%s)
    & $adb -s $device shell am instrument -w -e class $testClass -e role pin $runner | Out-Null
    $line = & $adb -s $device logcat -d -s LcsBench:I | Select-String 'PIN (\S+)' | Select-Object -Last 1
    $pins[$device] = $line.Matches[0].Groups[1].Value
}
if ($pins[$Sender] -eq $pins[$Receiver]) { throw 'Separate Keystore identities required' }
$route = (& $adb -s $Sender shell ip -4 -br addr show wlan0) -join ' '
if ($route -notmatch '(\d+\.\d+\.\d+\.\d+)/') { throw 'Wi-Fi IPv4 address unavailable' }
$address = $Matches[1]
$receiverRoute = (& $adb -s $Receiver shell ip -4 -br addr show wlan0) -join ' '
if ($receiverRoute -notmatch '(\d+\.\d+\.\d+\.\d+)/') { throw 'Receiver Wi-Fi IPv4 address unavailable' }
$receiverAddress = $Matches[1]
$probe = $Ceiling.IsPresent.ToString().ToLowerInvariant()
foreach ($device in $Sender,$Receiver) { & $adb -s $device shell pm grant dev.lanchatshare.connectivity.test android.permission.ACCESS_LOCAL_NETWORK }
function Read-Trace($device) {
    & $adb -s $device logcat -v epoch -d -s LcsBench:I | Where-Object {
        $_ -match '^\s*(\d+)\.' -and [long]$Matches[1] -ge $starts[$device]
    }
}
try {
    $server = Start-Process $adb -WindowStyle Hidden -PassThru -ArgumentList @('-s',$Sender,'shell','am','instrument','-w','-e','class',$testClass,'-e','ceiling',$probe,'-e','remote',$receiverAddress,'-e','role','send','-e','pin',$pins[$Receiver],'-e','sizes',$Sizes,'-e','host',$address,$runner) -RedirectStandardOutput (Join-Path $Output 'sender.txt')
    for ($i=0; $i -lt 90; $i++) {
        if ((Read-Trace $Sender) -match 'READY') { break }
        if ($server.HasExited) { throw 'Sender stopped before listener started' }
        Start-Sleep 1
    }
    & $adb -s $Receiver shell am instrument -w -e class $testClass -e role receive -e ceiling $probe -e remote $address -e pin $pins[$Sender] -e sizes $Sizes -e windowBytes $WindowBytes -e streams $Streams -e host $address $runner | Tee-Object (Join-Path $Output 'receiver.txt')
    if (!(Get-Content (Join-Path $Output 'receiver.txt') | Select-String 'OK \(1 test\)')) { throw 'Receiver failed; see traces' }
    if (!$server.WaitForExit(15000)) { throw 'Sender missing final receipt' }
} finally {
    foreach ($device in $Sender,$Receiver) { Read-Trace $device | Set-Content (Join-Path $Output "$device.log") }
    Write-Host "Saved both device traces to $Output"
    if ($server -and !$server.HasExited) { & $adb -s $Sender shell am force-stop dev.lanchatshare.connectivity.test }
}
