---
artifact:
  id: russia-launch-economics-2026
  type: product-research
  title: "Mnema Russia launch, payments and AI economics"
  status: proposed
  created_at: "2026-08-15"
  updated_at: "2026-09-06"
  owners: ["project-owner"]
  evidence_date: "2026-08-15"
---

# Запуск Mnema в России: рынок, платежи и AI-экономика

Цены, аудитории и тарифы внешних сервисов меняются. Этот документ отделяет подтверждённые источниками факты от сценарных предположений и требует повторной проверки перед запуском. Это не юридическая и не налоговая консультация.

## Status update — 2026-09-06

Ниже сохранено исследование AI-ориентированного предложения августа, а не действующий
тарифный контракт. Manual personal-deck web launch предшествует AI и каталогу.
Новые owner hypotheses допускают лимит количества своих/скопированных колод
(пример 5 + 5), расширение подпиской и возможный paid native offline. Числа, цена
и trial не утверждены; прежние «ограничиваем только AI/cost» и Free без deck-count
лимитов не являются текущим решением. При окончании подписки существующие колоды
сохраняются и доступны; ограничивается только добавление сверх free allowance.
См. [owner workflows](./authoring-and-study-workflows.md).
Цены и юридические предположения ниже нужно заново проверить перед платным запуском.

## Историческая рекомендация — не текущий launch contract

- Первый hosted-рынок — Россия; primary storage и account/payment systems находятся в российском контуре. Иностранный AI processor допустим только после localization, cross-border notification, disclosure и prompt redaction gates.
- Начальный wedge — учащиеся и преподаватели, которых владелец может достигнуть лично: ВШЭ, Центральный университет и языковые сообщества. Не пытаться рекламироваться «всем, кто учится» одним сообщением.
- Запускать Free + Starter `299 ₽/30 дней`. Plus и `899–999 ₽` отложить до доказанной дополнительной ценности и unit economics.
- Один 14-дневный trial без карты начинается по первому явному AI-intent, а не с момента регистрации, и не конвертируется в paid автоматически.
- Manual creation, study, собственные media и public decks остаются бесплатными. Ограничиваются только операции с реальной переменной себестоимостью.
- Месячный quota budget разблокируется недельными частями. Это поддерживает регулярное использование, но не создаёт неограниченный еженедельный расход.
- Yandex AI исключён owner decision. Direct DeepSeek — основной eval-кандидат, но provider abstraction и не-Yandex fallback обязательны.
- До recurring payments зарегистрировать ИП и выбрать с бухгалтером НПД/УСН, чеки и оферту.

## Рынок: что можно утверждать

