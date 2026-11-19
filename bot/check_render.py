# -*- coding: utf-8 -*-
"""Оффлайн-проверка отрисовки эмбедов.

Берёт ответы Java-API (если они сохранены в target/) или встроенные примеры и
печатает эмбеды, проверяя лимиты Discord. Не требует токена и сети.
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import bot  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent

PROFILE_SAMPLE = {
    "ok": True,
    "steamId": "76561198669833133",
    "name": "ЧВК ХимЗавод",
    "avatar": "https://avatars.steamstatic.com/492fe6aa15043001baad55e0f40f7ffdbc7643b0.jpg",
    "profileUrl": "https://steamcommunity.com/profiles/76561198669833133",
    "createdAt": "2026-07-26T20:38:10Z",
    "ageMonths": 3,
    "friends": 6,
    "friendsHidden": False,
    "defaultAvatar": False,
    "dayOneFriends": 0,
    "maxFriendsInWindow": 5,
    "windowDays": 1,
    "score": 57.5,
    "scoreMax": 100,
    "verdict": "SUSPICIOUS",
    "verdictLabel": "Подозрительный аккаунт",
    "checks": [
        {"code": "age", "title": "Возраст аккаунта", "points": 27.5, "maxPoints": 30.0,
         "detail": "3 мес. — моложе 3 лет"},
        {"code": "friends", "title": "Количество друзей", "points": 15.0, "maxPoints": 15.0,
         "detail": "6 — меньше 7"},
        {"code": "avatar", "title": "Аватар", "points": 0.0, "maxPoints": 10.0,
         "detail": "свой аватар"},
        {"code": "day1", "title": "Друзья Дня 1", "points": 0.0, "maxPoints": 20.0,
         "detail": "в первые 48 ч друзей не добавляли"},
        {"code": "pack", "title": "Плотность добавлений", "points": 15.0, "maxPoints": 25.0,
         "detail": "5 друзей за 1 дн."},
    ],
}

RELATED_SAMPLE = {
    "ok": True,
    "seed": "76561198890900243",
    "seedName": "Черепаха Хлофф",
    "durationMs": 61234,
    "cached": False,
    "candidatesChecked": 21,
    "group": ["76561198890900243", "76561198740323709", "76561198669833133"],
    "groupConfidence": 0.422,
    "confirmed": [
        {
            "steamId": "76561198740323709",
            "name": "Дядя Джон",
            "score": 0.464,
            "inGroup": True,
            "reasons": ["8 общих друзей (совпадение 0.89)", "круг друзей собран за один вечер"],
            "signals": {
                "commonFriends": 8, "shared": 0.89, "coAdd": 0.0, "creation": 0.08,
                "directFriend": 0.0, "profile": 0.03, "burst": 0.83,
            },
        }
    ],
    "unconfirmed": [
        {
            "steamId": "76561199014917917",
            "name": "Azdruba1l",
            "score": 0.419,
            "inGroup": False,
            "reasons": [
                "11 общих друзей (совпадение 0.39)",
                "не в группе: похож на цель, но не похож на остальных её участников",
            ],
            "signals": {
                "commonFriends": 11, "shared": 0.39, "coAdd": 0.0, "creation": 0.0,
                "directFriend": 1.0, "profile": 0.0, "burst": 0.0,
            },
        }
    ],
    "diagnostics": ["пар с участием цели: 21", "порог показа: 0.15, порог группы: 0.40"],
}


def load(name: str, fallback: dict) -> dict:
    path = ROOT / "target" / name
    if path.exists():
        print(f"Источник: {path}")
        return json.loads(path.read_text(encoding="utf-8-sig"))
    print(f"Источник: встроенный пример (target/{name} не найден)")
    return fallback


def check_embed(index: int, embed, problems: list[str]) -> None:
    payload = embed.to_dict()
    title = payload.get("title", "")
    description = payload.get("description", "")
    fields = payload.get("fields", [])
    print(f"\n[{index}] {title}")
    if description:
        print(f"  описание: {description.splitlines()[0]}")
    for field in fields:
        print(f"  - {field['name']}  ({len(field['value'])} симв.)")

    if len(title) > 256:
        problems.append(f"эмбед {index}: title {len(title)} > 256")
    if len(description) > 4096:
        problems.append(f"эмбед {index}: description {len(description)} > 4096")
    if len(fields) > 25:
        problems.append(f"эмбед {index}: полей {len(fields)} > 25")
    for field in fields:
        if len(field["name"]) > 256:
            problems.append(f"эмбед {index}: имя поля {len(field['name'])} > 256")
        if len(field["value"]) > 1024:
            problems.append(f"эмбед {index}: значение {len(field['value'])} > 1024")


def main() -> None:
    problems: list[str] = []
    index = 0

    print("=== Первичный анализ ===")
    index += 1
    check_embed(index, bot.profile_embed(load("profile-sample.json", PROFILE_SAMPLE)), problems)

    print("\n=== Смежные аккаунты ===")
    for embed in bot.build_related_embeds(load("detect-sample.json", RELATED_SAMPLE)):
        index += 1
        check_embed(index, embed, problems)

    print()
    if problems:
        print("ПРОБЛЕМЫ:")
        for problem in problems:
            print(" -", problem)
        raise SystemExit(1)
    print("Все лимиты Discord соблюдены.")


if __name__ == "__main__":
    main()
