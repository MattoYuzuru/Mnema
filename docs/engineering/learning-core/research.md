---
artifact:
  id: learning-core-scientific-review
  type: research
  title: "Scientific critique of learning-core hypotheses"
  status: proposed
  created_at: "2026-10-13"
  updated_at: "2026-10-13"
  owners: ["project-owner"]
---

# Научная проверка гипотез

Целевой обзор на 13.10.2026, с независимой критикой двух саб-агентов: литература и
реальный core. Это не preregistered systematic review всей науки о памяти.
Использованы primary papers, научные метаанализы, официальные conference records и
авторская документация. Для paywalled работ вывод ограничен доступным abstract;
актуальные model names/benchmarks не означают универсальную педагогическую валидность.

## Гипотезы и решения

| ID | Гипотеза | Проверка против неё | Решение |
|---|---|---|---|
| H01 | Retrieval + feedback помогает retention | Преимущество над содержательным обучением меньше, чем над rereading [S1,S3,S4] | Сохранить; не исключать объяснения и worked examples |
| H02 | Spacing полезен, поэтому текущие ступени оптимальны | Spacing устойчив; общего преимущества expanding над uniform нет [S2] | Польза пауз подтверждена, численные ступени — baseline |
| H03 | Нужно учитывать всю дополнительную практику | Она меняет память, но немедленный повтор после reveal зависим от него | Сохранять условия; не давать всем событиям одинаковый credit |
| H04 | Механика однозначно задаёт HIGH/MEDIUM/LOW | Точный ключ не доказывает валидность задания; слабая подсказка может почти раскрывать ответ | Сохранять result, assistance, evaluator и provenance отдельно |
| H05 | Оценка объяснения улучшит понимание и оценку памяти | Self-explanation intervention полезно [S7]; это не валидирует grader или memory weight | Criterion-level quality отдельно от scheduler |
| H06 | Маленький classifier заменит grader | Возможен [S12,S13], но task/language/domain generalization ограничена | Benchmark, abstention; новый production model не обязательный scope |
| H07 | Два согласных AI-прогона — независимое подтверждение | Ошибки моделей коррелируют [S16] | Это repeatability, не calibrated confidence |
| H08 | Больше упражнений означает больше знания | Дубликаты и cue-heavy варианты создают мало новой информации [S8] | Число вариантов — activity/diagnostic signal |
| H09 | Mastery = успехи / все упражнения материала | Дубликаты снижают score, удаление повышает без изменения знания | Формулу отвергнуть; scope задают distinct цели/критерии |
| H10 | Разные механики автоматически усиливают память | Смена UI не равна новой retrieval task; в одном classroom study были издержки у lower-aptitude learners [S6,S8] | Bounded rotation без memory multiplier; aptitude не тождественна новичку |
| H11 | Ответ на исходный вопрос переносится на новый | Transfer зависит от условий [S5]; свежий classroom study не нашёл общего эффекта [S9] | Retention и transfer измерять отдельно |
| H12 | FSRS оптимален для всех механик Mnema | Benchmark предсказательный; экономия reviews в ABC основана на simulation [S11] | Кандидат после пригодных наблюдений, не автозамена |
| H13 | Мнемоника должна стать общим core | Method of loci прежде всего serial recall; качество evidence low/very low [S17] | Encoding aids — отдельный ограниченный experiment |
| H14 | Две сессии агента докажут педагогическую пользу | Отсроченный тест требует календарного времени и участников | Разделить implementation и efficacy gates |

H03/H09 — прежде всего инженерные выводы о полноте данных и валидности измерения,
а не эффект отдельного психологического эксперимента. H04 не объявляет нынешнюю
классификацию бесполезной: она годится как прозрачный initial policy, но требует calibration.

## Что означает «качественный ответ»

Проверять наличие необходимых идей, причинных связей и misconceptions. Не использовать
длину, красоту, терминологическую насыщенность или скорость печати как самостоятельные
доказательства знания. Правильный краткий ответ и содержательно неверный длинный должны
различаться по rubric. Трудные PARTIAL ответы выделить в отдельный quality stratum [S13,S14].

Три независимых вопроса: что покрыто в ответе; насколько пригодна проверка;
что известно о самостоятельном воспроизведении спустя время. Один score 0…1 без этих
границ смешает разные величины. Уже реализованный ai-semantic следует измерить до добавления
ещё одного классификатора; Java/ONNX/API — технология исполнения, не научное обоснование.

Два сильных ответа могут быть информативнее двадцати зависимых повторов. Однако
универсального достаточного количества успешных ответов найденная литература не задаёт.
Парафразы одной цели не расширяют scope. Новая цель «применить к незнакомому случаю»
расширяет scope и появляется как непроверенная, а не как автоматически неверная.

## Протокол проверки пользы

Отдельно: точность grader, calibration memory prediction, retained performance,
transfer performance и время/число заданий. Зелёный unit test, хороший QWK,
log loss и synthetic lift подтверждают разные утверждения.

