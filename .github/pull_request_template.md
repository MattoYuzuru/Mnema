## Результат и решение

Какой outcome получен, зачем он нужен и почему выбран этот подход?

## Связанные задачи и решения

- Closes #...
- Parent epic: #...
- Docs/ADR: ...

## Что изменилось

- Поведение/API/data/UI:
- Что намеренно не входит:

## Evidence

- [ ] Backend quality/static analysis/tests
- [ ] Frontend lint/tests/build
- [ ] Integration/E2E/contract tests, где применимо
- [ ] Desktop/mobile screenshots и keyboard/a11y, если изменён UI
- [ ] Security/performance evidence, если затронут соответствующий boundary

Команды и существенный результат:

```text
command → result
```

## Migration, deployment и rollback

- Data/config/environment changes:
- Compatibility/point of no return:
- Rollout и rollback/roll-forward:
- Post-deploy smoke/monitoring:

## Security и privacy

- Trust boundary/permissions/input-output changes:
- Подтверждение, что secrets/PII не попали в diff и логи:

## Residual risks и follow-up

- Известные ограничения с issue/owner, либо `нет`.

## Checklist автора

- [ ] PR решает acceptance criteria связанной задачи.
- [ ] Diff ограничен одним reviewable outcome.
- [ ] Docs обновлены вместе с contract.
- [ ] Quality gates запущены на точном commit перед push.
- [ ] Нет скрытого dependency, destructive или external change.
