@echo off
setlocal
pushd "%~dp0"
set "JAVA_HOME=%CD%\.tools\jdk-17.0.20+8"
set "ANDROID_HOME=%CD%\.tools\android-sdk"
call "%CD%\.tools\gradle-8.7\bin\gradle.bat" %*
set "mapnet_exit_code=%errorlevel%"
popd
exit /b %mapnet_exit_code%
