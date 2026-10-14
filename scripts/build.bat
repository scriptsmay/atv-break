@echo off
setlocal

rem usage: build.bat [gradle tasks...]   (default: assembleDebug)

call "%~dp0_env.bat"

if not defined JAVA_HOME (
  echo [atv-break] JAVA_HOME is not set. Point it at a JDK 17, either in
  echo             scripts\env.bat - copy scripts\env.example.bat - or in the
  echo             environment of this shell.
  exit /b 1
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [atv-break] JAVA_HOME points to "%JAVA_HOME%" but no bin\java.exe there.
  exit /b 1
)
if not defined ANDROID_HOME (
  echo [atv-break] Warning: ANDROID_HOME is not set and no env.bat was found.
  echo             The build still works if local.properties has a sdk.dir line.
)

pushd "%~dp0.."
if "%1"=="" (
  call gradlew.bat assembleDebug
) else (
  call gradlew.bat %*
)
set "RC=%ERRORLEVEL%"
popd

if not "%RC%"=="0" (
  echo [atv-break] build FAILED
  exit /b %RC%
)
echo [atv-break] APK: app\build\outputs\apk\debug\app-debug.apk
exit /b 0
