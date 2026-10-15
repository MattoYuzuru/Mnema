// OB-001 onboarding prototype. Design evidence only: no API, no persistence, state lives in the tab.
// Do not port this router/state into Angular; the accepted architecture is docs/engineering/onboarding/architecture.md §8.
import { t } from './texts.js';

const HOLD_MS = 2000;
const app = document.querySelector('#app');
const layer = document.querySelector('#layer');
const toasts = document.querySelector('#toasts');
const live = document.querySelector('#tour-live');
const state = { catalog: true, calm: false, branch: 'sample', goal: 'LANGUAGE', topics: new Set(['Итальянский', 'Психология']), experience: 'NEW', days: '3', slide: 0 };
const params = new URLSearchParams(location.search);
if (params.has('shot')) document.body.classList.add('shot');
if (params.has('calm')) state.calm = true;
if (params.get('catalog') === '0') state.catalog = false;
let cleanup = [];
const on = (target, type, fn) => { target.addEventListener(type, fn); cleanup.push(() => target.removeEventListener(type, fn)); };

// ---------- glyphs ----------
const FINGER_HOLD = `<svg class="glyph" viewBox="0 0 24 24" aria-hidden="true" focusable="false" data-glyph="finger-hold">
  <path d="M9.5 3.5v9.75a2.5 2.5 0 0 0 5 0V3.5" /><path d="M10.75 12.5h2.5" />
  <rect x="3.5" y="17.5" width="17" height="3" rx="1" />
  <path d="M6.5 13 8 14.5M17.5 13 16 14.5" /></svg>`;
const CHEVRON = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="m9 5 7 7-7 7" /></svg>';
const STATE_GLYPH = {
  done: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" fill="currentColor" /><path d="m7.5 12.5 3 3 6-6.5" stroke="var(--mn-on-ink)" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round" /></svg>',
  partial: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="1.5" /><path d="M12 3a9 9 0 0 1 0 18Z" fill="currentColor" /></svg>',
  todo: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="3 2.6" /></svg>',
};

// ---------- app mock ----------
const masthead = active => `<header class="masthead">
  <a class="brand" href="#decks" aria-label="Mnema — главная">MNEMA</a>
  <nav class="primary-nav" aria-label="Основная навигация">
    ${[['decks', 'Мои колоды'], ['community', 'Сообщество'], ['new', 'Новая колода'], ['profile', 'Профиль']]
      .map(([key, label]) => `<a href="#${key === 'profile' ? 'path' : key === 'community' ? 'tour-community-1' : key === 'new' ? 'tour-own-1' : 'decks'}" data-anchor="nav.${key}" ${active === key ? 'aria-current="page"' : ''}>${label}</a>`).join('')}
  </nav>
  <div class="session"><span class="hint">anna@example.com</span><button type="button" class="button mono">Выйти</button></div>
</header>`;

