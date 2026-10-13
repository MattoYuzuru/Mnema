---
artifact:
  id: learning-core-behavior-proposal
  type: product-requirements
  title: "Learning observations and representative practice proposal"
  status: proposed
  created_at: "2026-10-13"
  updated_at: "2026-10-13"
  owners: ["project-owner"]
---

# Предлагаемое поведение

Пользователь хочет удерживать и применять материал и не получать ложное ощущение,
что два лёгких задания подтвердили весь материал. Первая очередь должна улучшить
наблюдаемость и выбор проверок, сохраняя работающую модель и данные учеников.
Результат — пригодная история, разные варианты одной цели и объяснимое покрытие.
Обещание педагогического превосходства в первую очередь не входит.

## Решения для первого scope

| ID | Предлагаемый default | Причина / граница |
|---|---|---|
| D01 | Сохранить material/objective/exercise/attempt/evidence/state разделение | Это уже полезная архитектура; не вводить cross-deck knowledge graph |
| D02 | Observation — факт взаимодействия, evidence — оценённое свидетельство | PRACTICE/REPLAY видны аналитике, но не меняют canonical due/streak/state |
| D03 | Не менять baseline reducer в LC-E1/E2 | Смена selector измерима отдельно от смены scheduling |
| D04 | Scope материала = distinct enabled objectives; variants — отдельная диагностика | Дубликат не меняет «знание», новая цель становится unknown |
| D05 | Ротация eligible вариантов одной цели без бонуса за число механик | Разнообразие не означает независимость или новый навык |
| D06 | Сохранить текущий ai-semantic; classifier сравнивать отдельным spike | Не повторять уже реализованную feature и не добавлять model runtime без evidence |
| D07 | Не объединять/разделять цели по одному title и не сбрасывать state по edit | Это требует отдельного authoring/owner решения |
| D08 | Первый diagnostic/export private-local; публичные learning claims отсутствуют | Данные аудитории и human labels пока не собраны |

После принятия этого scope обновляются canonical contracts, scoped AGENTS и user-facing
политика хранения; implementation docs не остаются параллельным источником правил.

## R01 — минимальные наблюдения

Необходимые факты terminal attempt: server identity event/attempt/presentation/session,
account/deck/member/objective и revisions, exercise и revision, learningEpoch,
mechanic/evaluator/version, mode, issuedAt/acceptedAt, result/status, evidence class
где она существует, policy/config versions, помощь/reveal и provenance quality.
Identity должна включать lineage/reuse scope там, где это нужно для однозначного чтения.
Для AI отдельно strictness, SemanticPolicy/prompt version и фактически использованный
grader alias/model version, если доступны, иначе explicit unknown. Requested evaluator
и effective self-check fallback/reason не смешиваются; configured route не доказывает actual provider.

Минимальный PRESENTATION_ISSUED record пишется атомарно с выдачей во всех трёх режимах:
identity/revisions/epoch/mode/mechanic/policy/issuedAt, без result/evidence. Это факт server
issuance, не просмотра учеником. Он нужен для presented-variant diagnostics после P7D;
terminal attempt и issuance — разные events с отдельными uniqueness constraints.

Result, assistance и evidence сохраняются раздельно. В PRACTICE/REPLAY можно хранить
diagnostic evaluation, но не писать строку canonical study_evidence или выдумывать
scheduler transition. В NOT_ASSESSED/UNAVAILABLE отсутствует assessed correctness.
Event не содержит вопрос, ответ, rubric descriptions, quotes, transcript, media URL,
email/IP/device fingerprint. Глобальные logs/metrics также не получают этих данных.

Server hint/transcript/pair-check — authoritative interaction, не доказательство чтения.
Presentation issuance и feedback delivery — факт выдачи, не «ученик увидел/понял».
SELF_CHECK reveal сейчас локален: его instrumentation, если нужен, явно client-reported,
идемпотентен и не является stronger evidence. Новый endpoint для него не обязателен в первом scope.

Terminal capture должен работать без обращения к AI и без внешнего analytics service.
Failure обязательной записи откатывает terminal transaction; retry возвращает тот же outcome.
Dispute append-only помечает компенсированную попытку; original record не переписывается.
Restart сохраняет старый epoch; late answer классифицируется отдельно и не заражает новый.

## R02 — хранение, приватность и scope

Предлагаемый default: минимальные records SCHEDULED и компенсации живут как существующая
нормализованная история до удаления аккаунта. PRACTICE/REPLAY и отдельные диагностические
interaction records — P180D, с явной границей доступной истории. Это новая data category,
не скрытое продление старого 24h receipt. При принятии scope описать её в пользовательской
политике; ship gate требует доступного удаления аккаунта для новых данных и корректного TTL.
Пока lifecycle удаления Learning в owning checkout не готов, production admission закрыт;
локальный schema/test scope может быть выполнен на synthetic данных.

