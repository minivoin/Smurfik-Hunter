#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Оффлайн-проверка сервиса истории (history/app.py).

Запускает uvicorn на свободном порту с временной базой и проверяет ручки:
запись, чтение истории, агрегаты, валидацию и /health. Не требует ни
Steam-ключа, ни Discord-токена, ни доступа к настоящей базе.

    python history/check_api.py
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent

CHECK_A = "76561198890900243"
CHECK_B = "76561198669833133"

problems: list[str] = []
checks = 0


def ensure_dependencies() -> bool:
    try:
        import fastapi  # noqa: F401
        import uvicorn  # noqa: F401
    except ImportError as error:
        print(
            f"Не хватает зависимостей сервиса истории: {error}\n"
            "Установите их: python -m pip install -r history/requirements.txt",
            file=sys.stderr,
        )
        return False
    return True


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def request(method: str, url: str, payload: dict | None = None) -> tuple[int, dict]:
    """HTTP-запрос без сторонних библиотек. Не бросает на 4xx/5xx."""
    data = None
    headers = {"Content-Type": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        try:
            return error.code, json.loads(body)
        except json.JSONDecodeError:
            return error.code, {"ok": False, "error": body}


def expect(condition: bool, message: str) -> None:
    global checks
    checks += 1
    if not condition:
        problems.append(message)


def check_health(base: str) -> None:
    status, body = request("GET", f"{base}/health")
    expect(status == 200, f"/health: код {status} вместо 200")
    expect(body.get("ok") is True, "/health: ok != true")
    expect(bool(body.get("db")), "/health: не указан путь к базе")


def check_post(base: str) -> None:
    good = {
        "steamId": CHECK_A,
        "seedName": "Черепаха Хлофф",
        "depth": 2,
        "group": [CHECK_A, "76561198740323709", "76561198669833133"],
        "groupConfidence": 0.422,
        "candidatesChecked": 21,
        "durationMs": 61234,
        "cached": False,
    }
    status, body = request("POST", f"{base}/api/v1/history", good)
    expect(status == 201, f"POST валидного: код {status} вместо 201 ({body})")
    expect(body.get("ok") is True, "POST валидного: ok != true")
    expect(isinstance(body.get("id"), int), "POST валидного: нет id")

    # Второй профиль — из кеша и без группы: проверим агрегаты и топ.
    second = {
        "steamId": CHECK_B,
        "seedName": "ЧВК ХимЗавод",
        "depth": 1,
        "group": [],
        "groupConfidence": 0.0,
        "candidatesChecked": 7,
        "durationMs": 15000,
        "cached": True,
    }
    status, body = request("POST", f"{base}/api/v1/history", second)
    expect(status == 201, f"POST второго: код {status} вместо 201 ({body})")

    # Невалидные данные не должны попадать в базу.
    bad_cases = [
        ("steamId не 17 цифр", {"steamId": "123", "depth": 2}),
        ("depth вне 1..3", {"steamId": CHECK_A, "depth": 5}),
        ("confidence вне 0..1", {"steamId": CHECK_A, "groupConfidence": 1.5}),
        ("group с мусором", {"steamId": CHECK_A, "group": ["123"]}),
    ]
    for label, payload in bad_cases:
        status, body = request("POST", f"{base}/api/v1/history", payload)
        expect(status == 422, f"POST {label}: код {status} вместо 422")
        expect(body.get("ok") is False, f"POST {label}: ok != false")


def check_history(base: str) -> None:
    status, body = request("GET", f"{base}/api/v1/history?steamId={CHECK_A}&limit=10")
    expect(status == 200, f"GET history: код {status} вместо 200")
    expect(body.get("count") == 1, f"GET history: count {body.get('count')} вместо 1")
    items = body.get("items") or []
    expect(bool(items), "GET history: пустой список")
    if items:
        item = items[0]
        expect(item.get("steamId") == CHECK_A, "GET history: вернулся не тот профиль")
        expect(len(item.get("group") or []) == 3, "GET history: группа не распарсилась")
        expect(item.get("cached") is False, "GET history: cached должен быть false")
        expect(item.get("durationMs") == 61234, "GET history: durationMs не сохранился")

    status, body = request("GET", f"{base}/api/v1/history?steamId=123")
    expect(status == 400, f"GET history с плохим id: код {status} вместо 400")


def check_stats(base: str) -> None:
    status, body = request("GET", f"{base}/api/v1/stats")
    expect(status == 200, f"GET stats: код {status} вместо 200")
    expect(body.get("totalChecks") == 2, f"stats: totalChecks {body.get('totalChecks')} вместо 2")
    expect(body.get("freshChecks") == 1, f"stats: freshChecks {body.get('freshChecks')} вместо 1")
    expect(body.get("cacheHits") == 1, f"stats: cacheHits {body.get('cacheHits')} вместо 1")
    expect(body.get("checksWithGroup") == 1, f"stats: checkWithGroup {body.get('checksWithGroup')} вместо 1")
    expect(body.get("uniqueProfiles") == 2, f"stats: uniqueProfiles {body.get('uniqueProfiles')} вместо 2")
    expect(
        body.get("avgFreshDurationMs") == 61234,
        f"stats: avgFreshDurationMs {body.get('avgFreshDurationMs')} вместо 61234",
    )
    expect(bool(body.get("lastCheckedAt")), "stats: lastCheckedAt пуст")

    top = body.get("topProfiles") or []
    expect(len(top) == 2, f"stats: в топе {len(top)} профилей вместо 2")
    ids = {row.get("steamId") for row in top}
    expect(ids == {CHECK_A, CHECK_B}, f"stats: в топе не те профили: {ids}")


def main() -> int:
    if not ensure_dependencies():
        return 1

    tmpdir = Path(tempfile.mkdtemp(prefix="history-check-"))
    db_file = tmpdir / "history.db"
    env = dict(os.environ, HISTORY_DB=str(db_file))

    port = free_port()
    base = f"http://127.0.0.1:{port}"
    process = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "app:app", "--host", "127.0.0.1", "--port", str(port)],
        cwd=str(ROOT),
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    try:
        deadline = time.monotonic() + 20
        while True:
            if process.poll() is not None:
                print("Сервис истории завершился до начала проверки.", file=sys.stderr)
                return 1
            try:
                status, _ = request("GET", f"{base}/health")
            except (urllib.error.URLError, ConnectionError, OSError):
                status = 0
            if status == 200:
                break
            if time.monotonic() > deadline:
                print("Сервис истории не поднялся за 20 секунд.", file=sys.stderr)
                return 1
            time.sleep(0.3)

        check_health(base)
        check_post(base)
        check_history(base)
        check_stats(base)

        # База должна остаться на диске: история переживает перезапуск.
        expect(db_file.exists(), "Файл базы не создан")
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()

    print(f"Проверок выполнено: {checks}")
    if problems:
        print("\nПРОБЛЕМЫ:")
        for problem in problems:
            print(" -", problem)
        return 1
    print("Сервис истории работает как ожидается.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
