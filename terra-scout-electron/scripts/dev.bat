@echo off
setlocal
rem Terra Scout dev mode launcher (double-click friendly).
rem Pins working dir to the electron project root, runs dev.ps1, and keeps
rem the window open with the error visible if startup fails.
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev.ps1"
if errorlevel 1 (
  echo.
  echo [dev] start failed ^(exit %errorlevel%^). Log: %USERPROFILE%\.terrascout\logs\electron-console.log
  pause
)
endlocal
