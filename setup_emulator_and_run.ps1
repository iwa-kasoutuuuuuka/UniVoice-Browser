# ==============================================================================
# UniVoice Browser - エミュレーター高速検証 & 自動ビルド・起動スクリプト
# 対象: Poco F6 Pro 相当 (Android 14 / API 34 / ATD 超軽量イメージ)
# ==============================================================================

$ErrorActionPreference = "Stop"

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host " [UniVoice Browser] エミュレーター動作検証環境の自動構築を開始します" -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan

# 1. Android CLI の確認
$androidCli = "$env:USERPROFILE\AppData\AndroidCLI\android.exe"
if (!(Test-Path $androidCli)) {
    Write-Host "Android CLI が見つかりません。公式インストーラーを実行します..." -ForegroundColor Yellow
    $installerUrl = "https://dl.google.com/android/cli/latest/windows_x86_64/install.cmd"
    $tmpCmd = "$env:TEMP\install_android_cli.cmd"
    Invoke-WebRequest -Uri $installerUrl -OutFile $tmpCmd
    cmd.exe /c $tmpCmd
}

if (!(Test-Path $androidCli)) {
    Write-Error "Android CLI のインストールに失敗しました。"
}
Write-Host "Android CLI 準備完了: $androidCli" -ForegroundColor Green

# 2. SDK コンポーネントのインストール (超軽量 Google ATD システムイメージ)
Write-Host "`n必要な SDK コンポーネント (API 34 ATD イメージ, エミュレータ) をインストール中..." -ForegroundColor Cyan
Write-Host "※ ATD (Automated Test Device) は通常の Play イメージに比べ起動速度3倍、メモリ消費1/3の検証特化イメージです。" -ForegroundColor DarkGray

& $androidCli sdk install "platforms/android-34" "system-images/android-34/google_atd/x86_64" "emulator" "platform-tools"

# 3. Poco F6 Pro 相当の仮想デバイス (AVD) の作成
$avdName = "Poco_F6_Pro_ATD"
Write-Host "`n仮想デバイス '$avdName' の作成状況を確認中..." -ForegroundColor Cyan

$existingAvds = & $androidCli emulator list 2>&1
if ($existingAvds -notmatch $avdName) {
    Write-Host "仮想デバイス '$avdName' を新規作成します..." -ForegroundColor Yellow
    & $androidCli emulator create --name $avdName --image "system-images/android-34/google_atd/x86_64"
    Write-Host "仮想デバイスの作成が完了しました。" -ForegroundColor Green
} else {
    Write-Host "既存の仮想デバイス '$avdName' を再利用します。" -ForegroundColor Green
}

# 4. エミュレーターの起動
Write-Host "`nエミュレーター '$avdName' をバックグラウンドで起動中..." -ForegroundColor Cyan
& $androidCli emulator start $avdName

Write-Host "`n==================================================================" -ForegroundColor Green
Write-Host " エミュレーターの起動が完了しました！" -ForegroundColor Green
Write-Host " アプリのビルド＆デプロイを実行するには以下を実行してください:" -ForegroundColor Yellow
Write-Host "   .\gradlew.bat assembleDebug" -ForegroundColor White
Write-Host "   & '$androidCli' run" -ForegroundColor White
Write-Host "==================================================================" -ForegroundColor Green
