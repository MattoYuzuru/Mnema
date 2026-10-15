---
artifact:
  id: adr-onb-001
  type: adr
  title: "ADR-ONB-001: self-built tour engine on native platform primitives"
  status: accepted
  created_at: "2026-10-14"
  owners: ["project-owner"]
---

# ADR-ONB-001 — Свой движок тура на нативных примитивах

## Контекст

Нужны spotlight-шаги с вырезом, карточка шага, action-gated шаги, пауза, подсказки без вуали и
побочные квесты — всё в бумажно-чернильном стиле стайлгайда и с WCAG 2.2 AA. Проект — Angular 22.2.1
без `@angular/cdk` и без дизайн-библиотек; root AGENTS требует обоснования и разрешения для новых
зависимостей. Сравнение — [research §8](../research.md#8-реализация-в-angular).

## Варианты

| Вариант | Плюсы | Минусы |
|---|---|---|
| **A. Свой движок на `<dialog>`/Popover API/`inert`/`ResizeObserver` + SVG-маска** | Ноль зависимостей; как уже сделаны `app-action-menu` и окно жалобы; полный контроль a11y и стиля | Своя логика позиционирования и следования за целью (~300–500 строк + тесты) |
| B. Добавить `@angular/cdk` (Overlay, A11y) | First-party, MIT, готовые FocusTrap/LiveAnnouncer/позиционирование | Новая зависимость ради одной функции; нужно разрешение владельца |
| C. driver.js (MIT) | Маленький, готовый вырез | Нет focus trap и объявлений; чужой DOM и стиль; обёртка съедает выгоду |
| D. Shepherd.js / intro.js | Зрелые | AGPL-3.0 или платная лицензия — исключены |
| E. ngx-ui-tour + core | Angular-native, MIT | Своя UI всё равно нужна; ещё одна зависимость |

## Решение (принято владельцем 2026-10-14)

A. `core/onboarding/TourEngine` (signals), директива `mnTourAnchor`, `app-tour-card` на элементе
`<dialog>` (`show()` для шагов с действием на цели, `showModal()` для информационных шагов), вуаль —
SVG `mask` на весь viewport с вырезом вокруг `getBoundingClientRect()` цели, следование —
`ResizeObserver` + scroll в `requestAnimationFrame`; позиционирование карточки в JS с fallback-сторонами,
CSS anchor positioning — только как прогрессивное улучшение после проверки MDN Baseline. Объявления —
существующая live-region тостов или своя `aria-live="polite"` region. Если при реализации окажется,
что позиционирование и фокус-менеджмент выходят за ~500 строк или нестабильны — вернуться к B с
запросом разрешения на `@angular/cdk`.

## Последствия

- Движок покрывается unit-тестами геометрии и a11y-тестами компонентов; стайлгайд показывает все режимы.
- Action-gated шаг: страница `inert`, кроме цели и карточки — проверить, что `inert` не ломает
  outside-click логику существующих меню (`app-hold-to-delete-button` слушает `document:pointerdown`).
- Шаги туров ссылаются на стабильные ключи anchors, не на CSS-селекторы; тест сверяет реестр.

## Проверка

Клавиатура, VoiceOver/NVDA smoke, 2.4.11 (карточка не закрывает цель), reduced motion, forced-colors,
320 px, следование при resize/scroll, отсутствие anchor.
