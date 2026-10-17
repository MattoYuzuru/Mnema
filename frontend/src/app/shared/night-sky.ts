import { seededRandom } from './seeded-random';

export interface NightStar {
    /** Percent of the surface, from its left and top edges. */
    readonly x: number;
    readonly y: number;
    /** Size in rem. */
    readonly size: number;
    /** Rotation in degrees. */
    readonly turn: number;
    readonly alpha: number;
    /** Seconds before the twinkle starts. */
    readonly delay: number;
    readonly tone: 'lavender' | 'paper';
    /** A small round point instead of the `✧` mark. */
    readonly dot: boolean;
    /** Twinkles three times, then rests (the brand contract allows no endless animation). */
    readonly twinkle: boolean;
    /** Stands in a side band beside the content: hidden on a phone, where only the top and bottom bands remain. */
    readonly side: boolean;
}

/** Share of the surface kept clear for the content: the centre column, between these percentages. */
const CLEAR = { left: 18, right: 82, top: 14, bottom: 86 } as const;
const ATTEMPTS = 2_000;

/** A stable decorative sky from a seed (the motif and the seeded generator of `app-deck-constellation`); no browser state, no network. */
export function skyFor(seed: string, count = 30): readonly NightStar[] {
    const random = seededRandom(seed);
    const stars: NightStar[] = [];
    for (let attempt = 0; stars.length < count && attempt < ATTEMPTS; attempt++) {
        const x = random() * 100;
        const y = random() * 100;
        const inCentre = x > CLEAR.left && x < CLEAR.right;
        const inBand = y > CLEAR.top && y < CLEAR.bottom;
        // The reading area stays clear: stars live in the top and bottom bands, and in the side bands beside the content.
        if (inCentre && inBand) continue;
        stars.push({
            x, y, size: round(1 + random() * 1.4, 2), turn: Math.round(random() * 30 - 15), alpha: round(.55 + random() * .4, 2),
            delay: round(random() * 3, 1), tone: random() < .5 ? 'lavender' : 'paper', dot: random() < .35, twinkle: random() < .4, side: inBand
        });
    }
    return stars;
}

function round(value: number, digits: number): number {
    const factor = 10 ** digits;
    return Math.round(value * factor) / factor;
}
