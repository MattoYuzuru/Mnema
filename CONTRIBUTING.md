# Как работать с Mnema

Спасибо за интерес к проекту. Mnema заменяет v1 новой платформой вокруг versioned `LearningItem`; checkout содержит только replacement runtime (`identity-account`, `learning`, Angular SPA), а v1 доступен лишь по тегу `v1-apache-final`. Сначала проверьте, относится ли изменение к текущему runtime или к proposed-документам (`docs/README.md` показывает статусы). Разработка и production-поставка: [production delivery](docs/operations/production-delivery.md).

## Лицензионная граница

Текущие ревизии Mnema распространяются по
[`Mnema Source-Available License 1.0`](LICENSE), а не по open-source лицензии.
Личное локальное использование разрешено одному физическому лицу. Использование
организацией или в её интересах, публичное и совместное развёртывание,
коммерческое использование и machine-learning use требуют отдельной письменной
лицензии. Последний Apache 2.0-срез доступен по тегу `v1-apache-final`.

Issues с багами, идеями, UX-наблюдениями и предложениями принимаются. Публичные
code-PR, патчи и иные производные работы временно не принимаются до публикации
отдельного contributor agreement. Не прикладывайте реализацию к issue и не
отправляйте код иным каналом без заранее согласованных письменных условий.

## Перед началом

1. Прочитайте [инструкцию для разработки](AGENTS.md).
2. Откройте [навигацию по документации](docs/README.md).
3. Найдите существующий Issue или создайте новый по [стандарту задач](docs/engineering/work-item-standard.md).
4. Не начинайте большую реализацию, если в задаче не приняты product/data/API решения.

## Ветка и pull request

Следующие правила предназначены для владельца репозитория и контрибьюторов,
которые уже заключили отдельное contributor agreement:

- одна логическая задача — одна ветка и один reviewable PR;
- для агентских веток используйте `codex/` по умолчанию; для остальных — понятный
  prefix `feat/`, `fix/`, `docs/`, `test/` или `chore/`;
- не смешивайте migration, массовое удаление legacy и новый UI в один change;
- свяжите PR с Issue и заполните verification, risks и rollback;
- не добавляйте secrets, production data или `.env` values.

Коммиты описывают outcome, например `docs(github): define work item standard` или `fix(study): make attempt submission idempotent`.

## Проверка

Перед отправкой PR выполните:

```bash
# Из корня репозитория после настройки toolchain:
(cd backend && ./gradlew clean quality)
(cd frontend && npm ci && npm run lint && npm run test && npm run build)
python3 scripts/verify_docs.py
```

Для документации проверьте также `git diff --check`. Полный CI-equivalent gate и
настройка JDK 25/Node 24/Colima — в [agent runbook](docs/engineering/agent-runbook.md).
Настроенные quality gates остаются обязательными перед push согласно `AGENTS.md`.

## Review

Автор не считает PR готовым только потому, что код сгенерирован или тесты зелёные. Reviewer проверяет соответствие Issue, архитектурным решениям, security/a11y boundaries и достаточность evidence.

Новые решения для schema, auth, payments, dependencies, CI/CD или production требуют
принятой владельцем границы и проверки рисков. Уже авторизованная задача следует
[task-scoped autonomy](AGENTS.md#task-scoped-autonomy-and-merge-boundary), без второго
цикла согласования обычных in-scope шагов. Merge и deployment — отдельные результаты;
production требует названных владельцем target и effect.