const MATERIALS = [
  ['Кривая забывания', 'Текст'], ['Почему повторять через паузу', 'Картинка'], ['Эффект тестирования', 'Аудио'],
  ['Термины: интервал, извлечение, чередование', 'Пары'], ['Как Mnema выбирает, что повторить', 'Конспект'], ['Сон и память', 'Текст'],
];
const pages = {
  decks: () => `${masthead('decks')}<main class="page" id="main"><h1>Мои колоды</h1>
    <div class="empty-state notice"><p>Колод пока нет. Создайте первую или возьмите готовую в Сообществе.</p></div>
    <div class="row"><a class="button primary" href="#tour-own-1">Новая колода</a><a class="button" href="#tour-community-1">Сообщество</a></div></main>`,
  sample: () => `${masthead('decks')}<main class="page" id="main">
    <div data-anchor="hub.header" style="display:grid;gap:.6rem"><p class="eyebrow">Колода</p><h1>Как устроена память</h1>
      <p class="sample-plaque"><span class="stamp">Учебная</span><span>${t('sample.plaque')}</span></p>
      <p>Почему мы забываем, как повторение через паузу и самопроверка помогают помнить дольше.</p>
      <p class="facts"><span>8 материалов</span><span>24 упражнения</span><span>Готово к занятию: 4</span></p></div>
    <div class="row"><button type="button" class="button primary" data-anchor="hub.start">${t('sample.4.action')}</button>
      <button type="button" class="button" data-anchor="hub.capture">На потом</button><button type="button" class="button">Мастерская</button></div>
    <section aria-labelledby="mat-h" data-anchor="hub.materials"><h2 id="mat-h" style="font-size:1.6rem;margin-block-end:.5rem">Материалы</h2>
      <ul class="material-list">${MATERIALS.map(([title, kind], i) => `<li ${i === 0 ? 'data-anchor="hub.material"' : ''}><a href="#">${title}</a><span class="kind">${kind}</span></li>`).join('')}</ul></section>
    <section aria-labelledby="ex-h" data-anchor="hub.exercises" class="paper-surface" style="display:grid;gap:.5rem"><h2 id="ex-h" style="font-size:1.4rem">Упражнения материала «Кривая забывания»</h2>
      <ul class="material-list"><li>Выбор ответа<span class="kind">Выбор</span></li><li>Вставьте пропущенное слово<span class="kind">Пропуск</span></li><li>Объясните своими словами<span class="kind">ИИ · самопроверка</span></li></ul>
      <p class="hint">${t('sample.ai.free', { n: 3 })}</p></section></main>`,
  community: () => `${masthead('community')}<main class="page" id="main"><h1>Сообщество</h1>
    <div data-anchor="community.search" style="display:grid;gap:1rem"><form class="search" role="search" onsubmit="return false"><label class="visually-hidden" for="q">Поиск колод</label><input id="q" type="search" placeholder="Название или тема"><button class="button">Найти</button></form>
    <h2 style="font-size:1.6rem">Для вас</h2>
    <div class="shelf">${[['Итальянский: первые шаги', '32 материала · Языки'], ['Испанский для поездки', '28 материалов · Языки'], ['Введение в Git', '24 материала · IT'], ['Корейский: хангыль', '20 материалов · Языки']]
      .map(([title, facts], i) => `<article class="deck-card" ${i === 0 ? 'data-anchor="community.card"' : ''}><h3>${title}</h3><p class="hint">${facts}</p><p class="hint">Автор: Мнема · редакция</p>
      ${i === 0 ? `<div class="row"><button type="button" class="button primary" data-anchor="community.add">${t('community.3.action')}</button></div>` : ''}</article>`).join('')}</div></div></main>`,
  copy: () => pages.sample().replace('Как устроена память', 'Итальянский: первые шаги').replace(/<p class="sample-plaque">.*?<\/p>/, '<p class="hint">Копия колоды автора Мнема · редакция</p>'),
  own: () => `${masthead('new')}<main class="page" id="main"><h1>Новая колода</h1>
    <div class="paper-surface" data-anchor="own.name" style="display:grid;gap:.75rem"><label>Название<br><input style="inline-size:100%;min-block-size:2.75rem" value="Испанский: глаголы"></label><label>Описание<br><textarea style="inline-size:100%" rows="2">Спряжение частых глаголов</textarea></label></div>
    <div class="row"><button type="button" class="button" data-anchor="own.material">Новый материал</button><button type="button" class="button" data-anchor="own.exercise">Новое упражнение</button><button type="button" class="button primary" data-anchor="own.start">${t('sample.4.action')}</button></div></main>`,
  path: () => pathPage(),
  rhythm: () => `${masthead('decks')}<main class="page" id="main"><section class="summary"><p class="eyebrow">Занятие завершено</p><h1>Четыре упражнения позади</h1>
    <p>Следующее повторение — завтра. Mnema напомнит.</p>
    <div class="rhythm" aria-labelledby="rh-h"><h2 id="rh-h" style="font-size:1.6rem">${t('rhythm.day3')}</h2>
      <ol class="week" aria-label="Эта неделя: занятия во вторник, четверг и пятницу">${['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'].map((d, i) => `<li class="${[1, 3, 4].includes(i) ? 'done' : ''} ${i === 4 ? 'today' : ''}"><span class="day" aria-hidden="true">${[1, 3, 4].includes(i) ? '✓' : ''}</span>${d}</li>`).join('')}</ol>
      <p class="hint">${t('rhythm.target', { n: 3 })}</p></div>
    <div class="row"><a class="button primary" href="#decks">К колоде</a><a class="button" href="#path">Открыть «Путь»</a></div></section></main>`,
  states: () => `${masthead('decks')}<main class="page" id="main"><h1>Завершить знакомство: три состояния</h1>
    <p class="hint">Кнопка — вариант общего жеста удержания (app-hold-to-delete-button): 2 с вместо 3, во взведённом состоянии — только знак. Ниже статичные состояния и живая кнопка.</p>
    <div class="states-board">
      <figure><button type="button" class="hold-finish" tabindex="-1"><span>${t('tour.finish')}</span></button><figcaption>1. Обычное: подпись.</figcaption></figure>
      <figure><button type="button" class="hold-finish" aria-pressed="true" tabindex="-1" aria-label="${t('tour.finish.armed')}"><span class="reserve" aria-hidden="true">${t('tour.finish')}</span>${FINGER_HOLD}</button><figcaption>2. Взведена первым нажатием: знак «палец удерживает», без подписи. Доступное имя: «${t('tour.finish.armed')}».</figcaption></figure>
      <figure><button type="button" class="hold-finish" aria-pressed="true" tabindex="-1" style="--hold-size:70px;--hold-x:50%;--hold-y:50%;--hold-step:50%" aria-label="${t('tour.finish.armed')}"><span class="fill" aria-hidden="true"></span><span class="reserve" aria-hidden="true">${t('tour.finish')}</span>${FINGER_HOLD}</button><figcaption>3. Удержание: заливка волной от точки касания; при спокойном движении — ступенями без анимации.</figcaption></figure>
    </div>
    <h2 style="font-size:1.6rem">Живая кнопка</h2><div class="row" data-live-hold></div>
    <p class="hint">Отпустили раньше — ничего не происходит. Escape или уход фокуса снимают взвод. После удержания — тост.</p></main>`,
};

