---
artifact:
  id: frontend-styleguide
  type: production-frontend-guide
  status: current
  created_at: "2026-10-05"
  updated_at: "2026-10-07"
  scope: "Angular web UI; living styleguide /styleguide and rules for shared elements"
---

# Стайлгайд frontend

Живой каталог дизайн-системы Mnema — маршрут `/styleguide`. Каждый пример на нём — настоящий компонент
или класс приложения, а не копия. Визуальное направление и токены описывают
[design and experience](./design-and-experience-2026-09.md) и
[brand and UI contract](./mnema-brand-and-ui-contract.md); этот документ — про то, как ими пользоваться.
Исполняемые примеры находятся в `frontend/src/theme/tokens.css`,
`frontend/src/theme/components.css`, `frontend/src/app/shared/`. При расхождении с
принятым design-контрактом проверь причину и исправь канонический источник: код
сам по себе не отменяет принятую визуальную границу.

## Правила

1. **Бери из стайлгайда.** Для кнопки, сообщения, подсказки, поля, метки, пустого состояния, листа нужен класс или компонент из каталога.
   Не рисуй свой вариант в стилях страницы и не копируй чужой: страница добавляет только раскладку
   (`.actions .button { flex: 1 1 12rem }`), а не второй вид элемента.
2. **Новый элемент — сначала в стайлгайд.** Нужно то, чего в каталоге нет: добавь пример и подпись «когда использовать»
   на страницу `/styleguide`, затем используй на экране. Если элемент общий, он живёт в `src/theme/components.css`
   (простые классы) или `src/app/shared/` (компоненты с поведением), а не в файле одной страницы.
3. **Только токены.** Цвета, отступы и радиусы — `--mn-*`. Hex, `px`-радиусы и теневые наборы в стилях компонентов не добавляются.
4. **Один главный приём на экран.** `.generate-cta` не больше одного, необратимое удаление — только `app-hold-to-delete-button`.
5. **Доступность — часть примера.** Область нажатия не меньше `--mn-touch-min`, фокус виден, статус назван словами,
   `forced-colors` и reduced motion не ломают смысл. Пример, не проходящий это, не попадает в каталог.

Тёмной темы и режимов плотности в продукте нет: страница не имеет их переключателей и честно об этом говорит.
Для движения есть переключатель «Спокойное движение»: он показывает вид страницы при reduced motion независимо от системной настройки.

## Как открыть

В полном локальном стеке включите development-сборку frontend, сохраняя текущие
аккаунты, материалы, сертификаты и порты:

```bash
MNEMA_LOCAL_FRONTEND_CONFIGURATION=development ./scripts/mnema-local-full-stack.sh start
```

Откройте [Style Guide в локальном стеке](https://localhost:3443/styleguide) (если при
bootstrap выбран другой `MNEMA_LOCAL_WEB_PORT`, используйте этот сохранённый порт).
Это тот же runtime, а не второй набор данных. На следующих `start` повторяйте флаг,
пока нужен каталог. Для возврата к production-сборке:

```bash
./scripts/mnema-local-full-stack.sh start
```

Только каталог без Docker/backend можно открыть отдельным dev-сервером:

```bash
(cd frontend && npx ng serve --host 127.0.0.1 --port 4200)
```

