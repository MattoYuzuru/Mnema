import { ONBOARDING_TEXTS, OnboardingTextKey, UNLISTED_TEXT_KEYS, onboardingText } from './onboarding-texts';

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
            expect(ONBOARDING_TEXTS[key], key).toBe(approved.get(key));
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

    it('fills the toast of a milestone', () => {
        expect(onboardingText('path.toast', { event: onboardingText('path.event.MEDIA_IN_MATERIAL'), done: 6, total: 12 })).toBe('Путь: вы добавили в материал картинку или звук · 6 из 12');
        expect(onboardingText('path.go', { title: onboardingText('m.TWO_DAYS') })).toBe('Перейти: Позанимайтесь в другой день');
    });

    it('says what the finish button needs to say', () => {
        expect(ONBOARDING_TEXTS['tour.finish.armed']).toBe('Удерживайте 2\u00a0секунды, чтобы завершить знакомство');
        expect(ONBOARDING_TEXTS['tour.finish']).toBe('Завершить знакомство');
    });
});