const MILESTONES = [
  ['start', [['GOAL_TOLD', 'done'], ['FIRST_DECK', 'done'], ['FIRST_SESSION', 'done']]],
  ['materials', [['OWN_MATERIAL', 'todo'], ['MEDIA_IN_MATERIAL', 'todo'], ['CAPTURE_NOTE', 'partial', 'Сохранена, не разобрана']]],
  ['exercises', [['OWN_EXERCISES', 'partial', null, [2, 3]], ['AI_EXPLANATION', 'done'], ['VOICE_ANSWER', 'todo'], ['WORKSHOP_APPROVED', 'todo']]],
  ['rhythm', [['RHYTHM_WEEK', 'partial', null, [2, 3]], ['TWO_DAYS', 'done'], ['COMMUNITY_COPY', 'todo']]],
];
function pathPage() {
  const done = MILESTONES.flatMap(([, list]) => list).filter(([, s]) => s === 'done').length;
  const total = MILESTONES.flatMap(([, list]) => list).length;
  const row = ([key, st, note, count]) => {
    const word = st === 'done' ? t('path.state.done') : st === 'todo' ? t('path.state.todo') : count ? `${count[0]} из ${count[1]}` : 'Начато';
    return `<li class="milestone" data-state="${st}"><span class="state">${STATE_GLYPH[st]}<span>${word}</span></span>
      <div><h3>${t('m.' + key)}</h3><p class="benefit">${note ?? t('m.' + key + '.b')}</p>
      ${count ? `<div class="bar"><div class="progress-track" aria-hidden="true"><div class="progress-fill" style="--value:${(count[0] / count[1]) * 100}%"></div></div><small>${count[0]} из ${count[1]}</small></div>` : ''}</div>
      ${st === 'done' ? '<span></span>' : `<a class="go" href="#hint" aria-label="${t('path.go', { title: t('m.' + key) })}">${CHEVRON}</a>`}</li>`;
  };
  return `${masthead('profile')}<main class="page" id="main">
    <p class="notice path-new"><span class="stamp">Новое</span><span>${t('path.new')}</span></p>
    <div class="path-head"><div><p class="eyebrow">Профиль</p><h1>${t('path.title')}</h1><p class="hint">${t('path.intro')}</p></div>
      <div class="path-total"><strong style="color:var(--mn-ink);font-size:1.3rem">${t('path.total', { done, total })}</strong>
      <div class="progress-track" aria-hidden="true"><div class="progress-fill" style="--value:${(done / total) * 100}%"></div></div></div></div>
    ${MILESTONES.map(([chapter, list], i) => {
      const d = list.filter(([, s]) => s === 'done').length;
      return `<details class="chapter" ${i === 1 || i === 2 ? 'open' : ''}><summary><h2>${t('path.chapter.' + chapter)}</h2><span class="count">${d} из ${list.length}</span></summary><ul class="milestones">${list.map(row).join('')}</ul></details>`;
    }).join('')}
    <div class="row"><a class="button" href="#welcome">${t('path.replay')}</a><button type="button" class="button quiet">${t('path.hide')}</button></div>
    <p class="hint">${t('rhythm.profile.weeks', { n: 6 })}</p></main>`;
}

// ---------- tours ----------
const TOURS = {
  sample: { page: 'sample', steps: [
    { anchor: 'hub.header', title: 'sample.1.title', body: 'sample.1.body' },
    { anchor: 'hub.material', title: 'sample.2.title', slides: ['sample.2.slide1', 'sample.2.slide2', 'sample.2.slide3'] },
    { anchor: 'hub.exercises', title: 'sample.3.title', body: 'sample.3.body' },
    { anchor: 'hub.start', title: 'sample.4.title', body: 'sample.4.body', gate: 'sample.4.action' },
  ] },
  community: { page: 'community', steps: [
    { anchor: 'community.search', title: 'community.1.title', body: 'community.1.body' },
    { anchor: 'community.card', title: 'community.2.title', body: 'community.2.body' },
    { anchor: 'community.add', title: 'community.3.title', body: 'community.3.body', gate: 'community.3.action' },
    { anchor: 'hub.start', title: 'community.4.title', body: 'community.4.body', gate: 'sample.4.action', page: 'copy' },
  ] },
  own: { page: 'own', steps: [
    { anchor: 'own.name', title: 'own.1.title', body: 'own.1.body' },
    { anchor: 'own.material', title: 'own.2.title', body: 'own.2.body' },
    { anchor: 'own.exercise', title: 'own.3.title', body: 'own.3.body' },
    { anchor: 'own.start', title: 'own.4.title', body: 'own.4.body', gate: 'sample.4.action' },
  ] },
};

