---
artifact:
  id: source-license-transition
  type: decision-record
  title: "Mnema source license transition"
  status: proposed
  created_at: "2026-08-15"
  updated_at: "2026-08-15"
  owners: ["project-owner"]
  evidence_date: "2026-08-15"
---

# Переход Mnema с Apache 2.0

Это исследовательская рекомендация, а не персональная юридическая консультация. Финальный текст proprietary-лицензии и chain of title должен проверить IP-юрист.

## Короткий ответ

Владелец может прекратить выпускать **будущие** версии Mnema под Apache 2.0. Уже выданные права отменить нельзя: получатели опубликованного Apache-кода сохраняют бессрочное право использовать, изменять, распространять и коммерциализировать его и производные работы. Они не получают будущие закрытые изменения, но могут продолжать собственный fork старой версии. Это следует из безотзывного copyright grant Apache 2.0 ([§2](https://www.apache.org/licenses/LICENSE-2.0.html)).

Простое удаление `LICENSE` — плохой cut-over:

- оно не отменяет лицензию исторических commits, tags, clones и forks;
- оно не объясняет, с какого commit действуют новые условия;
- публичный GitHub всё равно разрешает просматривать и форкать repository средствами платформы по своим [Terms, D.5](https://github.com/github/site-policy/blob/main/Policies/github-terms/github-terms-of-service.md?plain=1#L1208-L1215);
- неоднозначность затрудняет доказательство того, какие права действительно предоставлялись.

## Рекомендованный вариант

Цель «только владелец может запускать, изменять и коммерциализировать новый production-код» лучше всего достигается не публичной строгой лицензией, а разделением:

1. Зафиксировать последний Apache-срез тегом вроде `v1-apache-final` и архивировать его без переписывания истории.
2. Вести hosted v2 в новом **private** repository с явным proprietary notice.
3. Публично оставить документацию, спецификацию формата/API и только те SDK или компоненты, которые сознательно лицензируются отдельно.
4. Если позже понадобится self-hosted edition, выпускать её как односторонний downstream с отдельной лицензией и compatibility/security policy.

Публичный source нельзя сделать практически некопируемым: юридический запрет требует обнаружения нарушения и enforcement. Private repository лучше соответствует заявленной бизнес-цели и снижает риск случайной публикации secrets, payment logic и anti-abuse controls.

## Допустимая альтернатива: public source-available

Если весь v2 source принципиально должен оставаться публичным, нужен явный, проверенный юристом `Mnema Proprietary Source Code License`, а не отсутствие лицензии. Возможный scope для юридического ТЗ:

- разрешено только просматривать код на GitHub;
- запрещены запуск, локальное использование, изменение, производные работы, распространение, hosting/SaaS и коммерческое использование без отдельного письменного разрешения;
- third-party components и их notices исключены из proprietary grant;
- отдельно определены правообладатель, объект, территория, срок, прекращение, гарантии, ответственность, trademark и применимое право;
- contributions либо не принимаются, либо принимаются только по CLA/assignment, позволяющему sublicensing и relicensing.

Такой проект называется **source-available**, не open source: ограничения на использование или коммерциализацию несовместимы с [Open Source Definition](https://opensource.org/osd).

Готовые лицензии не совпадают с требованием «только владелец»:

| Лицензия | Что она всё равно разрешает другим |
|---|---|
| PolyForm Strict | определённое некоммерческое использование |
| PolyForm Shield | использование и изменение вне конкурирующего продукта |
| Elastic License 2.0 | использование, изменение и распространение с ограничениями вокруг managed service |
| BSL 1.1 | non-production use и обязательный будущий переход на open-source лицензию |
| AGPL | коммерческие forks при выполнении copyleft-условий |

Официальные тексты: [PolyForm](https://polyformproject.org/licenses), [Elastic License 2.0](https://www.elastic.co/licensing/elastic-license/faq/), [BSL 1.1](https://mariadb.com/bsl11/).

## Что проверено в Mnema

- `LICENSE` присутствует с initial commit `03d5797` от 2025-10-06.
- GitHub определяет текущий public repository как Apache 2.0.
- В истории найден один commit author — Matvei Riabushkin, 563 commits; `Co-authored-by` trailers не найдены.
- На дату проверки GitHub показывает `forkCount: 0`, но локальные clones обнаружить невозможно.

Это положительные сигналы, но не доказательство исключительных прав. До cut-over нужно проверить:

- не создавался ли код в рамках трудовых обязанностей или договора;
- не было ли подрядчиков, внешних патчей или скопированного кода;
- происхождение logo, favicon, `og-image.png`, fixtures, Anki-примеров и test data;
- лицензии распространяемых dependencies, Gradle wrapper и assets;
- корректное сохранение Apache/third-party copyright и NOTICE для сторонних частей.

Авторское право охраняет конкретную реализацию, но не идею, метод или архитектурный принцип ([ГК РФ 1259](https://www.consultant.ru/document/cons_doc_LAW_64629/be05678dc42ddc67aae5be9ba9beebd367fb9a3f/)). Для служебного произведения отдельно важна принадлежность исключительных прав работодателю ([ГК РФ 1295](https://www.consultant.ru/document/cons_doc_LAW_64629/b131343c6f094841b1ed8c5e6db72a390ea3e11c/)).

## Безопасный порядок перехода

Ни один пункт ниже пока не выполнен и не должен выполняться автоматически.

1. Юридически подтвердить правообладателя и chain of title.
2. Провести dependency/asset provenance audit.
3. Выбрать основной вариант: рекомендуемый private hosted v2 или public source-available.
4. Утвердить cut-off commit/tag и дату.
5. Не менять лицензию задним числом и не переписывать Apache history.
6. Подготовить lawyer-reviewed `LICENSE`, `NOTICE`, README wording и contribution policy для нового repository.
7. Заморозить внешние code contributions до появления CLA/assignment либо явно отказаться от них.
8. Только после отдельного подтверждения создать/перенести repository и обновить публичные материалы.

GitHub по умолчанию применяет к contributions принцип inbound = outbound, если отдельного соглашения нет ([GitHub Terms, D.6](https://github.com/github/site-policy/blob/main/Policies/github-terms/github-terms-of-service.md?plain=1#L1213-L1216)). `Signed-off-by`/DCO подтверждает происхождение вклада, но не заменяет CLA или отчуждение исключительных прав.

## Решение, которое требуется от владельца

Выбрать одно:

- **рекомендуется:** архивный public Apache v1 + private proprietary hosted v2;
- public source-available v2 с lawyer-reviewed view-only лицензией и осознанным риском копирования.

Требование «public, но пользоваться и изменять могу только я» юридически возможно сформулировать, но practically оно слабее private repository и не возвращает права на уже опубликованный Apache-код.
