import journey from '../../../../../contracts/onboarding/journey.json';
import { ONBOARDING_TEXTS } from '../../core/onboarding/onboarding-texts';
import { QUEST_ROUTES, QuestRoute } from '../../core/onboarding/path-places';
import { QUEST_MILESTONE } from '../../core/onboarding/quest.models';
import { MILESTONE_KEYS } from '../../core/onboarding/path.models';
import { TOUR_ANCHOR_KEYS } from '../../core/onboarding/tour-anchor-keys';
import { tourDefinitionProblems } from '../../core/onboarding/tour-definition';
import { plainText } from '../../core/onboarding/tour-rich-text';
import { QUEST_EVENTS, QuestContext, questDefinition, questDefinitionProblems } from './quest-definitions';

const DECK = '22222222-2222-4222-8222-222222222222';
const own: QuestContext = { deckId: DECK, sample: false, memberKey: 'material-key' };
const sample: QuestContext = { deckId: DECK, sample: true, memberKey: null };

describe('the side quests (OB-304)', () => {
    const all: [QuestRoute, QuestContext][] = QUEST_ROUTES.flatMap((route): [QuestRoute, QuestContext][] =>
        route === 'QUEST_EXPLAIN' || route === 'QUEST_VOICE' ? [[route, own], [route, sample]] : [[route, own]]);

    it.each(all)('%s is a sound quest of two to four steps, client-only, under its own keys', (route, context) => {
        const definition = questDefinition(route, context);
        expect(questDefinitionProblems(definition, TOUR_ANCHOR_KEYS), route).toEqual([]);
        expect(definition.quest).toBe(true);
        expect(definition.branch).toBeNull();
        expect(definition.steps.length).toBeGreaterThanOrEqual(2);
        expect(definition.steps.length).toBeLessThanOrEqual(4);
        // Not the main journey: no key of the server's registry, and a definition of the journey must not pass for a quest.
        const registry = new Set(journey.steps.map(item => item.key));
        for (const item of definition.steps) expect(registry.has(item.key), item.key).toBe(false);
        expect(tourDefinitionProblems(definition).join(' ')).toContain('a quest is not a tour of the journey');
    });

    it.each(all)('%s says its steps in the quest texts, awaiting approval', (route, context) => {
        for (const item of questDefinition(route, context).steps) {
            expect(item.title.length, item.key).toBeGreaterThan(3);
            expect(item.body.kind === 'paragraphs' && item.body.paragraphs.every(paragraph => plainText(paragraph).length > 10), item.key).toBe(true);
            expect(`${item.title} ${item.body.kind === 'paragraphs' ? item.body.paragraphs.map(plainText).join(' ') : ''}`, item.key).not.toContain('!');
        }
        expect(Object.keys(ONBOARDING_TEXTS).filter(key => key.startsWith('quest.')).length).toBeGreaterThan(30);
    });

    it('keeps the journey\'s registry unchanged: no quest key in journey.json', () => {
        expect(journey.steps.some(item => item.key.startsWith('quest.'))).toBe(false);
    });

    it('ends every quest with its milestone: each route has one in the registry', () => {
        for (const route of QUEST_ROUTES) expect(MILESTONE_KEYS).toContain(QUEST_MILESTONE[route]);
        expect(QUEST_MILESTONE).toEqual({ QUEST_WORKSHOP: 'WORKSHOP_APPROVED', QUEST_VOICE: 'VOICE_ANSWER', QUEST_MEDIA: 'MEDIA_IN_MATERIAL', QUEST_EXPLAIN: 'AI_EXPLANATION' });
    });

    describe('the Workshop', () => {
        const steps = questDefinition('QUEST_WORKSHOP', own).steps;

        it('goes through the notes of the deck, the picking, the composer and the approval of one material', () => {
            expect(steps.map(item => item.anchor)).toEqual(['capture.notes', 'capture.assemble', 'own.material', 'own.material']);
            expect(steps[0].route).toBe(`/decks/${DECK}/capture`);
            expect(steps.map(item => (item.gate === 'next' ? 'next' : item.gate.action))).toEqual([QUEST_EVENTS.notesPicked, QUEST_EVENTS.notesAssembled, QUEST_EVENTS.workshopOpened, 'material.published']);
        });
    });

    describe('the voice', () => {
        it('shows its own screen about the microphone first, with no place on a page and nothing that records', () => {
            const [screen, next] = questDefinition('QUEST_VOICE', own).steps;
            expect(screen.screen).toBe(true);
            expect(screen.anchor).toBe('');
            expect(screen.gate).toBe('next');
            expect(screen.route).toBeUndefined();
            const text = screen.body.kind === 'paragraphs' ? screen.body.paragraphs.map(plainText).join(' ') : '';
            expect(text).toContain('Браузер сам спросит разрешение на микрофон');
            expect(text).toContain('Остановить запись');
            expect(text).toContain('не дольше минуты');
            // The page with the microphone is reached only by the step after it.
            expect(next.route).toBe(`/decks/${DECK}/capture`);
            expect(next.anchor).toBe('capture.mic');
        });

        it('ends on the transcription: the note field in a deck of the learner\'s own, the study in the sample deck', () => {
            const last = questDefinition('QUEST_VOICE', own).steps[1];
            expect(last.gate).toEqual({ action: QUEST_EVENTS.voiceTranscribed, label: 'Начать запись' });
            const demo = questDefinition('QUEST_VOICE', sample).steps[1];
            expect(demo.anchor).toBe('hub.start');
            expect(demo.route).toBe(`/decks/${DECK}`);
            expect(demo.completes).toBe(QUEST_EVENTS.voiceTranscribed);
            expect(demo.gate).toMatchObject({ action: 'study.started', pauses: true });
        });
    });

    describe('the picture or the sound', () => {
        it('opens the material in the editor, waits for a medium in the text and then for the publication', () => {
            const steps = questDefinition('QUEST_MEDIA', own).steps;
            expect(steps[0].route).toBe(`/decks/${DECK}/materials/material-key/edit`);
            expect(steps.map(item => item.anchor)).toEqual(['editor.media', 'editor.publish']);
            expect(steps.map(item => (item.gate === 'next' ? 'next' : item.gate.action))).toEqual([QUEST_EVENTS.mediaInserted, 'material.published']);
        });
    });

    describe('the explanation', () => {
        it('explains the check on a screen, then waits at «Учить» and ends when the AI has checked an answer', () => {
            const [screen, study] = questDefinition('QUEST_EXPLAIN', own).steps;
            expect(screen.screen).toBe(true);
            expect(study.anchor).toBe('hub.start');
            expect(study.route).toBe(`/decks/${DECK}`);
            expect(study.completes).toBe(QUEST_EVENTS.answerChecked);
            expect(study.gate).toMatchObject({ action: 'study.started', label: 'Учить', pauses: true });
            expect(questDefinition('QUEST_EXPLAIN', sample).steps.map(item => item.anchor)).toEqual(['', 'hub.start']);
        });
    });

    describe('the check of a definition', () => {
        const good = questDefinition('QUEST_MEDIA', own);

        it('names what is wrong: recording, step count, keys, anchors, repeats', () => {
            expect(questDefinitionProblems({ ...good, quest: false }, TOUR_ANCHOR_KEYS).join(' ')).toContain('would record into the journey');
            expect(questDefinitionProblems({ ...good, steps: good.steps.slice(0, 1) }, TOUR_ANCHOR_KEYS).join(' ')).toContain('two to four steps');
            expect(questDefinitionProblems({ ...good, steps: [...good.steps, ...good.steps, good.steps[0]] }, TOUR_ANCHOR_KEYS).join(' ')).toContain('two to four steps');
            expect(questDefinitionProblems({ ...good, branch: 'OWN' }, TOUR_ANCHOR_KEYS).join(' ')).toContain('no branch');
            expect(questDefinitionProblems({ ...good, steps: [{ ...good.steps[0], key: 'sample.hub' }, good.steps[1]] }, TOUR_ANCHOR_KEYS).join(' ')).toContain('not under the quest');
            expect(questDefinitionProblems({ ...good, steps: [{ ...good.steps[0], anchor: 'nowhere' }, good.steps[1]] }, TOUR_ANCHOR_KEYS).join(' ')).toContain('not in the registry');
            expect(questDefinitionProblems({ ...good, steps: [good.steps[0], good.steps[0]] }, TOUR_ANCHOR_KEYS).join(' ')).toContain('repeated step');
        });
    });
});
