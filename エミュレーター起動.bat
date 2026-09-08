@echo off
chcp 65001 > nul
echo ===================================================
echo   UniVoice Browser エミュレーター起動ランチャー
echo ===================================================
echo.

:: 残留ロックのクリーンアップ
del /q /f /s "%USERPROFILE%\.android\avd\medium_phone.avd\*.lock" 2>nul
rmdir /s /q "%USERPROFILE%\.android\avd\medium_phone.avd\hardware-qemu.ini.lock" 2>nul
rmdir /s /q "%USERPROFILE%\.android\avd\medium_phone.avd\multiinstance.lock" 2>nul

echo エミュレーターを起動しています...
echo.

start "" "%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd medium_phone -gpu host -no-snapshot-load