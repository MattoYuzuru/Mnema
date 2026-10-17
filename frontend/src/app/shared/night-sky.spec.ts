import { skyFor } from './night-sky';

describe('skyFor', () => {
    it('is the same sky for the same seed and another for another seed', () => {
        expect(skyFor('welcome')).toEqual(skyFor('welcome'));
        expect(skyFor('welcome')).not.toEqual(skyFor('congratulations'));
    });

    it('has the stars asked for, all inside the surface', () => {
        const sky = skyFor('welcome', 30);
        expect(sky).toHaveLength(30);
        for (const star of sky) {
            expect(star.x).toBeGreaterThanOrEqual(0);
            expect(star.x).toBeLessThanOrEqual(100);
            expect(star.y).toBeGreaterThanOrEqual(0);
            expect(star.y).toBeLessThanOrEqual(100);
            expect(star.alpha).toBeGreaterThanOrEqual(.55);
            expect(star.alpha).toBeLessThanOrEqual(.95);
            expect(star.size).toBeGreaterThanOrEqual(1);
            expect(star.size).toBeLessThanOrEqual(2.4);
        }
    });

    it('keeps the reading area clear: no star in the centre column between the top and bottom bands', () => {
        for (const seed of ['a', 'b', 'c', 'welcome', 'congratulations']) {
            for (const star of skyFor(seed, 40)) {
                const inCentre = star.x > 18 && star.x < 82;
                const inBand = star.y > 14 && star.y < 86;
                expect(inCentre && inBand, `${seed} ${star.x},${star.y}`).toBe(false);
            }
        }
    });

    it('marks the stars of the side bands, which a phone hides', () => {
        const sky = skyFor('welcome', 40);
        for (const star of sky) expect(star.side).toBe(star.y > 14 && star.y < 86);
        expect(sky.some(star => star.side)).toBe(true);
        expect(sky.some(star => !star.side)).toBe(true);
    });

    it('draws both tones and both kinds, and some stars twinkle', () => {
        const sky = skyFor('welcome', 60);
        expect(new Set(sky.map(star => star.tone))).toEqual(new Set(['lavender', 'paper']));
        expect(new Set(sky.map(star => star.dot))).toEqual(new Set([true, false]));
        expect(sky.some(star => star.twinkle)).toBe(true);
        expect(sky.some(star => !star.twinkle)).toBe(true);
    });

    it('stops when it cannot place more instead of looping', () => {
        expect(skyFor('welcome', 100_000).length).toBeLessThan(100_000);
    });
});
