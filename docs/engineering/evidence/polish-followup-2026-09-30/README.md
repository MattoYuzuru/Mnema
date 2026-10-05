---
artifact:
  id: polish-followup-2026-09-30
  type: implementation-evidence
  status: current
  created_at: "2026-09-30"
---

# Декорации, удаление и аудиопары

Продолжение [правок бумажного интерфейса](../paper-polish-2026-09-30/README.md).
Проверено на локальном HTTPS стеке с синтетическим демо-аккаунтом, в отдельном
worktree. Незакоммиченные изменения владельца в исходном checkout сохранены.
Доставка ограничена [локальной разработкой](https://github.com/MattoYuzuru/Mnema/blob/4f3fb44d00e8e004a35a02d38cc026c8bbba7058/docs/operations/local-development-delivery.md).

## Наблюдаемый результат

- Звёзды занимают всю высоту основного блока, имеют случайные координаты по обеим
  осям, устойчивые для UID. Отбрасывание близких точек исключает пересечения;
  реальные размеры контента и main измеряются ResizeObserver. Между краями звезды,
  экраном и контентом остаётся минимум 5% ширины; сверху/снизу — 5% высоты.
  Узкие поля скрываются. Нет хранения, запросов или фоновой анимации декораций.
  [Колода](deck-decoration.png).
- Общая кнопка удаления резервирует ширину обеих подписей до включения удержания:
  соседние элементы и сама кнопка не меняют координаты. Убрана видимая инструкция
  клавиш. Сохраняются отдельное включение, непрерывное удержание 3 секунды,
  Enter/Пробел и отмена вне кнопки, при blur и Escape.
  Deck: до/после x=831.430, y=1176.719, width=158.117, height=44;
  статус и сохранение также неизменны. Exercise: x=524.047, y=3588.273,
  width=189.883, height=44. [Действия упражнения](exercise-actions.png).
- На странице материала удаление находится между упражнениями и редактированием;
  в упражнении — между сбросом и сохранением. Нижний блок удалён.
  [Действия материала](material-actions.png).
- Удаление материала использует существующую item publication, CAS по колоде,
  ревизии и позиции материала, command receipt. Неопределённый исход повторяется
  той же командой; конфликт отключает повтор до обновления. Текущая позиция приходит
  вместе с документом с сервера: браузер не сканирует страницы и не декодирует
  остальные материалы. История, выданные Study presentations и Replay сохраняются;
  будущие Scheduled/Practice исключают упражнения с удалённым material binding,
  в том числе вариантами-дистракторами. См. [Items](../../../../contracts/items/README.md)
  и [Study](../../../../contracts/study/README.md).
- Idle спектр зависит от exercise revision и cue UID. При воспроизведении девять
  речевых полос 80–9000 Hz читают настоящий FFT 1024. Обновление ограничено 20 Hz,
  сглаживание 90 ms плюс CSS переход 100 ms. Это сохраняет заметное движение и
  различия записей, без общей искусственной формы. На реальных трёх файлах
  наблюдались разные профили: у первой записи столбики менялись примерно
  0.24–0.57 → 0.29–0.52 → 0.27–0.36; у второй 0.19–0.68 → 0.15–0.57;
  у третьей 0.23–0.64. Анализ начинается только после нажатия, прекращается
  при паузе/скрытии/выходе; reduced-motion оставляет статичную форму.
- Выбранное аудио имеет сплошную рамку внутри плитки. CSS subgrid выравнивает
  противоположные плитки даже после появления длинных транскриптов.
  Desktop y: 118.625 / 243.25 / 367.875; обе колонки совпадают.
  Mobile 390 px: 103.305 / 250.953 / 421.633; обе колонки совпадают,
  scrollWidth=390, горизонтального переполнения нет.
  [Desktop](audio-pairs-desktop.png), [390 px](audio-pairs-mobile.png).

## Проверка и границы

- Полные локальные backend quality, frontend lint/tests/build; coverage floor 90%
  для Identity и Learning. Отдельный backend lint/static-analysis task в проекте
  не настроен; Gradle quality выполняет тесты и coverage.
- Regression tests: геометрия и ResizeObserver cleanup, устойчивость семени,
  отсутствие пересечений, неизменность ширины удержания, retry/stale delete,
  серверная позиция без обхода списка, спектральное различие/плавность,
  выравнивание транскриптов, ACL/CAS/replay и Study после удаления материала.
- Repository policy, release/security contracts, disposable PostgreSQL 16→18
  backup/recovery и purge rehearsal проходят локально.
- Identity→Learning black-box: 14 harness tests, 25 HTTP сценариев,
  SIGINT/SIGTERM cleanup проходят. Для ошибок удаления используется Testcontainers;
  существующие материалы владельца в браузере не удалялись.
- Браузер: Chrome 154, desktop и responsive 390×844. Проверены включение/отмена
  удержания и настоящие аудиозаписи. Физические устройства и screen reader не
  проверялись. Известные CommonJS warnings лениво загружаемого Mermaid сохраняются;
  два backend Media opt-in теста требуют отдельного Docker Media режима и пропущены.
- Один независимый агент проверил реализацию: обнаруженный обход всех страниц
  заменён серверной позицией. Schema migrations и новые зависимости не нужны.

## Решения и откат

[MDN ResizeObserver](https://developer.mozilla.org/en-US/docs/Web/API/ResizeObserver)
обосновывает измерение после layout без polling;
[FFT](https://developer.mozilla.org/en-US/docs/Web/API/AnalyserNode/fftSize) и
[frequency data](https://developer.mozilla.org/en-US/docs/Web/API/AnalyserNode/getByteFrequencyData)
— речевые полосы вместо линейной выборки до Nyquist;
[smoothingTimeConstant](https://developer.mozilla.org/en-US/docs/Web/API/AnalyserNode/smoothingTimeConstant)
— плавность настоящего спектра.

Клиент и Learning обновляются вместе: контракт текущего ItemDetail теперь содержит
позицию материала выбранного deck snapshot. Откат — исправляющий PR и совместное
обновление локальных Learning/frontend. История не переписывается, destructive down
migration отсутствует. Общий сервер, staging и production не обновлялись.
