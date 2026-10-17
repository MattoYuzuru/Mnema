import { PALETTE, SEMANTIC_PAIRS } from './styleguide.data';
import { TOUR_HOLD_MS } from '../shared/tour-card.component';
import { Rgb, blend, contrastGrade, contrastRatio, cssAlpha, parseCssColor } from './color-contrast';

interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

describe('color contrast helpers', () => {
    it('parses hex and rgb() colours and rejects the rest', () => {
        expect(parseCssColor('#fff')).toEqual([255, 255, 255]);
        expect(parseCssColor(' #281378 ')).toEqual([40, 19, 120]);
        expect(parseCssColor('rgb(1 2 3)')).toEqual([1, 2, 3]);
        expect(parseCssColor('rgba(1, 2, 3, .5)')).toEqual([1, 2, 3]);
        expect(parseCssColor('var(--x)')).toBeNull();
    });

    it('computes the WCAG ratio', () => {
        expect(contrastRatio([0, 0, 0], [255, 255, 255])).toBeCloseTo(21, 5);
        expect(contrastRatio([255, 255, 255], [255, 255, 255])).toBeCloseTo(1, 5);
    });

    it('keeps the Mnema text pairs at AA or better', () => {
        const ink = parseCssColor('#281378')!;
        const sheet = parseCssColor('#fbf8ef')!;
        const muted = parseCssColor('#625c70')!;
        expect(contrastGrade(contrastRatio(ink, sheet))).toBe('AAA');
        expect(contrastRatio(muted, sheet)).toBeGreaterThanOrEqual(4.5);
    });

    it('grades by threshold', () => {
        expect(contrastGrade(7)).toBe('AAA');
        expect(contrastGrade(4.5)).toBe('AA');
        expect(contrastGrade(3)).toBe('AA large');
        expect(contrastGrade(2.9)).toBe('fail');
    });
});

describe('alpha and blending', () => {
    it('reads the alpha of the modern and the legacy functional syntax', () => {
        expect(cssAlpha('rgb(22 12 69 / 74%)')).toBeCloseTo(.74, 5);
        expect(cssAlpha('rgb(22 12 69 / .5)')).toBeCloseTo(.5, 5);
        expect(cssAlpha('rgba(1, 2, 3, .25)')).toBeCloseTo(.25, 5);
        expect(cssAlpha('rgba(1, 2, 3, 40%)')).toBeCloseTo(.4, 5);
        expect(cssAlpha('#281378')).toBe(1);
        expect(cssAlpha('rgb(1 2 3)')).toBe(1);
    });

    it('blends a translucent colour over a background', () => {
        expect(blend([0, 0, 0], .5, [255, 255, 255])).toEqual([128, 128, 128]);
        expect(blend([10, 20, 30], 1, [255, 255, 255])).toEqual([10, 20, 30]);
        expect(blend([10, 20, 30], 0, [255, 255, 255])).toEqual([255, 255, 255]);
    });
});

/**
 * The night surface and the spotlight tour add tokens (design/prototype/onboarding/README.md, approved 2026-10-16). Every pair they
 * rely on is computed from the stylesheet itself, so a changed value that breaks the contrast fails here, not in review.
 */
