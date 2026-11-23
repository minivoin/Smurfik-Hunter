# Сервис истории проверок: FastAPI + SQLite.
#
# Отдельный образ и отдельный контейнер: история не должна падать вместе с
# Java-API и должна переживать его перезапуски (SQLite лежит на volume).

FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

COPY history/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY history/app.py ./app.py

# Каталог под базу создаём заранее и отдаём пользователю: Docker инициализирует
# пустой named volume правами этой папки.
RUN useradd --create-home --user-group --shell /usr/sbin/nologin history \
    && mkdir -p /app/data \
    && chown -R history:history /app
USER history

VOLUME ["/app/data"]
EXPOSE 8000

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
