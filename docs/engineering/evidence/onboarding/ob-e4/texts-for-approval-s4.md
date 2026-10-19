---
artifact:
  id: onboarding-texts-s4
  type: evidence
  title: "Onboarding S4: new strings awaiting owner approval"
  status: current
  created_at: "2026-10-19"
  owners: ["project-owner"]
---

# Новые строки S4 на утверждение владельцу

Источник — `frontend/src/app/core/onboarding/onboarding-texts.ts` (блоки «pending owner approval (S4)»), список снят с него. До утверждения строки
работают только за выключенными `learning.onboarding.rhythm.enabled` / `learning.onboarding.reward.enabled`. Утверждённые тексты OB-001 `rhythm.day`,
`rhythm.target`, `rhythm.met.toast`, `rhythm.profile.weeks`, `reward.eyebrow`, `reward.title`, `reward.terms`, `reward.action`, `reward.later` взяты дословно;
`reward.gift` — утверждённая фраза, в которой «10» стало `{percent}` из кампании. Порядковые «Первый…Седьмой» — из помощника прототипа (`texts.js`).
Блок «Ритм» в профиле переиспользует утверждённые `survey.days.*` и `survey.experience.*` S1.

## Ритм (OB-401)

| Ключ | Текст |
|---|---|
| `rhythm.week.short.1` | Пн |
| `rhythm.week.short.2` | Вт |
| `rhythm.week.short.3` | Ср |
| `rhythm.week.short.4` | Чт |
| `rhythm.week.short.5` | Пт |
| `rhythm.week.short.6` | Сб |
| `rhythm.week.short.7` | Вс |
| `rhythm.week.on.1` | в понедельник |
| `rhythm.week.on.2` | во вторник |
| `rhythm.week.on.3` | в среду |
| `rhythm.week.on.4` | в четверг |
| `rhythm.week.on.5` | в пятницу |
| `rhythm.week.on.6` | в субботу |
| `rhythm.week.on.7` | в воскресенье |
| `rhythm.week.and` | и |
| `rhythm.week.summary` | Эта неделя: занятия {days}. |
| `rhythm.week.summary.none` | Эта неделя: занятия ещё не отмечены. |
| `rhythm.settings.title` | Недельный ритм |
| `rhythm.settings.hint` | Это цель, а не обязательство: пропущенный день ничего не сжигает. |
| `rhythm.settings.save` | Сохранить |
| `rhythm.settings.saved` | Сохранено. |
| `rhythm.settings.error` | Не удалось сохранить. Попробуйте ещё раз. |
| `rhythm.settings.conflict` | Настройки уже изменились на другом устройстве. Проверьте выбор и сохраните ещё раз. |
| `hint.PROFILE_RHYTHM` | Здесь видна ваша неделя и выбирается цель: сколько дней в неделю заниматься. |

## Награда (OB-402)

| Ключ | Текст |
|---|---|
| `reward.gift.plan` | Ваш подарок: −{percent} % на подписку {plan} до {date}. |
| `reward.gift.tier` | Ваш подарок: {plan} на {days}. Активировать до {date}. |
| `reward.code.label` | Ваш промокод |
| `reward.code.copy` | Скопировать |
| `reward.code.copied` | Промокод скопирован. |
| `reward.code.manual` | Браузер не дал скопировать сам. Промокод выделен — скопируйте его. |
| `reward.redeemed` | Подарок за «Путь» использован. |
| `reward.notification` | Подарок за «Путь» действует до {date}. |

## Кабинет владельца (`/manage/promos`, раздел «Награда за «Путь»», и журнал `/manage/audit`)

Строки кабинета записаны прямо в шаблонах, как у остальных страниц кабинета (`features/admin/admin-reward-campaigns.component.*`,
`admin-audit-page.component.ts`): заголовок «Награда за «Путь»», пояснение, «Создать кампанию», «Кампании», «Тип предложения», «Скидка, %», «Тариф на дни»,
«Скидка, 1–90 %», «Дни, 1–366», «Любой тариф», «Включить» / «Выключить», «Да, включить» / «Да, выключить», «Отмена», «Выдачи» / «Скрыть выдачи», заголовки
таблицы выдач, сообщения о создании, переключении, `PROMO_CAMPAIGN_ACTIVE`, неизвестном исходе и пустых списках; журнал — «Создана кампания награды за «Путь»»,
«Кампания награды за «Путь» включена», «Кампания награды за «Путь» выключена». Это служебные тексты кабинета; на утверждение — вместе с остальными.
