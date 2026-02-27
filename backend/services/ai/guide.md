# AI Service Guide

## Кратко
`services/ai` отвечает за AI-задачи: генерация карточек, аудит, заполнение пропусков, TTS и AI-импорт.  
Сервис принимает job-запрос, кладет задачу в БД, воркер обрабатывает ее асинхронно и пишет summary результата.

## Основные endpoint'ы
- `POST /jobs` — создать AI job.
- `GET /jobs?deckId=...&limit=...` — список job по колоде.
- `GET /jobs/{jobId}` — статус job.
- `GET /jobs/{jobId}/results` — summary результата.
- `POST /jobs/{jobId}/cancel` — отмена job.
- `POST /imports/preview` / `POST /imports/generate` — AI import flow.
- `GET/POST/DELETE /providers` — управление ключами провайдеров.

## Как работает job pipeline
1. `AiJobService.createJob(...)` валидирует пользователя, считает `inputHash`, делает idempotency по `requestId`.
2. Задача пишется в `app_ai.ai_jobs` со статусом `queued`.
3. `AiJobWorker` выбирает задачи через `FOR UPDATE SKIP LOCKED`, переводит в `processing`, ставит heartbeat lock.
4. Провайдерный процессор (`Gemini/OpenAI/Qwen/Grok/Claude`) выполняет mode-логику.
5. Результат и usage пишутся в summary/ledger, статус `completed` или `failed`.

## Рантайм-эмбеддинги и новизна генерации
Для режима `generate_cards` добавлен слой runtime-новизны (`CardNoveltyService`), чтобы не плодить дубликаты:

- Индекс строится на лету по существующим карточкам колоды:
  - берется до `MAX_INDEX_CARDS = 3000` карточек, пагинация по `PAGE_SIZE = 200`;
  - используются только выбранные текстовые поля (без media).
- Для каждой карточки считаются отпечатки:
  - `exactKey` — нормализованная конкатенация полей;
  - `primaryKey` — первое значимое поле;
  - runtime-вектор (`VECTOR_DIM = 256`, hashing char-trigram, cosine).
- Фильтрация кандидатов идет в 4 шага:
  - empty;
  - exact;
  - primary;
  - semantic (`SIMILARITY >= 0.92`).
- На каждую генерацию делается до `GENERATE_MAX_ATTEMPTS = 4` проходов с over-sampling.
- Если уникальных карточек меньше, чем requested count, job завершается ошибкой (тихий недобор запрещен).
- В summary пишутся счетчики:
  - `duplicatesSkippedExact`,
  - `duplicatesSkippedPrimary`,
  - `duplicatesSkippedSemantic`,
  - `candidatesSkippedEmpty`.

## Почему runtime, а не persisted embeddings
- Не увеличивает размер БД и не требует миграций/ANN-индексов.
- Быстрый локальный сигнал новизны в рамках job.
- Предсказуемый fallback: если уникальных не хватает, операция явно падает.

Ограничения:
- это не full semantic search по всей истории;
- качество зависит от текстового наполнения обязательных полей.

## Безопасность и эксплуатация
- API ключи провайдеров хранятся шифрованно (`SecretVault`).
- Воркеры используют virtual threads для параллельной I/O обработки.
- Для flaky провайдерных вызовов есть retry/backoff на уровне worker/job.
