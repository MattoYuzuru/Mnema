import { contrastGrade, contrastRatio, parseCssColor } from './color-contrast';

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
