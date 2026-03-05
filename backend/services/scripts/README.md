# Mnema Local AI Bootstrap

`mnema-local-ai` делает единый bootstrap локального AI-рантайма для Mnema:
- выбор пресета моделей или свои модели;
- автозапуск `ollama serve`;
- `ollama pull` выбранных моделей;
- опциональный запуск вашего FastAPI рантайма;
- генерация env-файла для `services/ai` (локальный-only режим, без внешних провайдеров).

## Быстрый старт

```bash
./scripts/mnema-local-ai setup
```

Непосредственно без интерактива:

```bash
./scripts/mnema-local-ai setup \
  --non-interactive \
  --preset rtx5090 \
  --env-file scripts/.mnema-local-ai.env \
  --print-env
```

С вашим FastAPI runtime:

```bash
./scripts/mnema-local-ai setup \
  --non-interactive \
  --preset balanced \
  --fastapi-cmd "uvicorn app.main:app --host 127.0.0.1 --port 8000" \
  --fastapi-health-url http://127.0.0.1:8000/health \
  --fastapi-base-url http://127.0.0.1:8000
```

## Пресеты

Список пресетов:

```bash
./scripts/mnema-local-ai list-presets
```

Windows:
- `scripts\\mnema-local-ai.cmd setup`
- `powershell -File scripts\\mnema-local-ai.ps1 setup`

## Что делать дальше

1. Запустить bootstrap и получить env-файл.
2. Поднять `services/ai` с `SPRING_PROFILES_ACTIVE=selfhost-local`.
3. Передать значения из env-файла в окружение вашего запуска (`docker compose`, shell, IDE Run Configuration).
