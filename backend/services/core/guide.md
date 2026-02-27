# Core Service Guide

## Кратко
`services/core` — основной доменный сервис колод/карточек/повторов.  
В контексте AI-доработок здесь находится API поиска и резолва дубликатов.

## Endpoint'ы по дубликатам
В `UserCardController`:
- `POST /decks/{userDeckId}/cards/duplicates`
  - body: `fields`, `limitGroups`, `perGroupLimit`, `includeSemantic`, `semanticThreshold`.
- `POST /decks/{userDeckId}/cards/duplicates/resolve`
  - body: `fields`, `scope`, `operationId`.

## Поиск дубликатов: exact + semantic
`CardService.getDuplicateGroups(...)` работает в два этапа:

1. **Exact phase (SQL)**  
   Группы точных совпадений по нормализованным выбранным полям.

2. **Semantic phase (optional)**  
   Включается только если `includeSemantic=true`.
   - Берется набор кандидатов через репозиторий.
   - Для каждой карты строится fingerprint:
     - `primary` (первое значимое поле),
     - runtime-вектор (hashing char-trigram, cosine).
   - Граф похожести собирается через `union-find`:
     - если `primary` совпадает — карты объединяются;
     - иначе по `cosine >= semanticThreshold`.
   - Возвращаются группы с:
     - `matchType = semantic|exact`,
     - `confidence` (для semantic — максимум similarity внутри группы).

Порог `semanticThreshold` ограничивается в диапазоне `0.70..0.99`.

## Почему в UI иногда одинаковый confidence (например 92%)
Если карточки попали в группу в основном из-за порога, а не из-за высокой сверхпохожести, confidence часто близок к порогу запроса (например `0.92`).  
Это нормально для текущей модели: threshold задает нижнюю границу похожести.

## Контракт ответа
`DuplicateGroupDTO`:
- `matchType`
- `confidence`
- `size`
- `cards[]`

Это позволяет UI явно показывать: "точный дубликат" или "семантически похожие, проверь вручную".

## Resolve flow
`resolveDuplicateGroups(...)`:
- считает кандидатов и оставляет лучшую карточку на группу;
- остальные помечает удаленными локально;
- при `scope=global` дополнительно применяет глобальную логику версии публичной колоды;
- `operationId` используется для консистентного батчинга глобальных изменений.
