@echo off
rem Internal helper, called by the other scripts in this folder.
rem Resolves the toolchain locations in this precedence order:
rem   scripts\env.bat (machine-local, gitignored)  >  JAVA_HOME / ANDROID_HOME env  >  adb on PATH
rem
rem Sets: ADB

if exist "%~dp0env.bat" call "%~dp0env.bat"

set "ADB="
if defined ADB_BIN if exist "%ADB_BIN%" set "ADB=%ADB_BIN%"
if not defined ADB if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB set "ADB=adb"
exit /b 0