describe('night surface and tour tokens (src/theme/tokens.css)', () => {
    let tokens = new Map<string, string>();
    const color = (name: string): Rgb => {
        const parsed = parseCssColor(tokens.get(name) ?? '');
        if (parsed === null) throw new Error(`${name} is not a colour in tokens.css`);
        return parsed;
    };
    const ratio = (foreground: string, background: string): number => contrastRatio(color(foreground), color(background));

    beforeAll(async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        const css = readFileSync(`${root}/src/theme/tokens.css`, 'utf8');
        tokens = new Map([...css.matchAll(/(--mn-[a-z-]+):\s*([^;]+);/gu)].map(match => [match[1], match[2].trim()]));
    });

    it('defines every token the tour and the night surface use', () => {
        for (const name of ['--mn-night', '--mn-on-night', '--mn-on-night-muted', '--mn-star-lavender', '--mn-star-paper', '--mn-scrim', '--mn-glow', '--mn-path-hover', '--mn-tour-hold']) {
            expect(tokens.has(name), name).toBe(true);
        }
    });

    it('holds for two seconds: the token and the gesture agree', () => {
        expect(tokens.get('--mn-tour-hold')).toBe(`${TOUR_HOLD_MS / 1000}s`);
    });

    it('keeps text on the night surface above 4.5:1, with the values the owner approved', () => {
        expect(ratio('--mn-on-night', '--mn-night')).toBeCloseTo(16.86, 1);
        expect(ratio('--mn-on-night-muted', '--mn-night')).toBeCloseTo(11.02, 1);
        expect(ratio('--mn-star-lavender', '--mn-night')).toBeCloseTo(8.30, 1);
        expect(ratio('--mn-star-paper', '--mn-night')).toBeGreaterThanOrEqual(4.5);
        for (const foreground of ['--mn-on-night', '--mn-on-night-muted', '--mn-star-lavender']) {
            expect(ratio(foreground, '--mn-night'), foreground).toBeGreaterThanOrEqual(4.5);
        }
    });

    it('keeps the ink readable on the paper plate of the night and on the lavender hold fill', () => {
        expect(ratio('--mn-ink', '--mn-on-night')).toBeCloseTo(13.60, 1);
        expect(ratio('--mn-ink', '--mn-path-hover')).toBeCloseTo(10.95, 1);
        expect(ratio('--mn-ink', '--mn-on-night')).toBeGreaterThanOrEqual(4.5);
        expect(ratio('--mn-ink', '--mn-path-hover')).toBeGreaterThanOrEqual(4.5);
    });

    it('keeps the card text, the hint on the card and the hold label readable on the sheet', () => {
        expect(ratio('--mn-muted', '--mn-sheet')).toBeCloseTo(6.02, 1);
        expect(ratio('--mn-body', '--mn-sheet')).toBeGreaterThanOrEqual(4.5);
        expect(ratio('--mn-ink', '--mn-sheet')).toBeGreaterThanOrEqual(4.5);
    });

    it('keeps the frame of the cut-out distinct (3:1, a non-text boundary) from the page seen through the veil', () => {
        const veil = tokens.get('--mn-scrim')!;
        const underVeil = blend(parseCssColor(veil)!, cssAlpha(veil), color('--mn-paper'));
        expect(contrastRatio(color('--mn-star-lavender'), underVeil)).toBeCloseTo(3.94, 1);
        expect(contrastRatio(color('--mn-star-lavender'), underVeil)).toBeGreaterThanOrEqual(3);
    });

    it('lists its tokens and its pairs in the styleguide', () => {
        const listed = new Set(PALETTE.flatMap(group => group.tokens.map(entry => entry.token)));
        for (const name of ['--mn-night', '--mn-on-night', '--mn-on-night-muted', '--mn-star-lavender', '--mn-star-paper', '--mn-scrim', '--mn-glow', '--mn-path-hover', '--mn-night-wave']) {
            expect(listed.has(name), name).toBe(true);
        }
        const pairs = SEMANTIC_PAIRS.map(pair => `${pair.foreground} on ${pair.background}`);
        for (const pair of ['--mn-on-night on --mn-night', '--mn-on-night-muted on --mn-night', '--mn-star-lavender on --mn-night', '--mn-ink on --mn-on-night', '--mn-ink on --mn-path-hover']) {
            expect(pairs, pair).toContain(pair);
        }
    });

    it('passes every pair the styleguide lists at its own threshold', () => {
        for (const pair of SEMANTIC_PAIRS) {
            if (!tokens.has(pair.foreground) || !tokens.has(pair.background)) continue;
            const threshold = pair.foreground === '--mn-field-border' ? 3 : 4.5;
            expect(ratio(pair.foreground, pair.background), `${pair.foreground} on ${pair.background}`).toBeGreaterThanOrEqual(threshold);
        }
    });
});
