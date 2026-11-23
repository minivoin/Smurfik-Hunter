#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сервис истории проверок «Охотника на смурфиков».

Зачем он нужен: кеш Java живёт 30 минут и стирается при перезапуске, а история
результатов нужна надолго — что проверяли, когда, чем закончилось. Сервис
хранит только итог поиска (не списки друзей) в SQLite и отдаёт его по HTTP.

Ручки:
    POST /api/v1/history            сохранить результат поиска
    GET  /api/v1/history?steamId=   последние проверки профиля
    GET  /api/v1/stats              агрегаты по всей базе
    GET  /health                    жив ли сервис и открывается ли база

Запуск:
    uvicorn app:app --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import json
import os
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator

DEFAULT_DB = Path(__file__).resolve().parent / "data" / "history.db"
MAX_LIMIT = 50
SCHEMA = """
CREATE TABLE IF NOT EXISTS checks (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    checked_at         TEXT    NOT NULL,
    steam_id           TEXT    NOT NULL,
    seed_name          TEXT,
    depth              INTEGER NOT NULL,
    group_ids          TEXT    NOT NULL,
    group_size         INTEGER NOT NULL,
    group_confidence   REAL    NOT NULL,
    candidates_checked INTEGER NOT NULL,
    duration_ms        INTEGER NOT NULL,
    cached             INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_checks_steam ON checks (steam_id, checked_at DESC);
"""


# ----------------------------------------------------------------------
# База
# ----------------------------------------------------------------------

def db_path() -> Path:
    """Путь к файлу базы: по умолчанию рядом с этим файлом, в Docker — HISTORY_DB."""
    path = Path(os.getenv("HISTORY_DB", str(DEFAULT_DB)))
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


@contextmanager
def db() -> Iterator[sqlite3.Connection]:
    """Соединение на запрос: SQLite быстрый, а глобальное пришлось бы запирать."""
    connection = sqlite3.connect(db_path(), timeout=10)
    connection.row_factory = sqlite3.Row
    try:
        yield connection
        connection.commit()
    finally:
        connection.close()


def init_db() -> None:
    with db() as connection:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.executescript(SCHEMA)


init_db()


# ----------------------------------------------------------------------
# Модели и ошибки
# ----------------------------------------------------------------------

class CheckIn(BaseModel):
    """То, что присылает бот после удачного поиска смежных аккаунтов."""

    steamId: str
    seedName: str | None = None
    depth: int = 2
    group: list[str] = Field(default_factory=list)
    groupConfidence: float = 0.0
    candidatesChecked: int = 0
    durationMs: int = 0
    cached: bool = False

    @field_validator("steamId")
    @classmethod
    def valid_steam_id(cls, value: str) -> str:
        value = value.strip()
        if not (len(value) == 17 and value.isdigit()):
            raise ValueError("steamId должен быть SteamID64 (17 цифр)")
        return value

    @field_validator("depth")
    @classmethod
    def valid_depth(cls, value: int) -> int:
        if not 1 <= value <= 3:
            raise ValueError("depth должен быть 1..3")
        return value

    @field_validator("group")
    @classmethod
    def valid_group(cls, value: list[str]) -> list[str]:
        cleaned = []
        for item in value:
            item = item.strip()
            if not (len(item) == 17 and item.isdigit()):
                raise ValueError("group должен содержать только SteamID64 (17 цифр)")
            cleaned.append(item)
        return cleaned

    @field_validator("groupConfidence")
    @classmethod
    def valid_confidence(cls, value: float) -> float:
        if not 0.0 <= value <= 1.0:
            raise ValueError("groupConfidence должен быть в диапазоне 0..1")
        return value


ERROR_CODES = {400: "BAD_REQUEST", 404: "NOT_FOUND", 422: "INVALID_REQUEST"}

# Приложение создаётся до обработчиков ошибок: декоратор @app.exception_handler
# обращается к нему сразу во время импорта.
app = FastAPI(title="Smurfik-Hunter History", version="1.0")


@app.exception_handler(HTTPException)
async def on_http_error(request: Request, error: HTTPException) -> JSONResponse:
    return JSONResponse(
        status_code=error.status_code,
        content={
            "ok": False,
            "code": ERROR_CODES.get(error.status_code, "ERROR"),
            "error": str(error.detail),
        },
    )


