param()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$project = Join-Path $root "android-app"
$outputDirectory = Join-Path $root "dist"
$sourceApk = Join-Path $project "app\build\outputs\apk\debug\app-debug.apk"
$targetApk = Join-Path $outputDirectory "ParcelReLabel.apk"
$gradle = Join-Path $project "gradlew.bat"

$javaHome = "C:\Program Files\Android\Android Studio\jbr"
if (-not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    throw "Android Studio Java runtime was not found at $javaHome"
}

$androidHome = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
if (-not (Test-Path $androidHome)) {
    throw "Android SDK was not found at $androidHome"
}

if (-not (Test-Path $gradle)) {
    throw "Gradle wrapper was not found at $gradle"
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidHome

Push-Location $project
try {
    $command = "`"$gradle`" --no-daemon testDebugUnitTest assembleDebug"
    $build = Start-Process -FilePath "cmd.exe" -ArgumentList @("/d", "/c", $command) `
        -NoNewWindow -Wait -PassThru
    if ($build.ExitCode -ne 0) {
        throw "Android tests or build failed with exit code $($build.ExitCode)"
    }
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
Copy-Item -LiteralPath $sourceApk -Destination $targetApk -Force
Write-Host ""
Write-Host "APK ready: $targetApk" -ForegroundColor Green
