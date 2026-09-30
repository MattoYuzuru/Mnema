export interface ConstellationStar {
    readonly side: 'left' | 'right';
    readonly x: number;
    readonly y: number;
    readonly scale: number;
    readonly rotation: number;
}

const STAR_LAYOUT = { minCount: 3, maxCount: 7, edgeInset: 25, edgeSpan: 50, rotation: 15 } as const;

/** A stable decorative layout from an identity, with no browser state or network work. */
export function constellationFor(seed: string): readonly ConstellationStar[] {
    let state = 2166136261;
    for (const character of seed) state = Math.imul(state ^ character.codePointAt(0)!, 16777619);
    const random = () => {
        state += 0x6d2b79f5;
        let value = Math.imul(state ^ state >>> 15, 1 | state);
        value ^= value + Math.imul(value ^ value >>> 7, 61 | value);
        return ((value ^ value >>> 14) >>> 0) / 4294967296;
    };
    return (['left', 'right'] as const).flatMap(side => {
        const count = STAR_LAYOUT.minCount + Math.floor(random() * (STAR_LAYOUT.maxCount - STAR_LAYOUT.minCount + 1));
        // One star per vertical band prevents collisions, even at the maximum count.
        return Array.from({ length: count }, (_, index) => ({
            side, x: STAR_LAYOUT.edgeInset + random() * STAR_LAYOUT.edgeSpan,
            y: 10 + (index + .4 + random() * .2) / count * 80,
            scale: .85 + random() * .3, rotation: (random() * 2 - 1) * STAR_LAYOUT.rotation
        }));
    });
}
