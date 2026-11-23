# Discord-бот «Охотника на смурфиков».
#
# Бот — отдельный процесс и отдельный образ: он перезапускается независимо от
# Java-API и не тянет за собой JDK.

FROM python:3.12-slim

# Не буферизовать stdout (иначе логи появляются в docker logs с задержкой)
# и не писать .pyc в контейнер.
ENV PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

# Сначала только зависимости: слой кешируется, пока requirements.txt не менялся.
COPY bot/requirements.txt ./bot/requirements.txt
RUN pip install --no-cache-dir -r bot/requirements.txt

COPY bot/ ./bot/

# Боту не нужны права root: он только читает код и ходит в сеть.
RUN useradd --create-home --user-group --shell /usr/sbin/nologin bot \
    && chown -R bot:bot /app
USER bot

# bot.py ищет .env рядом с собой; в контейнере его нет, но все настройки
# приходят переменными окружения из docker-compose.
CMD ["python", "bot/bot.py"]
