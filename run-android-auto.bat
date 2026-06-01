@echo off
REM Launch Android Auto Desktop Head Unit emulator connected to a USB-attached phone.
REM Prereq on phone: Android Auto -> Developer settings -> Start head unit server.

set DHU="%LOCALAPPDATA%\Android\Sdk\extras\google\auto\desktop-head-unit.exe"

if not exist %DHU% (
    echo desktop-head-unit.exe not found at %DHU%
    echo Install via Android Studio -^> SDK Manager -^> SDK Tools -^> "Android Auto Desktop Head Unit emulator".
    exit /b 1
)

adb devices | findstr /R "device$" >nul
if errorlevel 1 (
    echo No device attached. Plug phone in with USB debugging enabled.
    exit /b 1
)

adb forward tcp:5277 tcp:5277
if errorlevel 1 (
    echo adb forward failed.
    exit /b 1
)

echo Launching DHU. If it fails to connect, start the head-unit server on the phone:
echo   Android Auto -^> Developer settings -^> Start head unit server.
%DHU%