// ---------- screens ----------
const SCREENS = [
  ['welcome', 'Приветствие'], ['survey-1', 'Опрос 1: цель'], ['survey-2', 'Опрос 2: интересы'], ['survey-3', 'Опрос 3: опыт'], ['survey-4', 'Опрос 4: дни'],
  ['branch', 'Выбор пути'], ['skip', 'Пропуск: тост'],
  ['tour-sample-1', 'Тур «На примере» 1'], ['tour-sample-2', 'Тур «На примере» 2: мини-слайдер'], ['tour-sample-3', 'Тур «На примере» 3'], ['tour-sample-4', 'Тур «На примере» 4: action-gate'],
  ['tour-community-1', 'Тур «Сообщество» 1'], ['tour-community-2', 'Тур «Сообщество» 2'], ['tour-community-3', 'Тур «Сообщество» 3: action-gate'], ['tour-community-4', 'Тур «Сообщество» 4'], ['community-stuck', '«Не нашли подходящую?»'],
  ['tour-own-1', 'Тур «Своя колода» 1'], ['tour-own-2', 'Тур «Своя колода» 2'], ['tour-own-3', 'Тур «Своя колода» 3'], ['tour-own-4', 'Тур «Своя колода» 4'],
  ['pause', 'Пауза'], ['finish-states', 'Завершить: три состояния'], ['finished', 'Завершено: тост'],
  ['ai-final', 'Финал «ИИ в Mnema»'], ['congrats', 'Поздравление'], ['path', '«Путь»'], ['path-toast', 'Тост вехи'], ['hint', 'Подсказка на месте'], ['rhythm', 'Ритм на итогах занятия'], ['reward', 'Награда за 100 % (после OB-002)'],
];

function render() {
  cleanup.forEach(fn => fn()); cleanup = [];
  layer.innerHTML = ''; toasts.innerHTML = '';
  document.documentElement.classList.toggle('calm', state.calm);
  const id = location.hash.slice(1) || 'welcome';
  document.querySelector('#screen').value = id;
  const tour = id.match(/^tour-(sample|community|own)-(\d)$/);
  if (tour) return renderTour(tour[1], Number(tour[2]) - 1);
  const survey = id.match(/^survey-(\d)$/);
  if (survey) { app.innerHTML = pages.decks(); return renderSurvey(Number(survey[1])); }
  switch (id) {
    case 'welcome': app.innerHTML = pages.decks(); return renderWelcome();
    case 'branch': app.innerHTML = pages.decks(); return renderBranch();
    case 'skip': app.innerHTML = pages.decks(); return toast(t('skip.toast'), t('skip.toast.link'), '#path');
    case 'community-stuck': return renderStuck();
    case 'pause': app.innerHTML = pages.sample(); return renderPause();
    case 'finish-states': app.innerHTML = pages.states(); return mountHold(app.querySelector('[data-live-hold]'), () => toast(t('tour.finish.toast')));
    case 'finished': app.innerHTML = pages.sample(); return toast(t('tour.finish.toast'));
    case 'ai-final': app.innerHTML = pages.sample(); return renderAiFinal();
    case 'congrats': app.innerHTML = pages.sample(); return renderNight('congrats');
    case 'path': app.innerHTML = pages.path(); return;
    case 'path-toast': app.innerHTML = pages.sample(); return toast(t('path.toast', { event: t('path.toast.example'), done: 6, total: 13 }), 'Открыть «Путь»', '#path');
    case 'hint': app.innerHTML = pages.sample(); return renderPlaceHint();
    case 'rhythm': app.innerHTML = pages.rhythm(); return toast(t('rhythm.met.toast', { n: 3 }));
    case 'reward': app.innerHTML = pages.path(); return renderNight('reward');
    default: app.innerHTML = pages.decks();
  }
}

