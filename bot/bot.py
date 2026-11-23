#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Discord-бот «Охотник на смурфиков».

Две функции, обе живут в Java — бот только показывает результат:

1. **Первичный анализ профиля** — кидаешь ссылку на Steam-профиль, бот за пару
   секунд отвечает баллом 0..100 и разбивкой по проверкам (возраст, друзья,
   аватар, друзья «Дня 1», плотность добавлений).
2. **Поиск смежных аккаунтов** — обход графа друзей, 40–120 секунд. Запускается
   командой ``/related`` или кнопкой под результатом первичного анализа.

Ссылку можно просто отправить сообщением в чат: бот сам её найдёт и сделает
первичный анализ (для этого нужно включить Message Content Intent в Discord
Developer Portal либо поставить ``DISCORD_LINK_AUTODETECT=0``).

Запуск:
    python bot/bot.py
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import sys
import time
from pathlib import Path
from typing import Any

import aiohttp
import discord
from discord import app_commands

BASE_DIR = Path(__file__).resolve().parent.parent

# Поиск смежных аккаунтов занимает 1–2 минуты: детектор докачивает друзей
# через Steam API с троттлингом.
RELATED_TIMEOUT_SECONDS = 300

# Ограничения Discord.
FIELD_NAME_LIMIT = 256
FIELD_VALUE_LIMIT = 1024
FIELDS_PER_EMBED = 25
MAX_EMBEDS = 10

# Сколько секунд один пользователь не может дёргать первичный анализ по ссылке
# из чата (защита от флуда одинаковыми ссылками).
LINK_ANALYSIS_COOLDOWN = 10.0

COLOR_SMURF = 0xC0392B
COLOR_SUSPICIOUS = 0xE67E22
COLOR_NORMAL = 0x2ECC71
COLOR_GROUP = 0xC0392B
COLOR_SOFT = 0xE67E22
COLOR_MUTED = 0x7F8C8D

STEAM_LINK_RE = re.compile(
    r"(?:https?://)?(?:www\.)?steamcommunity\.com/(?:profiles/\d{17}|id/[A-Za-z0-9_-]+)/?",
    re.IGNORECASE,
)
STEAM_ID_RE = re.compile(r"\b7656119\d{10}\b")

log = logging.getLogger("smurfik")


# ----------------------------------------------------------------------
# Настройки
# ----------------------------------------------------------------------

