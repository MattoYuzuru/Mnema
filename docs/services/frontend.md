# Frontend (`frontend`)

## Назначение

Angular SPA, которая связывает все backend-сервисы в единый пользовательский продукт: обучение, редактирование, импорт, AI, профиль.

## Что видит пользователь

- страницы: home/login/profile/my-study/decks/public-decks/templates/settings;
- мастер создания колоды и visual template builder;
- review session (изучение карточек);
- модалки импорта (классический import и AI import);
- AI-модалки для добавления/улучшения карточек;
- работа с медиа-вложениями;
- i18n (ru/en).

## Технологии

- Angular 18, standalone components;
- Router + auth guard;
- HttpClient + interceptor для Bearer токенов;
- RxJS, сервисный слой API-клиентов;
- Web Crypto (PKCE code challenge в auth flow).

## Технические решения

- Централизованный `auth.interceptor`:
  - добавляет access token к запросам в `core/user/media/import/ai`;
  - корректно обрабатывает невалидную сессию.
- `AuthService` поддерживает два сценария:
  - OAuth2 Authorization Code + PKCE;
  - локальный login/register через auth API.
- API-клиенты разделены по доменам (`deck-api`, `review-api`, `media-api`, `import-api`, `ai-api`), что упрощает изоляцию изменений.
- Маршруты защищены `authGuard` там, где нужны приватные данные.

## Связь с backend

- `auth` — login/register/OAuth2/token handling;
- `user` — профиль и пользовательские настройки;
- `core` — колоды/карточки/шаблоны/review/search;
- `media` — upload/resolve медиа;
- `import` — импорт/экспорт колод;
- `ai` — AI jobs и AI import.

