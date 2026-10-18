import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { QuestRoute } from '../../core/onboarding/path-places';
import { TOUR_ANCHORS } from '../../core/onboarding/tour-anchor-keys';
import { paragraphs } from '../../core/onboarding/tour-rich-text';
import { QuestStepKey, TourDefinition, TourStep } from '../../core/onboarding/tour.models';

/** The events the pages publish for the gates of the quests (besides the ones of the main tour: `material.published`, `study.started`). */
export const QUEST_EVENTS = {
    notesPicked: 'capture.selected',
    notesAssembled: 'capture.assembled',
    workshopOpened: 'workshop.opened',
    mediaInserted: 'media.inserted',
    voiceTranscribed: 'voice.transcribed',
    answerChecked: 'study.ai-checked'
} as const;

/** What a quest needs to know about where it runs. */
export interface QuestContext {
    readonly deckId: string;
    /** The deck is the account's sample copy (a demonstration of the explanation or the voice). */
    readonly sample: boolean;
    /** The material the editor opens, for the quest of the picture. */
    readonly memberKey: string | null;
}

/** The label of the study button: the quest quotes the real control, like the main tour does. */
const STUDY_LABEL = 'Учить';

const step = (key: QuestStepKey, anchor: string, name: string, extra: Partial<TourStep> & Pick<TourStep, 'gate'>, ...body: readonly string[]): TourStep => ({
    key, anchor, title: onboardingText(`${name}.title` as 'quest.workshop.pick.title'), body: paragraphs(...body), ...extra
});
const text = (key: Parameters<typeof onboardingText>[0]): string => onboardingText(key);

/**
 * The side quests (OB-304): short tours of the same engine, started from a chevron of «Путь». Client-only: the definitions carry `quest`,
 * so nothing of them reaches `PUT /api/onboarding/journey`, and their keys (`quest.*`) are outside the registry of the server's steps.
 * Each ends with the thing the milestone counts (an event the page publishes, or `completes` where no card can be drawn) and has at most
 * four steps.
 */
export function questDefinition(route: QuestRoute, context: QuestContext): TourDefinition {
    const deck = `/decks/${context.deckId}`;
    switch (route) {
        case 'QUEST_WORKSHOP': return quest('workshop', [
            step('quest.workshop.pick', TOUR_ANCHORS.captureNotes, 'quest.workshop.pick', { route: `${deck}/capture`, gate: { action: QUEST_EVENTS.notesPicked, label: null } }, text('quest.workshop.pick.body')),
            step('quest.workshop.assemble', TOUR_ANCHORS.captureAssemble, 'quest.workshop.assemble', { gate: { action: QUEST_EVENTS.notesAssembled, label: 'Создать материалы с ИИ' } }, text('quest.workshop.assemble.body')),
            step('quest.workshop.compose', TOUR_ANCHORS.ownMaterial, 'quest.workshop.compose', { gate: { action: QUEST_EVENTS.workshopOpened, label: null } }, text('quest.workshop.compose.body')),
            step('quest.workshop.approve', TOUR_ANCHORS.ownMaterial, 'quest.workshop.approve', { gate: { action: 'material.published', label: null } }, text('quest.workshop.approve.body'))
        ]);
        case 'QUEST_VOICE': return quest('voice', [
            { ...step('quest.voice.mic', '', 'quest.voice.mic', { gate: 'next' }, text('quest.voice.mic.body1'), text('quest.voice.mic.body2'), text('quest.voice.mic.body3')), screen: true },
            context.sample
                ? step('quest.voice.study', TOUR_ANCHORS.hubStart, 'quest.voice.study', { route: deck, completes: QUEST_EVENTS.voiceTranscribed, gate: { action: 'study.started', label: STUDY_LABEL, pauses: true } }, text('quest.voice.study.body'))
                : step('quest.voice.record', TOUR_ANCHORS.captureMic, 'quest.voice.record', { route: `${deck}/capture`, gate: { action: QUEST_EVENTS.voiceTranscribed, label: 'Начать запись' } }, text('quest.voice.record.body'))
        ]);
        case 'QUEST_MEDIA': return quest('media', [
            step('quest.media.insert', TOUR_ANCHORS.editorMedia, 'quest.media.insert', { route: `${deck}/materials/${context.memberKey ?? ''}/edit`, gate: { action: QUEST_EVENTS.mediaInserted, label: null } }, text('quest.media.insert.body')),
            step('quest.media.publish', TOUR_ANCHORS.editorPublish, 'quest.media.publish', { gate: { action: 'material.published', label: 'Опубликовать' } }, text('quest.media.publish.body'))
        ]);
        case 'QUEST_EXPLAIN': return quest('explain', [
            { ...step('quest.explain.what', '', 'quest.explain.what', { gate: 'next' }, text('quest.explain.what.body1'), text('quest.explain.what.body2')), screen: true },
            step('quest.explain.study', TOUR_ANCHORS.hubStart, 'quest.explain.study', { route: deck, completes: QUEST_EVENTS.answerChecked, gate: { action: 'study.started', label: STUDY_LABEL, pauses: true } }, text('quest.explain.study.body'))
        ]);
    }
}

function quest(name: string, steps: readonly TourStep[]): TourDefinition {
    return { id: `quest.${name}`, branch: null, steps, quest: true };
}

/** What is wrong with a quest definition, as sentences (empty: it is sound): not client-only, too many steps, a key outside `quest.*`, a missing anchor. */
export function questDefinitionProblems(definition: TourDefinition, anchors: readonly string[]): readonly string[] {
    const problems: string[] = [];
    if (definition.quest !== true) problems.push(`${definition.id}: not marked as a quest, so it would record into the journey`);
    if (definition.steps.length < 2 || definition.steps.length > 4) problems.push(`${definition.id}: a quest has two to four steps`);
    if (definition.branch !== null) problems.push(`${definition.id}: a quest belongs to no branch`);
    const seen = new Set<string>();
    for (const item of definition.steps) {
        const where = `${definition.id}/${item.key}`;
        if (!item.key.startsWith(`${definition.id}.`)) problems.push(`${where}: the key is not under the quest's id`);
        if (seen.has(item.key)) problems.push(`${where}: repeated step`);
        seen.add(item.key);
        if (item.screen !== true && !anchors.includes(item.anchor)) problems.push(`${where}: anchor ${item.anchor} is not in the registry`);
        if (item.screen === true && item.anchor !== '') problems.push(`${where}: a screen has no anchor`);
        if (item.title.trim().length === 0) problems.push(`${where}: no title`);
        if (item.external === true) problems.push(`${where}: a quest has no external steps`);
    }
    return problems;
}
