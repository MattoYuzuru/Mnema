import { seededRandom } from './seeded-random';

export interface ConstellationStar {
    readonly side: 'left' | 'right';
    readonly x: number;
    readonly y: number;
    readonly scale: number;
    readonly rotation: number;
}

const STAR_LAYOUT = { minCount: 3, maxCount: 7, separation: .12, attempts: 512, rotation: 15 } as const;

/** A stable decorative layout from an identity, with no browser state or network work. */
export function constellationFor(seed: string): readonly ConstellationStar[] {
    const random = seededRandom(seed);
    return (['left', 'right'] as const).flatMap(side => {
        const count = STAR_LAYOUT.minCount + Math.floor(random() * (STAR_LAYOUT.maxCount - STAR_LAYOUT.minCount + 1));
        const result: ConstellationStar[] = [];
        const add = (y: number) => result.push({ side, x: random() * 100, y: y * 100,
            scale: .85 + random() * .3, rotation: (random() * 2 - 1) * STAR_LAYOUT.rotation });
        // Anchor both ends, then sample the whole rail rather than fixed vertical bands.
        add(.02 + random() * .08);
        add(.9 + random() * .08);
        for (let attempt = 0; result.length < count && attempt < STAR_LAYOUT.attempts; attempt++) {
            const y = .02 + random() * .96;
            // At the minimum 48rem page height this clears even a rotated 1.15× star.
            if (result.every(star => Math.abs(star.y / 100 - y) >= STAR_LAYOUT.separation)) add(y);
        }
        return result;
    });
}
