@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "REPO_ROOT=%%~fI"

set "CP=%REPO_ROOT%\build\classes\java\main"
if not exist "%CP%\com\kerim\reclip\ipc\NativeMessagingHost.class" (
    if exist "%REPO_ROOT%\build\dist\Reclip\app\reclip-1.0-SNAPSHOT.jar" (
        set "CP=%REPO_ROOT%\build\dist\Reclip\app\reclip-1.0-SNAPSHOT.jar"
    )
)

set "JAVA_EXE=java"
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    )
) else if exist "%REPO_ROOT%\build\dist\Reclip\runtime\bin\java.exe" (
    set "JAVA_EXE=%REPO_ROOT%\build\dist\Reclip\runtime\bin\java.exe"
)

"%JAVA_EXE%" -cp "%CP%" com.kerim.reclip.ipc.NativeMessagingHost
exit /b %ERRORLEVEL%