Для pilot заранее сохранить assignment unit, eligibility, policy versions, primary
outcome, test windows, помощь/extra practice, attrition и uncertainty. Сто активных
учеников — полезный pilot, не гарантия мощности для малого эффекта. Не считать тысячи
попыток тысячами независимых участников; confidence intervals кластеризовать по ученику.
Часть материала не возвращается в тест из-за пропуска — это attrition, не ошибка памяти.

7/30 дней — предлагаемые измерительные окна, не оптимальные универсальные интервалы.
Новые формулировки и application items должны быть сопоставимы и оценены человеком;
сам отсроченный тест тоже обучает. Первый outcome оценивается до его feedback.
Offline replay старой истории не отвечает, как пользователь учился бы при другой policy.
Random assignment в реальный pilot и новые data purposes требуют отдельного принятия
протокола/прозрачности; prepared harness не включает эксперимент автоматически.

## Источники

Все ссылки проверены исследовательской линией на 13.10.2026. Нет длинных цитат;
размеры эффектов — стандартизированные величины, не проценты улучшения памяти.

| ID | Публикация и дата | Применимый вывод и ограничение |
|---|---|---|
| S1 | [Yang et al., 08.03.2021](https://pubmed.ncbi.nlm.nih.gov/33683913/) | Classroom testing: 222 studies, 48,478 learners, g≈0.50; moderators существенны |
| S2 | [Latimier et al., online 07.10.2020, issue 2021](https://link.springer.com/article/10.1007/s10648-020-09572-8) | 29 studies; spaced/massed g=0.74, expanding/uniform g=0.034 без общего преимущества |
| S3 | [Gonçalves et al., 30.10.2025](https://link.springer.com/article/10.1007/s10648-025-10076-6) | 44 studies; g=0.14 против elaborative conditions; feedback важен; abstract |
| S4 | [Murray et al., 29.07.2025](https://eprints.whiterose.ac.uk/id/eprint/229807/) | Math spacing g=0.28; retrieval/restudy CI пересекает ноль; не доказательство бесполезности |
| S5 | [Pan & Rickard, 07.05.2018](https://pubmed.ncbi.nlm.nih.gov/29733621/) | Transfer возможен, зависит от elaboration/congruency/initial performance |
| S6 | [Brunmair & Richter, 26.09.2019](https://pubmed.ncbi.nlm.nih.gov/31556629/) | Interleaving зависит от материала, для слов возможен выигрыш blocking |
| S7 | [Bisra et al., 29.03.2018](https://link.springer.com/article/10.1007/s10648-018-9434-x) | Self-explanation g=0.55; intervention, не качество автоматического scoring; abstract |
| S8 | [Foss et al., 2023](https://doi.org/10.1016/j.learninstruc.2023.101807) | Item variation: в одном classroom study lower-aptitude learners имели издержки; generalization на всех новичков не установлена; abstract |
| S9 | [Ramgopal et al., online 28.09.2026](https://kar.kent.ac.uk/116768/) | Semester-long retrieval: повторённые вопросы выигрывают, общего transfer effect нет; institutional record |
| S10 | [Settles & Meeder, 2016](https://aclanthology.org/P16-1174/) | Обучаемая time/history-based HLR осуществима, generalization ограничена контекстом |
| S11 | [FSRS benchmark](https://github.com/open-spaced-repetition/srs-benchmark), [ABC, checked 13.10.2026](https://github.com/open-spaced-repetition/awesome-fsrs/wiki/ABC-of-FSRS) | Prediction/calibration benchmark; fewer reviews claim уточнён 12.02.2026 как simulation |
| S12 | [Urrutia et al., BEA July 2025](https://aclanthology.org/2025.bea-1.4/) | Лёгкий объяснимый ASAG возможен, нужны language/task/OOD tests |
| S13 | [Gurin Schleifer et al., BEA July 2026](https://aclanthology.org/2026.bea-1.29/) | Partial answers трудны; task-trained encoder лучше few-shot LLMs в двух biology items |
| S14 | [Cong et al., BEA July 2026](https://aclanthology.org/2026.bea-1.19/) | Aggregate scores скрывают failure profiles; IRT здесь о grader, не памяти ученика |
| S15 | [CALM, ICLR 2025](https://openreview.net/pdf?id=3GTtZFiajM) | Judge bias/perturbations; это AI benchmark, не learning trial; повторный fetch ограничен verification |
| S16 | [Goel et al., ICML 2025](https://proceedings.mlr.press/v267/goel25b.html) | Correlated model errors ограничивают AI oversight |
| S17 | [Method of loci review, online 03.06.2025](https://pubmed.ncbi.nlm.nih.gov/40457944/) | Serial recall gains, low/very low evidence quality; не общий mastery |

Это основания для продуктовых гипотез и ограничений, не готовые numeric memory weights.
