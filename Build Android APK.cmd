@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0versions\v8-current\scripts\build-android.ps1"
if errorlevel 1 (
  echo.
  echo Build failed. See the error above.
)
echo.
pause
