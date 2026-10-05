---
artifact:
  id: frontend-styleguide
  type: production-frontend-guide
  status: current
  created_at: "2026-10-05"
  scope: "Angular web UI; living styleguide /styleguide and rules for shared elements"
---

# Стайлгайд frontend

Живой каталог дизайн-системы Mnema — маршрут `/styleguide`. Каждый пример на нём — настоящий компонент
или класс приложения, а не копия. Визуальное направление и токены описывают
[design and experience](./design-and-experience-2026-09.md) и
[brand and UI contract](./mnema-brand-and-ui-contract.md); этот документ — про то, как ими пользоваться.
Если документ и страница расходятся, правда в коде: `frontend/src/theme/tokens.css`,
`frontend/src/theme/components.css`, `frontend/src/app/shared/`.

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

```bash
cd frontend
npx ng serve            # http://localhost:4200/styleguide
```

Страница не требует входа и backend; данные — локальные фикстуры. `npm start` в проекте нет (`package.json` не менялся),
цель `serve` описана в `frontend/angular.json` и по умолчанию собирает `development`.

Локальный полный стек и production отдают production-сборку (`npm run build`), поэтому `/styleguide` там **нет** — это намеренно.
Если владельцу нужна страница и на стеке с реальным backend, правильный путь — отдельный флаг dev-сборки образа frontend
(например, build-arg конфигурации Angular `development` в `deploy/local-full-stack/frontend.Dockerfile`), а не включение маршрута в production.

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
| Обратная связь | `.notice`, тосты `ToastService`, `.empty-state` | `sg-surfaces` |
| Карточки и области | таблица сравнения `.data-table`, панель главного действия `.cta-bar`, `.paper-surface` | `sg-surfaces`, `components.css` |
| Пример экрана | Страница колоды из элементов каталога | `sg-screen` |

Код страницы — `frontend/src/app/styleguide/`; её собственная раскладка в классах `sg-*`, в примерах они не используются.

## Общие классы (`src/theme/components.css`)

`.button` (`.primary`, `.quiet`, `.small`, `.mono`), `.notice` (`.success`, `.warning`, `.error`), `.hint`, `.field`, `.field-error`,
`.stamp` (`.solid`), `.eyebrow`, `.empty-state`, `.paper-surface` (`.ruled`), `.data-table`, `.check-field` + `.check-row`,
`.settings-row` (`.is-switch`), `.cta-bar` (`.cta-bar--inline`), `.field-row`. Классы глобальны: инкапсуляция Angular не мешает написать
`class="button primary"` в любом шаблоне. Заливка волной и фокус заданы в `global_styles.css`; `.generate-cta` — там же.

Осторожно с локальными селекторами элементов (`p { font: … }`): в компоненте со scoped-стилями они по специфичности сильнее глобального
класса. Исключайте общий класс (`p:not(.eyebrow)`) или не пишите правило на элемент.
