import { constellationFor } from './deck-constellation';

describe('deck constellation', () => {
    it('reproduces the same layout across visits and distinguishes identities', () => {
        const deck = 'cf97fcac-daac-4345-bc33-7c63876d905a';
        expect(constellationFor(deck)).toEqual(constellationFor(deck));
        expect(constellationFor(deck)).not.toEqual(constellationFor('815868eb-54c9-41d2-b00b-4b304f551784'));
    });

    it('keeps 3–7 stars per side in safe non-overlapping bands', () => {
        for (let seed = 0; seed < 100; seed++) {
            const stars = constellationFor(String(seed));
            for (const side of ['left', 'right']) {
                const rail = stars.filter(star => star.side === side);
                expect(rail.length).toBeGreaterThanOrEqual(3); expect(rail.length).toBeLessThanOrEqual(7);
                for (const star of rail) {
                    expect(star.x).toBeGreaterThanOrEqual(25); expect(star.x).toBeLessThan(75);
                    expect(star.y).toBeGreaterThan(10); expect(star.y).toBeLessThan(90);
                    expect(star.scale).toBeGreaterThanOrEqual(.85); expect(star.scale).toBeLessThan(1.15);
                    expect(Math.abs(star.rotation)).toBeLessThanOrEqual(15);
                }
            }
        }
    });
});
