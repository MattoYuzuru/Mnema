---
artifact:
  id: economics-research-2026-10
  type: research
  title: "AI economics and provider research"
  status: historical
  created_at: "2026-10-01"
  owners: ["project-owner"]
---

# Mnema: экономика AI и выбор провайдеров — обновление на 2026-10-01

Статус: research для owner decision, не тарифный контракт и не юридическая/налоговая консультация. Обновляет и расширяет `docs/product/russia-launch-economics-2026.md` (август 2026). Все цены проверены 2026-10-01; маркеры: **[V]** — прочитано на официальной странице 2026-10-01; **[S]** — только поисковый сниппет/вторичный источник или страница не отрисовалась; **[L]** — последнее известное значение, сегодня не перепроверено. Цены вендоров в USD без НДС, если не указано иное.

## 0. Коротко

1. **Текст почти ничего не стоит.** DeepSeek 2026-09-10 заменил V4 Flash на V4.1 Flash (`deepseek-flash`) и снизил цены: $0.30/$1.20 за 1M input/output в peak, $0.15/$0.60 off-peak.
   - Worst case (peak, cache-miss, ×1,5 на repair, 100 ₽/$): средний материал ≈0,95 ₽, набор из 5 упражнений ≈0,72 ₽.
   - Маржу определяют медиа, Free-аудитория и легальный доступ к провайдерам, а не LLM.
2. **Хостинг в Финляндии, вероятно, не сработает.**
   - Hetzner в 2023 г. расторг договоры с клиентами с российскими адресами.
   - 152-ФЗ ст. 18(5) с 2025-07-01 **запрещает** первичную запись ПД граждан РФ в зарубежных БД: 1–6 млн ₽ штрафа, 6–18 млн ₽ при повторе.
   - Финский сервер не делает западные API разрешёнными. OpenAI запрещает *offering access* в неподдерживаемые страны, Gemini API — делать клиентов доступными вне регионов и для аудитории до 18 лет, ElevenLabs прямо называет Россию.
   - Для рекомендуемого стека (DeepSeek, GigaChat, self-host) зарубежный хостинг не нужен: данные — в РФ, в AI уходит только обезличенный учебный текст.
3. **Основной LLM — DeepSeek V4.1 Flash напрямую** (non-thinking для генерации; thinking у DeepSeek теперь включён по умолчанию). V4 Pro — только эскалация.
   - Fallback в RU-контуре — GigaChat: рубли, данные в РФ; новые клиенты с 2026-09-01 подключаются через cloud.ru.
   - DeepSeek **обучается на данных** и хранит их в КНР. Китай в списке «адекватных» стран РКН, поэтому достаточно уведомления о трансграничной передаче.
   - Оплату DeepSeek из РФ официальные источники не подтверждают; проверить малым пополнением.
