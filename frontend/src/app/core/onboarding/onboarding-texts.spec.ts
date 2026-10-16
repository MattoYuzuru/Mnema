import { ONBOARDING_TEXTS, OnboardingTextKey, onboardingText } from './onboarding-texts';

interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

/** The strings are the owner's approved ones (design/prototype/onboarding/texts.js, 2026-10-16): a copy that drifts fails here. */
describe('onboarding texts', () => {
    const approved = new Map<string, string>();
    let prototypeScript = '';

    beforeAll(async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        const texts = readFileSync(`${root}/../design/prototype/onboarding/texts.js`, 'utf8');
        for (const match of texts.matchAll(/^\s*'([\w.]+)':\s*'((?:[^'\\]|\\.)*)',?\s*$/gmu)) approved.set(match[1], match[2].replace(/\\'/gu, '\''));
        prototypeScript = readFileSync(`${root}/../design/prototype/onboarding/app.js`, 'utf8');
    });

    it('repeats every approved string verbatim', () => {
        const keys = Object.keys(ONBOARDING_TEXTS) as OnboardingTextKey[];
        expect(keys.length).toBeGreaterThan(10);
        for (const key of keys.filter(name => name !== 'tour.slider.label')) {
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

    it('says what the finish button needs to say', () => {
        expect(ONBOARDING_TEXTS['tour.finish.armed']).toBe('Удерживайте 2\u00a0секунды, чтобы завершить знакомство');
        expect(ONBOARDING_TEXTS['tour.finish']).toBe('Завершить знакомство');
    });
});
