param(
    [string]$Serial = "807eb598",
    [string]$SampleDir = ".\sample-photos2",
    [string]$DeviceDir = "/sdcard/Android/data/com.parcelrecode.app/files/sample-photos2",
    [string]$OutputDir = ".\sample-photos\exports\duration-run-continuous",
    [int]$SettleSeconds = 4,
    [switch]$NoInitialReset
)

$ErrorActionPreference = "Stop"

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$package = "com.parcelrecode.app"
$activity = "$package/.MainActivity"
$extra = "com.parcelrecode.app.DEBUG_PHOTO_PATH"

$files = Get-ChildItem $SampleDir -File |
    Where-Object { $_.Extension -match "^\.(jpg|jpeg|png)$" } |
    Sort-Object Name

if ($files.Count -eq 0) {
    throw "No sample photos found in $SampleDir"
}

function Invoke-AdbShell {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $adb -s $Serial shell @Args
}

function Get-Node([string]$Xml, [string]$ResourceId) {
    return [regex]::Match($Xml, '<node[^>]*resource-id="' + [regex]::Escape($ResourceId) + '"[^>]*>')
}

function Get-NodeText([System.Text.RegularExpressions.Match]$Node) {
    if (-not $Node.Success) { return "" }
    $textMatch = [regex]::Match($Node.Value, 'text="([^"]*)"')
    if ($textMatch.Success) { return $textMatch.Groups[1].Value }
    return ""
}

function Get-NodeBounds([System.Text.RegularExpressions.Match]$Node) {
    if (-not $Node.Success) { return $null }
    $boundsMatch = [regex]::Match($Node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $boundsMatch.Success) { return $null }
    return [pscustomobject]@{
        Left = [int]$boundsMatch.Groups[1].Value
        Top = [int]$boundsMatch.Groups[2].Value
        Right = [int]$boundsMatch.Groups[3].Value
        Bottom = [int]$boundsMatch.Groups[4].Value
        X = [int](([int]$boundsMatch.Groups[1].Value + [int]$boundsMatch.Groups[3].Value) / 2)
        Y = [int](([int]$boundsMatch.Groups[2].Value + [int]$boundsMatch.Groups[4].Value) / 2)
    }
}

function Get-UiXml {
    Invoke-AdbShell uiautomator dump /sdcard/parcel_duration_window.xml | Out-Null
    return (Invoke-AdbShell cat /sdcard/parcel_duration_window.xml) -join "`n"
}

function Get-Focus {
    $focus = (& $adb -s $Serial shell dumpsys window | Select-String -Pattern "mCurrentFocus" | Select-Object -First 1)
    if ($focus) { return $focus.ToString().Trim() }
    return ""
}

function Scroll-Down {
    Invoke-AdbShell input swipe 360 1180 360 320 500 | Out-Null
    Start-Sleep -Milliseconds 300
}

function Scroll-Up {
    Invoke-AdbShell input swipe 360 320 360 1180 500 | Out-Null
    Start-Sleep -Milliseconds 300
}

function Find-VisibleNode {
    param(
        [string]$ResourceId,
        [int]$MaxDownSwipes = 5
    )
    $xml = Get-UiXml
    $node = Get-Node $xml $ResourceId
    for ($attempt = 0; -not $node.Success -and $attempt -lt $MaxDownSwipes; $attempt++) {
        Scroll-Down
        $xml = Get-UiXml
        $node = Get-Node $xml $ResourceId
    }
    return [pscustomobject]@{ Xml = $xml; Node = $node }
}

function Tap-Resource {
    param(
        [string]$ResourceId,
        [int]$MaxDownSwipes = 5
    )
    $found = Find-VisibleNode -ResourceId $ResourceId -MaxDownSwipes $MaxDownSwipes
    $bounds = Get-NodeBounds $found.Node
    if (-not $bounds) { return $false }
    Invoke-AdbShell input tap $bounds.X $bounds.Y | Out-Null
    return $true
}

function Start-DebugPhoto {
    param([string]$FileName)
    Invoke-AdbShell am start --activity-single-top -n $activity --es $extra "$DeviceDir/$FileName" | Out-Null
}

function Read-4x6State {
    $found = Find-VisibleNode -ResourceId "$package`:id/topSlotPayloadText" -MaxDownSwipes 5
    $xml = $found.Xml
    $status = Get-NodeText (Get-Node $xml "$package`:id/statusText")
    $topText = Get-NodeText (Get-Node $xml "$package`:id/topSlotPayloadText")
    $bottomText = Get-NodeText (Get-Node $xml "$package`:id/bottomSlotPayloadText")
    $printNode = Get-Node $xml "$package`:id/printButton"

    if (-not $printNode.Success) {
        $printFound = Find-VisibleNode -ResourceId "$package`:id/printButton" -MaxDownSwipes 4
        $printNode = $printFound.Node
        $xml = $printFound.Xml
    }

    $printEnabled = $printNode.Success -and $printNode.Value -match 'enabled="true"'
    $topHasPayload = $topText -match "^[A-Z0-9]{8,}$"
    $bottomHasPayload = $bottomText -match "^[A-Z0-9]{8,}$"
    return [pscustomobject]@{
        Status = $status
        TopPayload = $topText
        BottomPayload = $bottomText
        PrintEnabled = $printEnabled
        Ready = $printEnabled -and $topHasPayload -and $bottomHasPayload
        ErrorStatus = ($status -match "No supported") -or ($status -match "Could not")
        Focus = Get-Focus
    }
}

