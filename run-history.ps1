<#
  Запускает сервис истории (history/app.py): FastAPI + SQLite.

  Запуск из корня репозитория:
      powershell -ExecutionPolicy Bypass -File run-history.ps1

  Сервису не нужны ни Steam-ключ, ни Discord-токен. Порт и адрес по умолчанию —
  127.0.0.1:8000, меняются переменными HISTORY_HOST и HISTORY_PORT в .env.
  Путь к базе — HISTORY_DB (по умолчанию history/data/history.db).
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

# Проверяем зависимости. 2>$null прячет stderr, чтобы он не стал ошибкой
# из-за $ErrorActionPreference = "Stop".
python -c "import fastapi, uvicorn" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Ставлю зависимости (fastapi, uvicorn)..." -ForegroundColor Cyan
    # cmd /c возвращает код pip корректно и не ломается на выводе в stderr.
    cmd /c "python -m pip install -r history\requirements.txt"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Не удалось поставить зависимости. Попробуйте вручную: python -m pip install -r history\requirements.txt" -ForegroundColor Red
        exit 1
    }
}

# Адрес и порт из .env, если заданы. $host зарезервирован в PowerShell,
# поэтому имя переменной другое.
$histHost = "127.0.0.1"
$histPort = "8000"
if (Test-Path ".env") {
    Get-Content ".env" -Encoding utf8 | ForEach-Object {
        if ($_ -match "^\s*HISTORY_HOST\s*=") { $histHost = ($_ -split "=", 2)[1].Trim().Trim('"').Trim("'") }
        if ($_ -match "^\s*HISTORY_PORT\s*=") { $histPort = ($_ -split "=", 2)[1].Trim().Trim('"').Trim("'") }
    }
}

Write-Host "Запуск сервиса истории на http://${histHost}:${histPort}. Остановка — Ctrl+C." -ForegroundColor Green
# --app-dir указывает, где лежит app.py, поэтому запускать можно из корня проекта.
python -m uvicorn app:app --app-dir history --host $histHost --port $histPort
