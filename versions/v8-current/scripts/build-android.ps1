param()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$project = Join-Path $root "android-app"
$outputDirectory = Join-Path $root "dist"
$sourceApk = Join-Path $project "app\build\outputs\apk\debug\app-debug.apk"
$targetApk = Join-Path $outputDirectory "ParcelReLabel.apk"
$carrierModel = Join-Path $project "app\src\main\assets\carrier_model_v7.bin"
$problemsReport = Join-Path $project "build\reports\problems\problems-report.html"
$gradle = Join-Path $project "gradlew.bat"
$cachedGradle = Get-ChildItem `
    -Path (Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-9.4.1-bin") `
    -Filter "gradle.bat" `
    -Recurse `
    -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like "*\gradle-9.4.1\bin\gradle.bat" } |
        Select-Object -First 1 -ExpandProperty FullName

$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else {
    Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
}
if (-not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    throw "Java was not found at $javaHome. Install Android Studio or set JAVA_HOME to a JDK 21 installation."
}

$androidHome = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else {
        Join-Path $env:LOCALAPPDATA "Android\Sdk"
    }
}
if (-not (Test-Path $androidHome)) {
    throw "Android SDK was not found at $androidHome"
}

if (-not $cachedGradle -and -not (Test-Path $gradle)) {
    throw "Gradle wrapper was not found at $gradle"
}
if (-not (Test-Path $carrierModel)) {
    throw "The trained carrier model was not found at $carrierModel"
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidHome

Push-Location $project
try {
    if (Test-Path -LiteralPath $problemsReport) {
        Remove-Item -LiteralPath $problemsReport -Force
    }
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
