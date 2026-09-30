param(
    [string]$Serial = "",
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"
$versionRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDir) {
    $OutputDir = Join-Path $versionRoot "dist\failed-samples"
}

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

function Save-AdbExecOutBytes([string[]]$Arguments, [string]$Path) {
    $processInfo = New-Object System.Diagnostics.ProcessStartInfo
    $processInfo.FileName = $script:adb
    $processInfo.UseShellExecute = $false
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $processInfo.Arguments = ($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') {
            '"' + $_.Replace('"', '\"') + '"'
        } else {
            $_
        }
    }) -join " "

    $process = [System.Diagnostics.Process]::Start($processInfo)
    try {
        $file = [System.IO.File]::Create($Path)
        try {
            $process.StandardOutput.BaseStream.CopyTo($file)
        } finally {
            $file.Dispose()
        }
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
            return $false
        }
        return $true
    } finally {
        $process.Dispose()
    }
}

$script:adb = Get-AdbPath
$Serial = Select-DeviceSerial $Serial
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$indexPath = Join-Path $OutputDir "failed-samples.csv"
$index = & $adb -s $Serial exec-out run-as com.parcelrecode.app cat files/failed-samples.csv 2>$null
if ($LASTEXITCODE -eq 0 -and $index) {
    $index | Set-Content -Encoding UTF8 -Path $indexPath
} else {
    Write-Host "No failed sample index found on device $Serial."
    return
}

$imageDir = Join-Path $OutputDir "images"
New-Item -ItemType Directory -Path $imageDir -Force | Out-Null
$files = & $adb -s $Serial shell run-as com.parcelrecode.app ls files/failed-samples 2>$null
foreach ($file in $files) {
    $name = $file.Trim()
    if (-not $name) { continue }
    $target = Join-Path $imageDir $name
    [void](Save-AdbExecOutBytes -Arguments @(
        "-s", $Serial,
        "exec-out",
        "run-as", "com.parcelrecode.app",
        "cat", "files/failed-samples/$name"
    ) -Path $target)
}

Write-Host "Failed sample curation files pulled to: $OutputDir" -ForegroundColor Green