// ---------- night surface ----------
function seeded(seed) { let s = seed; return () => (s = (s * 16807) % 2147483647) / 2147483647; }
function sky(seed) {
  const random = seeded(seed);
  const stars = [];
  const count = innerWidth < 600 ? 16 : 30;
  while (stars.length < count) {
    const x = random() * 100, y = random() * 100;
    if ((innerWidth < 600 || (x > 18 && x < 82)) && y > 14 && y < 86) continue; // keep the reading area clear; on a phone only top and bottom bands
    const dot = random() < .35;
    stars.push(`<span class="star ${random() < .5 ? 'lavender' : 'paper'} ${dot ? 'dot' : ''} ${random() < .4 ? 'twinkle' : ''}" style="left:${x}%;top:${y}%;--size:${(1 + random() * 1.4).toFixed(2)}rem;--turn:${Math.round(random() * 30 - 15)}deg;--alpha:${(.55 + random() * .4).toFixed(2)};--delay:${(random() * 3).toFixed(1)}s">${dot ? '' : '✧'}</span>`);
  }
  return `<div class="sky" aria-hidden="true">${stars.join('')}</div>`;
}
function nightShell(content, seed, label) {
  layer.innerHTML = `<div class="night" role="dialog" aria-modal="true" aria-labelledby="${label}">${sky(seed)}${content}</div>`;
  app.inert = true; cleanup.push(() => { app.inert = false; });
  layer.querySelector(`#${label}`).focus();
}
function renderWelcome() {
  nightShell(`<div class="night-content"><p class="eyebrow">${t('welcome.eyebrow')}</p><h1 id="night-title" tabindex="-1">${t('welcome.title')}</h1><p class="lead">${t('welcome.lead')}</p>
    <div class="night-actions"><a class="button primary" href="#survey-1">${t('welcome.start')}</a><a class="button quiet" href="#skip">${t('welcome.skip')}</a></div></div>`, 7, 'night-title');
}
function renderNight(kind) {
  const content = kind === 'congrats'
    ? `<p class="eyebrow">${t('congrats.eyebrow')}</p><h1 id="night-title" tabindex="-1">${t('congrats.title')}</h1><p class="lead">${t('congrats.body')}</p>
       <div class="night-actions"><a class="button primary" href="#decks">${t('congrats.action')}</a></div>`
    : `<p class="eyebrow">${t('reward.eyebrow')}</p><h1 id="night-title" tabindex="-1">${t('reward.title')}</h1><p class="lead">${t('reward.gift', { date: '21 октября, 23:59 МСК' })}</p><p class="muted">${t('reward.terms')}</p>
       <div class="night-actions"><a class="button primary" href="#path">${t('reward.action')}</a><a class="button quiet" href="#path">${t('reward.later')}</a></div>`;
  nightShell(`<div class="night-content">${content}</div>`, kind === 'congrats' ? 11 : 23, 'night-title');
}

// ---------- survey and branch ----------
function renderSurvey(n) {
  const progress = `<div class="progress"><p class="eyebrow">${t('survey.counter', { n })}</p><div class="progress-track" role="progressbar" aria-label="Вопросы" aria-valuemin="0" aria-valuemax="4" aria-valuenow="${n}"><div class="progress-fill" style="--value:${(n - 1) * 25}%"></div></div></div>`;
  const radios = (name, options, current) => options.map(([value, label, hint]) => `<label class="choice"><input type="radio" name="${name}" value="${value}" ${current === value ? 'checked' : ''}><span><strong>${label}</strong>${hint ? `<span class="hint">${hint}</span>` : ''}</span></label>`).join('');
  const bodies = {
    1: ['survey.goal.title', `<fieldset class="choices"><legend class="hint">${t('survey.goal.hint')}</legend>${radios('goal', [['EXAMS', 'Экзамены и сессия'], ['INTERVIEW', 'Собеседование'], ['LANGUAGE', 'Язык'], ['WORK', 'Работа'], ['SELF', 'Для себя']], state.goal)}</fieldset>`],
    2: ['survey.interests.title', `<fieldset class="choices"><legend class="hint">${t('survey.interests.hint')}</legend><div class="chips">${['Английский', 'Итальянский', 'Корейский', 'Испанский', 'Программирование', 'История', 'Психология', 'Медицина', 'Экзамены', 'Музыка']
      .map(topic => `<label class="chip"><input type="checkbox" name="topic" value="${topic}" ${state.topics.has(topic) ? 'checked' : ''}><span class="mark" aria-hidden="true">${state.topics.has(topic) ? '✓' : '+'}</span>${topic}</label>`).join('')}</div></fieldset>`],
    3: ['survey.experience.title', `<fieldset class="choices"><legend class="visually-hidden">${t('survey.experience.title')}</legend>${radios('experience', [['NEW', t('survey.experience.new'), t('survey.experience.new.hint')], ['FAMILIAR', t('survey.experience.familiar'), t('survey.experience.familiar.hint')], ['CONFIDENT', t('survey.experience.confident'), t('survey.experience.confident.hint')]], state.experience)}</fieldset>`],
    4: ['survey.days.title', `<fieldset class="choices"><legend class="hint">${t('survey.days.hint')}</legend><div class="segmented">${[['2', '2 дня'], ['3', '3 дня'], ['5', '5 дней'], ['7', t('survey.days.everyday')]].map(([v, l]) => `<label><input type="radio" name="days" value="${v}" ${state.days === v ? 'checked' : ''}>${l}</label>`).join('')}</div></fieldset>`],
  };
  const [title, body] = bodies[n];
  const next = n === 4 ? '#branch' : `#survey-${n + 1}`;
  nightShell(`<section class="sheet" aria-labelledby="survey-title">${progress}<h2 id="survey-title" tabindex="-1">${t(title)}</h2>${body}
    <div class="sheet-actions">${n > 1 ? `<a class="button quiet" href="#survey-${n - 1}">${t('survey.back')}</a>` : '<span></span>'}
      <div class="forward"><a class="button" href="${next}">${t('survey.skip')}</a><a class="button primary" href="${next}">${n === 4 ? t('survey.done') : t('survey.next')}</a></div></div>
    <p class="hint privacy-note">${t('survey.privacy')}</p></section>`, 3, 'survey-title');
  on(layer, 'change', event => {
    const input = event.target;
    if (input.name === 'topic') { input.checked ? state.topics.add(input.value) : state.topics.delete(input.value); input.nextElementSibling.textContent = input.checked ? '✓' : '+'; }
    else if (input.name) state[input.name] = input.value;
  });
}
function renderBranch() {
  const options = [['sample', t('branch.sample'), t('branch.sample.hint'), state.experience === 'NEW' ? t('branch.sample.stamp') : ''],
    ...(state.catalog ? [['community', t('branch.community'), t('branch.community.hint')]] : []), ['own', t('branch.own'), t('branch.own.hint')]];
  nightShell(`<section class="sheet" aria-labelledby="branch-title"><h2 id="branch-title" tabindex="-1">${t('branch.title')}</h2>
    <fieldset class="choices"><legend class="visually-hidden">${t('branch.title')}</legend>${options.map(([v, l, h, stamp]) => `<label class="choice"><input type="radio" name="branch" value="${v}" ${state.branch === v ? 'checked' : ''}><span><strong>${l} ${stamp ? `<span class="stamp">${stamp}</span>` : ''}</strong><span class="hint">${h}</span></span></label>`).join('')}</fieldset>
    <div class="sheet-actions"><a class="button quiet" href="#survey-4">${t('survey.back')}</a><a class="button primary" id="branch-go" href="#tour-${state.branch}-1">${t('branch.continue')}</a></div></section>`, 5, 'branch-title');
  on(layer, 'change', event => { state.branch = event.target.value; layer.querySelector('#branch-go').href = `#tour-${state.branch}-1`; });
}

