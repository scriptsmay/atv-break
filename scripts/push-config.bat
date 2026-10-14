@echo off
setlocal

rem usage: push-config.bat [box-ip] [port]
rem Pushes config.example.json into the app's internal config.json (works on Android 11+,
rem where adb cannot write /sdcard/Android/data directly; requires a debuggable build).

call "%~dp0_env.bat"
set "SRC=%~dp0..\config.example.json"

if not "%1"=="" (
  set "PORT=%2"
  if "%2"=="" set "PORT=5555"
  "%ADB%" connect %1:%PORT%
)

"%ADB%" shell "run-as com.atvbreak sh -c 'cat > files/config.json'" < "%SRC%"
"%ADB%" shell am broadcast -a com.atvbreak.action.RELOAD_CONFIG -n com.atvbreak/.ActionReceiver
"%ADB%" shell "run-as com.atvbreak sh -c 'cat files/config.json'"
exit /b 0
