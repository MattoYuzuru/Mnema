---
artifact:
  id: community-decks-epic-2-evidence
  type: verification-evidence
  title: "Колоды сообщества, эпик 2: evidence задач"
  status: current
  created_at: "2026-10-12"
  updated_at: "2026-10-12"
  owners: ["project-owner"]
---

# Эпик 2: обновления копий

Evidence задач эпика [MattoYuzuru/Mnema#420](https://github.com/MattoYuzuru/Mnema/issues/420).
Канон: [продуктовый контракт](../../../../product/community-decks.md) (раздел «Уточнено
реализацией (эпик 2)»), [архитектура](../../../../architecture/community-decks.md) (§7 и
«Уточнено реализацией (эпик 2)»), [refinement](../../../community-decks-refinement.md).
Подробности реализации по задачам: [guide Learning](../../../../../backend/services/learning/guide.md),
разделы Updates/1–6 и «Study snapshot retention (#460)»; ключи и метрики — в
[runtime policy index](../../../runtime-policy-index.md); контракты —
[`contracts/decks/updates.json`](../../../../../contracts/decks/updates.json) и
[`forks.json`](../../../../../contracts/decks/forks.json).

Точные цифры gate и ревью — в теле и комментариях каждого PR. Evidence описывает конкретные
коммиты и не заменяет код и контракты.

## Production сейчас

| Что | Состояние |
|---|---|
| Миграции | `V59`–`V65` применены (V62 нет: миграция перепривязки получила номер `V64`, чтобы идти после `V63`). |
| Экран обзора, значок, перепривязка, история, возврат | Включены с релизом #495. |
| Автоприём безопасного | Выключен: `learning.updates.auto.enabled=false` (код по умолчанию). Включение — решение владельца. |
| Retention Study (#460) | Включён (`LEARNING_STUDY_RETENTION_ENABLED: "true"` в `compose.yaml` с релиза #492; первый релиз #490 шёл выключенным, проверка после него показала ноль Study-сессий). |
| Пул соединений Learning | 16 (`compose.yaml` с релиза #493). |

## Задачи

«Релиз» — запуск Main CI, который выкатил коммит в production (deploy и post-deploy smoke).

| Задача | PR | Коммит в `main` | Миграция | Production | Проверка |
|---|---|---|---|---|---|
| Хвост эпика 1 (документы) | [#488](https://github.com/MattoYuzuru/Mnema/pull/488) | `20bc54c2` | нет | Без релиза (только документы). | Статусы канона приведены к факту: эпик 1 закрыт и включён. |
| Updates/1 [#440](https://github.com/MattoYuzuru/Mnema/issues/440) frontier-diff | [#489](https://github.com/MattoYuzuru/Mnema/pull/489) | `a680deb1` | нет | Релиз `38153459264` (третья попытка: внешний таймаут Sigstore, затем полный перезапуск). | Бенчмарк: 1 правка — 4/7/8 страниц при 10³/10⁴/10⁵ единиц; PostgreSQL 10⁵ и 100 правок — 57 мс. Property-тест 320×3 против полного сравнения, число прочитанных страниц равно симметрической разности. Ревью реализации: блокеров нет. |
| #460 retention Study (попутная) | [#490](https://github.com/MattoYuzuru/Mnema/pull/490) | `2f1e9302` | V59 | Релиз `38159674883`, выключателем выключен; включён в #492. | Замер: выдача ≈1,65 КБ, при H ≈2,4 ГБ в день брутто; после — окно ≈10 ГБ и ≈0,9 ГБ в день постоянного слоя. V59 на 1M выдач — 2,8 с. Ревью архитектуры: 4 major исправлены. |
| Updates/5 [#444](https://github.com/MattoYuzuru/Mnema/issues/444) фаза A (styleguide) | [#491](https://github.com/MattoYuzuru/Mnema/pull/491) | `f58431ee` | нет | Релиз `38163408068`. | Элементы сначала в `/styleguide`. A11y-ревью в headless Chrome (320/900/1280, forced-colors): 2 major исправлены. |
| Updates/2 [#441](https://github.com/MattoYuzuru/Mnema/issues/441) комбинированная публикация | [#492](https://github.com/MattoYuzuru/Mnema/pull/492) | `e7f686c3` | V60 | Релиз `38166694985`. | 37 тестов движка; 100 единиц ≈0,25–0,6 с, задача на 1 000 ≈2–5 с. Ревью: 2 major исправлены (осиротевшие команды, охрана приёма ревизий). |
| Updates/3 [#442](https://github.com/MattoYuzuru/Mnema/issues/442) план | [#493](https://github.com/MattoYuzuru/Mnema/pull/493) | `ce173df4` | V61 | Релиз `38176802902`. | Таблица истинности на настоящих сервисах, property-тест; актуальная копия на 1 000 материалов — 4 запроса и 0 страниц, 100 правок — 16 запросов. Ревью (xhigh): 1 major исправлен (бюджет расчёта). |
| Updates/4 [#443](https://github.com/MattoYuzuru/Mnema/issues/443) применение | [#494](https://github.com/MattoYuzuru/Mnema/pull/494) | `dabd0ad4` | V63 | Релиз `38196279686`. | 43 теста, все критерии приёмки применения. CI один раз упал на нехватке heap тестовой JVM: ограничен кеш Spring-контекстов. Harness нашёл баг (цель нового упражнения считалась исправлением) — исправлен здесь. Ревью (xhigh): 1 major исправлен. |
| Updates/5 [#444](https://github.com/MattoYuzuru/Mnema/issues/444) экран и Updates/6 [#445](https://github.com/MattoYuzuru/Mnema/issues/445) | [#495](https://github.com/MattoYuzuru/Mnema/pull/495) | см. PR | V64, V65 | См. PR и комментарий о релизе в задачах. | Gate на точном коммите; harness `--community --updates` 65/65 этапов (26 этапов обновления на desktop 1280 и mobile 390). Ревью: часть A (security) без блокеров; часть B (xhigh) 1 major исправлен; a11y экрана и хаба — 1 blocker и 5 major исправлены; a11y истории — 3 major исправлены. |
| Updates/7 [#446](https://github.com/MattoYuzuru/Mnema/issues/446) проверка эпика | этот PR | — | нет | Без релиза (документы). | Критерии эпика ниже. |

## Критерии завершения эпика

| Критерий | Чем подтверждён |
|---|---|
| «Принять всё безопасное» не трогает изменённое учеником; повтор команды не дублирует; устаревший план отвергается | `ApplyIntegrationTest` (Updates/4); harness: после «Принять всё безопасное» правка ученика осталась. |
| Отклонённое не предлагается до новой правки автора; удаления автора применяются только по выбору | `UpdatePlanDecisionsIntegrationTest`, `ApplyIntegrationTest`; harness: удаление осталось до явного «Удалить и у меня». |
| Экран обзора доступен с клавиатуры и скринридером, на mobile переключатель вместо колонок | Harness (клавиатура: вкладки, переключатель, выбор, фокус на сводке после применения; mobile 390 — переключатель); a11y-ревью DOM и ARIA. Настоящие скринридеры не проверялись. |
| Бенчмарк diff подтверждает рост по числу изменений, а не по размеру колоды | `CountedPageDiffBenchmarkTest`, `ManifestDiffIntegrationTest` (Updates/1). |
| Каждое решение проверено при нулевой нагрузке и в сценарии H | Разделы «Load» каждого PR и guide; при H: план на копию O(изменений), автоприём вирусной колоды ≈2 ч при 50 в минуту для 6 тыс. активных копий, значок — 2 запроса на страницу списка. |
| Новые элементы UI сначала в styleguide; существующие компоненты | #491 до экранов; `app-segmented-choice`, `app-auto-load`, `.check-row`, `app-toggletip`, тосты. |
| Sub-issues связаны native parent и в Project #4; Done только после merged/applied | Доска Project #4; evidence-комментарий в каждой задаче. |

## Не проверено

- Настоящие скринридеры, Safari и Firefox, touch-устройства.
- Путь фоновой задачи применения (202) на очень большом плане в браузере.
- Автоприём в production (выключен).
- Сквозной нагрузочный прогон сценария H (H проверен расчётом, бенчмарками и замерами задач).
