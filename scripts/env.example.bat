@echo off
rem Machine-local toolchain overrides for the scripts in this folder.
rem
rem Copy this file to env.bat (same folder) and fill in your own paths.
rem env.bat is gitignored on purpose: it holds machine-specific locations,
rem and scripts/*.bat work without it as long as JAVA_HOME / ANDROID_HOME are
rem already exported in the environment (or adb is on PATH).
rem
rem Do not put credentials in here.

rem --- required by build.bat ---
set "JAVA_HOME=C:\path\to\jdk-17"
set "ANDROID_HOME=C:\path\to\android-sdk"

rem --- optional: adb location, used by install / test-reminder / push-config ---
rem Leave unset to fall back to %ANDROID_HOME%\platform-tools\adb.exe, then PATH.
rem set "ADB_BIN=C:\path\to\adb.exe"
