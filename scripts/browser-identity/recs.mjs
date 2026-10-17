// Recs/4 (#459): real HTTPS Chrome against real Identity and Learning. Only disposable API fixtures are seeded.
// User decisions (chips, sorting, opening, reset) use keyboard events, with no application state injection.
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { makeActor } from './community.mjs';
import { seedCatalogDeck } from './catalog-seed.mjs';

const DESKTOP = { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false };
const MOBILE = { width: 320, height: 844, deviceScaleFactor: 1, mobile: true };

export async function runRecs(ctx) {
  const { config, record, SafeFailure, until, exists, navigate, saveFullScreenshot, setStep, ownerTab, ownerBearer, memberTab, memberBearer, openGuestTab } = ctx;
  const owner = makeActor(ctx, 'owner', ownerTab, ownerBearer);
  const member = makeActor(ctx, 'member', memberTab, memberBearer);
  const guestSession = await openGuestTab();
  const guest = makeActor(ctx, 'guest', guestSession.tab, null);
  const failures = [];
  const broken = new Set();
  const world = { decks: [], copied: null, own: null, leaves: [] };
  const need = (value, message) => { if (!value) throw new SafeFailure(message); };
  const shoot = async (actor, name) => {
    await until(() => actor.page(`return document.getAnimations().filter(a=>a.effect?.target?.closest('app-community-page')&&a.playState==='running'&&Number.isFinite(a.effect.getTiming().iterations)&&Number(a.effect.getTiming().duration)>1).length===0;`), 'finite UI motion did not settle before capture', 5000);
    return saveFullScreenshot(`recs-${name}.png`, actor.tab);
  };
  const stage = async (name, dependencies, run) => {
    const key = `recs_${name}`;
    setStep(key);
    const dependency = dependencies.find(value => broken.has(value));
    if (dependency) { broken.add(name); record(key, { state: 'skipped', because: `recs_${dependency} failed` }); return; }
    const started = Date.now();
    try { const detail = await run(); record(key, { state: 'passed', durationMs: Date.now() - started, ...(detail ?? {}) }); }
    catch (error) {
      const reason = error instanceof SafeFailure ? error.message : 'driver: ' + String(error?.message ?? error).slice(0, 160);
      failures.push({ stage: key, reason }); broken.add(name);
      record(key, { state: 'failed', reason, durationMs: Date.now() - started });
      await shoot(member, `failure-${name}`).catch(() => {});
      await writeFile(join(config.output, `failure-${key}.txt`), reason + '\n');
    }
  };
  const learning = async (method, path, { bearer = memberBearer, payload, headers = {} } = {}) => {
    const response = await fetch(config.learningOrigin + path, { method, signal: AbortSignal.timeout(25000),
      headers: { ...(bearer === null ? {} : { Authorization: 'Bearer ' + bearer }), ...(payload === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
      body: payload === undefined ? undefined : JSON.stringify(payload) });
    const text = await response.text();
    return { status: response.status, etag: response.headers.get('etag'), body: text ? JSON.parse(text) : null };
  };
  const fixture = async (path, payload = {}) => {
    const answer = await owner.page(`const r = await fetch(args[0], {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(args[1])});
      return {status:r.status,body:await r.json()};`, path, payload);
    need(answer.status === 200, `fixture ${path} answered ${answer.status}`); return answer.body;
  };
  const consent = async bearer => {
    const answer = await owner.page(`const r=await fetch(args[0]+'/api/accounts/me/public-profile',{method:'PUT',credentials:'omit',
      headers:{Authorization:'Bearer '+args[1],'Content-Type':'application/json'},body:JSON.stringify({enabled:true,showDisplayName:false,showAvatar:false,showBio:false,textVersion:'2026-10-10'})});
      return {status:r.status,body:await r.json()};`, config.identity, bearer);
    need(answer.status === 200 && answer.body.publishReady, 'synthetic author consent failed');
  };
  const profile = () => learning('GET', '/api/community/interests');
  const recommendations = (suffix = '') => learning('GET', '/api/community/recommendations?limit=48' + suffix);
  const open = async (actor, path = '/community', size = DESKTOP) => {
    await actor.front(); await actor.metrics(size); await navigate(path, actor.tab);
    await until(() => exists(path === '/profile' ? '#profile-title' : 'app-community-page h1', actor.tab), `${path} missing`, 30000);
    if (path.startsWith('/community')) await until(() => actor.page(`return !document.querySelector('app-community-page .status')?.textContent.includes('Загружаем');`), 'community stayed loading', 30000);
  };
  const forYouCards = actor => actor.page(`const shelf=[...document.querySelectorAll('app-deck-shelf')].find(s=>s.querySelector('h2')?.textContent.trim()==='Для вас');
    return shelf ? [...shelf.querySelectorAll('app-public-deck-card .title a')].map(a=>({title:a.textContent.trim(),href:a.getAttribute('href')})) : [];`);
  const button = async (actor, text) => {
    await until(() => actor.page(`return [...document.querySelectorAll('main button')].some(b=>b.textContent.trim()===args[0]&&!b.disabled&&b.getClientRects().length>0);`, text), `button «${text}» did not become available`, 30000);
    await actor.enter('main button', text);
  };
  const topicCheck = async (actor, topicId) => {
    const topic = world.leaves.find(topic => topic.id === topicId);
    need(topic, `interest topic ${topicId} missing from catalogue`);
    const selector = 'app-interest-picker button[data-interest-topic="' + topicId + '"]';
    if (!(await actor.page('return Boolean(document.querySelector(args[0]));', selector)) && topic.parentId !== null) {
      await actor.enter('app-interest-picker button[data-interest-category="' + topic.parentId + '"]');
      for (let step = 0; step < Math.ceil(world.leaves.filter(candidate => candidate.parentId === topic.parentId).length / 5); step++) {
        if (await actor.page('return Boolean(document.querySelector(args[0]));', selector)) break;
        await actor.enter('app-interest-picker button.more');
      }
    }
    need(await actor.page('return Boolean(document.querySelector(args[0]));', selector), `interest topic ${topicId} is unreachable`);
    await actor.focus(selector); await actor.press('Space');
  };

  await stage('empty_and_guests', [], async () => {
    const p = await profile(); need(p.status === 200 && p.body.forYouEnabled && p.body.eventsEnabled && !p.body.onboardingComplete, 'first interest profile wrong');
    world.leaves = p.body.availableTopics;
    need((await recommendations()).body.decks.length === 0, 'empty catalogue has recommendations');
    for (const path of ['/api/community/interests', '/api/community/recommendations']) need((await learning('GET', path, { bearer: null })).status === 401, 'guest private route admitted');
    await open(guest); need(!(await guest.has('Что вы хотите изучать в Мнеме?')), 'guest sees interest onboarding');
    need((await forYouCards(guest)).length === 0, 'guest sees For you');
    await open(member); need(await member.has('Что вы хотите изучать в Мнеме?'), 'first-entry chips missing');
    await member.enter('app-interest-picker button[data-interest-category="languages"]');
    await member.focus('app-interest-picker button[data-interest-topic="english"]'); await member.press('Space');
    await until(() => exists('app-interest-picker .chosen [data-interest-topic="english"]', member.tab), 'selected topic did not stay visible');
    await member.tab.call('Emulation.setEmulatedMedia', { features: [{ name: 'forced-colors', value: 'active' }] });
    try {
      const colours = await member.page(`const button=document.querySelector('app-interest-picker .chosen [data-interest-topic="english"]');
        const style=getComputedStyle(button); return {foreground:style.color,background:style.backgroundColor,outline:style.outlineStyle,width:style.outlineWidth,pressed:button.getAttribute('aria-pressed')};`);
      need(colours.pressed === 'true' && colours.foreground !== colours.background && colours.outline === 'solid' && parseFloat(colours.width) >= 2,
        'forced-colours selected topic is indistinguishable: ' + JSON.stringify(colours));
      await shoot(member, 'desktop-interest-forced-colours');
    } finally { await member.tab.call('Emulation.setEmulatedMedia', { features: [] }); }
    await member.focus('app-interest-picker .chosen [data-interest-topic="english"]'); await member.press('Space');
    await shoot(member, 'desktop-first-entry'); return { privateRoutes: true, emptyShelfHidden: true, guestUntracked: true, topicForcedColours: true };
  });

  await stage('seed', ['empty_and_guests'], async () => {
    const authors = await fixture('/__fixture/catalog-accounts', { names: Array.from({ length: 6 }, (_, i) => `recs_author_${i}`) });
    const learners = await fixture('/__fixture/catalog-accounts', { names: Array.from({ length: 6 }, (_, i) => `recs_rank_learner_${i}`) });
    const otherTopics = world.leaves.filter(t => t.id !== 'spanish').slice(0, 6);
    need(otherTopics.length === 6, 'six distinct secondary topics missing');
    for (let i = 0; i < authors.length; i++) {
      await consent(authors[i].token);
      for (let j = 0; j < 4; j++) {
        const topic = j === 0 ? 'spanish' : otherTopics[(i + j - 1) % otherTopics.length].id;
        const spec = { title: `Подбор ${topic} ${i} ${j}`, description: 'Учебная подборка для проверки рекомендаций.', topicId: topic,
          content: 'ru', target: topic === 'spanish' ? 'es' : null, level: 'BEGINNER', tags: [] };
        const deck = await seedCatalogDeck(learning, need, authors[i].token, spec, { visibility: 'PUBLIC' });
        world.decks.push({ ...deck, topic, author: authors[i].accountId });
        const copy = await learning('POST', '/api/decks/forks', { bearer: learners[i].token, payload: { commandId: randomUUID(), sourceCode: deck.code } });
        need(copy.status === 201 || copy.status === 200, 'ranking fixture copy failed: ' + copy.status);
      }
    }
    await consent(memberBearer);
    world.own = await seedCatalogDeck(learning, need, memberBearer, { title: 'Моя авторская испанская колода', description: 'Своя колода исключается из рекомендаций.', topicId: 'spanish',
      content: 'ru', target: 'es', level: 'BEGINNER', tags: [] }, { visibility: 'PUBLIC' });
    const list = () => learning('GET', '/api/public/community/decks?sort=new&limit=48');
    await until(async () => (await list()).body?.total === 25, 'projection did not list all fixtures', 120000);
    const rank = await fixture('/__fixture/catalog-rank'); await fixture('/__fixture/catalog-rank', { enqueue: true });
    await until(async () => (await fixture('/__fixture/catalog-rank')).generations > rank.generations, 'rank job did not finish', 60000);
    return { authors: 6, decks: 25, viaRealApi: true };
  });

  await stage('onboarding_and_shelf', ['seed'], async () => {
    await open(member); await topicCheck(member, 'spanish'); await button(member, 'Сохранить интересы');
    await until(async () => (await profile()).body.selectedTopics.some(t => t.id === 'spanish'), 'selection not persisted');
    await until(async () => (await forYouCards(member)).length >= 4, 'For you shelf did not appear', 30000);
    need(await member.has('Потому что вы учите'), 'explanation missing');
    const answer = await recommendations(); need(answer.status === 200 && answer.body.rankVersion === 'recs-v1', 'rank version missing');
    const cards = answer.body.decks;
    need(cards.length >= 12, 'diverse fixture does not fill twelve recommendations');
    for (const key of ['ownerId', 'topic']) {
      const counts = new Map(); for (const card of cards.slice(0, 10)) { const value = key === 'topic' ? card.topic?.id : card[key]; counts.set(value, (counts.get(value) ?? 0) + 1); }
      need([...counts.values()].every(n => n <= 2), 'first10 diversity violated: ' + key);
    }
    need(!cards.some(c => c.code === world.own.code), 'own authored deck recommended');
    need(answer.body.explanationTopic?.id === 'spanish', 'explanation is not main topic');
    need(!JSON.stringify(answer.body).match(/"(?:score|rank|weight|interestWeight)":/u), 'private ranking numbers leaked');
    await shoot(member, 'desktop-for-you');
    return { first10Diverse: true, twelvePlaces: true, explanation: 'spanish', ownExcluded: true };
  });

  await stage('copy_and_attribution', ['onboarding_and_shelf'], async () => {
    const cards = await forYouCards(member); const chosen = cards[0]; need(chosen, 'no shelf card');
    await member.enter(`app-deck-shelf .title a[href="${chosen.href}"]`);
    await until(() => exists('app-public-deck-page', member.tab), 'public deck did not open');
    await button(member, 'Добавить к себе');
    await until(async () => /^\/decks\/[0-9a-f-]{36}$/u.test(await member.location()), 'copy did not reach own hub', 60000);
    world.copied = chosen.href.split('/')[2];
    await open(member); const answer = await recommendations();
    need(!answer.body.decks.some(c => c.code === world.copied), 'own copy returned in recommendations');
    need(answer.body.decks.length === 12, 'copy with unchanged interest weights did not refill a feasible twelve-card shelf');
    need((await forYouCards(member)).every(c => !c.href.includes('/' + world.copied + '/')), 'copied deck still in UI shelf');
    const writes = member.wire.filter(w => w.path === '/api/community/events' || w.path === '/api/community/exposures');
    need(writes.some(w => w.status === 204 || w.status === 200), 'no telemetry command accepted');
    need(writes.every(w => w.authorization), 'new telemetry routes lack bearer');
    const logged = await fixture('/__fixture/catalog-recs-summary');
    need(logged.events.some(row => row.version === 'recs-v1' && row.kind === 'DETAIL_VIEW' && row.count > 0), 'detail rank version not persisted');
    need(logged.events.some(row => row.version === 'recs-v1' && row.kind === 'FORK' && row.count > 0), 'fork did not inherit recommendation attribution');
    need(logged.exposures.some(row => row.version === 'recs-v1' && row.count > 0), 'anonymous recommendation exposures not persisted');
    need(logged.forbiddenEventColumns === 0, 'raw event schema contains query/content columns');
    return { existingCopyExcluded: true, realCopyButton: true, acceptedAuthenticatedTelemetry: true, loggedRankVersion: true, noQueryOrContent: true };
  });

  await stage('sorting_motion_reflow_a11y', ['copy_and_attribution'], async () => {
    await open(member, '/community?sort=for-you');
    await until(() => exists('app-deck-grid app-public-deck-card', member.tab), 'for-you sorting has no grid');
    need(member.lastRequest('/api/community/recommendations')?.authorization, 'recommendation request lacks bearer');
    need(await exists('[data-testid="recommendation-explanation"]', member.tab), 'For you grid lost its explanation');
    for (const [name, size] of [['mobile-320', MOBILE], ['zoom-200', { ...DESKTOP, width: 720, height: 450 }]]) {
      await open(member, '/community?sort=for-you', size); need(!(await member.overflow()), name + ' horizontally overflows'); await shoot(member, name);
    }
    await member.tab.call('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
    await open(member, '/community?sort=for-you', MOBILE);
    await until(() => exists('app-deck-grid app-public-deck-card', member.tab), 'reduced-motion grid missing');
    const styles = await member.page(`return {book:getComputedStyle(document.querySelector('app-book-search .spread')).animationName,cards:[...document.querySelectorAll('app-deck-grid .cell')].map(e=>getComputedStyle(e).animationName)};`);
    need(styles.book === 'none' && styles.cards.every(name=>name==='none'), 'book/card motion survives reduce');
    const animations = await member.page(`return document.getAnimations().filter(a=>a.effect?.target?.closest('app-community-page')).map(a=>({duration:a.effect.getTiming().duration,iterations:a.effect.getTiming().iterations,name:a.animationName??a.transitionProperty??'',target:a.effect.target.tagName+'.'+a.effect.target.className}));`);
    need(animations.every(value => Number(value.duration) <= 1 && value.iterations === 1), 'recommendation UI animates under reduced motion: ' + JSON.stringify(animations));
    const accessible = await member.page(`const missing=[...document.querySelectorAll('main button, main input')].filter(e=>!e.disabled&&e.getClientRects().length&&
      !(e.getAttribute('aria-label')||e.getAttribute('aria-labelledby')||e.labels?.length||e.textContent.trim()));
      return {missing:missing.length,h1:document.querySelectorAll('h1').length};`);
    need(accessible.missing === 0 && accessible.h1 === 1, 'accessible names or heading hierarchy broken');
    const focus = await member.tabTo(`e=>e.matches('app-public-deck-card .title a')`, 'a recommendation card');
    need(focus.focusVisible, 'keyboard card focus invisible');
    await shoot(member, 'mobile-reduced-motion');
    await member.tab.call('Emulation.setEmulatedMedia', { features: [] });
    return { width320: true, zoomEquivalent200: true, reducedMotion: true, namedControls: true, keyboardFocus: true, realScreenReader: false };
  });

  await stage('profile_reset', ['sorting_motion_reflow_a11y'], async () => {
    await open(member, '/profile', MOBILE);
    await until(() => member.has('Интересы'), 'settings interests missing');
    need(await member.has('Испанский'), 'saved interests not visible in profile');
    await button(member, 'Сбросить интересы');
    // Confirmation text is a separate action; the implementation uses this short label.
    await button(member, 'Да, сбросить интересы');
    await until(async () => { const p = (await profile()).body; return p.selectedTopics.length === 0 && p.inferredTopics.length === 0; }, 'reset did not clear profile');
    need((await recommendations()).body.decks.length === 0, 'reset recommendations survived');
    need(!(await member.overflow()), 'profile interests overflow at320');
    await shoot(member, 'profile-reset');
    await open(member, '/community', MOBILE);
    need((await forYouCards(member)).length === 0, 'For you remained after reset');
    need(!(await member.has('Что вы хотите изучать в Мнеме?')), 'reset nags with first-entry prompt');
    return { resetOldSignals: true, profileVisible: true, shelfHidden: true, noRepeatedOnboarding: true };
  });
  await stage('sparse', ['profile_reset'], async () => {
    const topic = world.leaves.find(t => t.id !== 'spanish');
    const matches = world.decks.filter(d => d.topic === topic.id);
    need(matches.length > 0 && matches.length < 4, 'sparse fixture must have one to three matching candidates');
    await open(member, '/profile', MOBILE);
    await button(member, 'Изменить интересы');
    await topicCheck(member, topic.id); await button(member, 'Сохранить интересы');
    await until(async () => (await profile()).body.selectedTopics.some(t => t.id === topic.id), 'sparse interest not saved');
    await open(member, '/community', MOBILE);
    need((await recommendations()).body.decks.length === 0, 'sparse interest pool produced a personal shelf');
    need((await forYouCards(member)).length === 0, 'sparse personal shelf not hidden in UI');
    need((await member.count('app-public-deck-card')) > 0, 'sparse personal shelf hid ordinary catalogue');
    await shoot(member, 'mobile-sparse');
    return { nonemptyCatalogue: true, matchingCandidates: matches.length, shelfHidden: true };
  });
  await guestSession.close().catch(() => {});
  for (const actor of [owner, member]) await navigate('/login', actor.tab);
  for (const actor of [owner, member]) await actor.metrics({ width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  record('recs_summary', { state: failures.length === 0 ? 'passed' : 'failed', failures: failures.length });
  return { failures };
}
