---
artifact:
  id: onboarding-texts-s3
  type: evidence
  title: "Onboarding S3: new strings awaiting owner approval"
  status: current
  created_at: "2026-10-18"
  owners: ["project-owner"]
---

# Новые строки S3 на утверждение владельцу

Источник — `frontend/src/app/core/onboarding/onboarding-texts.ts` (блоки «pending owner approval (S3)»), список ниже снят с него.
До утверждения строки работают только за выключенным `learning.onboarding.enabled`. Утверждённые тексты OB-001 (`path.*`, `m.*`, `hint.capture`,
`hint.ok`) не меняются. Две строки S1 («С какой колоды начнём?», «Новая колода») по-прежнему ждут решения.

| Ключ | Текст |
|---|---|
| `path.event.GOAL_TOLD` | вы рассказали о цели |
| `path.event.FIRST_DECK` | у вас первая колода |
| `path.event.FIRST_SESSION` | вы прошли первое занятие |
| `path.event.OWN_MATERIAL` | вы написали свой материал |
| `path.event.MEDIA_IN_MATERIAL` | вы добавили в материал картинку или звук |
| `path.event.CAPTURE_NOTE` | вы разобрали заметку «На потом» |
| `path.event.OWN_EXERCISES` | вы создали три своих упражнения |
| `path.event.AI_EXPLANATION` | ИИ проверил ваше объяснение |
| `path.event.VOICE_ANSWER` | вы ответили голосом |
| `path.event.WORKSHOP_APPROVED` | вы собрали материалы в Мастерской |
| `path.event.RHYTHM_WEEK` | вы провели неделю в ритме |
| `path.event.TWO_DAYS` | вы позанимались во второй день |
| `path.event.COMMUNITY_COPY` | вы взяли готовую колоду |
| `path.toast.many` | Путь: отмечено {n} {noun} · {done} из {total} |
| `path.show` | Показывать «Путь» |
| `path.loading` | Загружаем «Путь»… |
| `hint.PROFILE_GOAL` | Расскажите, для чего вам Mnema, — подсказки подстроятся под цель. |
| `hint.DECKS` | Нажмите «Новая колода», чтобы завести свою. |
| `hint.STUDY` | Нажмите «Учить», чтобы начать занятие. |
| `hint.MATERIAL_NEW` | Добавьте материал — то, что хотите запомнить. |
| `hint.EXERCISE_NEW` | Добавьте упражнение, чтобы проверять себя. |
| `hint.COMMUNITY` | Найдите колоду по своей теме и нажмите «Добавить к себе». |
| `goal.profile.title` | Цель |
| `goal.profile.loading` | Загружаем цель… |
| `goal.profile.error` | Не удалось загрузить цель. |
| `goal.profile.saveError` | Не удалось сохранить цель. Попробуйте ещё раз. |
| `goal.profile.current` | Ваша цель: {goal}. |
| `goal.profile.none` | Цель не выбрана. Её можно выбрать в любой момент. |
| `goal.profile.change` | Изменить |
| `goal.profile.choose` | Выбрать |
| `goal.profile.save` | Сохранить |
| `goal.profile.cancel` | Отмена |
| `quest.loading` | Готовим задание… |
| `quest.choose.lead` | Выберите колоду, в которой пройдём это задание. |
| `quest.choose.legend` | Колода |
| `quest.start` | Начать |
| `quest.cancel` | Не сейчас |
| `quest.leave` | Выйти из задания |
| `quest.pause.label` | Задание на паузе |
| `quest.pause.resume` | Продолжить задание |
| `quest.done` | Задание выполнено. Пункт «Пути» отметится сам. |
| `quest.error` | Не удалось подготовить задание. Попробуйте ещё раз. |
| `quest.blocked.tour` | Сначала завершите знакомство: задание можно начать после него. |
| `quest.blocked.unavailable` | Эта возможность сейчас недоступна, поэтому задание не начать. Загляните позже. |
| `quest.blocked.budget` | Лимит ИИ для этого задания исчерпан. Он обновится по расписанию, а больше ИИ даёт тариф. |
| `quest.blocked.budget.sample` | Лимит ИИ для этого задания исчерпан, но в учебной колоде ещё есть бесплатные проверки: {n}. Пройдём задание в ней. |
| `quest.sample.use` | Пройти в учебной колоде |
| `quest.nodeck` | У вас пока нет своей колоды. Заведите её, и задание станет доступно. |
| `quest.nonotes` | В этой колоде нет заметок «На потом». Сохраните заметку, и Мастерская соберёт из неё материал. |
| `quest.nonotes.action` | К заметкам |
| `quest.nomaterial` | В этой колоде пока нет материалов. Добавьте материал, и в него можно будет вставить картинку или звук. |
| `quest.nomaterial.action` | Добавить материал |
| `quest.noai` | В этой колоде нет заданий со свободным ответом, а своими словами отвечают только на такие. Добавьте такое задание или возьмите учебную колоду. |
| `quest.noai.action` | К колоде |
| `quest.workshop.pick.title` | Выберите заметки |
| `quest.workshop.pick.body` | Отметьте флажками заметки, из которых нужны материалы. Мнема соберёт их вместе. |
| `quest.workshop.assemble.title` | Соберите материалы |
| `quest.workshop.assemble.body` | Нажмите «Создать материалы с ИИ»: заметки перейдут в составитель. |
| `quest.workshop.compose.title` | Запустите создание |
| `quest.workshop.compose.body` | Проверьте заметки и запустите создание. Черновики материалов появятся в Мастерской. |
| `quest.workshop.approve.title` | Одобрите материал |
| `quest.workshop.approve.body` | ИИ собрал черновики. Прочитайте один из них и одобрите: так он попадёт в колоду. |
| `quest.voice.mic.title` | Сначала о микрофоне |
| `quest.voice.record.title` | Скажите вслух |
| `quest.voice.record.body` | Нажмите «Начать запись», скажите пару слов и остановите запись. Текст появится в поле, его можно поправить. |
| `quest.media.insert.title` | Добавьте картинку или звук |
| `quest.media.insert.body` | Выберите файл или найдите картинку здесь, и она встанет в текст материала. |
| `quest.media.publish.title` | Опубликуйте материал |
| `quest.media.publish.body` | Нажмите «Опубликовать», чтобы материал с картинкой или звуком попал в колоду. |
| `quest.explain.what.title` | Ответ своими словами |
| `quest.explain.study.title` | Пройдите занятие |
| `quest.explain.study.body` | Нажмите «Учить». Задание со свободным ответом попадается не в каждом занятии: если его не было, начните занятие ещё раз или добавьте такое упражнение в материал. Задание закончится, когда ИИ проверит ответ. |

Также: `path.eyebrow` «Профиль» (из разметки прототипа) — надзаголовок и ссылка назад на странице «Пути».
