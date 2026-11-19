<#
  Запускает Discord-бота (bot/bot.py).

  Перед запуском должен быть запущен Java-API (run-api.ps1) и заполнен .env:
  DISCORD_TOKEN, при желании API_BASE_URL и DISCORD_GUILD_ID.

      powershell -ExecutionPolicy Bypass -File run-bot.ps1
#>

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# Русский текст в логах Python не ломается при перенаправлении вывода в файл.
$env:PYTHONIOENCODING = "utf-8"

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    Write-Host "Python не найден в PATH. Установите Python 3.10+ и перезапустите терминал." -ForegroundColor Red
    exit 1
}

# Проверяем зависимости; пайп в Out-Null нужен, чтобы stderr от python не
# превращался в исключение из-за $ErrorActionPreference = "Stop".
python -c "import discord, aiohttp" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Ставлю зависимости (discord.py, aiohttp)..." -ForegroundColor Cyan
    python -m pip install -r "bot\requirements.txt" 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Не удалось поставить зависимости." -ForegroundColor Red
        exit 1
    }
}

$token = ""
if (Test-Path ".env") {
    $line = Get-Content ".env" -Encoding utf8 |
        Where-Object { $_ -match "^\s*DISCORD_TOKEN\s*=" } |
        Select-Object -First 1
    if ($line) {
        $token = ($line -split "=", 2)[1].Trim().Trim('"').Trim("'")
    }
}

if (-not $token) {
    Write-Host "DISCORD_TOKEN пуст в .env — бот не сможет войти в Discord." -ForegroundColor Yellow
    Write-Host "Где взять: https://discord.com/developers/applications" -ForegroundColor Yellow
    Write-Host "  -> ваше приложение -> Bot -> Reset Token -> скопировать значение в .env" -ForegroundColor Yellow
    exit 1
}

Write-Host "Запуск бота. Остановка — Ctrl+C." -ForegroundColor Green
python "bot\bot.py"
