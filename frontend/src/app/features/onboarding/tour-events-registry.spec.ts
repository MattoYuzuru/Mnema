import { QUEST_ROUTES } from '../../core/onboarding/path-places';
import { QUEST_EVENTS, questDefinition } from './quest-definitions';
import { TOUR_EVENTS, tourFor } from './tour-definitions';

interface NodeFs {
    readFileSync(path: string, encoding: 'utf8'): string;
    readdirSync(path: string): string[];
    statSync(path: string): { isDirectory(): boolean };
}

/**
 * The pages publish the gate events of the tours as string literals (`this.tour.emit('deck.copied', …)`), because a page must not import
 * the onboarding feature. This spec keeps the two sides together: every event a page emits is a gate or a signal of the flow, and every
 * gate of a definition is emitted by a real page.
 */
describe('tour events', () => {
    const emitted = new Map<string, string[]>();

    beforeAll(async () => {
        const fs = await import('node:fs' as string) as NodeFs;
        const root = `${(globalThis as unknown as { process: { cwd(): string } }).process.cwd()}/src/app`;
        const walk = (directory: string): void => {
            for (const name of fs.readdirSync(directory)) {
                const path = `${directory}/${name}`;
                if (fs.statSync(path).isDirectory()) { if (name !== 'styleguide') walk(path); continue; }
                if (!name.endsWith('.ts') || name.endsWith('.spec.ts') || name.endsWith('-test-data.ts')) continue;
                for (const match of fs.readFileSync(path, 'utf8').matchAll(/\bthis\.tour\.emit\('([^']+)'/gu)) {
                    emitted.set(match[1], [...(emitted.get(match[1]) ?? []), path.slice(root.length + 1)]);
                }
            }
        };
        walk(root);
    });

    const values = [...Object.values(TOUR_EVENTS), ...Object.values(QUEST_EVENTS)] as string[];

    it('has no page that emits an event the tours do not know', () => {
        expect([...emitted.keys()].filter(event => !values.includes(event))).toEqual([]);
    });

    it('has a page behind every event the tours wait for or hear', () => {
        expect(values.filter(event => !emitted.has(event))).toEqual([]);
    });

    it('has every gate of every definition emitted by a page', () => {
        const context = { experience: 'NEW' as const, aiAvailable: true, deckId: () => 'd', memberKey: () => 'm' };
        for (const branch of ['COMMUNITY', 'OWN', 'SAMPLE'] as const) {
            for (const step of tourFor(branch, context).steps) {
                if (step.gate !== 'next') expect(emitted.has(step.gate.action), `${branch}/${step.key}: ${step.gate.action}`).toBe(true);
            }
        }
    });

    it('has every gate and every completing event of every side quest emitted by a page', () => {
        for (const route of QUEST_ROUTES) {
            for (const sample of [false, true]) {
                for (const step of questDefinition(route, { deckId: 'd', sample, memberKey: 'm' }).steps) {
                    if (step.gate !== 'next') expect(emitted.has(step.gate.action), `${route}/${step.key}: ${step.gate.action}`).toBe(true);
                    if (step.completes !== undefined) expect(emitted.has(step.completes), `${route}/${step.key}: ${step.completes}`).toBe(true);
                }
            }
        }
    });

    it('names the right page for the events of the side quests', () => {
        const owner = (event: string): string => (emitted.get(event) ?? []).join(',');
        expect(owner(QUEST_EVENTS.notesPicked)).toContain('capture-page');
        expect(owner(QUEST_EVENTS.notesAssembled)).toContain('capture-page');
        expect(owner(QUEST_EVENTS.workshopOpened)).toContain('new-material-page');
        expect(owner(QUEST_EVENTS.mediaInserted)).toContain('item-editor-page');
        expect(owner(QUEST_EVENTS.voiceTranscribed)).toContain('mic-button');
        expect(owner(QUEST_EVENTS.answerChecked)).toContain('study-session-page');
    });

    it('names the right page for the main events', () => {
        const owner = (event: string): string => (emitted.get(event) ?? []).join(',');
        expect(owner(TOUR_EVENTS.deckOpened)).toContain('public-deck-page');
        expect(owner(TOUR_EVENTS.deckCopied)).toContain('public-deck-page');
        expect(owner(TOUR_EVENTS.deckCreated)).toContain('own-deck-create-page');
        expect(owner(TOUR_EVENTS.materialPublished)).toContain('item-editor-page');
        expect(owner(TOUR_EVENTS.materialPublished)).toContain('workshop-page');
        expect(owner(TOUR_EVENTS.exercisePublished)).toContain('exercise-authoring-page');
        // The session begins on the study page, not at the link that opens it.
        expect(owner(TOUR_EVENTS.studyStarted)).toContain('study-session-page');
        expect(owner(TOUR_EVENTS.studyCompleted)).toContain('study-session-page');
    });
});
