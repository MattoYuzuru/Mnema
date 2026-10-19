import { ONBOARDING_TEXTS, OnboardingTextKey, REWARD_APPROVED_KEYS, UNLISTED_TEXT_KEYS, onboardingText } from './onboarding-texts';

interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

/** The strings are the owner's approved ones (design/prototype/onboarding/texts.js, 2026-10-16): a copy that drifts fails here. */
describe('onboarding texts', () => {
    const approved = new Map<string, string>();
    let prototypeScript = '';

    beforeAll(async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        const texts = readFileSync(`${root}/../design/prototype/onboarding/texts.js`, 'utf8');
        // An entry is `'key': 'text'`; the milestone lines of the prototype carry two entries each (title and benefit).
        for (const match of texts.matchAll(/'([\w.]+)':\s*'((?:[^'\\]|\\.)*)'/gu)) approved.set(match[1], match[2].replace(/\\'/gu, '\''));
        prototypeScript = readFileSync(`${root}/../design/prototype/onboarding/app.js`, 'utf8');
    });

    it('repeats every approved string verbatim', () => {
        const keys = Object.keys(ONBOARDING_TEXTS) as OnboardingTextKey[];
        expect(keys.length).toBeGreaterThan(10);
        for (const key of keys.filter(name => !UNLISTED_TEXT_KEYS.includes(name))) {
            expect(approved.has(key), `${key} is an approved text`).toBe(true);
            // The gift sentence is the approved one with the percent of the campaign in place of the approved 10.
            expect(ONBOARDING_TEXTS[key], key).toBe(key === 'reward.gift' ? approved.get(key)!.replace('−10', '−{percent}') : approved.get(key));
        }
        // The mini-slider's group name is in the prototype's markup, not in its list of texts.
        expect(prototypeScript).toContain(`aria-label="${ONBOARDING_TEXTS['tour.slider.label']}"`);
    });

    it('fills placeholders and leaves an unknown one visible', () => {
        expect(onboardingText('tour.counter', { n: 2, m: 4 })).toBe('Шаг 2 из 4');
        expect(onboardingText('tour.gate', { action: 'Добавить к себе' })).toBe('Нажмите «Добавить к себе», чтобы продолжить');
        expect(onboardingText('tour.counter', { n: 2 })).toBe('Шаг 2 из {m}');
        expect(onboardingText('tour.back')).toBe('Назад');
    });

    it('has the approved texts of «Путь» verbatim and lists the new ones for approval', () => {
        for (const key of ['path.title', 'path.intro', 'path.total', 'path.hide', 'path.replay', 'path.state.done', 'path.state.todo', 'path.state.partial', 'path.open',
            'path.new.stamp', 'path.go', 'path.error', 'path.retry', 'path.new', 'path.toast', 'hint.capture', 'hint.ok', 'm.CAPTURE_NOTE.partial', 'm.COMMUNITY_COPY.b'] as const) {
            expect(approved.get(key), key).toBe(ONBOARDING_TEXTS[key]);
            expect(UNLISTED_TEXT_KEYS, key).not.toContain(key);
        }
        const fresh = Object.keys(ONBOARDING_TEXTS).filter(key => !approved.has(key)).sort();
        expect(fresh).toEqual([...UNLISTED_TEXT_KEYS].sort());
        expect(fresh).toContain('path.toast.many');
        expect(fresh).toContain('hint.STUDY');
    });

    it('has the approved sentences of the rhythm verbatim, and lists every other rhythm string as awaiting approval (S4)', () => {
        for (const key of ['rhythm.day', 'rhythm.target', 'rhythm.met.toast', 'rhythm.profile.weeks'] as const) {
            expect(approved.get(key), key).toBe(ONBOARDING_TEXTS[key]);
            expect(UNLISTED_TEXT_KEYS, key).not.toContain(key);
        }
        const rhythmKeys = (Object.keys(ONBOARDING_TEXTS) as OnboardingTextKey[]).filter(key => key.startsWith('rhythm.') || key === 'hint.PROFILE_RHYTHM');
        const pending = rhythmKeys.filter(key => !approved.has(key));
        // 7 letters + 7 days of the sentence + «и» + 2 week sentences + 6 strings of the profile block + the hint of the place.
        expect(pending).toHaveLength(7 + 7 + 1 + 2 + 6 + 1);
        for (const key of pending) expect(UNLISTED_TEXT_KEYS, key).toContain(key);
    });

    it('has the approved texts of the reward verbatim (the gift with its percent as a placeholder) and lists every other reward string as awaiting approval (S4)', () => {
        for (const key of ['reward.eyebrow', 'reward.title', 'reward.terms', 'reward.action', 'reward.later'] as const) {
            expect(approved.get(key), key).toBe(ONBOARDING_TEXTS[key]);
            expect(UNLISTED_TEXT_KEYS, key).not.toContain(key);
        }
        expect(approved.get('reward.gift')).toBe('Ваш подарок: −10\u00a0% на подписку до {date}.');
        expect(ONBOARDING_TEXTS['reward.gift']).toBe('Ваш подарок: −{percent}\u00a0% на подписку до {date}.');
        expect([...REWARD_APPROVED_KEYS].sort()).toEqual(['reward.action', 'reward.eyebrow', 'reward.gift', 'reward.later', 'reward.terms', 'reward.title']);
        const pending = (Object.keys(ONBOARDING_TEXTS) as OnboardingTextKey[]).filter(key => key.startsWith('reward.') && !REWARD_APPROVED_KEYS.includes(key));
        // The gift of a discount for one plan and of a tier, the label, the copy button, its two results, the redeemed line and the notification.
        expect(pending).toHaveLength(8);
        for (const key of pending) expect(UNLISTED_TEXT_KEYS, key).toContain(key);
    });

    it('fills the sentences of the reward', () => {
        expect(onboardingText('reward.gift', { percent: 10, date: '29 октября, 21:41\u00a0МСК' })).toBe('Ваш подарок: −10\u00a0% на подписку до 29 октября, 21:41\u00a0МСК.');
        expect(onboardingText('reward.gift.tier', { plan: 'Plus', days: '30\u00a0дней', date: '29 октября, 21:41\u00a0МСК' })).toBe('Ваш подарок: Plus на 30\u00a0дней. Активировать до 29 октября, 21:41\u00a0МСК.');
    });

    it('says nothing of a countdown, a rush or a loss in any reward string', () => {
        for (const [key, text] of Object.entries(ONBOARDING_TEXTS).filter(([key]) => key.startsWith('reward.'))) {
            expect(text, key).not.toMatch(/осталось|успей|скорее|последн|спешите|торопитесь|упустите|потеря|только сегодня|\d+:\d+:\d+/iu);
        }
    });

    it('fills the sentences of the rhythm: the ordinal, the days, the toast and the weeks', () => {
        expect(onboardingText('rhythm.day', { ordinal: 'Второй' })).toBe('Второй день на этой неделе');
        expect(onboardingText('rhythm.target', { days: '5\u00a0дней' })).toBe('Цель — 5\u00a0дней в неделю');
        expect(onboardingText('rhythm.met.toast', { days: '3\u00a0дня' })).toBe('Цель недели выполнена: вы занимались 3\u00a0дня.');
        expect(onboardingText('rhythm.profile.weeks', { n: 6 })).toBe('Недель в ритме: 6');
    });

    it('says nothing of loss, debt or a streak in any rhythm string', () => {
        for (const [key, text] of Object.entries(ONBOARDING_TEXTS).filter(([key]) => key.startsWith('rhythm.') || key === 'hint.PROFILE_RHYTHM')) {
            expect(text, key).not.toMatch(/потер|долг|серия|стрик|огонь|не успел|отстал|штраф/iu);
        }
    });

    it('fills the toast of a milestone', () => {
        expect(onboardingText('path.toast', { event: onboardingText('path.event.MEDIA_IN_MATERIAL'), done: 6, total: 12 })).toBe('Путь: вы добавили в материал картинку или звук · 6 из 12');
        expect(onboardingText('path.go', { title: onboardingText('m.TWO_DAYS') })).toBe('Перейти: Позанимайтесь в другой день');
    });

    it('says what the finish button needs to say', () => {
        expect(ONBOARDING_TEXTS['tour.finish.armed']).toBe('Удерживайте 2\u00a0секунды, чтобы завершить знакомство');
        expect(ONBOARDING_TEXTS['tour.finish']).toBe('Завершить знакомство');
    });
});