def load_dotenv() -> None:
    """Читает .env из корня проекта, не перетирая уже заданные переменные."""
    path = BASE_DIR / ".env"
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def env_flag(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    return raw.strip().lower() not in {"0", "false", "no", "off"}


load_dotenv()


class Config:
    def __init__(self) -> None:
        self.token = os.getenv("DISCORD_TOKEN", "").strip()
        self.api_base = os.getenv("API_BASE_URL", "http://127.0.0.1:8080").strip().rstrip("/")
        self.guild_id = os.getenv("DISCORD_GUILD_ID", "").strip()
        # Читать ссылки из обычных сообщений (требует Message Content Intent).
        self.link_autodetect = env_flag("DISCORD_LINK_AUTODETECT", True)
        # Сервис истории. Пусто = выключен: бот работает и без него.
        self.history_base = os.getenv("HISTORY_BASE_URL", "").strip().rstrip("/")


CONFIG = Config()


# ----------------------------------------------------------------------
# Клиент Java-API
# ----------------------------------------------------------------------

class ApiError(Exception):
    """Ошибка, которую можно показать пользователю как есть."""

    def __init__(self, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.code = code


async def call_api(
    session: aiohttp.ClientSession,
    path: str,
    payload: dict[str, Any],
    timeout: int,
) -> dict[str, Any]:
    """POST на Java-API. Возвращает разобранный JSON или бросает ApiError."""
    url = f"{CONFIG.api_base}{path}"
    try:
        async with session.post(
            url, json=payload, timeout=aiohttp.ClientTimeout(total=timeout)
        ) as response:
            body = await response.text()
            data = _parse_json(body)

            if response.status == 200 and data.get("ok"):
                return data

            message = (data.get("error") or "").strip()
            if not message:
                message = f"Сервер анализа ответил кодом {response.status}."
            raise ApiError(message, data.get("code"))

    except ApiError:
        raise
    except TimeoutError as error:
        raise ApiError(
            f"Анализ идёт дольше {timeout} секунд и был прерван. "
            "Для поиска смежных аккаунтов попробуйте глубину 1.",
            "TIMEOUT",
        ) from error
    except aiohttp.ClientError as error:
        raise ApiError(
            "Сервер анализа не запущен или недоступен.\n"
            f"Адрес: {CONFIG.api_base}\n"
            "Запустите `docker compose up -d` или `powershell -ExecutionPolicy Bypass -File run-api.ps1`.",
            "UNREACHABLE",
        ) from error


async def call_profile(session: aiohttp.ClientSession, profile: str) -> dict[str, Any]:
    return await call_api(session, "/api/v1/profile", {"steamId": profile}, 60)


async def call_related(
    session: aiohttp.ClientSession, profile: str, depth: int
) -> dict[str, Any]:
    return await call_api(
        session, "/api/v1/detect", {"steamId": profile, "depth": depth}, RELATED_TIMEOUT_SECONDS
    )


async def save_history(
    session: aiohttp.ClientSession, data: dict[str, Any], depth: int
) -> None:
    """Отправляет итог поиска в сервис истории.

    История — вспомогательная функция: если сервис не запущен или упал, поиск
    всё равно считается успешным, поэтому здесь нет ни ApiError, ни ответа
    пользователю. Только предупреждение в лог.
    """
    if not CONFIG.history_base:
        return

    payload = {
        "steamId": data.get("seed"),
        "seedName": data.get("seedName"),
        "depth": depth,
        "group": data.get("group") or [],
        "groupConfidence": float(data.get("groupConfidence") or 0.0),
        "candidatesChecked": int(data.get("candidatesChecked") or 0),
        "durationMs": int(data.get("durationMs") or 0),
        "cached": bool(data.get("cached")),
    }

    try:
        async with session.post(
            f"{CONFIG.history_base}/api/v1/history",
            json=payload,
            timeout=aiohttp.ClientTimeout(total=5),
        ) as response:
            if response.status >= 300:
                body = await response.text()
                log.warning("История ответила кодом %s: %s", response.status, body[:200])
    except Exception as error:
        log.warning("Не удалось сохранить историю: %s", error)


def _parse_json(body: str) -> dict[str, Any]:
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError:
        return {"ok": False, "error": "Сервер вернул не JSON. Смотрите его консоль."}
    return parsed if isinstance(parsed, dict) else {"ok": False, "error": "Неожиданный ответ сервера."}


# ----------------------------------------------------------------------
# Общее форматирование
# ----------------------------------------------------------------------

def truncate(value: str, limit: int) -> str:
    value = value.strip()
    if len(value) <= limit:
        return value
    return value[: limit - 1].rstrip() + "…"


def profile_link(steam_id: str) -> str:
    return f"https://steamcommunity.com/profiles/{steam_id}"


def error_embed(message: str, code: str | None = None) -> discord.Embed:
    embed = discord.Embed(title="Не получилось", description=truncate(message, 4000), color=COLOR_MUTED)
    if code:
        embed.set_footer(text=f"код: {code}")
    return embed


# ----------------------------------------------------------------------
# Первичный анализ
# ----------------------------------------------------------------------

VERDICTS = {
    "SMURF": (COLOR_SMURF, "🔴", "Похоже на смурф"),
    "SUSPICIOUS": (COLOR_SUSPICIOUS, "🟠", "Подозрительный аккаунт"),
    "NORMAL": (COLOR_NORMAL, "🟢", "Обычный аккаунт"),
}


def score_bar(score: float, score_max: int, width: int = 10) -> str:
    ratio = 0.0 if score_max <= 0 else max(0.0, min(1.0, score / score_max))
    filled = int(round(ratio * width))
    return "█" * filled + "░" * (width - filled)


def profile_embed(data: dict[str, Any]) -> discord.Embed:
    color, mark, fallback_label = VERDICTS.get(
        str(data.get("verdict") or ""), (COLOR_MUTED, "⚪", "Без вердикта")
    )
    label = data.get("verdictLabel") or fallback_label
    score = float(data.get("score") or 0.0)
    score_max = int(data.get("scoreMax") or 100)
    steam_id = str(data.get("steamId") or "")
    name = discord.utils.escape_markdown(str(data.get("name") or "без ника"))

    embed = discord.Embed(
        title=f"Первичный анализ: {name}",
        url=profile_link(steam_id) if steam_id else None,
        color=color,
    )
    embed.description = (
        f"{score_bar(score, score_max)}  **{score:.0f} / {score_max}**\n"
        f"{mark} {label}"
    )

    age = data.get("ageMonths")
    created = str(data.get("createdAt") or "")[:10]
    if age is None or data.get("createdAt") is None:
        embed.add_field(name="Возраст", value="скрыт", inline=True)
    else:
        embed.add_field(name="Возраст", value=f"{int(age)} мес.\n{created}", inline=True)

    friends = int(data.get("friends") or 0)
    friends_text = f"{friends}" + ("\nсписок скрыт" if data.get("friendsHidden") else "")
    embed.add_field(name="Друзей", value=friends_text, inline=True)

    embed.add_field(
        name="Друзья Дня 1",
        value=f"{int(data.get('dayOneFriends') or 0)} за 48 ч",
        inline=True,
    )
    embed.add_field(
        name="Плотная пачка",
        value=f"{int(data.get('maxFriendsInWindow') or 0)} за {int(data.get('windowDays') or 0)} дн.",
        inline=True,
    )
    embed.add_field(
        name="Аватар",
        value="стандартный" if data.get("defaultAvatar") else "свой",
        inline=True,
    )

    checks = data.get("checks") or []
    if checks:
        lines = []
        for check in checks:
            points = float(check.get("points") or 0.0)
            lines.append(
                f"• **{check.get('title')}**: {check.get('detail')} (+{points:g})"
            )
        embed.add_field(
            name="Из чего сложился балл",
            value=truncate("\n".join(lines), FIELD_VALUE_LIMIT),
            inline=False,
        )

    if data.get("friendsHidden"):
        embed.add_field(
            name="Учтите",
            value="Список друзей закрыт, поэтому проверки «Друзья Дня 1» и "
            "«Плотная пачка» не выполнялись.",
            inline=False,
        )

    embed.set_footer(
        text="0–100 — эвристика по открытым данным Steam, а не доказательство. "
        "Кнопка ниже ищет смежные аккаунты."
    )
    return embed


# ----------------------------------------------------------------------
# Смежные аккаунты
# ----------------------------------------------------------------------

def signals_line(signals: dict[str, Any] | None) -> str:
    """Компактная разбивка скора: видно, на чём именно основан вывод."""
    if not signals:
        return ""
    parts = [f"общих друзей: {signals.get('commonFriends', 0)}"]
    labels = [
        ("shared", "друзья"),
        ("coAdd", "добавления"),
        ("creation", "дата"),
        ("directFriend", "дружба"),
        ("profile", "профиль"),
        ("burst", "залп"),
    ]
    for key, title in labels:
        value = signals.get(key)
        if value is None or float(value) <= 0.004:
            continue
        parts.append(f"{title} {float(value):.2f}")
    return " · ".join(parts)


def candidate_body(item: dict[str, Any]) -> str:
    lines: list[str] = []
    reasons = item.get("reasons") or []
    for reason in reasons:
        lines.append(f"• {reason}")
    if not reasons:
        lines.append("• явных причин нет, набрал скор на сумме слабых сигналов")

    signals = signals_line(item.get("signals"))
    if signals:
        lines.append(f"_{signals}_")
    steam_id = str(item.get("steamId") or "")
    if steam_id:
        lines.append(profile_link(steam_id))
    return truncate("\n".join(lines), FIELD_VALUE_LIMIT)


def candidate_name(index: int, item: dict[str, Any]) -> str:
    name = discord.utils.escape_markdown(str(item.get("name") or "без ника"))
    score = float(item.get("score") or 0.0)
    return truncate(f"{index}. {name} — {score:.2f}", FIELD_NAME_LIMIT)


def candidate_embeds(title: str, items: list[dict[str, Any]], color: int) -> list[discord.Embed]:
    """Раскладывает кандидатов по эмбедам, соблюдая лимиты Discord."""
    if not items:
        return []

    embeds: list[discord.Embed] = []
    chunk: list[tuple[str, str]] = []

    def flush() -> None:
        if not chunk:
            return
        suffix = "" if not embeds else " (продолжение)"
        embed = discord.Embed(title=truncate(title + suffix, FIELD_NAME_LIMIT), color=color)
        for name, value in chunk:
            embed.add_field(name=name, value=value, inline=False)
        embeds.append(embed)
        chunk.clear()

    for index, item in enumerate(items, start=1):
        chunk.append((candidate_name(index, item), candidate_body(item)))
        if len(chunk) >= FIELDS_PER_EMBED:
            flush()
    flush()
    return embeds


def header_embed(data: dict[str, Any]) -> discord.Embed:
    group = data.get("group") or []
    confirmed = data.get("confirmed") or []
    unconfirmed = data.get("unconfirmed") or []
    duration = float(data.get("durationMs") or 0) / 1000.0
    seed = str(data.get("seed") or "")

    embed = discord.Embed(
        title="Смежные аккаунты",
        description=(
            f"Цель: **{discord.utils.escape_markdown(str(data.get('seedName') or '—'))}**\n"
            f"`{seed}`"
        ),
        url=profile_link(seed) if seed else None,
        color=COLOR_GROUP if len(group) > 1 else COLOR_MUTED,
    )

    if len(group) > 1:
        embed.add_field(name="Аккаунтов в группе", value=str(len(group)), inline=True)
        embed.add_field(
            name="Уверенность",
            value=f"{float(data.get('groupConfidence') or 0):.2f}",
            inline=True,
        )
    else:
        embed.add_field(name="Итог", value="подтверждённых альтов нет", inline=True)

    embed.add_field(name="Проверено кандидатов", value=str(data.get("candidatesChecked", 0)), inline=True)
    embed.add_field(
        name="Подтверждено / похожих",
        value=f"{len(confirmed)} / {len(unconfirmed)}",
        inline=True,
    )

    timing = f"{duration:.1f} с"
    if data.get("cached"):
        timing += " (из кеша)"
    embed.add_field(name="Время", value=timing, inline=True)

    embed.set_footer(text="Число рядом с аккаунтом — схожесть 0..1, а не вероятность")
    return embed


def diagnostics_embed(data: dict[str, Any]) -> discord.Embed | None:
    lines = data.get("diagnostics") or []
    if not lines:
        return None
    text = truncate("\n".join(f"• {line}" for line in lines), 4000)
    return discord.Embed(title="Диагностика", description=text, color=COLOR_MUTED)


def build_related_embeds(data: dict[str, Any]) -> list[discord.Embed]:
    group = data.get("group") or []
    embeds = [header_embed(data)]

    if len(group) > 1:
        chain = "\n".join(
            f"• {item.get('name')} — `{item.get('steamId')}`" for item in data.get("confirmed") or []
        )
        if data.get("seedName"):
            chain = f"• {data['seedName']} — `{data.get('seed')}` (цель)\n" + chain
        embeds[0].add_field(
            name="Один человек", value=truncate(chain, FIELD_VALUE_LIMIT), inline=False
        )

    embeds.extend(
        candidate_embeds("ОДИН ЧЕЛОВЕК (подтверждено)", data.get("confirmed") or [], COLOR_GROUP)
    )
    embeds.extend(
        candidate_embeds(
            "ПОХОЖИ НА ЦЕЛЬ, НЕ ПОДТВЕРЖДЕНЫ",
            data.get("unconfirmed") or [],
            COLOR_SOFT,
        )
    )

    if len(embeds) > 1 and (data.get("unconfirmed") or []):
        embeds[-1].set_footer(
            text="Эти аккаунты делят друзей с целью, но не проходят проверку на совпадение "
            "со всей группой. Обычно это просто знакомые."
        )

    diag = diagnostics_embed(data)
    if diag is not None:
        embeds.append(diag)

    return embeds[:MAX_EMBEDS]


def history_embed(data: dict[str, Any]) -> discord.Embed:
    """Карточка с последними проверками профиля из сервиса истории."""
    steam_id = str(data.get("steamId") or "")
    items = data.get("items") or []

    embed = discord.Embed(
        title="История проверок",
        description=f"`{steam_id}`" if items else f"`{steam_id}`\nПроверок пока не было.",
        url=profile_link(steam_id) if steam_id else None,
        color=COLOR_MUTED,
    )

    for item in items[:10]:
        checked = str(item.get("checkedAt") or "").replace("T", " ")[:16]
        group_size = int(item.get("groupSize") or 0)
        confidence = float(item.get("groupConfidence") or 0.0)
        duration = float(item.get("durationMs") or 0) / 1000.0

        parts = [f"группа: {group_size}"]
        if group_size > 1:
            parts[-1] += f" ({confidence:.2f})"
        parts.append(f"кандидатов: {int(item.get('candidatesChecked') or 0)}")
        parts.append(f"{duration:.0f} с" + (" (кеш)" if item.get("cached") else ""))
        parts.append(f"глубина {int(item.get('depth') or 0)}")

        embed.add_field(
            name=truncate(checked or "без даты", FIELD_NAME_LIMIT),
            value=" · ".join(parts),
            inline=False,
        )

    if items:
        embed.set_footer(text="Время указано в UTC · хранится в истории, а не в кеше Java")
    return embed


# ----------------------------------------------------------------------
# Кнопка «искать смежные»
# ----------------------------------------------------------------------

class RelatedView(discord.ui.View):
    """Кнопка под первичным анализом: запускает тяжёлый поиск по тому же профилю."""

    def __init__(self, profile: str) -> None:
        super().__init__(timeout=15 * 60)
        self.profile = profile

    @discord.ui.button(label="Найти смежные аккаунты", style=discord.ButtonStyle.primary, emoji="🔎")
    async def find_related(
        self, interaction: discord.Interaction, button: discord.ui.Button
    ) -> None:
        await interaction.response.send_message(
            f"Ищу смежные аккаунты для `{self.profile}`… Это обычно 1–2 минуты."
        )
        placeholder = await interaction.original_response()

        # Кнопку гасим после ответа: если сначала полезть в API Discord за
        # взаимодействием, можно выйти за трёхсекундный дедлайн.
        button.disabled = True
        await interaction.message.edit(view=self)
        self.stop()

        assert session is not None

        try:
            data = await call_related(session, self.profile, 2)
        except ApiError as error:
            await placeholder.edit(content=None, embed=error_embed(error.message, error.code))
            return
        except Exception:
            log.exception("Ошибка поиска смежных аккаунтов для %s", self.profile)
            await placeholder.edit(
                content=None, embed=error_embed("Непредвиденная ошибка. Подробности в консоли бота.")
            )
            return

        await placeholder.edit(content=None, embeds=build_related_embeds(data))
        await save_history(session, data, 2)


# ----------------------------------------------------------------------
# Бот
# ----------------------------------------------------------------------

intents = discord.Intents.default()
intents.message_content = CONFIG.link_autodetect
client = discord.Client(intents=intents)
tree = app_commands.CommandTree(client)
session: aiohttp.ClientSession | None = None

# Последний первичный анализ по ссылке из чата: user_id -> monotonic time.
link_activity: dict[int, float] = {}


def extract_steam_id(text: str) -> str | None:
    """Достаёт SteamID64 из строки. Для красивого id/имя вернёт None: без Steam
    такой ник в номер не превратить, а сервис истории хранит только номера."""
    match = STEAM_ID_RE.search(text or "")
    return match.group(0) if match else None


def extract_profile(text: str) -> str | None:
    """Достаёт из сообщения ссылку на Steam-профиль или одиночный SteamID64."""
    if not text:
        return None
    match = STEAM_LINK_RE.search(text)
    if match:
        return match.group(0).rstrip("/")
    stripped = text.strip()
    if STEAM_ID_RE.fullmatch(stripped):
        return stripped
    return None


def link_allowed(user_id: int) -> bool:
    now = time.monotonic()
    last = link_activity.get(user_id, 0.0)
    if now - last < LINK_ANALYSIS_COOLDOWN:
        return False
    link_activity[user_id] = now
    return True


@client.event
async def on_ready() -> None:
    global session
    if session is None or session.closed:
        session = aiohttp.ClientSession()

    log.info("Бот вошёл как %s", client.user)
    log.info("Первичный анализ по ссылке в чате: %s", "включён" if CONFIG.link_autodetect else "выключен")

    try:
        if CONFIG.guild_id:
            guild = discord.Object(id=int(CONFIG.guild_id))
            tree.copy_global_to(guild=guild)
            synced = await tree.sync(guild=guild)
            log.info("Команды синхронизированы для сервера %s: %d", CONFIG.guild_id, len(synced))
        else:
            synced = await tree.sync()
            log.info("Команды синхронизированы глобально: %d (может занять до часа)", len(synced))
    except Exception:
        log.exception("Не удалось синхронизировать команды")

    try:
        async with session.get(f"{CONFIG.api_base}/health", timeout=aiohttp.ClientTimeout(total=5)) as response:
            health = await response.json()
        log.info("Java-API доступен: %s", CONFIG.api_base)
        if not health.get("steamKeyConfigured"):
            log.warning("Java-API не видит STEAM_API_KEY — анализ будет падать")
    except Exception:
        log.warning("Java-API недоступен по адресу %s. Запустите com.papasmurf.web.ApiServer", CONFIG.api_base)

    if CONFIG.history_base:
        try:
            async with session.get(
                f"{CONFIG.history_base}/health", timeout=aiohttp.ClientTimeout(total=5)
            ) as response:
                await response.json()
            log.info("Сервис истории доступен: %s", CONFIG.history_base)
        except Exception:
            log.warning("Сервис истории недоступен: %s", CONFIG.history_base)
    else:
        log.info("Сервис истории отключён (HISTORY_BASE_URL не задан)")


@client.event
async def on_disconnect() -> None:
    log.warning("Соединение с Discord потеряно, переподключаюсь")


@client.event
async def on_resumed() -> None:
    log.info("Соединение с Discord восстановлено")


@client.event
async def on_message(message: discord.Message) -> None:
    """Ссылка в чате → сразу первичный анализ (быстро), тяжёлый поиск — по кнопке."""
    if not CONFIG.link_autodetect or message.author.bot:
        return

    profile = extract_profile(message.content)
    if profile is None:
        return
    if not link_allowed(message.author.id):
        return
    if session is None:
        return

    try:
        async with message.channel.typing():
            data = await call_profile(session, profile)
    except ApiError as error:
        await message.reply(embed=error_embed(error.message, error.code), mention_author=False)
        return
    except Exception:
        log.exception("Ошибка первичного анализа %s", profile)
        await message.reply(
            embed=error_embed("Непредвиденная ошибка. Подробности в консоли бота."),
            mention_author=False,
        )
        return

    await message.reply(
        embeds=[profile_embed(data)], view=RelatedView(profile), mention_author=False
    )


@tree.command(name="profile", description="Первичный анализ профиля Steam по ссылке")
@app_commands.describe(profile="Ссылка на профиль, SteamID64 или имя из steamcommunity.com/id/имя")
@app_commands.checks.cooldown(1, 10.0, key=lambda interaction: interaction.user.id)
async def profile_command(interaction: discord.Interaction, profile: str) -> None:
    await interaction.response.defer(thinking=True)
    assert session is not None

    try:
        data = await call_profile(session, profile.strip())
    except ApiError as error:
        await interaction.edit_original_response(embed=error_embed(error.message, error.code))
        return
    except Exception:
        log.exception("Ошибка первичного анализа %s", profile)
        await interaction.edit_original_response(
            embed=error_embed("Непредвиденная ошибка. Подробности в консоли бота.")
        )
        return

    await interaction.edit_original_response(
        embeds=[profile_embed(data)], view=RelatedView(profile.strip())
    )


@tree.command(name="related", description="Найти аккаунты, которые могут принадлежать тому же человеку")
@app_commands.describe(
    profile="Ссылка на профиль, SteamID64 или имя из steamcommunity.com/id/имя",
    depth="Насколько глубоко копать: 1 — быстро, 3 — максимально широко",
)
@app_commands.choices(
    depth=[
        app_commands.Choice(name="1 — только прямые друзья (быстро)", value=1),
        app_commands.Choice(name="2 — друзья друзей (по умолчанию)", value=2),
        app_commands.Choice(name="3 — широкий охват (долго)", value=3),
    ]
)
@app_commands.checks.cooldown(1, 20.0, key=lambda interaction: interaction.user.id)
async def related_command(
    interaction: discord.Interaction,
    profile: str,
    depth: app_commands.Choice[int] | None = None,
) -> None:
    await interaction.response.defer(thinking=True)
    assert session is not None

    try:
        data = await call_related(session, profile.strip(), depth.value if depth else 2)
    except ApiError as error:
        await interaction.edit_original_response(embed=error_embed(error.message, error.code))
        return
    except Exception:
        log.exception("Ошибка поиска смежных аккаунтов для %s", profile)
        await interaction.edit_original_response(
            embed=error_embed("Непредвиденная ошибка. Подробности в консоли бота.")
        )
        return

    await interaction.edit_original_response(embeds=build_related_embeds(data))
    await save_history(session, data, depth.value if depth else 2)


@tree.command(name="history", description="Последние проверки профиля из сервиса истории")
@app_commands.describe(profile="SteamID64 (17 цифр) или ссылка .../profiles/...")
@app_commands.checks.cooldown(3, 10.0, key=lambda interaction: interaction.user.id)
async def history_command(interaction: discord.Interaction, profile: str) -> None:
    if not CONFIG.history_base:
        await interaction.response.send_message(
            "Сервис истории не подключён: задайте HISTORY_BASE_URL в .env.", ephemeral=True
        )
        return

    steam_id = extract_steam_id(profile)
    if steam_id is None:
        await interaction.response.send_message(
            "Нужен SteamID64 (17 цифр) или ссылка вида .../profiles/7656119…",
            ephemeral=True,
        )
        return

    await interaction.response.defer(thinking=True)
    assert session is not None

    url = f"{CONFIG.history_base}/api/v1/history"
    try:
        async with session.get(
            url,
            params={"steamId": steam_id, "limit": 10},
            timeout=aiohttp.ClientTimeout(total=10),
        ) as response:
            data = await response.json(content_type=None)
            if response.status != 200 or not data.get("ok"):
                message = (data.get("error") or "").strip()
                if not message:
                    message = f"Сервис истории ответил кодом {response.status}."
                await interaction.edit_original_response(embed=error_embed(message))
                return
    except Exception:
        log.exception("Ошибка запроса истории для %s", steam_id)
        await interaction.edit_original_response(
            embed=error_embed(
                "Сервис истории недоступен. Проверьте, что запущен контейнер history."
            )
        )
        return

    await interaction.edit_original_response(embeds=[history_embed(data)])


@tree.error
async def on_tree_error(interaction: discord.Interaction, error: app_commands.AppCommandError) -> None:
    if isinstance(error, app_commands.CommandOnCooldown):
        await interaction.response.send_message(
            f"Слишком часто. Подождите {error.retry_after:.0f} с: один анализ — это десятки запросов к Steam.",
            ephemeral=True,
        )
        return
    log.exception("Ошибка команды", exc_info=error)
    message = "Команда не выполнилась. Подробности в консоли бота."
    if interaction.response.is_done():
        await interaction.followup.send(message, ephemeral=True)
    else:
        await interaction.response.send_message(message, ephemeral=True)


async def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    if not CONFIG.token:
        print("Не найден DISCORD_TOKEN. Добавьте в .env строку DISCORD_TOKEN=...", file=sys.stderr)
        raise SystemExit(1)

    print("Охотник на смурфиков: Discord-бот запускается")
    print(f"  Java-API: {CONFIG.api_base}")
    print(f"  История: {CONFIG.history_base or 'выключена (HISTORY_BASE_URL не задан)'}")
    print(f"  Анализ ссылок из чата: {'вкл' if CONFIG.link_autodetect else 'выкл'}")
    try:
        async with client:
            await client.start(CONFIG.token)
    except discord.PrivilegedIntentsRequired:
        print(
            "\nDiscord не разрешил читать содержимое сообщений (Message Content Intent).\n"
            "Вариант 1: Developer Portal → ваш бот → Bot → Privileged Gateway Intents →\n"
            "           включить MESSAGE CONTENT INTENT и перезапустить бота.\n"
            "Вариант 2: добавить в .env строку DISCORD_LINK_AUTODETECT=0 — тогда бот\n"
            "           запустится, но будет работать только через /profile и /related.",
            file=sys.stderr,
        )
        raise SystemExit(1)
    except discord.LoginFailure:
        print("Discord не принял токен: проверьте DISCORD_TOKEN в .env", file=sys.stderr)
        raise SystemExit(1)
    finally:
        if session and not session.closed:
            await session.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nОстановлено пользователем")
