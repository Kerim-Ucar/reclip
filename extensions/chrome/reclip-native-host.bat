@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "REPO_ROOT=%%~fI"

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        "%JAVA_HOME%\bin\java.exe" -cp "%REPO_ROOT%\build\classes\java\main" com.kerim.reclip.ipc.NativeMessagingHost
        exit /b %ERRORLEVEL%
    )
)

java -cp "%REPO_ROOT%\build\classes\java\main" com.kerim.reclip.ipc.NativeMessagingHost
