@echo off
setlocal enabledelayedexpansion
title USB Advance - APK Builder (32-bit / 64-bit / Universal)

echo ====================================================================
echo                 USB ADVANCE - AUTOMATED APK BUILDER
echo ====================================================================
echo.

:: 1. Detect and configure JAVA_HOME
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" goto :java_found
)

if exist "C:\Users\Minez400\.jdks\jbr-21.0.11\bin\java.exe" (
    set "JAVA_HOME=C:\Users\Minez400\.jdks\jbr-21.0.11"
    goto :java_found
)

if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    goto :java_found
)

for /d %%D in ("%LOCALAPPDATA%\Programs\Android Studio\jbr") do (
    if exist "%%D\bin\java.exe" (
        set "JAVA_HOME=%%D"
        goto :java_found
    )
)

:java_found
if not defined JAVA_HOME (
    echo [ERROR] JAVA_HOME could not be located.
    echo Please install a JDK or set your JAVA_HOME environment variable.
    pause
    exit /b 1
)

echo [*] Using Java: "%JAVA_HOME%"
echo [*] Starting Gradle assembleRelease build...
echo.

:: 2. Run Gradle assembleRelease
call gradlew.bat assembleRelease --no-daemon
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Gradle build failed with error code %ERRORLEVEL%.
    pause
    exit /b %ERRORLEVEL%
)

:: 3. Prepare output directory
set "SRC_DIR=app\build\outputs\apk\release"
set "OUT_DIR=release_apks"

if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

echo.
echo [*] Copying and organizing APK packages...
echo.

:: 4. Copy and rename APKs
set "VERSION=v1.0.0"

if exist "%SRC_DIR%\app-arm64-v8a-release.apk" (
    copy /y "%SRC_DIR%\app-arm64-v8a-release.apk" "%OUT_DIR%\USB_Advance_%VERSION%_arm64-v8a_64bit.apk" >nul
    echo  [+] Generated: %OUT_DIR%\USB_Advance_%VERSION%_arm64-v8a_64bit.apk
)

if exist "%SRC_DIR%\app-armeabi-v7a-release.apk" (
    copy /y "%SRC_DIR%\app-armeabi-v7a-release.apk" "%OUT_DIR%\USB_Advance_%VERSION%_armeabi-v7a_32bit.apk" >nul
    echo  [+] Generated: %OUT_DIR%\USB_Advance_%VERSION%_armeabi-v7a_32bit.apk
)

if exist "%SRC_DIR%\app-universal-release.apk" (
    copy /y "%SRC_DIR%\app-universal-release.apk" "%OUT_DIR%\USB_Advance_%VERSION%_Universal.apk" >nul
    echo  [+] Generated: %OUT_DIR%\USB_Advance_%VERSION%_Universal.apk
)

if exist "%SRC_DIR%\app-x86_64-release.apk" (
    copy /y "%SRC_DIR%\app-x86_64-release.apk" "%OUT_DIR%\USB_Advance_%VERSION%_x86_64_Emulator.apk" >nul
    echo  [+] Generated: %OUT_DIR%\USB_Advance_%VERSION%_x86_64_Emulator.apk
)

if exist "%SRC_DIR%\app-x86-release.apk" (
    copy /y "%SRC_DIR%\app-x86-release.apk" "%OUT_DIR%\USB_Advance_%VERSION%_x86_Emulator.apk" >nul
    echo  [+] Generated: %OUT_DIR%\USB_Advance_%VERSION%_x86_Emulator.apk
)

echo.
echo ====================================================================
echo                     BUILD COMPLETED SUCCESSFULLY!
echo ====================================================================
echo.
echo All APKs are organized in the folder: "%CD%\%OUT_DIR%"
echo.
echo  - arm64-v8a  : Recommended for modern 64-bit Android devices (~4.5 MB)
echo  - armeabi-v7a: For older 32-bit Android devices (~4.2 MB)
echo  - Universal  : All-in-one APK containing all ABIs (~8.3 MB)
echo.

:: 5. Open Explorer in output directory
start "" explorer.exe "%CD%\%OUT_DIR%"

pause
