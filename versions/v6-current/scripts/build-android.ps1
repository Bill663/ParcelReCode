param()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$project = Join-Path $root "android-app"
$outputDirectory = Join-Path $root "dist"
$sourceApk = Join-Path $project "app\build\outputs\apk\debug\app-debug.apk"
$targetApk = Join-Path $outputDirectory "ParcelReLabel.apk"
$gradle = Join-Path $project "gradlew.bat"
$cachedGradle = Get-ChildItem `
    -Path (Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-9.4.1-bin") `
    -Filter "gradle.bat" `
    -Recurse `
    -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like "*\gradle-9.4.1\bin\gradle.bat" } |
        Select-Object -First 1 -ExpandProperty FullName

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

if (-not $cachedGradle -and -not (Test-Path $gradle)) {
    throw "Gradle wrapper was not found at $gradle"
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidHome

Push-Location $project
try {
    $gradleCommand = if ($cachedGradle) { $cachedGradle } else { $gradle }
    & $gradleCommand --no-daemon testDebugUnitTest assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Android tests or build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
Copy-Item -LiteralPath $sourceApk -Destination $targetApk -Force
Write-Host ""
Write-Host "APK ready: $targetApk" -ForegroundColor Green
