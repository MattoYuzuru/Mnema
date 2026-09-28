---
artifact:
  id: runtime-policy-index
  type: reference
  title: "Learning runtime policy index"
  status: proposed
  created_at: "2026-09-28"
  updated_at: "2026-09-28"
  owners: ["learning-api", "web"]
  source_tasks: ["GitHub Issue #241", "GitHub Epic #76"]
---

# Изменяемые политики Learning

Эта страница отвечает на вопрос «какую настройку менять?» по **владельцу и
области действия**. Значения по умолчанию сохраняют уже принятое поведение.
Проверка границ происходит при старте backend или при создании bounded client
policy. Изменение policy влияет только на новые операции; опубликованные
ревизии и сохранённые evidence не переписываются. Вводимый отдельно media
transport (#235) и processor (#236) дополнят таблицу своими ключами.

| Ключ / место | Владелец и влияние | Единица; default; допустимый диапазон |
|---|---|---|
| `learning.authoring.draft.recovery-window` | Editing draft: expiry после create/save | Duration; `P30D`; 1–90 дней |
| `learning.authoring.draft.max-active-per-account` | Editing draft: число активных черновиков владельца | Count; `200`; 1–1000 |
| `learning.authoring.draft.max-total-bytes-per-account` | Editing draft: суммарные JSON bytes владельца | Bytes; `20971520` (20 MiB); 1 MiB–1 GiB |
| `learning.authoring.capture.max-active-per-account` | Capture notes: число заметок владельца | Count; `10000`; 1–100000 |
| `learning.authoring.capture.max-total-bytes-per-account` | Capture notes: суммарные bytes владельца | Bytes; `67108864` (64 MiB); 1 MiB–1 GiB |
| `learning.media.unattached-ready-hold` | Media catalog: READY asset без revision/draft ref | Duration; `P7D`; 1–30 дней |
| `learning.storage.max-staging-lease` | Immutable native storage: максимальная временная pin lease | Duration; `PT1H`; 1 ms–1 день |
| `learning.storage.orphan-grace` | Immutable native storage: grace до sweep | Duration; `PT10M`; 1 ms–7 дней |
| `learning.storage.lock-timeout` | Immutable native storage: ожидание lock | Duration; `PT1S`; 1 ms–5 секунд |

`max-active-per-account` draft допускает рост до 1000. Web list aggregation
запрашивает страницы по 20 и обнаруживает превышение 50 страниц,
вместо успешного ответа с неполным списком. При изменении верхней
границы draft count нужно одновременно обновить этот клиентский контракт.

## Аудит ещё не вынесенных значений

Следующие числа являются кандидатами с узкой областью. Их нельзя объединять
в одну глобальную `MAX_DURATION`/`MAX_BYTES` настройку.

| Кандидат сейчас | Область и зависимость | Следующее действие |
|---|---|---|
| Study session lifetime 24 h; compact receipt retention 24 h; browser recovery TTL 24 h | Session/attempt/recovery должны оставаться согласованы | Ввести пару session/receipt policy с проверкой retention ≥ retry window, затем client expiry contract test |
| Study raw response retention 30 d | Точно 30 дней обещаны в `contracts/study/README.md` | Менять только через новую версию evidence/retention contract, не как свободный operator knob |
| Study presets Quick 10/2, Standard 20/5, Replay/Practice 20 | Client policy и видимый текст, backend принимает диапазон 1–100 | Назвать пресеты по intent и проверить `maxNewObjectives ≤ maxPresentations`; обновлять UI copy вместе с policy |
| Study PREPARING poll 20 × 500 ms | Только экран сессии; ≈10 s до сообщения о задержке | Назвать отдельно interval и max wait; bounded attempts выводить из них |
| Каталог: preparation lease 1 min | Item/Exercise/Deck preparation; не длиннее `learning.storage.max-staging-lease` | Вынести scoped lease после проверки latency и rollback конкурирующих publish |
| Retention worker batch 500 | DB lock/transaction duration; cadence уже configurable | Измерить latency, затем назвать scoped batch-size |
| Browser own-deck recovery 24 h/5 entries/64 KiB/16 KiB | Local storage safeguards | Документировать/именовать в browser recovery policy отдельно от Study |

## Не runtime policy

`NativeDocumentReader` и Angular renderer limits (1 MiB, 10 000 nodes,
depth 32, scalar 32 KiB), storage object layout, counted-page dimensions,
exercise response bounds и pagination maximum являются версионированными
wire/storage invariants. Их меняют вместе с контрактом, fixtures и обеими
сторонами API. `BaselineReducer` interval ladder принадлежит versioned learning
algorithm; изменение требует нового policy version/hash и migration evidence.
Planner query/memory bounds и CSS tokens имеют другие причины изменения.
Повторяющийся `@Transactional(timeout=10)` не превращается в один глобальный
таймаут без измерений блокировок.

## Проверка и rollback

Неверное значение backend policy приводит к ошибке старта. Тесты должны
проверять default, края диапазона и независимость соседних областей. Если
новый предел вызывает нагрузку или ломает UX, вернуть предыдущий default для
новых операций; существующие revision/attempt IDs и format versions не менять.
Среда только локальная, rollout и восстановление серверных данных здесь не
заявлены.
