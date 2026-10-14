@echo off
setlocal

rem usage: test-reminder.bat [box-ip] [port]
rem Shows the overlay right away, without waiting for the threshold.

call "%~dp0_env.bat"

if not "%1"=="" (
  set "PORT=%2"
  if "%2"=="" set "PORT=5555"
  "%ADB%" connect %1:%PORT%
)

"%ADB%" shell am broadcast -a com.atvbreak.action.TEST_REMINDER -n com.atvbreak/.ActionReceiver
echo [atv-break] overlay triggered; it disappears automatically after the configured seconds.
exit /b 0