В России в 2024 году обучались 4,4317 млн студентов программ бакалавриата, специалитета и магистратуры. Это полезный category proxy, но не число покупателей Mnema ([Минобрнауки, статистический сборник](https://minobrnauki.gov.ru/upload/2025/11/%D0%9C%D0%A1%D0%9E%202024.pdf)).

В ВШЭ в 2024/25 учебном году было более 60,6 тыс. студентов и аспирантов ([ВШЭ](https://www.hse.ru/priority/current_results)). В официальном отчёте Центрального университета за 2025 год указано 976 первокурсников и 501 студент второго курса — около 1 477 всего ([ЦУ, отчёт о самообследовании](https://static.centraluniversity.ru/documents/legal/document/Otchet-o-rezultatah-samoobsledovaniya-AHO-BO-centralnyi_universitet-za-2025-god.pdf)). Суммарные ~62,1 тыс. — верхняя граница первоначального university reach, не реально достижимая аудитория.

### Telegram — канал, не TAM

Снимок открытых русскоязычных каналов на дату исследования:

| Язык | Пример канала | Подписчики | Просмотры недавнего поста, порядок |
|---|---|---:|---:|
| Английский | [English Galaxy](https://t.me/englishgalaxy_school) | 73,6 тыс. | 7,5–9,1 тыс. |
| Французский | [Le Français](https://t.me/s/le_francais_french) | 43,3 тыс. | 3,8–4,8 тыс. |
| Китайский | [Китайский язык](https://t.me/s/chinese_lingvistika_slovar) | 57,8 тыс. | около 11,3 тыс. |
| Японский | [Японский язык](https://t.me/s/yaponskoe) | 22,3 тыс. | 1,1–1,3 тыс. |
| Испанский | [Испанский язык](https://t.me/s/canal_espanol) | 11,2 тыс. | около 5 тыс. |
| Корейский | [Корейский язык](https://t.me/s/VandJK) | 11,2 тыс. | около 2 тыс. |

Сумма около 219 тыс. подписок и 31–32 тыс. просмотров условного свежего поста не является уникальной аудиторией: пересечения, боты, география и доступность рекламы неизвестны. Эти числа годятся только для планирования тестов каналов.

## 90-дневный acquisition model

Все коэффициенты ниже — наши проверяемые гипотезы, не отраслевые нормы.

| Этап | Осторожный | Базовый | Сильный |
|---|---:|---:|---:|
| Квалифицированные показы | 25 000 | 100 000 | 300 000 |
| CTR | 0,8% | 1,5% | 3% |
| Посетители | 200 | 1 500 | 9 000 |
| Visit → signup | 25% | 35% | 45% |
| Регистрации | 50 | 525 | 4 050 |
| Signup → activation | 40% | 50% | 60% |
| Активированные | 20 | 263 | 2 430 |
| Activated → retained D30 | 20% | 30% | 40% |
| D30 users | 4 | 79 | 972 |
| D30 → paid | 8% | 12% | 18% |
| Платящие | 0–1 | около 9 | около 175 |

При blended ARPPU 290 ₽ это примерно `0–0,3 тыс. / 2,6–2,8 тыс. / 50,8 тыс. ₽ MRR`. В базовом сценарии 100 платящих требуют около 1,06 млн квалифицированных показов, а 1 000 — около 10,6 млн. Поэтому платная Telegram-реклама годится для проверки сообщения и активации, но не является самостоятельным устойчивым moat.

Growth loop должен проходить через преподавателя/автора:

```text
author creates useful deck
  → shares class/public link
  → learners study without setup
  → useful local changes and usage signal
  → maintained deck/reputation improves discovery
```

## Конкурентные якоря

- Quizlet Plus стоит $35.99/year, Unlimited — $44.99/year ([Quizlet](https://quizlet.com/upgrade)).
- RemNote Pro стоит $8/month при годовой оплате, Pro+AI — $18/month; AI credits обновляются ежемесячно ([RemNote](https://www.remnote.com/pricing), [trial/limits](https://help.remnote.com/en/articles/6084981-remnote-pro-frequently-asked-questions)).
- AnkiMobile стоит $24.99 one-time в US App Store ([AnkiMobile](https://apps.apple.com/us/app/ankimobile-flashcards/id373493387)).
- Российские App Store anchors включают Flash AI 349 ₽/месяц, «Интервальное повторение» 499 ₽/месяц и English Galaxy 890 ₽/месяц ([Flash AI](https://apps.apple.com/ru/app/flash-ai-photo-to-flashcard/id6755168592), [интервальное повторение](https://apps.apple.com/ru/app/id6478831271), [English Galaxy](https://apps.apple.com/ru/app/english-galaxy-%D1%83%D1%87%D0%B8%D0%BC-%D0%B0%D0%BD%D0%B3%D0%BB%D0%B8%D0%B9%D1%81%D0%BA%D0%B8%D0%B9/id1531458404)).
- Российский Space уже заявляет rich cards, offline, Anki import, collaboration, AI and 160k+ learners, поэтому «красивые карточки + SRS + AI» недостаточно как отличие ([Space](https://apps.apple.com/ru/app/space-%D0%B8%D0%BD%D1%82%D0%B5%D1%80%D0%B2%D0%B0%D0%BB%D1%8C%D0%BD%D0%BE%D0%B5-%D0%BF%D0%BE%D0%B2%D1%82%D0%BE%D1%80%D0%B5%D0%BD%D0%B8%D0%B5/id1546202212)).

Mnema должна отличаться комбинацией: несколько упражнений над одним rich item, безопасный renderer, живые публичные колоды с sparse personal changes, classes/coauthors и удобный managed AI с контролируемой стоимостью и privacy boundary.

## Тарифная гипотеза

Внутренний AI credit представляет budget, а не provider token. Начальный ориентир — не более `0,10 ₽ p95 variable cost` на credit; коэффициенты уточняются после eval.

| План | Цена | Бюджет | Граница |
|---|---:|---:|---|
| Free | 0 ₽ | 20 credits/month | text + short voice draft; без image generation |
| Trial | 14 дней один раз, без карты | 150 total | максимум 3 изображения; старт по AI-intent; без auto-conversion |
| Starter | 299 ₽/30 days | 300/cycle | 75 разблокируются каждую неделю; quota валидируется по p95 cost |
| Plus | deferred | не задан | вводится только после measured demand/cost, не вместе с MVP |
| Tutor | 899–999 ₽ позже | после unit economics | только с validated speaking/tutor value |

Недельная порция накапливается до конца billing cycle, но не переносится дальше. Годовой тариф можно тестировать только после двух полных платёжных cohorts и измеренного churn. Lifetime несовместим с постоянной AI-cost.

Guardrail: p95 total variable cost — AI, storage/egress, acquiring and fiscalization — не выше 20–25% фактической revenue тарифа. Для 299 ₽ это `59,80–74,75 ₽`. Нужны real-time ledger, hard plan cap, preflight estimate и graceful upsell; “unlimited AI” запрещён.

## Unit economics тарифа 299 ₽

Цена экономически жизнеспособна для платящего пользователя. Риск находится не в direct DeepSeek text cost, а в стоимости Free/Trial-аудитории, media generation, слабой конверсии и churn.

### Входы модели

| Параметр | Значение | Статус |
|---|---:|---|
| Starter | 299 ₽/30 дней | принято |
| НПД с платежа физлица | 4% = 11,96 ₽ | закон; расходы/комиссия не уменьшают базу |
| Эквайринг картой | пример 2,5% × 1,22 НДС = 9,12 ₽ | сценарий; фактическую ставку даёт T‑Bank |
| РКО «Простой» | 490 ₽ в активный месяц, 0 ₽ при отсутствии операций в предыдущем | текущий публичный тариф, проверить перед открытием |
| Сервер | 0 ₽ cash cost сейчас | owner input; replacement/backup cost не равен нулю |
| Starter quota | 300 credits/cycle | гипотеза |
| Максимальная p95 cost/credit | 0,10 ₽ | design guardrail, измерить |
| Refund/chargeback reserve | 3% = 8,97 ₽ | sensitivity, не измерено |

С 2026 года T‑Bank указывает НДС 22% на комиссию карточного интернет-эквайринга; ставка самого эквайринга индивидуальна. Для T‑Pay/СБП режим комиссии отличается ([T‑Bank](https://www.tbank.ru/business/payments/acquiring/vat/)). Налоговый бонус НПД временно снижает эффективную ставку, но здесь не учтён.

Публичные reference points для повторной проверки перед launch: [РКО T‑Bank](https://www.tbank.ru/business/account/help/tariffs-and-payment/pay/) и [Yandex Object Storage pricing](https://yandex.cloud/ru/docs/storage/pricing). Free allowances и фактический aggregate egress считаются на весь account, поэтому per-user media numbers ниже — сценарий, не invoice forecast.

### Один платящий пользователь

Media/storage ниже — консервативные сценарии до вычета общих free allowances Object Storage. Труд владельца, маркетинг и fixed legal costs не входят.

| Использование Starter | Credits | AI | Media/storage | НПД | Эквайринг | Contribution | Margin |
|---|---:|---:|---:|---:|---:|---:|---:|
| Light: 15% | 45 | 4,50 ₽ | 0,58 ₽ | 11,96 ₽ | 9,12 ₽ | **272,84 ₽** | **91,3%** |
| Typical: 50% | 150 | 15 ₽ | 2,87 ₽ | 11,96 ₽ | 9,12 ₽ | **260,05 ₽** | **87,0%** |
| Full quota | 300 | 30 ₽ | 10,78 ₽ | 11,96 ₽ | 9,12 ₽ | **237,14 ₽** | **79,3%** |

```text
paid contribution =
299 − НПД − эквайринг − consumed credits × cost/credit − storage/egress
```

Даже stress-case `299 − 11,96 − 74,75 = 212,29 ₽` оставляет около 71% до fixed costs и труда. Резерв 3% на refund/chargeback уменьшает contribution ещё на 8,97 ₽.

Для direct DeepSeek Flash 300 текстовых карточек — примерно 15 batch по 20 карточек. По peak cache-miss цене после 16 августа и с двукратным retry/repair factor это ориентировочно `18–22,5 ₽`. Cap 30 ₽ безопасен для текста, но ничего не доказывает для images/video/STT/TTS: им нужны отдельные cost-weighted credits и hard caps.

### «Один платит из тысячи»: важен знаменатель

Один payer на тысячу исторических регистраций может быть нормальным ранним результатом, если остальные почти не создают AI-cost. Один payer на тысячу AI-active MAU — почти наверняка убыточен.

Рабочие funnel hypotheses, не отраслевые нормы:

| Сценарий | Registrations → MAU | Paid/MAU | Paid/registrations |
|---|---:|---:|---:|
| Осторожный | 10% | 0,5% | 0,05% = 0,5/1000 |
| Базовый | 20% | 1% | 0,2% = 2/1000 |
| Сильный | 30% | 2% | 0,6% = 6/1000 |

Нужно измерять отдельно `signup → MAU`, `AI intent → trial`, `trial → first paid` и `first paid → renewal`, а не один общий conversion rate.

### Free-аудитория определяет break-even

При typical paid contribution `260,05 ₽`:

| Средняя cost одного Free MAU | Минимальная Paid/MAU без fixed cost |
|---:|---:|
| 0,30 ₽ | 0,115% — 1 payer/868 MAU |
| 1 ₽ | 0,383% — 1/261 MAU |
| 2,50 ₽ | 0,952% — 1/105 MAU |

```text
break-even paid share = free cost / (paid contribution + free cost)
```

При одном payer на 1000 MAU contribution равен примерно 0,26 ₽ на каждого активного пользователя. Значит recurring Free AI в среднем должен стоить меньше 0,26 ₽/MAU, иначе даже бесплатный сервер не спасает модель. Manual creation/study почти ничего не стоят; AI allowance нужно выдавать по verified intent и защищать от abuse.

Trial на 150 credits имеет максимальную текстовую cost 15 ₽. Если trial используется на 40%, средняя cost около 6 ₽ и для окупаемости первым paid cycle требуется примерно 2,3% trial→paid, или один payer на 43 trials. При одном payer на 1000 trial starters допустимая средняя cost trial — лишь около 0,26 ₽. Поэтому trial стартует по AI-intent, имеет total budget и cost-weighted media caps.

### DAU/MAU sensitivity

Модель ниже принимает `DAU/MAU=20%`, `Paid/MAU=1%`, Free cost `1 ₽/MAU`, typical paid contribution `260,05 ₽` и 490 ₽ РКО. Fractional payer — математическое ожидание, не реальный человек.

| DAU | MAU | Expected paid | Gross MRR | Contribution после Free и РКО |
|---:|---:|---:|---:|---:|
| 10 | 50 | 0,5 | 150 ₽ | −409 ₽ |
| 50 | 250 | 2,5 | 748 ₽ | −85 ₽ |
| 100 | 500 | 5 | 1 495 ₽ | **315 ₽** |
| 500 | 2 500 | 25 | 7 475 ₽ | **3 536 ₽** |
| 1 000 | 5 000 | 50 | 14 950 ₽ | **7 563 ₽** |
| 5 000 | 25 000 | 250 | 74 750 ₽ | **39 773 ₽** |
| 10 000 | 50 000 | 500 | 149 500 ₽ | **80 035 ₽** |

При 1 000 DAU sensitivity по Paid/MAU: около `1 036 ₽` contribution при 0,5%, `7 563 ₽` при 1% и `20 615 ₽` при 2%. Для `100 тыс. ₽/месяц` при базовых предпосылках нужно примерно 62 тыс. MAU, 12,4 тыс. DAU и 621 payer.

Это почти потолок НПД: `2,4 млн / 299 / 12 ≈ 669` постоянных подписчиков. На этом масштабе нужен переход на другой налоговый режим.

### Churn и медленный рост

```text
contribution LTV ≈ paid contribution / monthly churn
paid_t = paid_(t−1) × (1 − churn) + new paid_t
steady paid = new paid/month / churn
```

| Monthly churn | Условная жизнь | Contribution LTV | CAC ceiling при LTV/CAC≥3 |
|---:|---:|---:|---:|
| 40% | 2,5 cycle | 650 ₽ | 217 ₽ |
| 25% | 4 cycle | 1 040 ₽ | 347 ₽ |
| 15% | 6,7 cycle | 1 734 ₽ | 578 ₽ |

При churn 25% один новый payer в месяц выходит только примерно на 4 active subscribers и 1 196 ₽ steady MRR. Десять новых payer/месяц — около 40 active и 11 960 ₽ steady MRR. Цена 299 ₽ хорошо подходит для валидации и небольшого side business, но заметный доход требует тысяч DAU, хорошего renewal или более дорогого тарифа с доказанной tutor/media ценностью.

Go/no-go для первых cohorts:

- paid contribution margin ≥75%;
- Free cost/MAU ≤1 ₽;
- trial cost/resulting payer ≤25% первого paid contribution;
- Paid/MAU стремится к ≥0,5%;
- first renewal ≥55–60% как цель, не исходное допущение;
- image/video не включаются без отдельного cost cap.

## AI providers и cost examples

### Current official prices and constraints

DeepSeek changes pricing at 16:00 UTC on 16 August 2026, so development from 17 August must use the new peak/off-peak table. Both V4 models have a 1M context, JSON output and an OpenAI-compatible base URL. Prices per 1M tokens:

| Model | Window | Cache-hit input | Cache-miss input | Output |
|---|---|---:|---:|---:|
| V4 Flash | off-peak | $0.007 | $0.22 | $0.66 |
| V4 Flash | peak | $0.014 | $0.44 | $1.32 |
| V4 Pro | off-peak | $0.022 | $0.66 | $1.98 |
| V4 Pro | peak | $0.044 | $1.32 | $3.96 |

Peak windows are 01:00–04:00 and 06:00–10:00 UTC; all other hours are off-peak ([DeepSeek pricing](https://api-docs.deepseek.com/quick_start/pricing/)). Quota planning uses peak cache-miss; caching/off-peak are margin upside, not a promise.

Official concurrency is 2 500 for Flash and 500 for Pro. Over-limit requests receive 429; a request that has not started inference after ten minutes is closed. `user_id` must not contain personal data ([rate limits](https://api-docs.deepseek.com/quick_start/rate_limit/)). This requires async jobs, Mnema deadlines, retry/circuit breaker, opaque IDs and fallback; the published limit is not an SLA.

GigaChat remains a possible non-Yandex fallback. Legal-entity pricing lists Lite at 0,065 ₽/1k tokens, Pro 0,50 ₽ and Max 0,65 ₽, with a 600 ₽ monthly minimum only in a month when API is used ([GigaChat tariffs](https://developers.sber.ru/docs/ru/gigachat/tariffs/legal-tariffs), [limits](https://developers.sber.ru/docs/ru/gigachat/limitations)). Text LLM selection does not solve image generation, STT, TTS or video; each needs its own adapter and quota.

### Illustrative batch

Assume 20 cards from 5k input + 4k output tokens, before retries/repair/moderation:

| Model | Scenario | Cost/batch | ₽/batch at 80–100 ₽/$ | ₽/100 cards |
|---|---|---:|---:|---:|
| DeepSeek V4 Flash | off-peak | $0.00374 | 0.30–0.37 | 1.50–1.87 |
| DeepSeek V4 Flash | peak | $0.00748 | 0.60–0.75 | 2.99–3.74 |
| DeepSeek V4 Pro | off-peak | $0.01122 | 0.90–1.12 | 4.49–5.61 |
| DeepSeek V4 Pro | peak | $0.02244 | 1.80–2.24 | 8.98–11.22 |
| GigaChat Lite | listed token price | — | 0.585 | 2.93 |
| GigaChat Pro | listed token price | — | 4.50 | 22.50 |

Ruble conversion is a scenario, not an exchange-rate quote. Until production data exists, multiply laboratory text cost by at least 1.5–2 for retries, repair, moderation and failed outputs. Media economics remain unknown until image/STT/TTS providers are separately evaluated.

### Model selection gate

Build at least 300 accepted/rejected fixtures across RU/EN/FR/ES/JA/ZH/KO, code, math, chemistry, furigana and multiple exercise outputs. Measure:

- factual/content correctness and script/transliteration preservation;
- JSON/schema pass rate, duplicates and hallucinations;
- user acceptance after edit;
- p50/p95 time-to-first-token and total latency from Mnema's production region;
- retry/repair/fallback rate;
- **cost per accepted item**, not cost per raw generation.

Start with direct DeepSeek Flash in synthetic/shadow eval, deterministic validation, and Pro or a non-Yandex fallback only on failed validation or explicitly complex grading. Direct DeepSeek does not enter production before account/payment accessibility, cross-border/legal and privacy gates. Provider selection is an operational policy hidden from ordinary users.

## T‑Bank and legal form

T‑Bank internet acquiring supports recurring payments but is offered to IP/legal entities. A self-employed physical person without IP cannot connect; self-employed + IP can ([T‑Bank conditions](https://www.tbank.ru/business/help/business-payments/internet-acquiring/how-work/working-conditions/)).

The first payment is initialized as recurrent and returns a `RebillId`; later charges use it ([Init](https://developer.tbank.ru/eacq/api/init), [Charge](https://developer.tbank.ru/eacq/api/charge)). Prefer the bank-hosted form to keep card data outside Mnema's PCI boundary ([hosted form](https://developer.tbank.ru/eacq/scenarios/payments/nonPCI)). Commission is individual; the public 2.5% is an example, minimum successful commission is 3.49 ₽ and a failed authorization may cost 1 ₽ ([pricing](https://www.tbank.ru/business/help/business-payments/internet-acquiring/how-work/price/)).

NPD payers do not use a cash register but must create and provide a “Мой налог” receipt for each settlement ([ФНС](https://www.nalog.gov.ru/rn72/news/tax_doc_news/16629318/)). An IP may use NPD while eligible, but the annual limit is 2.4m ₽ and employees are prohibited ([ФНС NPD](https://npd.nalog.ru/), [IP transition](https://www.nalog.gov.ru/rn77/taxation/taxes/vibor_sn//go_to_npd/)). At 299 ₽/30 days the limit is about 669 average annual subscribers before other NPD income. Treat IP+NPD as a launch bridge, not an architecture assumption.

The complete legal and data-processing contract is maintained in the [Russia legal/payment checklist](./russia-legal-launch-checklist-2026.md). Before public recurring launch:

1. register IP and choose NPD/USN with an accountant;
2. approve offer/privacy/recurring-consent and receipt flow with counsel;
3. use unique order and idempotency IDs, signed webhook verification and durable payment/subscription state;
4. show next charge amount/date and one-click cancellation;
5. implement retry/dunning, refunds and chargeback reconciliation;
6. never unlock entitlement from the browser return URL alone.

## Experiment scorecard

The first 90 days should be judged by the chain:

```text
qualified visit
→ first useful deck/item
→ first assessed exercise
→ second learning session
→ D7 and D30 return
→ quota value event
→ paid conversion
```

Required breakdowns: acquisition channel, segment, content path, exercise type, trial cohort, fixed 299 ₽ offer, quota, provider/model route, p95 cost and accepted-item rate. Do not optimize registrations, generated-card count or streak independently of retained assessed learning.
