param(
    [string]$Serial = "",
    [string]$SampleDir = "",
    [string]$DeviceSampleDir = "",
    [string]$OutputDir = "",
    [switch]$SkipBuild,
    [int]$WaitSeconds = 7
)

$ErrorActionPreference = "Stop"
$versionRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputDir = Join-Path $versionRoot "dist\device-regression-$stamp"
}

$package = "com.parcelrecode.app"
$activity = "$package/.MainActivity"
$debugPhotoExtra = "com.parcelrecode.app.DEBUG_PHOTO_PATH"
$debugLayoutExtra = "com.parcelrecode.app.DEBUG_LABEL_LAYOUT"
$devicePushDir = "/sdcard/Android/data/$package/files/regression-v6"

function Get-AdbPath {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb) { return $sdkAdb }
    return "adb"
}

function Select-DeviceSerial([string]$RequestedSerial) {
    if ($RequestedSerial) { return $RequestedSerial }
    $devices = @(& $script:adb devices |
        Select-String -Pattern "^\S+\s+device$" |
        ForEach-Object { ($_.Line -split "\s+")[0] })
    if (-not $devices -or $devices.Count -eq 0) {
        throw "No connected Android device was found."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple Android devices are connected. Pass -Serial."
    }
    return $devices[0]
}

function Convert-ToCsvCell([string]$Value) {
    if ($null -eq $Value) { return "" }
    if ($Value -notmatch '[,"\r\n]') { return $Value }
    return '"' + $Value.Replace('"', '""') + '"'
}

function Get-UiText([string]$XmlText, [string]$Id) {
    $nodePattern = '<node[^>]*resource-id="' + [regex]::Escape("${package}:id/$Id") + '"[^>]*>'
    $node = [regex]::Match($XmlText, $nodePattern)
    if (-not $node.Success) { return "" }
    $text = [regex]::Match($node.Value, 'text="([^"]*)"')
    if (-not $text.Success) { return "" }
    return [System.Net.WebUtility]::HtmlDecode($text.Groups[1].Value)
}

function New-SampleSpec([string]$Name, [string]$Path, [bool]$IsLocal) {
    [PSCustomObject]@{
        Name = $Name
        Path = $Path
        IsLocal = $IsLocal
    }
}

function Get-LocalSampleSpecs([string]$Directory) {
    if (-not (Test-Path $Directory)) {
        throw "Sample directory was not found: $Directory"
    }
    @(Get-ChildItem -LiteralPath $Directory -File |
        Where-Object { $_.Extension -match '^\.(jpg|jpeg|png)$' } |
        Sort-Object Name |
        ForEach-Object { New-SampleSpec $_.Name $_.FullName $true })
}

function Get-DeviceSampleSpecs([string]$Directory) {
    $paths = @()
    if ($Directory) {
        $paths = @(& $adb -s $Serial shell find $Directory -maxdepth 2 -type f 2>$null |
            Where-Object { $_ -match '\.(jpg|jpeg|png)$' })
    } else {
        $internalNames = @(& $adb -s $Serial shell run-as $package ls files 2>$null |
            Where-Object { $_ -match '\.(jpg|jpeg|png)$' })
        if ($internalNames.Count -gt 0) {
            $paths = @($internalNames | ForEach-Object { "/data/data/$package/files/$($_.Trim())" })
        } else {
            $externalRoot = "/sdcard/Android/data/$package/files"
            $paths = @(& $adb -s $Serial shell find $externalRoot -maxdepth 2 -type f 2>$null |
                Where-Object { $_ -match '\.(jpg|jpeg|png)$' -and $_ -notmatch '/regression-v6/' })
        }
    }

    @($paths |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ } |
        Sort-Object -Unique |
        ForEach-Object {
            $name = ($_ -split '/')[-1]
            New-SampleSpec $name $_ $false
        })
}

$script:adb = Get-AdbPath
$Serial = Select-DeviceSerial $Serial

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build-android.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed."
    }
}