[Style Guide без backend](http://localhost:4200/styleguide) использует локальные
фикстуры и не требует входа. `npm start` в проекте нет; цель `serve` в
`frontend/angular.json` по умолчанию собирает `development`. Остановите dev-сервер
через Ctrl+C.

Production и обычный локальный `start` отдают production-сборку (`npm run build`),
поэтому `/styleguide` там **нет**. Флаг работает только с
`deploy/local-full-stack/frontend.Dockerfile` и допускает `development` или
`production`; release Dockerfile его не использует. Это следует Angular
[build configurations](https://angular.dev/tools/cli/environments) и Docker
[build arguments](https://docs.docker.com/build/building/variables/).
Подробнее о данных и портах: [локальный runtime](../deploy/selfhost-local.md).

## Почему страницы нет в production

`app.routes.ts` регистрирует маршрут только при `typeof ngDevMode === 'undefined' || ngDevMode`. Сборщик приложения Angular
в конфигурации `production` (`optimization.scripts`) определяет `ngDevMode` как `false`, esbuild убирает условие вместе с
динамическим `import()`, и ни маршрут, ни чанк страницы в бандл не попадают. `isDevMode()` — вызов времени выполнения и код бы оставил.

Проверка выходного каталога после `npm run build`:

```bash
cd frontend
node scripts/verify-no-styleguide.mjs              # dist/mnema-frontend; exit 0 — страницы нет
npx ng build --configuration development && node scripts/verify-no-styleguide.mjs   # обязан упасть: проверка честная
```

Скрипт ищет в файлах сборки имя `styleguide`, компонент и русские тексты страницы. Юнит-тест `app.routes.spec.ts`
фиксирует, что в development-сборке маршрут есть, ленивый и без `canActivate`.

## Карта разделов

| Раздел | Что внутри | Где код |
|---|---|---|
| Принципы | Короткие правила бумаги и чернил | `sg-foundations` |
| Палитра, Семантические цвета | Токены `--mn-*` с живыми значениями; контраст пар считается из вычисленных цветов | `sg-foundations`, `color-contrast.ts` |
| Типографика | Display, чтение, рубрика `.eyebrow`, `.hint` | `sg-foundations` |
| Сетка и отступы, Радиусы и поверхности | Шкала `--mn-space-*`, размеры, лист и правило | `sg-foundations` |
| Движение, Иконки | Волна кнопок, чернильное появление, набор знаков | `sg-foundations` |
| Фирменные приёмы | `.generate-cta`, удаление удержанием | `sg-controls` |
| Кнопки | `.button` (primary, quiet, small, mono), недоступные | `sg-controls`, `components.css` |
| Поля ввода, Выбор | `.field`, `.hint`, `.field-error`, `app-mnema-select`, радио/флажки, флажок с пояснением `.check-field`, недоступная настройка `.settings-row`, `app-segmented-choice` (в том числе вопрос о цели), карточка тарифа `app-plan-option`, поле с кнопкой `.field-row` и поле промокода `app-promo-redeem`, `app-choice-list` | `sg-controls`, `components.css` |
| Меню и окна, Вкладки и пейджер | `app-toggletip`, `app-ai-prompt-window`, промо-окно `app-promo-popup`, `app-batch-pager` | `sg-surfaces` |
| Статусы и ход | `.stamp`, `app-new-badge`, `app-usage-meter`, плейсхолдер медиа | `sg-surfaces` |
| Обратная связь | `.notice`, тосты `ToastService`, `.empty-state`, контакт поддержки `app-support-contact` с подписанным знаком Telegram | `sg-surfaces`, `shared/` |
| Публичный футер | `app-public-footer`: реквизиты проекта, разделы, правовые документы и контакт; четыре, две или одна колонка | `sg-surfaces`, `shared/` |
| Карточки и области | таблица сравнения `.data-table`, панель главного действия `.cta-bar`, `.paper-surface` | `sg-surfaces`, `components.css` |
| Пример экрана | Страница колоды из элементов каталога | `sg-screen` |

Код страницы — `frontend/src/app/styleguide/`; её собственная раскладка в классах `sg-*`, в примерах они не используются.

Контакт поддержки берётся из `appConfig.supportTelegramUsername`; текущий публичный
бот — `Mnema_Support_Bot`. Контейнер может переопределить имя через
`MNEMA_SUPPORT_TELEGRAM_USERNAME` без изменения сборки. Допускается только имя бота
в формате BotFather (5–32 латинских символа, цифры или `_`, окончание `bot`);
ссылка строится на `https://t.me/`. Для отсутствующего или некорректного контакта
компонент показывает пояснение без ссылки. SVG-знак оригинальный, `aria-hidden`,
смысл и указание новой вкладки передаёт текст ссылки. Реквизиты футера предоставлены
владельцем; изменения этих сведений требуют проверенных данных, а не копирования референса.

Поле промокода показывает ответ сервера: доступ до даты без автопродления или скидку на будущую оплату.
При неизвестном результате оно повторяет ту же команду и не обещает, что тариф не изменился.
Промо-окно появляется только в разрешённой паузе, закрывается по Esc и явной кнопке; частоту ограничивает
решение текущего аккаунта в памяти и sessionStorage, а cooldown и окончательный отказ проверяет сервер.
Запись закрытия или отказа подтверждает только `Promo-Event-Recorded: true`, а не сам статус 204.
Пока подтверждения нет, браузер хранит только id кампании и выбор аккаунта,
не показывает новое предложение и повторяет запись в следующей разрешённой паузе. Явный отказ сохраняется
в этом браузере и после подтверждения; недоступное хранилище ограничивает эту защиту текущей вкладкой.

## Общие классы (`src/theme/components.css`)

`.button` (`.primary`, `.quiet`, `.small`, `.mono`), `.notice` (`.success`, `.warning`, `.error`), `.hint`, `.field`, `.field-error`,
`.stamp` (`.solid`), `.eyebrow`, `.empty-state`, `.paper-surface` (`.ruled`), `.data-table`, `.check-field` + `.check-row`,
`.settings-row` (`.is-switch`), `.cta-bar` (`.cta-bar--inline`), `.field-row`. Классы глобальны: инкапсуляция Angular не мешает написать
`class="button primary"` в любом шаблоне. Заливка волной и фокус заданы в `global_styles.css`; `.generate-cta` — там же.

Осторожно с локальными селекторами элементов (`p { font: … }`): в компоненте со scoped-стилями они по специфичности сильнее глобального
класса. Исключайте общий класс (`p:not(.eyebrow)`) или не пишите правило на элемент.