4. **Google TTS не стоит выбирать по двум причинам.**
   - **Доступ:** российскому ИП он недоступен.
   - **Качество:** по цене всё нормально (Standard $4, WaveNet $4–16 [S], Chirp 3 HD $30 за 1M символов [S]), но Chirp 3 HD — лишь 57-е место из 92 на TTS Arena. Сильная Google-модель Gemini 3.8 Flash TTS (#3) заблокирована по региону и с 2027 г. подорожает вдвое.
   - Главный рычаг TTS — **кэш по хэшу (текст, язык, голос)**: при cache hit credits не списываются.
   - API-кандидаты — Fish Audio (~$15 за 1M байт) и MiniMax ($60 за 1M), после проверки контрагента; затем self-host.
5. **Бесплатный STT для всех — да, но как fair-use 30 мин/мес, не «безлимит».**
   - Самый дешёвый API — Groq whisper-turbo: $0.04/ч, ≈0,07–0,13 ₽/мин. Худший случай — ≤4 ₽ на Free-пользователя, в среднем ≈0,1–0,3 ₽/MAU.
   - Голос — это ПД, а у US/EU-API санкционные оговорки. Поэтому рекомендуемый primary — self-host faster-whisper в РФ: на старте на сервере приложения (инкрементально ≈0 ₽), потом отдельный CPU-узел (≈2,8 тыс. ₽/мес). Бенчмарк латентности обязателен.
   - 100 мин или безлимит — только после замера.
6. **Видео на старте не включать.** 5 секунд стоят 13–200 ₽, вендоры закрывают модели за месяцы (Veo 2/3, Sora 2, Imagen 4).
   - Изображения на старте — поиск в лицензионных стоках (Pexels, Pixabay, Openverse, Wikimedia) почти за 0 ₽ и с поддержкой offline.
   - Генерация (≈0,6–1,4 ₽ эконом, 3–7 ₽ качество) — позже, в Pro/Max.
7. **Фактчек:** low ≈0,8–1,6 ₽, high (до 15 запросов) ≈3,7–16 ₽.
   - Лучшее RU-покрытие у Yandex Search API (0,49 ₽ за запрос) — нужно решение, распространяется ли на него исключение Yandex.
   - Иначе — Perplexity Search API или Exa после legal-проверки.
8. **Usage:** один видимый бар «AI-бюджет» на создание (текст, озвучка, картинки, фактчек).
   - Голосовой ввод и AI-проверка ответов — fair-use вне бара, чтобы учёба никогда не блокировалась генерацией.
   - Дорогие медиа ограничены штучными caps.
   - 1 credit = 0,10 ₽ p95-себестоимости (внутренняя единица); пользователь видит проценты и «≈N материалов».
9. **449 / 990 / 1 900 ₽ проходят guardrail ≤25%** (НПД и эквайринг включены).
   - Бюджет AI + STT + storage: ≈80 / 178 / 341 ₽.
   - Бар при hard guarantee: 360 / 820 / 1 780 credits — это ≈12–17 / 29–39 / 63–85 «материалов с упражнениями и озвучкой» в месяц.
   - При типичном использовании (40%) contribution ≈86%. СБП-подписка вместо карты даёт +2,4 п.п.
10. **Free:** 50 credits/мес (пополнение раз в неделю), 30 мин STT, 50 AI-проверок.
    - Худший случай ≈11,5 ₽, ожидаемо ≈1,2–1,5 ₽/MAU.
    - Break-even ≈0,3–0,4% Paid/MAU.
11. **Paywall:**
    - Free по умолчанию снижает конверсию (default effect, d=0,68). Компромисс: Free выбран, но персонально рекомендуемый тир визуально выделен.
    - Для «чашки кофе» опубликованных A/B нет, и честно она подходит только к 449 ₽. Доказательно работает «≈15 ₽ в день» или monthly-equivalent (+8–45%).
    - Сравнения с такси и репетитором — проверить по ФЗ «О рекламе».
12. **Лимит колод — слабый и рискованный рычаг:** бэклэш Evernote и Quizlet; внутри колоды всё безлимитно, лимит легко обойти. Если вводить — только **свои** колоды, копии публичных не считать.

## 1. Что изменилось с августа 2026

| Факт | Было (август) | Сейчас (2026-10-01) | Следствие |
|---|---|---|---|
| DeepSeek Flash | V4 Flash: peak $0.44 miss / $1.32 out | **V4.1 Flash** (`deepseek-flash`, с 2026-09-10): peak $0.30 / $1.20, off-peak $0.15 / $0.60, cache hit $0.006 / $0.003 [V] | текст ещё на ~10–30% дешевле |
| DeepSeek peak | 01–04 и 06–10 UTC | то же, но **только пн–пт и не в китайские праздники** [V] | реальная доля off-peak выше; планировать всё равно по peak |
| DeepSeek thinking | — | thinking **включён по умолчанию** у обеих моделей [V]; JSON — только `json_object`, strict tools в beta [V] | явно выключать thinking для генерации; schema-валидация на нашей стороне |
| Google media | Imagen 4, Veo 3, Gemini 2.5 Flash Image | Imagen 4 закрыт 2026-08-17, Veo 2/3 — 2026-06-30, 2.5 Flash Image закрывается 2026-10-02 [V] | model id — это конфигурация; нужен adapter + fallback |
| OpenAI video | Sora 2 API | **закрыт 2026-09-24**, замены нет [V] | — |
| Gemini 3.x цены | — | Gemini 3.8 Flash (текст и TTS) **дорожает вдвое с 2027-01-01** [V] | промо-цены не закладывать в unit economics |
| Российские speech-API | SaluteSpeech как fallback | **SaluteSpeech закрыт для новых клиентов** (пакеты и pay-as-you-go) с 2026-07-15 [V] | RU-fallback для STT/TTS почти исчез (Yandex исключён владельцем) |
| GigaChat | 600 ₽ минимум, оплата через Sber | новые клиенты **с 1 сентября 2026 оплачивают через cloud.ru** [V]; GigaChat 3.x на cloud.ru: ≈73/176 ₽ за 1M (3-Pro) [S] | RU-fallback для текста остаётся |
| Эквайринг | карта ≈2,5% × 1,22 НДС | НДС 22% на карточную комиссию; **СБП и T‑Pay — без НДС**, СБП-ставки 0,4–0,7% (по QR) [S]; рекуррент по СБП через `AddAccountQr`/`ChargeQr` [S] | СБП-подписка экономит ≈10 ₽ с каждого платежа 449 ₽ |
| Подписки, закон | — | ЗоЗПП ст. 16.1 п. 4.2 (376-ФЗ) **в силе с 2026-03-01**: отказ от сохранённых платёжных данных — электронно, списание после отказа запрещено [V] | one-click отмена — must-have (уже в checklist) |
| Курс | сценарий 80–100 ₽/$ | ЦБ на 2026-10-01: **83,5588 ₽/$**, 94,881 ₽/€ ([akm.ru](https://www.akm.ru/news/tsb_rf_ustanovil_na_1_oktyabrya_2026_goda_kursy_inostrannykh_valyut_k_rublyu/)) [S] | база 85 ₽/$, stress 100 ₽/$ |

## 2. Допущения модели

- **Курс:** база 85 ₽/$; stress 100 ₽/$. Stress включает возможную премию за трансграничную оплату, комиссии посредника и скачок курса. Квоты строятся по stress.
- **LLM worst case:** peak, cache-miss, × **1,5** на retry/repair/валидацию. Upside: off-peak, 30–70% cache-hit по системному промпту и схемам, ×1,2.
- **Токены операций** (вход включает системный промпт, JSON-схему и контекст колоды):

| Операция | Input | Output | Комментарий |
|---|---:|---:|---|
| Короткий материал | 3k | 1,5k | карточка-объяснение, слово с примерами |
| Средний материал | 5k | 4k | тема урока |
| Подробный материал | 8k | 10k | конспект главы |
| 5 упражнений на материал | 6k | 2,5k | ≈500 токенов на упражнение с дистракторами/feedback |
| AI-проверка ответа (`ai-semantic`) | 1,2k | 0,15–0,2k | rubric + ответ → уровень + короткий evidence |
| Правка по выделению/чат | 4k | 1k | S5 из сценариев владельца |
| «Умный план» | 20k | 6k | статистика колоды; thinking включён |

- **Налог и эквайринг:** НПД 4% с физлиц; карта ≈2,5% × 1,22 = 3,05% (ставка индивидуальна, [T‑Bank](https://www.tbank.ru/business/help/business-payments/internet-acquiring/how-work/price/) [L]); СБП ≈0,4–0,7% без НДС [S].
- **Guardrail:** p95 variable cost ≤20–25% цены, где variable = AI + STT + storage/egress + НПД + эквайринг. Значит, на AI + STT + storage остаётся ≈13–18% цены. Альтернативное прочтение — 20–25% только на AI, без налога и эквайринга — даёт в ≈1,4 раза больше бюджета; см. §6.

## 3. Провайдеры и цены по категориям

### 3.1 Text LLM

Цены за 1M токенов, USD.

| Провайдер / модель | Input miss | Cache hit | Output | Контекст / прочее | Источник |
|---|---:|---:|---:|---|---|
| **DeepSeek V4.1 Flash** (`deepseek-flash`), peak | 0.30 | 0.006 | 1.20 | 1M / max out 384K; concurrency 2 500; vision; JSON `json_object`; tools; strict tools (beta) | [pricing](https://api-docs.deepseek.com/quick_start/pricing), [news 09-10](https://api-docs.deepseek.com/news/news260910) [V] |
| DeepSeek V4.1 Flash, off-peak | 0.15 | 0.003 | 0.60 | off-peak = 50% от peak | там же [V] |
| **DeepSeek V4 Pro** (`deepseek-v4-pro`), peak / off-peak | 1.32 / 0.66 | 0.044 / 0.022 | 3.96 / 1.98 | concurrency 500; сервис продолжен после 09-14 по [changelog](https://api-docs.deepseek.com/updates) | [V] |
| OpenRouter → DeepSeek V4.1 Flash (DeepInfra, ZDR-хост) | 0.14 | 0.0042 | 0.42 | + 5,5% комиссия на покупку кредитов (крипто 5%) | [endpoints](https://openrouter.ai/api/v1/models/deepseek/deepseek-v4.1-flash/endpoints), [fees](https://openrouter.ai/pricing) [V] |
| OpenRouter → Qwen3.8 Flash (Alibaba) | 0.15 | 0.016 | 0.47 | 1M контекст | [OpenRouter](https://openrouter.ai/qwen) [V] |
| OpenRouter → GLM-5.3 Flash (Z.AI / DeepInfra) | 0.15 / 0.075 | 0.03 / 0.015 | 0.50 / 0.25 | — | [V] |
| OpenRouter → Kimi K2.6 (Moonshot) | 0.95 | 0.16 | 4.00 | 262K | [V] |
| **GigaChat 2 Lite / Pro / Max** (юрлица и ИП, вкл. НДС) | 65 / 500 / 650 **₽** | — | то же | минимум 600 ₽/мес; бесплатного лимита для юрлиц нет; новые клиенты — через cloud.ru | [legal tariffs](https://developers.sber.ru/docs/ru/gigachat/tariffs/legal-tariffs) [V] |
| GigaChat 3 Pro / 3.5 Ultra (cloud.ru) | ≈73 / 96 ₽ | — | ≈176 / 289 ₽ | каталог cloud.ru не загрузился | [S] |
| Gemini 3.1 Flash-Lite / 2.5 Flash-Lite | 0.25 / 0.10 | 0.025 / 0.01 | 1.50 / 0.40 | **Россия не в available regions; 18+ правило** | [pricing](https://ai.google.dev/gemini-api/docs/pricing), [terms](https://ai.google.dev/gemini-api/terms) [V] |
| Gemini 3.8 Flash | 0.75 → **1.50 с 2027-01-01** | 0.075 | 3.75 → **7.50** | — | [V] |
| OpenAI gpt-5-mini / gpt-5-nano / gpt-6-luna | 0.25 / 0.05 / 0.10 | 0.025 / 0.005 / 0.01 | 2.00 / 0.40 / 0.50 | Batch/Flex −50%; **Россия не в supported countries** | [pricing](https://developers.openai.com/api/docs/pricing) [V] |
| Anthropic Claude Haiku 4.5 / Sonnet 5 | 1 / 2 | 0.10 / 0.20 | 5 / 10 | Batch −50%; **Россия не в supported countries** | [pricing](https://platform.claude.com/docs/en/about-claude/pricing) [V] |

**Что важно для выбора**

- **Цена.** Средний материал на DeepSeek Flash, peak, ×1,5, 85 ₽/$ — 0,80 ₽. Для сравнения: GigaChat Lite — 0,88 ₽, Gemini 3.1 Flash-Lite — 0,92 ₽, gpt-5-mini — 1,18 ₽, DeepSeek Pro — 2,86 ₽, Haiku 4.5 — 3,19 ₽, GigaChat 2 Pro — 6,75 ₽. Разница не решает экономику: решают качество на RU/KO/JA/ZH/STEM, процент принятых результатов и доступность.
- **Thinking mode.** DeepSeek включает его по умолчанию. Reasoning-токены, по всей видимости, оплачиваются как output (вывод, на pricing-странице не проверено). Для генерации материалов и упражнений его нужно явно выключать, а включать только для «умного плана» и спорных проверок. Иначе стоимость output вырастет в 2–4 раза.
- **Privacy DeepSeek** [V]. В [Privacy Policy](https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html) сказано, что данные используются «to train and improve our technology»; opt-out — письмом на privacy@deepseek.com; хранение — в КНР. Значит, в DeepSeek уходит только обезличенный учебный текст. OpenRouter классифицирует DeepSeek как провайдера `training=true, retains prompts=true`. С `data_collection: "deny"` / `zdr: true` OpenRouter обходит собственный endpoint DeepSeek и отправляет ту же модель на DeepInfra/Fireworks и других без обучения ([OpenRouter terms](https://openrouter.ai/terms), [FAQ](https://openrouter.ai/docs/faq) [V]).
- **Оплата** [S]. DeepSeek работает по предоплаченному балансу. Способы оплаты на официальной FAQ не загрузились; по вторичным источникам — PayPal, карта, Alipay/WeChat. Возврат остатка — по усмотрению DeepSeek. OpenRouter принимает карты, AliPay и USDC [V]; кредиты могут сгореть через 365 дней; крипто-платежи не возвращаются. Российские Visa/MC за рубежом не работают с 2022 г. [L]. Тезис владельца «DeepSeek оплачиваем из России» нужно проверить малым пополнением; держать на балансе минимум.
- **Rate limits** [V]. Concurrency — 2 500 (Flash) и 500 (Pro) на аккаунт, 429 при превышении. Запрос, который не начал inference за 10 минут, закрывается. Значит: async jobs, наши дедлайны, circuit breaker. `user_id` — только opaque pseudonymous.

### 3.2 STT (speech-to-text)

| Провайдер / модель | Цена | $/мин | ₽/мин @85 / @100 | Языки / качество | Data / доступ из РФ | Источник |
|---|---|---:|---:|---|---|---|
| **Groq whisper-large-v3-turbo** | $0.04/час, **минимум 10 с на запрос** | 0.00067 | 0,057 / 0,067 (клипы 5 с: ≈0,13) | Whisper-класс; Groq WER 12% (датасет не указан); без перевода | не обучает; хранение до 30 дн., self-serve ZDR; данные в US GCP [S]; есть санкционная оговорка, списка стран нет [S] | [Groq STT](https://console.groq.com/docs/speech-to-text) [V] |
| Groq whisper-large-v3 | $0.111/час | 0.00185 | 0,16 / 0,19 | WER 10,3% | то же | [V] |
| **Mistral Voxtral Mini Transcribe 2** | $0.003/мин | 0.003 | 0,26 / 0,30 | 13 языков, включая ru/ko/ja/zh/fr/es; FLEURS: ru 4,75, ko 12,3, ja 4,1 (CER), zh 7,3 (CER) — замеры самого Mistral | на Scale по умолчанию opt-out из обучения; ZDR; 30 дн. [S] | [Mistral pricing](https://mistral.ai/pricing/api) [V], [arXiv 2602.11298](https://arxiv.org/html/2602.11298) |
| OpenAI gpt-4o-mini-transcribe / gpt-transcribe / whisper-1 | $0.003 / $0.0045 / $0.006 за мин | 0.003–0.006 | 0,26–0,51 | gpt-4o-mini на FLEURS хуже Whisper по ko/ja/zh | не обучает; ZDR по approval | [pricing](https://developers.openai.com/api/docs/pricing) [V]; **Россия не поддерживается** |
| ElevenLabs Scribe v2 | $0.22/час | 0.0037 | 0,31 / 0,37 | AA-WER 2,2% (лучший); ru/ja «Excellent», ko «Good» | non-Enterprise по умолчанию обучает (opt-out) [S]; **Россия в restricted** [S] | [API pricing](https://elevenlabs.io/pricing/api) [V] |
| Deepgram Nova-3 multilingual | $0.0052/мин | 0.0052 | 0,44 / 0,52 | `multi` без ko/zh | Model Improvement Program, opt-out через `mip_opt_out` | [pricing](https://deepgram.com/pricing) [V] |
| AssemblyAI Universal-2 | $0.15/час | 0.0025 | 0,21 / 0,25 | ru/ja «High»; zh/ko «Good»; Universal-3.5 Pro без ru/ko | обучает по ToS, opt-out для paid | [pricing](https://www.assemblyai.com/pricing) [V] |
| xAI Grok Voice Transcribe 2.0 | $0.10/час (batch) | 0.0017 | 0,14 / 0,17 | AA-WER 2,3%; список языков не опубликован | не проверено | [x.ai](https://x.ai/news/grok-voice-transcribe-2) [V] |
| Gemini 3.5 Transcribe / 2.5 Flash-Lite (аудиовход) | ≈$0.005 / ≈$0.0007 за мин (оценка) | — | — | 85+ языков | **Россия не в регионах; 18+** | [pricing](https://ai.google.dev/gemini-api/docs/pricing) [V] |
| SaluteSpeech (Sber), юрлицо | 0,6 ₽/мин с НДС | — | 0,6 | только ru/en/kk/ky/uz | **новым клиентам недоступен с 2026-07-15** | [legal tariffs](https://developers.sber.ru/docs/ru/salutespeech/tariffs/legal-tariffs) [V] |
| Self-host faster-whisper / Qwen3-ASR (Apache-2.0) на GPU, ориентир **Hetzner GEX45** (RTX PRO 4000 24 GB, Helsinki) | €214/мес + €209 setup [S] | — | 20,3 тыс. ₽/мес фикс. → 0,05–0,12 ₽/мин при 25%/10% загрузке и ≈40× realtime | large-v2 на 3070 Ti: ≈12× (beam 5) – 49× (batch 8 int8); turbo ≈41× (community) | данные не уходят третьей стороне; **Hetzner, вероятно, не обслуживает российских клиентов (§9.2)**; цены российских GPU-облаков не получены | [GEX45](https://www.hetzner.com/dedicated-rootserver/gex45/) [V], цена — [datacenter.news](https://datacenter.news/story/hetzner-launches-gex45-gpu-server-for-ai-workloads) [S]; [faster-whisper](https://github.com/SYSTRAN/faster-whisper) [V] |
| **Self-host на CPU в РФ** (faster-whisper int8) | Timeweb Cloud-100: 8 vCPU / 12 GB — 2 790 ₽/мес [V] | — | фикс.; оценка ≈0,03–0,07 ₽/мин при 30% загрузке (вывод) | small int8 на 8 потоках i7-12700K ≈7,6–15× realtime [V]; **для turbo на CPU бенчмарка нет**; для KO/JA small заметно хуже (вывод) | ПД не покидают РФ, нет трансграничной передачи | [Timeweb](https://timeweb.cloud/services/cloud-servers), [faster-whisper](https://github.com/SYSTRAN/faster-whisper) [V] |

**Выводы по STT**

- Короткие клипы (5–60 с) — основной профиль Mnema. Whisper паддит вход до 30-секундных окон, а Groq берёт минимум 10 с. Поэтому для 5-секундных ответов эффективная цена Groq turbo — ≈$0.0013/мин (≈0,13 ₽/мин @100). Это всё равно в 2–3 раза дешевле Voxtral.
- **GPU self-host окупается не на старте.** Фикс GEX45 ≈20,3 тыс. ₽/мес равен ≈80 тыс. мин Voxtral или ≈150–300 тыс. мин Groq в месяц.
- **CPU self-host в РФ** (≈2,8 тыс. ₽/мес) равен ≈9 тыс. мин Voxtral или ≈21–42 тыс. мин Groq. При этом голос не уходит за рубеж, а лимит Free STT становится фиксированной ёмкостью: при перегрузке — очередь, а не счёт.
- Главный риск — латентность turbo на CPU для интерактивных ответов. Бенчмарк на 5–15-секундных клипах обязателен до выбора; запасной план — small/medium для RU/EN и внешний API для KO/JA после legal OK.
- **Качество по языкам.** Публичных per-language бенчмарков для Groq turbo, gpt-transcribe и Nova-3 нет. Нужен свой eval: 50–100 клипов на RU/EN/KO/JA/ZH/FR/ES, акценты учащихся, шум, клипы 3–60 с. Метрики: WER/CER и, главное, процент ответов в упражнениях, оценённых правильно после транскрипции.

### 3.3 TTS (text-to-speech)

Нормализация: 400 символов ≈ 30 с для RU/EN/FR/ES; 2 400 символов ≈ 3 мин. CJK-текст той же длины в символах звучит в 1,5–2,5 раза дольше, поэтому посимвольная тарификация для CJK относительно выгоднее.

| Провайдер / модель | Цена | $/1M симв. | ₽ за 400 симв. @85 | ₽ за 3 мин @85 | Arena (из 92) | Доступ / лицензия | Источник |
|---|---|---:|---:|---:|---:|---|---|
| Google Cloud TTS Standard | $4/1M | 4 | 0,14 | 0,82 | — | Google Cloud не принимает новых RU-клиентов [S] | [pricing](https://cloud.google.com/text-to-speech/pricing) [S]: таблица не загрузилась |
| Google WaveNet / Neural2 | $4 или $16 / $16 | 4–16 | 0,14–0,54 | 0,8–3,3 | — | Neural2 **без RU** | [S], источники противоречат друг другу |
| Google **Chirp 3: HD** | $30/1M; 1M/мес бесплатно [S] | 30 | 1,02 | 6,12 | **#57** | RU/KO/JA/ZH/FR/ES — GA; стриминг | [Chirp 3 HD](https://docs.cloud.google.com/text-to-speech/docs/chirp3-hd) [V], цена [S] |
| **Gemini 3.8 Flash TTS** (API) | $0.50 текст + $9 аудио за 1M токенов, **×2 с 2027-01-01** | ≈17 (2027: ≈34) | 0,58 | 3,47 | **#3** | **Россия не в регионах; 18+**; free tier используется для обучения | [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing) [V] |
| Gemini 3.8 Flash-Lite TTS | $0.50 + $6 | ≈11,4 | 0,39 | 2,33 | #6 | то же | [V] |
| OpenAI gpt-4o-mini-tts / tts-1 | $0.60 + $12 за 1M токенов / $15 за 1M симв. | ≈19 / 15 | 0,64 / 0,51 | 3,9 / 3,1 | tts-1-hd #33 | **Россия не поддерживается**; нужно раскрывать пользователю, что голос сгенерирован AI | [pricing](https://developers.openai.com/api/docs/pricing) [V] |
| ElevenLabs Flash v2.5 / v3 / **v4** | $0.04 / $0.08 / $0.08 за 1K (v4 промо $0.022 до 12.10) | 40 / 80 / 80 | 1,36 / 2,72 | 8,2 / 16,3 | v4 **#1**, v3 #16 | **Россия в restricted** [S]; коммерческая лицензия от платного плана | [API pricing](https://elevenlabs.io/pricing/api) [V] |
| Azure Neural / HD | $15 / $22 за 1M | 15 / 22 | 0,51 | 3,06 | HD #26, Neural #65 | Microsoft остановил продажи в РФ [S] | [Azure Retail Prices](https://prices.azure.com) [V] |
| **Fish Audio** s2.1-pro | $15 за 1M **UTF-8 байт** (RU ≈ $28–30 за 1M симв., CJK ≈ $45) | 15 (EN) / ≈28 (RU) | 0,51 (EN) / 0,95 (RU) | 3,1 / 5,7 | #22 | pay-as-you-go; открытые веса **некоммерческие**; доступ из РФ не проверен | [Fish pricing](https://docs.fish.audio/developer-guide/models-pricing/pricing-and-rate-limits) [V] |
| **MiniMax** speech-2.8-turbo / hd | $60 / $100 за 1M | 60 / 100 | 2,04 / 3,40 | 12,2 / 20,4 | #20 / #18 | доступ из РФ не проверен | [MiniMax paygo](https://platform.minimax.io/docs/guides/pricing-paygo.md) [V] |
| Cartesia Sonic 3.6 | ≈$37–50 за 1M (через план) | 37–50 | 1,26 | 7,6 | #2 | коммерция от Pro | [Cartesia](https://cartesia.ai/pricing) [V] |
| Amazon Polly Neural / Generative | $16 / $30 | 16 / 30 | 0,54 / 1,02 | 3,3 / 6,1 | Generative #51 | AWS остановил продажи в РФ [S] | [Polly](https://aws.amazon.com/polly/pricing/) [V] |
| SaluteSpeech TTS | 186 ₽ за 1M симв. с НДС | ≈2,2 | 0,07 | 0,45 | — | **закрыт для новых клиентов** с 2026-07-15 | [legal tariffs](https://developers.sber.ru/docs/ru/salutespeech/tariffs/legal-tariffs) [V] |
| Self-host **Qwen3-TTS** (Apache-2.0) | GPU (GEX45) | — | ≈0 маржинально | — | **#82** | все 7 языков, стриминг ≈97 мс; качество на арене низкое | [Qwen3-TTS](https://github.com/QwenLM/Qwen3-TTS) [V] |
| Self-host Kokoro (Apache-2.0) / Piper (GPL-3.0) | CPU | — | ≈0 | — | Kokoro #50 | **Kokoro без RU и KO; Piper без KO/JA** | [V] |

Arena — [Artificial Analysis TTS leaderboard](https://artificialanalysis.ai/text-to-speech/leaderboard), 2026-10-01 [V]. Рейтинг фактически англоязычный; независимого русского бенчмарка нет. Русские обзоры хвалят SpeechKit и SaluteSpeech за ударения [S], но первый исключён владельцем, а второй закрыт.

**Ответ на гипотезу «Google TTS качественный, дешёвый и разнообразный»**

- **Дешёвый** — да, но только legacy-голоса (Standard/WaveNet). Chirp 3 HD ($30) стоит как tts-1-hd и дороже Gemini-TTS.
- **Разнообразный** — да: Chirp 3 HD поддерживает все 7 нужных языков, SSML, стриминг.
- **Качественный** — на английской арене средне (#57). Лидеры — ElevenLabs v4, Cartesia и сам Google в линейке **Gemini 3.8 Flash TTS**.
- **Блокер — доступ, а не цена.** Новых клиентов из РФ Google Cloud не берёт; Gemini API исключает Россию и детскую аудиторию. Для российского ИП это не production-вариант. Решать вопрос через иностранное юрлицо или посредников — задача юриста, здесь это не рекомендуется.

**Главный экономический рычаг TTS — кэш, а не выбор вендора.** Озвучка слова или предложения детерминирована по `(нормализованный текст, язык, голос, модель, версия)`. Если хранить аудио в object storage и переиспользовать между пользователями и копиями публичных колод, стоимость на пользователя падает в разы. Генерация при cache hit не должна списывать credits.

### 3.4 Изображения: генерация и поиск

| Провайдер / модель | Эконом | Качество | ₽/шт. @85 (эконом / качество) | Заметки | Источник |
|---|---:|---:|---:|---|---|
| fal FLUX.1 schnell / Z-Image Turbo | $0.003 / $0.005 за MP | — | 0,26 / — | fal (US), санкционные оговорки | [fal pricing](https://fal.ai/pricing) [V] |
| OpenAI gpt-image-1-mini (low / med / high) | $0.005 / $0.011 | $0.036 | 0,43–0,94 / 3,06 | **Россия не поддерживается** | [model page](https://developers.openai.com/api/docs/models/gpt-image-1-mini) [V] |
| Recraft V4.1 Flash / V4.1 | $0.007 / $0.035 | V4.1 Pro $0.21 | 0,60 / 3,0 | коммерческие права у платных планов | [Recraft API](https://www.recraft.ai/docs/api-reference/pricing) [V] |
| BFL FLUX.2 klein / pro / max | от $0.014 за MP | $0.03–0.07 за MP | 1,19 / 2,6–6 | output принадлежит клиенту; есть export/sanctions-оговорки | [BFL pricing](https://docs.bfl.ai/quick_start/pricing), [terms](https://bfl.ai/legal/developer-terms-of-service) [V] |
| fal Qwen-Image / Seedream 5.0 Lite | $0.02 / $0.035 | Seedream 5.0 Pro $0.135 [S] | 1,7 / 3,0 | китайские модели через US-хостинг | [V] |
| Gemini 3.1 Flash Lite Image / 3.1 Flash Image (1K) | $0.0336 / $0.067 | 3 Pro Image $0.134 | 2,9 / 5,7–11,4 | **Россия вне регионов; 18+**; 2.5 Flash Image закрывается 2026-10-02 | [pricing](https://ai.google.dev/gemini-api/docs/pricing), [deprecations](https://ai.google.dev/gemini-api/docs/deprecations) [V] |
| Ideogram 3.0 / Stability Core–Ultra | $0.03–0.09 / $0.03–0.08 | — | 2,6–7,7 | страницы JS-only | [S] |
| Kandinsky (Sber) | не оценён | — | — | RU-вариант; цену не удалось получить (лимит поиска) | open item |

**Поиск «похожего изображения»: лицензионно безопасные источники**

| Источник | Цена / лимиты | Лицензия и хранение | Пригодность для Mnema (offline-пакеты!) |
|---|---|---|---|
| [Pexels API](https://www.pexels.com/api/documentation/) [V] | бесплатно; 200 req/h, 20k/мес | коммерция ок, атрибуция желательна; нельзя перепродавать неизменённые фото | **хорошо**: можно хранить копию |
| [Pixabay API](https://pixabay.com/api/docs/) [V] | бесплатно; 100 req/60 с; кэш ответа 24 ч | **hotlink запрещён — нужно скачать к себе**; коммерция без атрибуции | **хорошо** для offline |
| [Openverse](https://docs.openverse.org/api/reference/authentication_and_throttling.html) / [Wikimedia Commons](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia) [V] | бесплатно; ≈200/день анонимно [S]; Wikimedia ≤5 req/s | CC: хранить автора, лицензию и ссылку; BY-SA требует share-alike; точность лицензий не гарантируется | хорошо для научных и исторических тем; нужен attribution-node в документе |
| [Unsplash API](https://unsplash.com/documentation) [V] | бесплатно; 50/1 000 req/h | **обязателен hotlink** с `ixid` и вызов `download_location` | **плохо**: конфликтует с offline и с доступностью CDN из РФ |
| Brave / Serper / SerpAPI image search | $0.3–25 за 1k | права на сами изображения **не даются** | только как «источник-ссылка», не как вставка |
| Bing Image Search API | **закрыт 2025-08-11** [V] | — | — |
| Google Custom Search JSON API | $5 за 1k; **закрыт для новых клиентов, конец 2027-01-01** [V] | — | — |

**Вывод:** на старте «Проиллюстрировать» = поиск в Pexels + Pixabay + Openverse/Wikimedia (LLM строит запрос, ≈0,05 ₽) с сохранением файла и атрибуции. Генерацию добавлять позже как платную capability. Кандидаты: Recraft V4.1 Flash, FLUX.2 klein, Qwen-Image — после legal-проверки контрагента.

### 3.5 Видео

| Модель | $/с | 5 с, ₽ @85 / @100 | Источник |
|---|---:|---:|---|
| Gemini Omni 1.1 Flash 360p (fal) | 0.03 | 12,8 / 15 | [fal](https://fal.ai/models/google/gemini-omni-flash/v1.1/text-to-video) [V] |
| Kling 2.6 720p, без звука (official) | 0.042 | 17,9 / 21 | [S] |
| Hailuo-02 768p (fal, клип 6 с) | 0.045 | 23 за 6 с | [fal](https://fal.ai/models/fal-ai/minimax/hailuo-02/standard/text-to-video) [V] |
| Veo 3.1 Lite 720p / Runway gen4_turbo | 0.05 | 21 / 25 | [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing), [Runway](https://docs.dev.runwayml.com/guides/pricing/) [V] |
| Veo 3.1 Fast / Wan 2.6 720p | 0.10 | 42,5 / 50 | [V] |
| Veo 3.1 Standard (со звуком) | 0.40 | 170 / 200 | [V] |

**Вывод:** не включать на старте. Один 5-секундный клип стоит как 20–200 средних материалов. Учебная ценность короткого стокового видео без сценария и контроля фактов низкая. Вендоры меняются быстро: Veo 3 и Sora 2 закрыты за три месяца. Google-модели к тому же исключают РФ и аудиторию до 18 лет. Если владелец хочет витрину — только в старшем тире, ≤3–5 клипов в месяц, low-res и с ручным подтверждением стоимости.

### 3.6 Web search для фактчека

| Провайдер | $/запрос | $/страница | Low ₽ @100* | High ₽ @100* | Free | Заметки | Источник |
|---|---:|---:|---:|---:|---|---|---|
| **Perplexity Search API** | 0.005 за request (до 5 queries); fast 0.001 | контент страниц включён | **0,8** | **3,7** | нет | FAQ: zero retention, AWS США [S]; языковой фильтр | [pricing](https://docs.perplexity.ai/getting-started/pricing) [V] |
| Serper + Tavily extract | 0.001 | 0.0016 | 0,9 | 5,3 | 2 500 разово | выдача Google без прав на хранение | [S] / [Tavily](https://docs.tavily.com/documentation/api-credits) [V] |
| Brave Search (+ Tavily extract) | 0.005 | 0.0016 | 1,3 | 11,3 | $5/мес | **хранение результатов — только на плане с storage rights** | [Brave](https://brave.com/search/api/) [V] |
| Exa Auto + contents | 0.007 | 0.001 | 1,3 | 13,7 | $10/мес | ZDR только Enterprise; фильтр по языку запроса | [Exa](https://exa.ai/pricing) [V] |
| Tavily basic | 0.008 (0.005 на плане) | 0.0016 | 1,6 | 15,8 | 1 000 credits/мес | «zero retention» в маркетинге vs privacy policy | [V] |
| OpenRouter web plugin (Exa) | 0.007 + токены | — | 1,0 | 12,7 | — | для DeepSeek по умолчанию Exa | [docs](https://openrouter.ai/docs/features/web-search) [V] |
| Gemini grounding (3.x) | 0.014 за query | — | — | — | 5k/мес на paid | **запрещено кэшировать и встраивать результаты** — не подходит для сохраняемых материалов | [terms](https://ai.google.dev/gemini-api/terms) [V] |
| Yandex Search API | $0.004 / 0,488 ₽ с НДС (sync) | — | 0,9 | 7,6 | — | лучшее RU-покрытие; **Yandex исключён владельцем** — уточнить, распространяется ли исключение на поиск | [Yandex](https://aistudio.yandex.ru/docs/en/search-api/pricing.html) [V] |

\* Low = 1 query + 3 страницы + чтение LLM (6k вход / 0,8k выход, Flash peak). High = 15 queries + 10 страниц + 60k / 3k.

## 4. Себестоимость типовых операций, ₽

Worst = peak + cache-miss + ×1,5 + 100 ₽/$. Base = то же при 85 ₽/$. Upside = off-peak + cache + ×1,2 при 85 ₽/$. Credits = ⌈worst / 0,10 ₽⌉, округлено до удобного веса.

| Операция | Маршрут | Worst | Base | Upside | Вес, credits |
|---|---|---:|---:|---:|---:|
| Короткий материал | DeepSeek V4.1 Flash, non-thinking | 0,40 | 0,34 | 0,12 | 4 |
| Средний материал | то же | 0,95 | 0,80 | 0,28 | 10 |
| Подробный материал | то же | 2,16 | 1,84 | 0,67 | 22 |
| 5 упражнений на материал | то же | 0,72 | 0,61 | 0,21 | 8 |
| Правка по выделению / чат | то же | 0,36 | 0,31 | 0,09 | 4 |
| AI-проверка одного ответа | Flash, rubric в кэшируемом префиксе | 0,05–0,09 | 0,04–0,08 | 0,02 | fair-use (≈0,5) |
| «Умный план» | Flash + thinking / **Pro** + thinking | 1,98 / **7,52** | 1,68 / 6,40 | 0,6 / 2,17 | 20 / 75 |
| 1 мин STT | Groq turbo (клипы ≥10 с / 5 с) / Voxtral | 0,07 / 0,13 / 0,30 | 0,06 / 0,11 / 0,26 | — | fair-use |
| TTS слова/фразы ≤100 симв. | Fish (RU) / Gemini-класс | 0,28 / 0,12 | 0,24 / 0,10 | **0 при cache hit** | 2–3 |
| TTS-клип 30 с (400 симв.) | Fish RU / MiniMax turbo / Chirp 3 HD | 1,12 / 2,40 / 1,20 | 0,95 / 2,04 / 1,02 | 0 при cache hit | 10 |
| «Подкаст» 3 мин (2 400 симв. + сценарий) | Fish RU + Flash / MiniMax turbo | 7,2 / 14,9 | 6,2 / 12,7 | — | 75 (cap по штукам) |
| Изображение, эконом | Recraft V4.1 Flash / FLUX.2 klein / gpt-image-1-mini low | 0,7–1,4 | 0,6–1,2 | — | 15 |
| Изображение, качество | FLUX.2 pro / Gemini 3.1 Flash Image / Recraft V4.1 | 3–7 | 2,6–5,7 | — | 60 (cap) |
| Поиск лицензированного изображения | Pexels/Pixabay/Openverse + Flash для запроса | 0,05 | 0,04 | — | 1 |
| Видео 5 с | Omni 360p / Kling 2.6 / Veo 3.1 Fast | 15 / 21 / 50 | 13 / 18 / 43 | — | 250–500 (cap) |
| Фактчек low | Perplexity Search / Exa / Tavily | 0,8–1,6 | 0,7–1,3 | — | 15 |
| Фактчек high (до 15 запросов) | Perplexity Search / Serper+Tavily / Exa | 3,7 / 5,3 / 13,7 | 3,1–11,6 | — | 120 (cap) |

Порядки, которые стоит запомнить: **1 средний материал ≈ 1 озвученный 30-секундный клип ≈ 1 эконом-картинка ≈ 1 low-фактчек ≈ 10 минут STT**. **1 видео ≈ 20–50 материалов.**

## 5. Модель usage и credits

### 5.1 Единица

- **Внутренний ledger** хранит фактическую себестоимость в микрорублях по каждому вызову (провайдер, модель, токены/секунды/символы, курс) и **credits**. 1 credit = 0,10 ₽ плановой p95-себестоимости при stress-допущениях; это сохраняет совместимость с августовским guardrail.
- **Пользователь** видит не credits и не рубли, а **процент бара** («использовано 37%») и перевод в понятные единицы («≈ 12 материалов с упражнениями или ≈ 25 озвучек»). Так работают Claude, где бар в процентах с окнами 5 ч и неделя ([support](https://support.claude.com/en/articles/11049741-what-is-the-max-plan) [V]), и Cursor, где included usage не раскрывается в долларах ([pricing](https://cursor.com/pricing) [V]). Видимые коины (GoGPT, Chad AI, RemNote) честнее, но провоцируют «торг за каждую искру» и раскрывают маржу.
- **Веса** фиксируются таблицей §4 и пересматриваются после 2 недель реальных замеров: вес = ⌈p95 фактической стоимости операции за 14 дней / 0,10 ₽⌉. Цена операции для пользователя не меняется задним числом.

### 5.2 Один бар или два

| Вариант | Плюсы | Минусы |
|---|---|---|
| **A. Один общий бар на всё** (текст + медиа + STT) | максимально просто; подстраивается под persona: языковой ученик тратит на аудио, экзаменационный — на текст | STT и AI-проверка тратят бар во время учёбы → человек «боится заниматься»; дорогая медиа может съесть месяц за вечер |
| **B. Два бара: текст и медиа** | изолирует дорогие медиа; медиа можно отключить при сбое провайдера, не трогая текст | два бара и два upsell путают; у каждого пользователя остаётся неиспользованный бар → ниже воспринимаемая ценность; persona-разницу не ловит |
| **C (рекомендация). Один бар «AI-бюджет» на создание + fair-use на учебный цикл + count-caps на дорогие медиа** | учёба не блокируется генерацией; один понятный бар; медиа-риск ограничен штучными caps; работает с persona-paywall | чуть больше правил; caps надо объяснять в preflight |

Подробно вариант C:

- **Бар «AI-бюджет»**: материалы, упражнения, правки, озвучка, изображения, фактчек, подкасты, видео (если появится).
- **Fair-use «Голос и проверка»**: минуты STT и число AI-проверенных ответов в день/месяц. Это не бар, а тихий счётчик, который появляется при >80%. Себестоимость небольшая, а блокировать учебную сессию нельзя: это ядро retention.
- **Count-caps** внутри бара: подкасты, «качественные» изображения, high-фактчек, видео. Они защищают от «съел месяц за вечер» и ограничивают зависимость от дорогих вендоров.
- В UI в любом случае есть строка «На тарифе Plus доступно ≈2,2× больше» с конкретным примером для persona.

### 5.3 Окна, перенос, недельное разблокирование

- **Paid:** весь месячный бюджет доступен сразу; **дневной burst-cap ≤35%** месячного бара против скриптов и шаринга аккаунта. Недельное разблокирование (август) не снижает худшую стоимость оплаченного месяца: guardrail и так считается на полный бар. Зато оно раздражает при «импорте курса за выходные». Это рычаг breakage, а не безопасности.
- **Free:** недельное пополнение (¼ месячного бара в понедельник, без накопления дальше недели) — даёт привычку и ограничивает разовые всплески у фермы аккаунтов.
- **Rollover:** нет. Остаток сгорает в конце цикла; у годового тарифа бюджет тоже выдаётся помесячно. GoGPT переносит до 2× ([gogpt.ru](https://gogpt.ru/) [V]), но это увеличивает tail-risk без доказанного роста retention.
- **Докупка:** «буст» +50% бара на текущий цикл, разовая покупка (аналог «Буст» Алисы за 100 ₽ — [Alice Plus](https://alice.yandex.ru/support/ru/assistant/alice-plus) [V]). Вводить только после recurring и с чеком НПД на каждую покупку.

### 5.4 Preflight-оценка и списание

```text
estimate(op) = Σ_calls [ in_tokens_est × p_in_miss_peak + max_out_tokens × p_out_peak ] × fx_stress × repair(op)
             + Σ_media [ units × unit_price ] × fx_stress
             + (search_queries × p_query + pages × p_page) × fx_stress
credits_reserved = ceil(estimate / 0.10 ₽)

if credits_reserved > remaining_bar or count_cap_exceeded or daily_burst_exceeded:
    offer smaller variant (меньше упражнений / low effort / без картинки) or upsell
else:
    hold(credits_reserved) → run job → settle(actual_cost) → release(hold − actual)
```

- `max_out_tokens` и thinking budget **жёстко задаются в запросе**. Без этого worst-case не ограничен.
- Если фактическая стоимость превысила hold (редко, при repair-цикле), разница списывается с бара, а не с пользователя «в минус». Пользователь в минус не уходит, разницу покрывает маржа.
- При неуспехе job (сбой провайдера, невалидный JSON после всех retry) — полный возврат hold.
- Preflight показывает: «≈3% бюджета • ≈40 с • DeepSeek (Китай) получит обезличенный текст».

## 6. Тарифы и guardrail

### 6.1 Бюджет себестоимости на p95-пользователя

Variable = AI + STT + storage + НПД 4% + эквайринг. Для карты эквайринг = 3,05%, для СБП ≈0,7% без НДС.

| Тир | Цена | НПД | Эквайринг (карта / СБП) | Бюджет AI+STT+storage при 20% / **25%** (карта) | То же, СБП, 25% | Альтернатива: 20–25% только на AI |
|---|---:|---:|---:|---:|---:|---:|
| Plus | 449 ₽ | 17,96 | 13,69 / 3,14 | 58,1 / **80,6** | 91,1 | 89,8–112,3 |
| Pro | 990 ₽ | 39,60 | 30,20 / 6,93 | 128,2 / **177,7** | 201,0 | 198–247,5 |
| Max | 1 900 ₽ | 76,00 | 57,95 / 13,30 | 246,0 / **341,0** | 385,7 | 380–475 |

### 6.2 Предлагаемые allowances (launch, hard guarantee: сумма всех caps ≤25% цены)

Резервы fair-use посчитаны по худшему случаю: STT — Groq при 5-секундных клипах, 0,133 ₽/мин; AI-проверка — 0,05 ₽/ответ.

| | Free | Trial (14 дн., 1 раз, без карты, старт по AI-intent) | **Plus 449** | **Pro 990** | **Max 1 900** |
|---|---|---|---|---|---|
| Бар «AI-бюджет», credits/мес | 50 (12–13 в неделю) | 150 на весь trial | **360** | **820** | **1 780** |
| STT fair-use, мин/мес (≤ в день) | 30 (10) | 60 | 120 (30) | 300 (60) | 600 (120) |
| AI-проверка ответов/мес (≤ в день) | 50 (5) | 200 | 500 (40) | 1 000 (80) | 1 500 (120) |
| Подкасты 3 мин (cap) | — | — | 2 | 6 | 15 |
| Изображения | только поиск по стокам | поиск + 3 эконом | эконом в баре (≤20/день) | эконом + 10 «качество» | эконом + 25 «качество» |
| Фактчек | low | low | low + 2 high | low + 5 high | low + 12 high |
| «Умный план» | — | 1 | 4/мес (Flash) | еженедельно | еженедельно + 4 Pro |
| Видео | — | — | — | — | на старте нет (опция: 3 клипа low-res) |
| Max себестоимость, ₽ | ≈11,5 | ≈33 | **80** (24,9% с налогом/эквайрингом) | **177** (24,9%) | **341** (25,0%) |

### 6.3 Что помещается в бар («материал + 5 упражнений + сопутствующее»)

| Persona | Связка, credits | Free | Plus | Pro | Max |
|---|---:|---:|---:|---:|---:|
| A. Экзамен / собеседование: средний материал + 5 упражнений + 0,2 low-фактчека + 0,5 правки | 23 | ≈2 | **15** | **35** | **77** |
| B. Язык: короткий материал + 5 упражнений + 4 озвучки слов + 0,5 клипа 30 с + 0,3 правки | 28 (≈21 при 50% cache hit) | ≈2 | **12–17** | **29–39** | **63–85** |
| C. Визуал: средний материал + 5 упражнений + эконом-картинка + 0,5 клипа | 38 (24 при поиске по стокам) | ≈1 | **9–15** | **21–34** | **46–74** |

Если брать операции по одной, Plus = ≈36 средних материалов, или 45 наборов упражнений, или 36 озвученных 30-секундных клипов, или 24 эконом-картинки. Подкасты на Plus ограничены cap 2, хотя по бару поместилось бы ≈4–5.

После двух полных когорт стоит перейти от hard guarantee к **measured p95**: p95-пользователь почти никогда не выбирает все fair-use caps одновременно. Тогда бар можно поднять примерно в 1,35–1,5 раза (Plus ≈530, Pro ≈1 190, Max ≈2 400 credits) при том же guardrail по фактическому p95.

### 6.4 Маржа

| Тир | Contribution при 100% использования | При 40% (типично) | Маржа, 40% | +СБП вместо карты |
|---|---:|---:|---:|---:|
| Plus 449 | 337 ₽ | 385 ₽ | 86% | +10 ₽ |
| Pro 990 | 743 ₽ | 849 ₽ | 86% | +23 ₽ |
| Max 1 900 | 1 425 ₽ | 1 630 ₽ | 86% | +44 ₽ |

Contribution не включает fixed: сервер, РКО 490 ₽ в активный месяц, юрист и бухгалтер, маркетинг, refund/chargeback reserve ≈3–5% (RevenueCat: refund rate в Education 4,86% за 2025 г. [S]).

### 6.5 Free и бесплатный STT: не разорит ли

Стоимость STT на одного Free-пользователя в месяц. Худший случай — весь cap израсходован. Среднее на MAU — при 30% пользующихся и 20% использования cap.

| Cap Free | Groq turbo, худший | Groq, среднее/MAU | Voxtral / 4o-mini, худший | Voxtral, среднее/MAU | Self-host CPU в РФ |
|---|---:|---:|---:|---:|---|
| 10 мин | 0,7–1,3 ₽ | 0,04–0,08 ₽ | 3 ₽ | 0,18 ₽ | маргинально ≈0; фикс ≈2,8 тыс. ₽/мес = 2,8 ₽/MAU при 1 тыс. MAU и 0,28 ₽ при 10 тыс. |
| **30 мин** | 2–4 ₽ | 0,12–0,24 ₽ | 9 ₽ | 0,54 ₽ | — |
| 100 мин | 6,7–13,3 ₽ | 0,4–0,8 ₽ | 30 ₽ | 1,8 ₽ | — |

Ожидаемая полная стоимость Free MAU (бар + STT + проверка + ≈0,3 ₽ инфраструктуры): **≈1,2–1,5 ₽** в базовом сценарии (25% пользуются AI, тратят половину бара) и **≈2,9–4,2 ₽** при активном использовании. Break-even доля платящих при contribution ≈380 ₽: **0,26%** при 1 ₽/MAU (1 из ~380), 0,39% при 1,5 ₽, 0,65% при 2,5 ₽.

На малом масштабе (<≈10 тыс. MAU) отдельный STT-сервер дороже Groq в пересчёте на MAU. Поэтому на старте faster-whisper стоит запускать **на уже оплаченном сервере приложения** в РФ (инкрементально ≈0 ₽) с низким приоритетом и очередью. Отдельный узел — когда загрузка CPU от STT стабильно >50%. Self-host выбирается из-за ПД и санкционных рисков, а не цены; Groq — дешёвый fallback после legal OK.

**Вывод:** «STT бесплатно для всех» допустим как **fair-use 30 мин/мес на self-host в РФ** (или Groq после legal OK) с защитой:

- verified account и одна Free-квота на identity;
- клип ≤60 с, ≤10 мин/день;
- **VAD/обрезка тишины на сервере до отправки** (снижает оплачиваемые минуты);
- отказ от не-речевого аудио;
- velocity-лимиты по устройству и IP;
- глобальный дневной лимит расходов Free-когорты с graceful degradation («распознавание временно в очереди»).

100 мин или «безлимит» — только на self-host и после измерения реального использования.

### 6.6 Потолок НПД

2,4 млн ₽/год = 200 тыс. ₽ gross в месяц. Это ≈445 подписчиков при ARPPU 449 ₽ и ≈308 при смешанном ARPPU ≈650 ₽. Старшие тиры быстрее доводят до потолка в штуках, но не в деньгах. Алерты на 1,8 и 2,1 млн ₽ и переход на УСН — как в checklist.

## 7. Как упаковывают AI конкуренты и аналоги

| Продукт | Цена | Упаковка AI | Источник |
|---|---|---|---|
| RemNote | Pro $8; Pro+AI $18/мес | видимый пул credits: 250 / 1 000 / 20 000 в месяц; AI-карточки из PDF 5 / 50 / 1 000 | [pricing](https://www.remnote.com/pricing) [V] |
| Claude | Pro $20; Max $100 / $200 | непрозрачный множитель («≥5× Free»); окна 5 ч + неделя; бар в процентах; overage по API-ценам | [pricing](https://claude.com/pricing), [Max](https://support.claude.com/en/articles/11049741-what-is-the-max-plan) [V] |
| Cursor | Pro $20; Pro+ $60; Ultra $200 | included usage без раскрытия в $, затем on-demand | [pricing](https://cursor.com/pricing) [V] |
| ChatGPT | Go $8 (₹399), Plus, Pro | «безлимитный» текст + отдельные caps на изображения, файлы, анализ | [Go](https://openai.com/index/introducing-chatgpt-go/) [S] |
| Notion | AI только в Business (€19,50) | бандл в старшем тире + credits на агентов | [pricing](https://www.notion.com/pricing) [V] |
| Quizlet | Plus $35.99/год; Unlimited $44.99/год | caps на учебные действия (тесты, раунды Learn); Q-Chat закрыт в 06.2025 | [S] |
| Gizmo / Knowt | ≈$13.99/нед / $149.99/год | «жизни» 15/день, 10 AI-квизов/день / ≈20 AI-использований у Free | [S] / [Knowt](https://knowt.com/plans) [V] |
| Duolingo Max / Speak / Memrise | $29.99/мес / $84–165/год / Pro $24.99/мес или $61.99/год | AI как отдельный премиум-тир (видеозвонок, roleplay, AI Buddies) | [S] / [Speak](https://www.speak.com/us/bfcm) [V] / [S] |
| **Алиса Плюс** | 199 ₽/мес поверх Яндекс Плюс 449 ₽ | одна опция поднимает все лимиты; **«Буст» 100 ₽ разово** поднимает один лимит на месяц | [Alice Plus](https://alice.yandex.ru/support/ru/assistant/alice-plus) [V] |
| GoGPT / Chad AI / Syntx (RU-агрегаторы) | от 699 ₽ / 290–1 690 ₽ / 750–4 990 ₽ | коины или «искры» с оценкой стоимости до запроса; перенос до 2× (GoGPT) | [gogpt.ru](https://gogpt.ru/) [V]; остальные [S] |
| Skyeng Self-Study / English Galaxy | от 750 ₽ / 799 ₽ в месяц | безлимитные AI-уроки / подписка | [Skyeng](https://skyeng.ru/self-study/) [V] / [S] |
| RU flashcard-приложения | Flashcards Plus и Flashka 349 ₽/мес, 899–1 490 ₽/год; Flash AI 349 ₽/мес (август, [L]); Space — RU-цена не найдена (US $4.99/мес, $44.99/год) | в основном без AI-квот | [S] |

**Выводы для Mnema**

- Российский ценовой коридор для AI-подписок — **≈200–1 700 ₽**. Ниже — встроенные в экосистемы: GigaChat бесплатно, Алиса Плюс +199 ₽. Выше — агрегаторы и языковые школы. 449 ₽ совпадает с ценой Яндекс Плюс, это узнаваемый якорь. 990 ₽ — уровень языковых приложений. 1 900 ₽ — выше топ-тиров агрегаторов (1 690 ₽); его нужно оправдывать медиа и объёмом.
- Учебные продукты почти не показывают usage-бар — они ставят caps на действия. AI-native продукты показывают бар. Комбинация «бар на создание + fair-use на учёбу» соединяет оба паттерна.
- Шаг цен 449 → 990 → 1 900 (×2,2 и ×1,9) соответствует рынку: у конкурентов шаг ≈×2.

## 8. Paywall и ценовая психология

| Приём | Что известно | Практика для Mnema |
|---|---|---|
| «Чашка кофе / такси / репетитор» | **Опубликованных A/B для «чашки кофе» нет** ([S](https://botsi.com/blog-posts/19-paywall-tests)). Базовое исследование — Gourville 1998 «pennies-a-day»: per-day framing переводит сравнение в категорию мелких трат (52% vs 30% согласий). Но **для крупных сумм эффект разворачивается** (Gourville 2003) ([JCR](https://academic.oup.com/jcr/article-abstract/24/4/395/1797969) [V]). Якоря РФ: капучино ≈241–352 ₽, такси по Москве в среднем ≈928 ₽, онлайн-урок с репетитором ≈1 025 ₽ [S]. | 449 ₽ = **≈15 ₽ в день** = «1–2 капучино в месяц». 990 ₽ ≈ «одно занятие с репетитором в месяц». 1 900 ₽ ≈ 63 ₽/день. Писать «≈15 ₽ в день», а не «дешевле репетитора»: сравнение с чужими товарами по ФЗ «О рекламе» ст. 5 требует доказуемого критерия → **проверить с юристом** |
| Monthly-equivalent для годового | +8% (US) … +45% (Бразилия) revenue per impression; эффект сильнее в ценочувствительных рынках ([RevenueCat](https://www.revenuecat.com/blog/growth/subscription-pricing-psychology-how-to-influence-purchasing-decisions) [V]) | при вводе годового показывать «≈333 ₽/мес при оплате за год» |
| Три тира, decoy, center-stage | Decoy-эффект (Huber 1982) в реалистичных условиях слаб (Frederick 2014 [L]). Center-stage: средний вариант воспринимается как популярный (Valenzuela & Raghubir 2009 [S]) | три тира оправданы, только если у Max есть реальная ценность (медиа); фиктивный «якорный» тир не делать |
| **Free выбран по умолчанию** | Default effect силён: мета-анализ 58 датасетов, **d=0,68**, сильнее в consumer-решениях и когда дефолт читается как рекомендация ([BPP 2019](https://www.cambridge.org/core/journals/behavioural-public-policy/article/when-and-why-defaults-influence-decisions-a-metaanalysis-of-default-effects/67AF6972CFB52698A60B6BD94B70C2C0) [V]). Публичных A/B «default Free vs подсвеченный средний» **не найдено** | Free по умолчанию снизит конверсию: пользователь прочитает это как «мы советуем Free». Компромисс: радио-выбор стоит на Free (честно, без pre-ticked платных опций — ЗоЗПП ст. 16 в ред. 69-ФЗ запрещает навязывание [S]), но рядом **визуально выделен рекомендуемый тир** с бейджем из онбординга («Для подготовки к экзамену») и конкретикой («≈35 тем с упражнениями в месяц»). A/B-тест «Free selected vs recommended selected» — после трафика ≥1–2 тыс. показов paywall |
| Онбординг «для чего вам Mnema» → персональный paywall | RevenueCat JTBD-кейсы: +72% конверсии, +169% free-to-paid — но это **10–20 последовательных тестов**, а не один вопрос ([RevenueCat](https://www.revenuecat.com/blog/growth/jtbd-paywall-optimization/) [V]). Вопросы помогают, только если меняют последующие экраны ([Adapty](https://adapty.io/blog/how-to-personalize-onboarding-and-paywalls-in-your-mobile-app/) [V]) | ответ меняет рекомендуемый тир, примеры в баре, иллюстрации и первый AI-сценарий (язык → озвучка/STT; экзамен → фактчек/упражнения; собеседование → разбор ответов) |
| Trial без карты | С картой конверсия ≈49%, без карты ≈18% (B2B SaaS, [S](https://www.shno.co/marketing-statistics/free-trial-conversion-statistics)). Trial 17–32 дня конвертирует лучше коротких (42,5% vs 25,5%) ([RevenueCat 2026](https://www.revenuecat.com/blog/growth/subscription-app-trends-benchmarks-2026) [V]). В Госдуме предлагают запретить обязательную карту для trial (письмо, не законопроект [S]) | 14 дней без карты, старт по первому AI-intent, без автоконверсии (как в августовском плане) |
| Годовой тариф | Education: доля annual 59–66%, медиана $44.99/год, первое продление annual 24% [V/S] ([RevenueCat Education](https://www.revenuecat.com/state-of-subscription-apps-2026-education)); у Adapty другой mix ([Adapty](https://adapty.io/blog/education-app-subscription-benchmarks/) [V]) | вводить после двух когорт; −25% (449 → 3 990 ₽/год); AI-бюджет — помесячно; возврат по ЗоЗПП ст. 32 — пропорционально, с учётом фактических расходов [S] |
| Лимит колод как платный рычаг | Бэклэш Evernote (лимит 2 устройства, 2016) и Quizlet (Learn/Test за paywall, 2022); Notion снял лимит блоков для личного использования (2020) [S] | слабый рычаг: внутри колоды безлимит → пользователь сольёт всё в одну колоду. Если вводить — **≥10 своих колод на Free, копии публичных колод не считать** (иначе налог на teacher→learner growth loop), существующие никогда не блокировать (уже в owner decisions) |

**Правовые флаги** (только «проверить с юристом»):

- отказ от сохранённых платёжных данных и запрет списания после отказа — ЗоЗПП ст. 16.1 п. 4.2, с 2026-03-01 ([Роспотребнадзор](https://zpp.rospotrebnadzor.ru/news/federal/571896) [V]);
- **обязательного уведомления перед списанием в итоговом законе нет**, но как продуктовый guardrail его стоит оставить [S];
- цена — в рублях, полная сумма к оплате (ЗоЗПП ст. 10) [S];
- запрет pre-ticked согласий на дополнительные платные услуги — ст. 16 в ред. 69-ФЗ [S];
- сравнения с такси и репетитором — ФЗ «О рекламе» ст. 5 [S].

## 9. Доступ, оплата из РФ и 152-ФЗ (всё — «проверить с юристом»)

### 9.1 Провайдеры: страна, terms, оплата

| Провайдер | Россия разрешена? | Ключевое условие | Оплата | Источник |
|---|---|---|---|---|
| OpenAI | **нет** | «Accessing **or offering access**… outside of the countries… may result in your account being blocked» — важно, где конечные пользователи, а не только сервер | — | [supported countries](https://developers.openai.com/api/docs/supported-countries) [V] |
| Anthropic | **нет** | AUP запрещает давать доступ пользователям из unsupported regions; может отказать компаниям с владельцами из неподдерживаемых стран | — | [supported countries](https://www.anthropic.com/supported-countries), [AUP](https://www.anthropic.com/legal/aup) [V] |
| Gemini API | **нет** | «only access the Services (**or make API Clients available to users**) within an available region»; 18+ | — | [regions](https://ai.google.dev/gemini-api/docs/available-regions), [terms](https://ai.google.dev/gemini-api/terms) [V] |
| ElevenLabs | **нет, Россия названа явно** | гарантия, что вы «not located in» санкционной стране, включая Russia | карта | [ToS §15](https://elevenlabs.io/terms-of-use) [V] |
| Deepgram | формулировка OFAC «any country… for which the US maintains sanctions», распространяется на ваших пользователей | вероятно, исключает (вывод) | — | [terms §14](https://deepgram.com/terms) [V] |
| OpenRouter | своего списка стран нет | §5.7: нельзя обходить ограничения провайдеров моделей, в том числе через VPN и прокси; §7: запрет перепродажи доступа. OpenAI/Anthropic/Google через OpenRouter наследуют запрет; open-weight модели (DeepSeek, Qwen, GLM) — по terms конкретной модели | карты, AliPay, USDC | [terms](https://openrouter.ai/terms), [FAQ](https://openrouter.ai/docs/faq) [V] |
| **DeepSeek** | списка стран нет; «no warranty… available in certain jurisdictions»; разработчик сам отвечает за санкции и export control своих end users; право КНР | Privacy Policy не покрывает конечных пользователей вашего приложения: disclosure и основания — на операторе Mnema | баланс; PayPal/карта/Alipay/WeChat — **только по вторичным источникам**, противоречат друг другу | [ToS](https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html), [privacy](https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html) [V] |
| GigaChat / cloud.ru | да | рубли, договор с РФ-лицом | рубли | [V] |
| Карточные сети | российские Visa/MC за рубежом не работают с 2022-03 | — | — | [Visa](https://usa.visa.com/about-visa/newsroom/press-releases.releaseId.18871.html) [V] |

Посредники и «виртуальные карты» не официальны. Их обещания противоречат друг другу, а terms провайдеров прямо запрещают обход через прокси и перепродажу. Последствие — блокировка аккаунта и потеря баланса. В отчёте это только риск, не рекомендация.

### 9.2 Хостинг в Финляндии: два блокера

1. **Hetzner прекратил договоры с клиентами с российскими почтовыми адресами.** Письмо от 2023-12-08, расторжение с 2024-01-31. По данным TASS (2024-04-17), Hetzner сообщил Роскомнадзору о прекращении обслуживания российских пользователей. Новые регистрации проверяются вручную [S] ([Habr](https://habr.com/en/news/779352), [TASS](https://tass.com/society/1776723)). Для российского ИП Hetzner, вероятно, недоступен. Брать «финский хостинг» через третье лицо — вопрос юриста и terms, не архитектуры.
2. **152-ФЗ ст. 18 ч. 5 в редакции 23-ФЗ (с 2025-07-01)** прямо **запрещает** запись, систематизацию, накопление и хранение ПД граждан РФ в базах за пределами РФ. Первая запись должна быть в РФ, зеркалирование позже не помогает ([comply.ru](https://comply.ru/tpost/c43ezsout1-lokalizatsiya-i-transgranichnaya-peredac) [V], текст закона — через сниппет [S]). Штрафы по КоАП 13.11: ч. 8 — 1–6 млн ₽, ч. 9 (повтор) — 6–18 млн ₽. ИП по примечанию отвечают как юрлица (пересказ [S]); ч. 10 (неуведомление РКН) — 100–300 тыс. ₽ ([klerk.ru](https://www.klerk.ru/cdoc/view/kodeks-ob-administrativnyh-pravonaruseniah-koap-rf/stata-1311-narusenie-zakonodatelstva-rossijskoj-federacii-v-oblasti-personalnyh-dannyh/) [V]).

**Вывод.** Production-MVP, где primary БД с аккаунтами и учебной историей российских пользователей лежит в Финляндии, нарушает локализацию с первого дня. Финский сервер также не делает западные API разрешёнными (см. 9.1). Рекомендуемые провайдеры (DeepSeek, GigaChat, self-host) доступны из РФ напрямую, поэтому зарубежный хостинг почти ничего не даёт.

Российский хостинг недорог: Timeweb Cloud — 900 ₽/мес (2 vCPU, 2 GB), 2 790 ₽/мес (8 vCPU, 12 GB); заявлено соответствие 152-ФЗ ([Timeweb](https://timeweb.cloud/services/cloud-servers) [V]). Selectel, VK Cloud и цены на российские GPU получить не удалось.

### 9.3 Трансграничная передача в AI-провайдеров

- **Режим:** ст. 12 (266-ФЗ, с 2023-03-01). Уведомление РКН — **до** начала передачи. Для стран без «адекватной» защиты сначала нужно получить сведения о мерах получателя; у РКН 10 рабочих дней на запрет [S].
- **Список адекватных стран.** В Приказе РКН № 128 есть Финляндия (участник Конвенции 108) и **Китай** (раздел 2); **США нет** ([rppa.pro](https://rppa.pro/npa/rkn128_05.08.2022) [V]). Новый **265-ФЗ от 26.07.2026** убрал автоматическую «адекватность» участников Конвенции, проект нового списка от 31.07.2026 [S] ([meganorm](https://meganorm.ru/mega_doc/FZ_upd_01082026/federalnyj-zakon/0/federalnyy_zakon_ot_26_07_2026_N_265-fz_o_vnesenii_izmeneniy.html) [V]). Сейчас передача в DeepSeek — это передача в «адекватную» страну, нужно только уведомление. Передача в US-хосты через OpenRouter (DeepInfra, Fireworks) — в «неадекватную»: выше порог и риск запрета.
- **Pseudonymized ID с ключом в РФ остаётся ПД** [L]. Значит, уведомление, скорее всего, нужно даже при полной redaction, если запрос связан с аккаунтом.

**Архитектурные следствия** (независимо от ответов юриста):

1. Identity, аккаунты, платежи, учебная история и медиа — **в РФ**. Первая запись — в российской БД.
2. AI-шлюз — отдельный stateless-компонент. Он получает только **учебный контент без идентификаторов**: удаляет email, имена, телефоны и ссылки на профиль из пользовательского текста (regex + NER), подставляет `user_id` = ротируемый HMAC от аккаунта и **не хранит** промпты дольше job-а.
3. **Голос пользователя для STT — это ПД.** Биометрией по ст. 11 он не считается, пока не используется для идентификации, но он чувствительнее текста. Предпочтительно распознавать **self-host в РФ**; внешний STT — только после явного указания в UI и юридической проверки.
4. **Per-provider kill-switch и routing policy в конфигурации**: при изменении terms или списка стран провайдер отключается без релиза.
5. **Disclosure** в privacy policy и в preflight: страна обработки и получатель (DeepSeek — КНР). Отдельное согласие — если юрист скажет, что основание «договор» не покрывает передачу.

## 10. Рекомендация

### 10.1 Матрица провайдеров

| Capability | Primary | Fallback | Stub / деградация | Почему |
|---|---|---|---|---|
| Генерация материалов, упражнений, правок | **DeepSeek V4.1 Flash** напрямую, non-thinking, `json_object` + наша schema-валидация | **GigaChat** (Lite — простое, 3 Pro через cloud.ru — качество) | ручное создание (ядро без AI); задача в очередь | самая низкая цена, доступен из РФ, Китай в списке «адекватных» стран; GigaChat — RU-резидент с рублёвым договором |
| Эскалация: сложный STEM, «умный план», спорная проверка | DeepSeek V4 Pro (thinking) | GigaChat Max / 3.5 Ultra | Flash + thinking | Pro в 3,3 раза дороже Flash — только по триггеру валидатора |
| AI-проверка ответа (`SemanticAssessmentProvider`) | DeepSeek Flash, rubric в кэшируемом префиксе | GigaChat Lite | `UNAVAILABLE` → self-check (уже в контракте #266) | ≈0,02–0,09 ₽ за ответ; учёба не должна ломаться |
| STT (`SpeechToTextProvider`) | **self-host faster-whisper (large-v3-turbo int8) в РФ**: на старте на уже оплаченном сервере приложения с очередью; затем отдельный CPU-узел (≈2,8 тыс. ₽/мес за 8 vCPU), затем GPU. Бенчмарк латентности обязателен | Groq turbo (самый дешёвый API) или Voxtral (лучше на KO/JA) — **только после legal OK** | ручной ввод текстом; очередь при перегрузке | голос — ПД; US/EU-API несут санкционные оговорки; фиксированная стоимость идеальна для «бесплатно с fair-use» |
| TTS | **API с кэшем**: Fish Audio (дешевле) или MiniMax turbo (качество, #20 на арене) — после проверки контрагента | self-host Piper (RU/EN/FR/ES/ZH, CPU) для слов; Qwen3-TTS на GPU позже | аудио, загруженное автором (уже поддерживается) | Google, ElevenLabs, Azure, OpenAI, Polly недоступны российскому ИП; кэш снижает стоимость в разы |
| Изображения | **поиск по стокам**: Pexels + Pixabay + Openverse/Wikimedia с сохранением файла и атрибуции | генерация позже: Recraft V4.1 Flash / FLUX.2 klein / Qwen-Image / Kandinsky (оценить) | загрузка своей картинки | почти 0 ₽, совместимо с offline, лицензионно чисто |
| Видео | **нет** | — | YouTube-embed (уже есть узел) | 13–200 ₽ за 5 с, вендоры закрываются |
| Веб-поиск (фактчек) | Yandex Search API, **если исключение Yandex не распространяется на поиск** (0,49 ₽ с НДС за запрос, лучшее RU-покрытие) | Perplexity Search API или Exa — после legal OK | фактчек выключен, ссылки на источники вручную | DeepSeek не умеет искать; Gemini grounding запрещает хранить результаты |

**Обязательные инженерные условия для любой строки:**

- provider port + adapter;
- model id и цены — в конфигурации;
- per-provider kill-switch и дневной лимит расходов;
- golden eval: ≥300 fixtures на RU/EN/FR/ES/JA/ZH/KO + STEM/code, метрика — **cost per accepted item**;
- p95 latency из региона production.

### 10.2 Тарифная сетка

| Тир | Цена | Позиционирование | Когда запускать |
|---|---:|---|---|
| Free (выбран по умолчанию) | 0 ₽ | всё ручное + небольшой AI-бюджет + голосовой ввод | сразу |
| Trial | 14 дней, 1 раз, без карты | старт по первому AI-intent, без автосписания | вместе с Plus |
| **Plus** | **449 ₽/мес** (≈15 ₽/день) | «AI-помощник для регулярной учёбы» | первый платный |
| **Pro** | **990 ₽/мес** | рекомендуемый для экзамена/собеседования: ×2,3 бюджета, фактчек high, качественные картинки | вместе с Plus |
| **Max** | **1 900–1 990 ₽/мес** | медиа: подкасты, ×4,9 бюджета | **только когда TTS и изображения готовы**; до этого Max — пустой якорь, а decoy-эффект слаб |
| Годовые | −25% (3 990 / 8 900 / 17 900 ₽) | monthly-equivalent на paywall | после двух когорт и замера churn |

Allowances — таблица §6.2. Платёжный метод по умолчанию — **СБП-подписка** (дешевле карты на ≈2,4 п.п.), карта — альтернатива. Нужен письменный тариф T‑Bank на `ChargeQr` для интернет-эквайринга.

### 10.3 Hard caps (не обсуждаются)

1. `max_tokens` и thinking budget в каждом запросе; лимит входного контекста на операцию.
2. Месячный бар + дневной burst ≤35%. Нет отрицательного баланса: preflight-hold и settle.
3. Штучные caps на подкасты, качественные изображения, high-фактчек, видео. На Free генерации медиа нет.
4. STT: клип ≤60 с (заметка ≤5 мин), минуты в день и в месяц, VAD до распознавания. AI-проверка: N в день.
5. Дневной лимит расходов на провайдера и на Free-когорту + circuit breaker с понятным сообщением.
6. ≤2 параллельных AI-job на пользователя; ограниченная очередь.
7. Trial и Free-квота — одна на verified identity; velocity-лимиты по устройству и IP.
8. Kill-switch на capability и провайдера без релиза.

### 10.4 Формула preflight — §5.4

Кратко: `credits = ceil(((Σ input × p_in_miss_peak + Σ max_out × p_out_peak) × repair + media + search) × fx_stress / 0,10 ₽)`. Hold → run → settle по факту; при неуспехе hold возвращается полностью.

## 11. Открытые вопросы владельцу

| # | Вопрос | Рекомендация |
|---|---|---|
| 1 | Где primary-хостинг, если Hetzner расторгает договоры с клиентами с российскими адресами, а 152-ФЗ ст. 18(5) с 2025-07-01 запрещает первичную запись ПД в зарубежных БД? | Identity, данные, медиа и платежи — в РФ (Timeweb/Selectel/VK Cloud, от ≈1–3 тыс. ₽/мес). Зарубежный узел — только stateless и только после ответа юриста. Для DeepSeek и GigaChat он не нужен |
| 2 | Подтверждена ли фактическая оплата DeepSeek из РФ (каким способом)? | пополнить на $10–20, держать минимальный баланс; параллельно открыть GigaChat-аккаунт (минимум 600 ₽ только в месяц использования) как fallback |
| 3 | Распространяется ли исключение Yandex на Search API (не генеративный поиск)? | разрешить Search API только для фактчека: лучшее RU-покрытие и рублёвый договор; SpeechKit/YandexGPT оставить исключёнными |
| 4 | Один бар или два? | вариант C: один бар «AI-бюджет» на создание + fair-use на голос и проверку + штучные caps на дорогие медиа |
| 5 | STT бесплатно для всех? | да, как fair-use 30 мин/мес на Free на self-host в РФ (фиксированная стоимость, очередь при перегрузке); «безлимит» — нет |
| 6 | Сколько платных тиров на старте и цена Max? | Plus 449 + Pro 990 сразу; Max (1 900, тестировать 1 990) — когда готовы TTS и изображения |
| 7 | Free выбран по умолчанию? | оставить Free выбранным (честно и без навязывания), но визуально выделить персонально рекомендованный тир с конкретикой; A/B после ≥1–2 тыс. показов paywall |
| 8 | Лимит колод как платный рычаг? | не на старте AI-монетизации. Если нужен — только свои колоды, Free ≥10, копии публичных не считаются, существующие не блокируются |
| 9 | Генерация изображений и видео на старте? | видео — нет; изображения — поиск по лицензионным стокам; генерация — в Pro/Max после выбора легально доступного провайдера |
| 10 | Как читать guardrail 20–25%? | включать НПД и эквайринг (строгое прочтение); первые две когорты — hard guarantee (сумма caps ≤25%), затем measured p95 с увеличением бара ≈×1,35–1,5 |

## 12. Что не подтверждено и что проверить перед запуском

- **Цены [S] без официального подтверждения:**
  - таблица Google Cloud TTS (WaveNet $4 или $16) и бесплатные лимиты;
  - цена Hetzner GEX45;
  - тарифы Ideogram, Stability, Kling, Serper;
  - GigaChat 3.x на cloud.ru;
  - СБП-ставка для интернет-эквайринга T‑Bank;
  - способы оплаты DeepSeek.
- **Не исследовано:**
  - Alibaba Model Studio (Qwen3-ASR, Qwen-TTS, Wan): страница цен не содержит медиа-моделей;
  - Kandinsky API;
  - российские GPU-облака;
  - доступность Fish Audio и MiniMax для российского ИП;
  - реестр хостинг-провайдеров (149-ФЗ ст. 10.2-1) [L];
  - российские правила использования зарубежных LLM в edtech.
- **Противоречия:**
  - DeepSeek V4 Pro: перенаправление на Flash объявлено с 09-14, потом отменено changelog'ом;
  - Gemini audio tokens: 25/с официально vs ≈32/с по замерам;
  - KoAP: ч. 8–9 введены 405-ФЗ (2019), 420-ФЗ добавил ч. 11–15.
- **Нужно измерить, а не искать:**
  - реальный p95 токенов на операцию;
  - доля cache-hit и off-peak;
  - repair rate;
  - латентность faster-whisper на CPU для 5–15-секундных клипов;
  - TTS cache-hit rate на публичных колодах;
  - фактическое использование Free STT.
- **Методическая оговорка:** большинство страниц прочитаны через summarizing fetch. Перед решением о деньгах ключевые цифры (DeepSeek, GigaChat, T‑Bank, Fish/MiniMax) стоит открыть вручную.
