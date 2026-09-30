/** Small deterministic generator for decoration only; never use for security decisions. */
export function seededRandom(seed: string): () => number {
    let state = 2166136261;
    for (const character of seed) state = Math.imul(state ^ character.codePointAt(0)!, 16777619);
    return () => {
        state += 0x6d2b79f5;
        let value = Math.imul(state ^ state >>> 15, 1 | state);
        value ^= value + Math.imul(value ^ value >>> 7, 61 | value);
        return ((value ^ value >>> 14) >>> 0) / 4294967296;
    };
}
