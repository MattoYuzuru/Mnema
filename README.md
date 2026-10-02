# Mnema

Mnema помогает учиться по собственным материалам. Вы сохраняете конспект, слово или
фрагмент текста как `LearningItem`, делаете из него упражнения и проходите их в
занятиях. Расписание повторений объясняет, почему материал вернулся сегодня.

Репозиторий — новая реализация продукта, которая полностью заменила первую версию.
Старые API, маршруты `/v2`, двойное чтение и запись и обратная совместимость с v1 не
поддерживаются.

## Что уже работает

- **Аккаунты.** Сервис Identity & Account: регистрация, вход через OAuth 2.0/OIDC с PKCE,
  сессии, профиль, аватар и удаление аккаунта.
- **Материалы.** Learning API хранит приватные колоды и их `LearningItem` с неизменяемыми
  ревизиями. Редактор поддерживает текст, изображения, аудио, видео, YouTube, Mermaid и
  таблицы, черновики и быстрые заметки (Capture).
- **Упражнения.** Семь механик: вспомнить и сверить (`SELF_CHECK`), ввести ответ
  (`FREE_RESPONSE`), заполнить пропуски (`CLOZE`), выбрать ответ (`CHOICE`), сопоставить
  (`MATCH`), восстановить порядок (`ORDER`) и распределить по группам (`CATEGORIZE`).
  Условие, варианты и элементы могут сочетать текст и медиа. Автор пишет упражнение на
  одной странице: выбирает механику, пробует интерактивный пример и сразу видит, как
  его задание увидит ученик.
- **Занятия.** Плановое повторение, повтор сегодняшнего занятия и свободная практика.
  Прогресс меняется только в плановом режиме. Проверку ответов и подсказки выдаёт
  сервер, и ключи ответов ученику заранее не приходят.
- **Возможности ИИ.** Смысловая проверка ответов и распознавание речи подготовлены как
  выключенные серверные возможности. Провайдеров нет, и обойти запрет через API нельзя.
- **Интерфейс.** Angular 22 с «бумажным» оформлением: тёплый фон, классическая типографика,
  индиго.

Исходники первой версии лежат в теге
[`v1-apache-final`](https://github.com/MattoYuzuru/Mnema/tree/v1-apache-final); текущий
checkout их не собирает.

## Запустить локально

Нужны JDK 21, Node 24, Docker (на macOS — Colima), OpenSSL, `keytool` и Chrome. Лаунчер
поднимает весь стек с локальным HTTPS: PostgreSQL 18, Identity & Account, Learning,
MinIO для файлов, обработку медиа и фронтенд.

```bash
./scripts/mnema-local-full-stack.sh bootstrap
./scripts/mnema-local-full-stack.sh start
./scripts/mnema-local-full-stack.sh smoke
```

После `start` приложение доступно на `https://localhost:3443`. Перезапуск сохраняет
данные. Доверие к локальному CA, сброс данных, особенности Colima и ограничения
описаны в [runbook локального запуска](docs/deploy/selfhost-local.md). Файл
`docker-compose.yml` поднимает только backend и нужен для обслуживания, а не для
ежедневной работы.

## Проверить изменения

Перед PR нужен полный набор проверок. Минимальный локальный набор:

```bash
cd backend
./gradlew clean quality

cd ../frontend
npm ci
npm run lint
npm run test
npm run build

cd ..
python3 scripts/verify_docs.py
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
```

Backend-проверки поднимают PostgreSQL в Testcontainers, пороги покрытия обязательны.
Переменные окружения для Colima, браузерный прогон и остальные проверки PR описаны в
[руководстве по репозиторию](docs/engineering/repository-guide.md#полный-quality-gate).

## Документация

1. [`AGENTS.md`](AGENTS.md) — обязательные правила разработки для людей и агентов.
2. [Навигация по документации](docs/README.md) — где лежит актуальное описание
   продукта, архитектуры и контрактов.
3. [Обзор системы](docs/system-overview.md) и
   [руководство по репозиторию](docs/engineering/repository-guide.md).
4. [Стандарт задач и PR](docs/engineering/work-item-standard.md) — перед созданием Issue
   или pull request.

## Поставка

Общего сервера сейчас нет. Работа считается готовой после такого пути:

```text
feature branch → полный local gate → hosted PR checks → protected squash → main checks
```

Staging, production, SSH-доступ, выкатка и восстановление в эту схему не входят и не
считаются выполненными. Подробности — в политике
[Delivery without hosted infrastructure](docs/operations/local-development-delivery.md).

## Лицензия и участие

Текущие ревизии распространяются по
[Mnema Source-Available License 1.0](LICENSE). Как участвовать в разработке — в
[`CONTRIBUTING.md`](CONTRIBUTING.md), как сообщить об уязвимости — в
[`SECURITY.md`](SECURITY.md). Последний срез под Apache 2.0 сохранён в теге
[`v1-apache-final`](https://github.com/MattoYuzuru/Mnema/tree/v1-apache-final).