Snapshot horizon P7D и raw response P30D сохраняются. Не удлинять их для нового export.
Expiry interactions не превращает старое assistance в «не было подсказки»: устойчивый
terminal record хранит known assistance flags; неполная история имеет explicit unknown.
После TTL diagnostics отчёт указывает horizon, не заявляет lifetime coverage.

Export по умолчанию только explicit fixture/local input, без automatic production fetch.
Нужен bounded cursor/watermark и stable pseudonymous analysis identity; source UUID и
text в публикуемые datasets не попадают. Anonymous aggregate не является согласием
на обучение модели на ответах. Live research corpus — только отдельно разрешённый opt-in.

## R03 — ротация вариантов

Для due/introduced objective выбрать следующий eligible variant после objective-local
round-robin cursor в стабильном inventory order, с bounded seek/wrap. Это не алгоритм
глобально least-recently-seen и не доказательство содержательной репрезентативности.
Сравнивать только варианты одной explicit цели;
не подменять проверку определения заданием на применение без иной цели.
Подготовленный bounded manifest/index хранит доступный inventory; запрос выдачи не
сканирует всю колоду. Предлагаемый cap scan 80 кандидатов на batch (как нынешнее 20×4),
с отдельным индексированным per-objective retrieval (не больше 80 кандидатов на цель и
20 целей/batch, history window не больше 8 issued variants на цель); measurement gate
проверяет суммарную нагрузку на нескольких целях с 1000 вариантами. Не материализовать unlimited history.

Сначала сохраняются нынешние due/new budgets и один objective на session. «Сегодня всё
сделано» не блокирует later due. Единственный eligible вариант остаётся usable.
У pending presentation immutable content сохраняется, resume/retry не перевыдают вариант.
Concurrent edit/remove/media-not-ready проверяются existing eligibility boundary.
REPLAY воспроизводит source order/revisions, rotation к нему не применяется.

Пустое scan window продвигает idempotent scan cursor и допускает bounded continuation;
если scope ещё не исчерпан, не объявлять всё objective пустым. Это не issuance и не
learning event. Тест с единственным eligible вариантом за первым окном обязателен.

Новая policy имеет версию. Каноническая память не меняется от того, что пришёл другой
mechanic; слабая проверка не получает дополнительный credit за разнообразие.
Отсутствие старого provenance означает unknown history, а не будто вариант никогда не выдавался.

## R04 — честный прогресс

Сохранить ON_TRACK/LEARNING/DUE/NOT_STARTED смысл действующего контракта.
Показать покрытие целей: введено/проверено из enabled; если помогает learner,
раскрыть неподтверждённые цели progressive disclosure в material view.
Название внутреннего objective не заменяет понятный текст навыка.

Variant diagnostic: distinct variants presented/assessed за declared horizon, последняя
mechanic и наличие assisted/unassisted наблюдений. Без строки «освоено 2/20 упражнений»
и без шкалы процента знания. Разные criterion UUID rubric не объединяются в latent skill.
Два unhinted ответа в разные даты и на разные варианты остаются observations, не гарантией mastery.

R04 инварианты: парафраз/удаление дубликата не меняет objective coverage; отключение
упражнения пересчитывает active inventory; добавление новой цели расширяет scope;
завершённая сессия не означает весь материал проверен; скрытые/чужие источники не раскрываются.
UI сохраняет paper/indigo, semantic HTML, keyboard/focus, live feedback и shared styleguide.

Инвариант дубликата действует, пока у каждой цели остаётся хотя бы одно enabled exercise.
Удаление/отключение последнего варианта корректно удаляет цель из active scope и может менять state.

## R05 — экспериментальные результаты

LC-E2 создаёт reproducible offline selection report и протокол delayed checks,
без нового session mode и без automatic online randomization. Синтетика проверяет
инварианты/нагрузку; held-out последовательности дают observational diagnostics,
а при наличии валидного predictor/outcome — проверку его прогноза. Selection bias они
автоматически не устраняют; ни один из этих результатов не является learning lift. Sources/seed/versions/completeness/TTL должны быть
в отчёте. Любая более поздняя модель проходит [migration gates](migration.md).

## Не принятые shortcuts

Не умножать memory strength на число упражнений/механик; не делить mastery на inventory;
не считать SAME_DAY after reveal самостоятельным delayed recall; не называть two AI runs
human-reviewed; не повышать evidence по длине/скорости; не сбрасывать пользователя при
смене модели; не вводить новое `/v2` API, dual product paths или отдельный datastore.

Неопределённость сохраняется: педагогическая польза rotation, численные evidence weights,
качество human labels и optimum target retention. Исследовательские задачи принимают
решение «сохранить baseline» как полноценный итог.