// ---------- spotlight ----------
function renderTour(branch, index) {
  const tour = TOURS[branch];
  const step = tour.steps[index];
  app.innerHTML = pages[step.page ?? tour.page]();
  const target = app.querySelector(`[data-anchor="${step.anchor}"]`);
  const m = tour.steps.length;
  const nextHref = index + 1 < m ? `#tour-${branch}-${index + 2}` : '#ai-final';
  const backHref = index > 0 ? `#tour-${branch}-${index}` : '#branch';
  const body = step.slides ? slider(step.slides) : `<p>${t(step.body)}</p>`;
  layer.innerHTML = `<svg class="scrim" aria-hidden="true"><defs><mask id="cut"><rect width="100%" height="100%" fill="white" /><rect id="hole" rx="6" fill="black" /></mask></defs><rect class="veil" width="100%" height="100%" mask="url(#cut)" /></svg>
    <div class="cutout-ring" aria-hidden="true"></div>
    <dialog class="tour-card" aria-labelledby="step-title" aria-describedby="step-body">
      <div class="tour-head"><p class="eyebrow">${t('tour.counter', { n: index + 1, m })}</p><div class="dots" aria-hidden="true">${tour.steps.map((_, i) => `<span class="${i <= index ? 'on' : ''}"></span>`).join('')}</div></div>
      <h2 id="step-title" tabindex="-1">${t(step.title)}</h2><div id="step-body">${body}</div>
      ${step.gate ? `<p class="gate">→ ${t('tour.gate', { action: t(step.gate) })}</p>` : ''}
      <div class="tour-nav"><div class="steps"><a class="button quiet" href="${backHref}">${t('tour.back')}</a>${step.gate ? '' : `<a class="button primary" href="${nextHref}">${t('tour.next')}</a>`}</div><span data-hold></span></div>
    </dialog>`;
  const card = layer.querySelector('dialog');
  card.show();
  mountHold(card.querySelector('[data-hold]'), () => { location.hash = 'finished'; });
  if (step.slides) wireSlider(card, step.slides);
  if (!target) { card.querySelector('#step-body').innerHTML = `<p>${t('tour.missing')}</p>`; return; }
  target.classList.add('is-tour-target');
  if (step.gate) {
    restoreInert = inertOutside(target);
    const go = () => { location.hash = nextHref.slice(1); };
    target.addEventListener('click', go);
    cleanup.push(() => target.removeEventListener('click', go));
  }
  const place = () => positionSpotlight(target, card);
  target.scrollIntoView({ block: 'center', behavior: 'instant' });
  place();
  const onMove = () => requestAnimationFrame(place);
  addEventListener('resize', onMove); addEventListener('scroll', onMove, true);
  const observer = new ResizeObserver(onMove); observer.observe(target);
  const onKey = event => { if (event.key === 'Escape') { event.preventDefault(); location.hash = 'pause'; } };
  document.addEventListener('keydown', onKey);
  cleanup.push(() => { removeEventListener('resize', onMove); removeEventListener('scroll', onMove, true); observer.disconnect(); document.removeEventListener('keydown', onKey); document.body.style.paddingBottom = ''; restoreInert(); restoreInert = () => {}; });
  card.querySelector('#step-title').focus({ preventScroll: true });
  live.textContent = `${t('tour.counter', { n: index + 1, m })}. ${t(step.title)}`;
}
let restoreInert = () => {};
function inertOutside(target) {
  const marked = [];
  for (let node = target; node && node !== app; node = node.parentElement) {
    for (const sibling of node.parentElement.children) if (sibling !== node && !sibling.inert) { sibling.inert = true; marked.push(sibling); }
  }
  return () => marked.forEach(el => { el.inert = false; });
}
function positionSpotlight(target, card) {
  const r = target.getBoundingClientRect();
  const pad = 8;
  const hole = layer.querySelector('#hole');
  const box = { x: r.left - pad, y: r.top - pad, w: r.width + pad * 2, h: r.height + pad * 2 };
  hole.setAttribute('x', box.x); hole.setAttribute('y', box.y); hole.setAttribute('width', box.w); hole.setAttribute('height', box.h);
  Object.assign(layer.querySelector('.cutout-ring').style, { left: `${box.x}px`, top: `${box.y}px`, width: `${box.w}px`, height: `${box.h}px` });
  if (matchMedia('(max-width: 37.5rem)').matches) {
    // Bottom sheet: reserve its height under the page and keep the target above it.
    card.style.left = card.style.top = '';
    const sheet = card.offsetHeight;
    document.body.style.paddingBottom = `${sheet}px`;
    const overlap = r.bottom + 16 - (innerHeight - sheet);
    if (overlap > 0 && !positionSpotlight.adjusting) { positionSpotlight.adjusting = true; scrollBy(0, Math.min(overlap, r.top - 16)); positionSpotlight.adjusting = false; }
    return;
  }
  const cw = card.offsetWidth, ch = card.offsetHeight, gap = 16, vw = innerWidth, vh = innerHeight;
  const below = box.y + box.h + gap, above = box.y - gap - ch;
  const top = below + ch <= vh ? below : above >= 0 ? above : Math.max(gap, Math.min(vh - ch - gap, box.y));
  const sideways = below + ch > vh && above < 0;
  let left = sideways ? (box.x + box.w + gap + cw <= vw ? box.x + box.w + gap : box.x - gap - cw) : box.x;
  left = Math.max(gap, Math.min(vw - cw - gap, left));
  card.style.left = `${left}px`; card.style.top = `${top}px`;
}
function slider(slides) {
  return `<div class="slider" aria-roledescription="мини-слайдер"><p aria-live="polite" data-slide>${t(slides[0])}</p>
    <div class="slider-controls"><button type="button" class="button" data-dir="-1" aria-label="${t('tour.slider.prev')}">‹</button><span class="hint" data-count>1 / ${slides.length}</span><button type="button" class="button" data-dir="1" aria-label="${t('tour.slider.next')}">›</button></div></div>`;
}
function wireSlider(card, slides) {
  let i = 0;
  card.querySelectorAll('[data-dir]').forEach(button => button.addEventListener('click', () => {
    i = (i + Number(button.dataset.dir) + slides.length) % slides.length;
    card.querySelector('[data-slide]').innerHTML = t(slides[i]);
    card.querySelector('[data-count]').textContent = `${i + 1} / ${slides.length}`;
  }));
}
function renderStuck() {
  app.innerHTML = pages.community();
  layer.innerHTML = `<div class="place-hint" role="note" style="top:0;left:0"><p>${t('community.browse.hint')}</p></div>
    <dialog class="tour-card" aria-labelledby="stuck-title" style="left:auto;right:var(--mn-page-gutter);top:auto;bottom:1rem"><h2 id="stuck-title" tabindex="-1">${t('community.stuck.title')}</h2><p>${t('community.stuck.body')}</p>
    <div class="tour-nav"><a class="button quiet" href="#tour-community-1">${t('tour.back')}</a><a class="button primary" href="#tour-own-1">${t('community.stuck.action')}</a></div></dialog>`;
  const search = app.querySelector('#q').getBoundingClientRect();
  Object.assign(layer.querySelector('.place-hint').style, { top: `${search.bottom + scrollY + 12}px`, left: `${search.left + scrollX}px` });
  layer.querySelector('dialog').show();
}

