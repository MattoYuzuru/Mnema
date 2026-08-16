# Как работать с Mnema

Спасибо за интерес к проекту. Mnema находится в переходе от v1 к hosted v2, поэтому сначала проверьте, относится ли изменение к текущему checkout или к proposed v2.

## Перед началом

1. Прочитайте [инструкцию для разработки](AGENTS.md).
2. Откройте [навигацию по документации](docs/README.md).
3. Найдите существующий Issue или создайте новый по [стандарту задач](docs/engineering/work-item-standard.md).
4. Не начинайте большую реализацию, если в задаче не приняты product/data/API решения.

## Ветка и pull request

- одна логическая задача — одна ветка и один reviewable PR;
- используйте понятный prefix: `feat/`, `fix/`, `docs/`, `test/`, `chore/`;
- не смешивайте migration, массовое удаление legacy и новый UI в один change;
- свяжите PR с Issue и заполните verification, risks и rollback;
- не добавляйте secrets, production data или `.env` values.

Коммиты описывают outcome, например `docs(github): define work item standard` или `fix(study): make attempt submission idempotent`.

## Проверка

Перед отправкой PR выполните:

```bash
cd backend && ./gradlew quality
cd ../frontend && npm run lint && npm test && npm run build
```

Если изменение затрагивает только документацию, дополнительно проверьте относительные ссылки и `git diff --check`. Настроенные quality gates всё равно остаются обязательными перед push согласно `AGENTS.md`.

## Review

Автор не считает PR готовым только потому, что код сгенерирован или тесты зелёные. Reviewer проверяет соответствие Issue, архитектурным решениям, security/a11y boundaries и достаточность evidence.

Schema, auth, payments, dependencies, CI/CD и production changes требуют явного owner review. Merge и deployment выполняются отдельно после зелёных required checks.
