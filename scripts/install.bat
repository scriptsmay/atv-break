@echo off
setlocal

rem usage: install.bat [box-ip] [port]
rem   install.bat                -> install to the only connected device
rem   install.bat 192.0.2.10     -> adb connect 192.0.2.10:5555, then install
rem                                 (192.0.2.0/24 is the documentation range,
rem                                  replace it with your box's address)

call "%~dp0_env.bat"
set "APK=%~dp0..\app\build\outputs\apk\debug\app-debug.apk"

if not exist "%APK%" (
  echo [atv-break] no APK at %APK%
  echo             build it first: scripts\build.bat
  exit /b 1
)

if not "%1"=="" (
  set "PORT=%2"
  if "%2"=="" set "PORT=5555"
  echo [atv-break] connecting %1:%PORT%
  "%ADB%" connect %1:%PORT%
)

"%ADB%" install -r "%APK%"
if errorlevel 1 (
  echo [atv-break] install FAILED
  exit /b 1
)

echo [atv-break] granting overlay permission (required for the reminder overlay)
"%ADB%" shell appops set com.atvbreak SYSTEM_ALERT_WINDOW allow
echo [atv-break] adding to battery-optimization whitelist (keeps background alarms alive)
"%ADB%" shell dumpsys deviceidle whitelist +com.atvbreak
"%ADB%" shell am start -n com.atvbreak/.MainActivity
exit /b 0