// ---------- hold to finish ----------
function mountHold(host, onDone) {
  host.innerHTML = `<button type="button" class="hold-finish" aria-pressed="false"><span class="fill" aria-hidden="true"></span><span class="reserve" aria-hidden="true">${t('tour.finish')}</span><span data-label>${t('tour.finish')}</span></button>`;
  const button = host.querySelector('button');
  const label = button.querySelector('[data-label]');
  let armed = false, timer = null, started = 0, radius = 0, suppressClick = false;
  const calm = () => state.calm || matchMedia('(prefers-reduced-motion: reduce)').matches;
  const setArmed = value => {
    armed = value;
    button.setAttribute('aria-pressed', String(value));
    if (value) { button.setAttribute('aria-label', t('tour.finish.armed')); label.innerHTML = FINGER_HOLD; }
    else { button.removeAttribute('aria-label'); label.textContent = t('tour.finish'); }
  };
  const stop = () => { clearInterval(timer); timer = null; button.style.setProperty('--hold-size', '0px'); button.style.setProperty('--hold-step', '0%'); };
  const begin = (x, y) => {
    if (timer) return;
    button.style.setProperty('--hold-x', `${x}px`); button.style.setProperty('--hold-y', `${y}px`);
    radius = Math.hypot(Math.max(x, button.offsetWidth - x), Math.max(y, button.offsetHeight - y));
    started = Date.now();
    timer = setInterval(() => {
      const share = Math.min(1, (Date.now() - started) / HOLD_MS);
      button.style.setProperty('--hold-size', `${radius * share}px`);
      button.style.setProperty('--hold-step', `${Math.floor(share * 4) * 25}%`); // calm: four still steps, no motion
      if (share === 1) { stop(); setArmed(false); suppressClick = true; onDone(); }
    }, calm() ? 100 : 25);
  };
  button.addEventListener('click', () => { if (suppressClick) { suppressClick = false; return; } if (!armed) setArmed(true); });
  button.addEventListener('pointerdown', event => { if (!armed || event.button !== 0) return; event.preventDefault(); const r = button.getBoundingClientRect(); begin(event.clientX - r.left, event.clientY - r.top); });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach(type => button.addEventListener(type, stop));
  button.addEventListener('keydown', event => {
    if (event.key === 'Escape' && armed) { event.preventDefault(); event.stopPropagation(); stop(); setArmed(false); return; }
    if (!armed || event.repeat || (event.key !== 'Enter' && event.key !== ' ')) return;
    event.preventDefault(); begin(button.offsetWidth / 2, button.offsetHeight / 2);
  });
  button.addEventListener('keyup', event => { if (event.key === 'Enter' || event.key === ' ') stop(); });
  button.addEventListener('blur', () => { stop(); setArmed(false); });
  cleanup.push(stop);
}