$apk = Join-Path $versionRoot "dist\ParcelReLabel.apk"
if (-not (Test-Path $apk)) {
    throw "APK was not found: $apk"
}

& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "APK install failed on $Serial."
}

$samples = if ($SampleDir) {
    Get-LocalSampleSpecs $SampleDir
} else {
    Get-DeviceSampleSpecs $DeviceSampleDir
}
if (-not $samples -or $samples.Count -eq 0) {
    if ($SampleDir) {
        throw "No jpg/jpeg/png sample photos were found in $SampleDir"
    }
    throw "No jpg/jpeg/png sample photos were found on device storage. Pass -SampleDir or -DeviceSampleDir."
}

if ($SampleDir) {
    & $adb -s $Serial shell mkdir -p $devicePushDir | Out-Null
}
& $adb -s $Serial logcat -c | Out-Null

$resultPath = Join-Path $OutputDir "device-regression-results.csv"
"sample,carrier,status,failed,ui_xml,logcat" | Set-Content -Encoding UTF8 -Path $resultPath

$failCount = 0
foreach ($sample in $samples) {
    $safeName = $sample.Name -replace '[^A-Za-z0-9._-]', '_'
    $remotePath = $sample.Path
    if ($sample.IsLocal) {
        $remotePath = "$devicePushDir/$safeName"
        & $adb -s $Serial push $sample.Path $remotePath | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Could not push sample $($sample.Path)."
        }
    }

    & $adb -s $Serial logcat -c | Out-Null
    & $adb -s $Serial shell am force-stop $package | Out-Null
    & $adb -s $Serial shell am start -n $activity --es $debugPhotoExtra $remotePath --es $debugLayoutExtra single | Out-Null
    Start-Sleep -Seconds $WaitSeconds

    $uiXmlDevice = "/sdcard/parcel-regression-window.xml"
    & $adb -s $Serial shell uiautomator dump $uiXmlDevice | Out-Null
    $xmlText = (& $adb -s $Serial exec-out cat $uiXmlDevice) -join "`n"
    $uiPath = Join-Path $OutputDir ($safeName + ".xml")
    $xmlText | Set-Content -Encoding UTF8 -Path $uiPath

    $logPath = Join-Path $OutputDir ($safeName + ".logcat.txt")
    $logText = (& $adb -s $Serial logcat -d -t 500) -join "`n"
    $logText | Set-Content -Encoding UTF8 -Path $logPath

    $carrier = Get-UiText $xmlText "carrierText"
    $status = Get-UiText $xmlText "statusText"
    $crash = $logText -match "FATAL EXCEPTION|E AndroidRuntime"
    $knownCarrier = $carrier -match "^(FedEx|USPS|UniUni|GOFO|SwiftX|SpeedX|OnTrac)$"
    $failed = $crash -or -not $knownCarrier
    if ($failed) { $failCount++ }

    $row = @(
        Convert-ToCsvCell $sample.Name
        Convert-ToCsvCell $carrier
        Convert-ToCsvCell $status
        $failed
        Convert-ToCsvCell $uiPath
        Convert-ToCsvCell $logPath
    ) -join ","
    Add-Content -Encoding UTF8 -Path $resultPath -Value $row
}

$timingPath = Join-Path $OutputDir "recognition-timing.csv"
$timing = & $adb -s $Serial exec-out run-as $package cat files/recognition-timing.csv 2>$null
if ($LASTEXITCODE -eq 0 -and $timing) {
    $timing | Set-Content -Encoding UTF8 -Path $timingPath
}

try {
    & (Join-Path $PSScriptRoot "pull-failed-samples.ps1") -Serial $Serial -OutputDir (Join-Path $OutputDir "failed-samples") | Out-Null
} catch {
    Write-Host "Failed sample pull skipped: $($_.Exception.Message)"
}

Write-Host "Regression output: $OutputDir" -ForegroundColor Green
if ($failCount -gt 0) {
    throw "$failCount sample(s) failed. See $resultPath"
}
Write-Host "All $($samples.Count) sample(s) completed without visible failure." -ForegroundColor Green
