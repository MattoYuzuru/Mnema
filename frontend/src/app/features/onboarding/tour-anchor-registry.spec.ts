import { TOUR_ANCHORS, TOUR_ANCHOR_KEYS } from '../../core/onboarding/tour-anchor-keys';

interface NodeFs {
    readFileSync(path: string, encoding: 'utf8'): string;
    readdirSync(path: string): string[];
    statSync(path: string): { isDirectory(): boolean };
}

/**
 * The central anchor list against the pages: every key a component mounts with `mnTourAnchor` is in the list (so a definition can name
 * it), and every key in the list is mounted by a real component outside the styleguide (so a tour never points at nothing).
 */
describe('anchor registry', () => {
    const mounted = new Map<string, string[]>();

    beforeAll(async () => {
        const fs = await import('node:fs' as string) as NodeFs;
        const root = `${(globalThis as unknown as { process: { cwd(): string } }).process.cwd()}/src/app`;
        const walk = (directory: string): void => {
            for (const name of fs.readdirSync(directory)) {
                const path = `${directory}/${name}`;
                if (fs.statSync(path).isDirectory()) { if (name !== 'styleguide') walk(path); continue; }
                if (!/\.(?:html|ts)$/u.test(name) || name.endsWith('.spec.ts')) continue;
                for (const match of fs.readFileSync(path, 'utf8').matchAll(/mnTourAnchor="([^"]+)"/gu)) {
                    mounted.set(match[1], [...(mounted.get(match[1]) ?? []), path.slice(root.length + 1)]);
                }
            }
        };
        walk(root);
    });

    it('lists every key a component mounts', () => {
        expect([...mounted.keys()].filter(key => !(TOUR_ANCHOR_KEYS as readonly string[]).includes(key))).toEqual([]);
    });

    it('has a real component behind every key of the list', () => {
        expect(TOUR_ANCHOR_KEYS.filter(key => !mounted.has(key))).toEqual([]);
    });

    it('mounts the main anchors on the pages that own them', () => {
        const owner = (key: string): string => (mounted.get(key) ?? []).join(',');
        expect(owner(TOUR_ANCHORS.communitySearch)).toContain('community-page');
        expect(owner(TOUR_ANCHORS.communityDeck)).toContain('public-deck-page');
        expect(owner(TOUR_ANCHORS.communityAdd)).toContain('public-deck-page');
        expect(owner(TOUR_ANCHORS.ownDetails)).toContain('own-deck-create-page');
        expect(owner(TOUR_ANCHORS.ownMaterial)).toContain('new-material-page');
        expect(owner(TOUR_ANCHORS.ownExercise)).toContain('exercise-authoring-page');
        expect(owner(TOUR_ANCHORS.hubStart)).toContain('own-deck-detail-page');
    });

    it('has no repeated key or value', () => {
        expect(new Set(TOUR_ANCHOR_KEYS).size).toBe(TOUR_ANCHOR_KEYS.length);
        expect(Object.keys(TOUR_ANCHORS)).toHaveLength(TOUR_ANCHOR_KEYS.length);
    });
});