function Simulate-PrintAndReturn {
    $printed = Tap-Resource -ResourceId "$package`:id/printButton" -MaxDownSwipes 6
    if (-not $printed) { return "print_button_not_visible" }

    Start-Sleep -Seconds 2
    $focus = Get-Focus
    if ($focus -match "printspooler|PrintActivity") {
        Invoke-AdbShell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 1
    }

    $focus = Get-Focus
    if ($focus -notmatch [regex]::Escape($package)) {
        Invoke-AdbShell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 1
    }
    return "print_preview_opened_and_returned"
}

function Clear-4x6SlotsForRecovery {
    $cleared = Tap-Resource -ResourceId "$package`:id/clear4x6SlotsButton" -MaxDownSwipes 6
    if ($cleared) {
        Start-Sleep -Milliseconds 500
        return "clear_button"
    }
    return "clear_button_not_visible"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$csvPath = Join-Path $OutputDir "duration-run-results.csv"
$summaryPath = Join-Path $OutputDir "duration-run-summary.json"
$logPath = Join-Path $OutputDir "duration-run-logcat.txt"
Remove-Item -LiteralPath $csvPath, $summaryPath, $logPath -Force -ErrorAction SilentlyContinue

if (-not $NoInitialReset) {
    Invoke-AdbShell am force-stop $package | Out-Null
}
Invoke-AdbShell logcat -c | Out-Null

$results = New-Object System.Collections.Generic.List[object]
$totalPairs = [math]::Ceiling($files.Count / 2)
$firstName = $files[0].Name
$startedAt = Get-Date

for ($i = 0; $i -lt $files.Count; $i += 2) {
    $pairIndex = [int]($i / 2) + 1
    $a = $files[$i].Name
    $b = if ($i + 1 -lt $files.Count) { $files[$i + 1].Name } else { $firstName }
    $repeatSecond = ($i + 1 -ge $files.Count)

    Start-DebugPhoto $a
    Start-Sleep -Seconds $SettleSeconds
    Start-DebugPhoto $b
    Start-Sleep -Seconds $SettleSeconds

    $state = Read-4x6State
    $action = ""
    if ($state.Ready) {
        $action = Simulate-PrintAndReturn
    } else {
        $action = Clear-4x6SlotsForRecovery
    }

    $row = [pscustomobject]@{
        Pair = $pairIndex
        First = $a
        Second = $b
        SecondRepeated = $repeatSecond
        Ready = $state.Ready
        ErrorStatus = $state.ErrorStatus
        Status = $state.Status
        TopPayload = $state.TopPayload
        BottomPayload = $state.BottomPayload
        PrintEnabled = $state.PrintEnabled
        Action = $action
        Focus = Get-Focus
    }
    $results.Add($row)
    $row | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $csvPath -Append

    if (($pairIndex % 10) -eq 0 -or -not $state.Ready) {
        Write-Host ("Pair {0}/{1}: ready={2} action={3} top='{4}' bottom='{5}' first={6} second={7}" -f
            $pairIndex, $totalPairs, $state.Ready, $action, $state.TopPayload, $state.BottomPayload, $a, $b)
    }
}

& $adb -s $Serial logcat -d > $logPath

$failures = @($results | Where-Object { -not $_.Ready -or $_.ErrorStatus -or -not ($_.Focus -match "com\.parcelrecode\.app") })
$fatalMatches = Select-String -Path $logPath -Pattern "FATAL EXCEPTION|AndroidRuntime: FATAL|Fatal signal|JNI DETECTED ERROR|>>> com\.parcelrecode\.app <<<|Photo processing failed|com\.parcelrecode\.app.*(NullPointerException|SecurityException|IllegalArgumentException)|ParcelReLabel.*(Exception|failed)"

$summary = [pscustomobject]@{
    StartedAt = $startedAt.ToString("s")
    FinishedAt = (Get-Date).ToString("s")
    ContinuousSession = $true
    InitialReset = -not $NoInitialReset
    AppRestartsInsideLoop = 0
    SimulatedPrintAfterReadyPair = $true
    Images = $files.Count
    Pairs = $results.Count
    ReadyPairs = @($results | Where-Object { $_.Ready }).Count
    Failures = $failures.Count
    FatalLogMatches = @($fatalMatches).Count
    ResultCsv = (Resolve-Path $csvPath).Path
    Logcat = (Resolve-Path $logPath).Path
}
$summary | ConvertTo-Json | Set-Content -Encoding UTF8 $summaryPath

$summary | Format-List
if ($failures.Count -gt 0) {
    $failures | Select-Object -First 30 | Format-Table -AutoSize
}
if ($fatalMatches) {
    $fatalMatches | Select-Object -First 30
}
