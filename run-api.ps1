<#
  Собирает проект и запускает HTTP-API (com.papasmurf.web.ApiServer).

  Запуск из корня репозитория:
      powershell -ExecutionPolicy Bypass -File run-api.ps1

  Порт и адрес задаются переменными API_PORT и API_HOST в .env.
#>

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "Сборка..." -ForegroundColor Cyan
mvn -q -DskipTests compile
if ($LASTEXITCODE -ne 0) {
    Write-Host "Сборка не удалась." -ForegroundColor Red
    exit 1
}

if (-not (Test-Path "target\cp.txt")) {
    Write-Host "Считаю classpath зависимостей..." -ForegroundColor Cyan
    mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Не удалось получить classpath." -ForegroundColor Red
        exit 1
    }
}

$classpath = "target\classes;" + (Get-Content "target\cp.txt" -Raw).Trim()

Write-Host "Запуск API. Остановка — Ctrl+C." -ForegroundColor Green
# Кавычки вокруг -D... обязательны: PowerShell 5.1 иначе разбивает аргумент по "=".
java "-Dstdout.encoding=UTF-8" "-Dfile.encoding=UTF-8" -cp $classpath com.papasmurf.web.ApiServer