@app.exception_handler(RequestValidationError)
async def on_validation_error(request: Request, error: RequestValidationError) -> JSONResponse:
    first = error.errors()[0] if error.errors() else {}
    message = first.get("msg", "Некорректный запрос")
    field = ".".join(str(part) for part in first.get("loc", []) if part != "body")
    return JSONResponse(
        status_code=422,
        content={
            "ok": False,
            "code": "INVALID_REQUEST",
            "error": f"{field}: {message}" if field else message,
        },
    )


# ----------------------------------------------------------------------
# Ручки
# ----------------------------------------------------------------------

@app.get("/")
def root() -> dict[str, Any]:
    return {
        "ok": True,
        "service": "history",
        "endpoints": [
            "POST /api/v1/history",
            "GET /api/v1/history?steamId=...&limit=10",
            "GET /api/v1/stats",
            "GET /health",
        ],
    }


@app.get("/health")
def health() -> dict[str, Any]:
    try:
        with db() as connection:
            connection.execute("SELECT 1").fetchone()
    except sqlite3.Error as error:
        raise HTTPException(503, f"База недоступна: {error}") from error
    return {"ok": True, "status": "ok", "db": str(db_path())}


@app.post("/api/v1/history", status_code=201)
def add_check(item: CheckIn) -> dict[str, Any]:
    checked_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
    group_ids = json.dumps(item.group, ensure_ascii=False)

    with db() as connection:
        cursor = connection.execute(
            "INSERT INTO checks (checked_at, steam_id, seed_name, depth, group_ids, "
            "group_size, group_confidence, candidates_checked, duration_ms, cached) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                checked_at,
                item.steamId,
                item.seedName,
                item.depth,
                group_ids,
                len(item.group),
                float(item.groupConfidence),
                int(item.candidatesChecked),
                int(item.durationMs),
                1 if item.cached else 0,
            ),
        )
        new_id = cursor.lastrowid

    return {"ok": True, "id": new_id, "checkedAt": checked_at}


@app.get("/api/v1/history")
def history(
    steamId: str = Query(..., description="SteamID64 профиля"),
    limit: int = Query(10, ge=1, le=MAX_LIMIT),
) -> dict[str, Any]:
    if not (len(steamId) == 17 and steamId.isdigit()):
        raise HTTPException(400, "steamId должен быть SteamID64 (17 цифр)")

    with db() as connection:
        rows = connection.execute(
            "SELECT * FROM checks WHERE steam_id = ? ORDER BY checked_at DESC, id DESC LIMIT ?",
            (steamId, limit),
        ).fetchall()

    items = [
        {
            "id": row["id"],
            "checkedAt": row["checked_at"],
            "steamId": row["steam_id"],
            "seedName": row["seed_name"],
            "depth": row["depth"],
            "group": json.loads(row["group_ids"]),
            "groupSize": row["group_size"],
            "groupConfidence": row["group_confidence"],
            "candidatesChecked": row["candidates_checked"],
            "durationMs": row["duration_ms"],
            "cached": bool(row["cached"]),
        }
        for row in rows
    ]
    return {"ok": True, "steamId": steamId, "count": len(items), "items": items}


@app.get("/api/v1/stats")
def stats() -> dict[str, Any]:
    with db() as connection:
        totals = connection.execute(
            "SELECT COUNT(*) AS total, "
            "SUM(CASE WHEN cached = 0 THEN 1 ELSE 0 END) AS fresh, "
            "SUM(cached) AS hits, "
            "SUM(CASE WHEN group_size > 1 THEN 1 ELSE 0 END) AS with_group, "
            "COUNT(DISTINCT steam_id) AS profiles, "
            "AVG(CASE WHEN cached = 0 THEN duration_ms END) AS avg_duration, "
            "MAX(checked_at) AS last_checked "
            "FROM checks"
        ).fetchone()

        top = connection.execute(
            "SELECT steam_id, MAX(seed_name) AS seed_name, COUNT(*) AS checks, "
            "MAX(group_size) AS best_group "
            "FROM checks GROUP BY steam_id ORDER BY checks DESC, steam_id LIMIT 5"
        ).fetchall()

    return {
        "ok": True,
        "totalChecks": totals["total"] or 0,
        "freshChecks": totals["fresh"] or 0,
        "cacheHits": totals["hits"] or 0,
        "checksWithGroup": totals["with_group"] or 0,
        "uniqueProfiles": totals["profiles"] or 0,
        "avgFreshDurationMs": round(totals["avg_duration"] or 0),
        "lastCheckedAt": totals["last_checked"],
        "topProfiles": [
            {
                "steamId": row["steam_id"],
                "seedName": row["seed_name"],
                "checks": row["checks"],
                "bestGroupSize": row["best_group"],
            }
            for row in top
        ],
    }