// ---------- pause, final, hints, toasts ----------
function renderPause() {
  layer.innerHTML = `<div class="pause-pill" role="region" aria-label="${t('tour.pause.label')}"><span>${t('tour.pause.label')}</span><a class="button primary" href="#tour-sample-3">${t('tour.pause.resume')}</a></div>`;
}
function renderAiFinal() {
  layer.innerHTML = `<svg class="scrim" aria-hidden="true"><rect class="veil" width="100%" height="100%" /></svg>
    <dialog class="tour-card" aria-labelledby="ai-title" style="left:50%;top:50%;transform:translate(-50%,-50%)">
      <p class="eyebrow">${t('ai.eyebrow')}</p><h2 id="ai-title" tabindex="-1">${t('ai.title')}</h2><p>${t('ai.body')}</p>
      <ul style="margin:0;padding-inline-start:1.1rem;display:grid;gap:.35rem"><li>${t('ai.workshop')}</li><li>${t('ai.check')}</li></ul>
      <p class="hint">${t('ai.free', { freeAllowance: '{из тарифа Free}' })} <a href="#ai-final">${t('ai.plans')}</a></p>
      <div class="tour-nav"><a class="button quiet" href="#tour-${state.branch}-4">${t('tour.back')}</a><a class="button primary" href="#congrats">${t('ai.next')}</a></div></dialog>`;
  app.inert = true; cleanup.push(() => { app.inert = false; });
  const card = layer.querySelector('dialog'); card.show();
  if (matchMedia('(max-width: 37.5rem)').matches) card.style.transform = 'none';
  card.querySelector('#ai-title').focus();
}
function renderPlaceHint() {
  const target = app.querySelector('[data-anchor="hub.capture"]');
  target.scrollIntoView({ block: 'center', behavior: 'instant' });
  const r = target.getBoundingClientRect();
  layer.innerHTML = `<div class="place-hint" role="note" aria-label="Подсказка" style="top:${r.bottom + scrollY + 14}px;left:${Math.max(16, r.left + scrollX - 12)}px"><p>${t('hint.capture')}</p><button type="button" class="button" style="justify-self:start">${t('hint.ok')}</button></div>`;
  layer.querySelector('.place-hint button').addEventListener('click', () => { layer.innerHTML = ''; target.focus(); });
}
function toast(text, linkText, href) {
  toasts.innerHTML = `<div class="toast"><p>${text}</p><button type="button" class="close" aria-label="Закрыть уведомление">×</button>${linkText ? `<a href="${href}">${linkText}</a>` : ''}</div>`;
  document.querySelector('#toast-status').textContent = text;
  toasts.querySelector('.close').addEventListener('click', () => { toasts.innerHTML = ''; });
}

// ---------- prototype rail ----------
const select = document.querySelector('#screen');
select.innerHTML = SCREENS.map(([id, label]) => `<option value="${id}">${label}</option>`).join('');
select.addEventListener('change', () => { location.hash = select.value; });
const calmBox = document.querySelector('#calm');
calmBox.checked = state.calm;
calmBox.addEventListener('change', () => { state.calm = calmBox.checked; render(); });
const catalogBox = document.querySelector('#catalog');
catalogBox.checked = state.catalog;
catalogBox.addEventListener('change', () => { state.catalog = catalogBox.checked; render(); });
addEventListener('hashchange', render);
render();
