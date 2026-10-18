import { QuestRoute } from './path-places';
import { MilestoneKey } from './path.models';

/** The milestone each side quest ends with (its notification and the re-read of «Путь» show it). */
export const QUEST_MILESTONE: Readonly<Record<QuestRoute, MilestoneKey>> = {
    QUEST_WORKSHOP: 'WORKSHOP_APPROVED', QUEST_VOICE: 'VOICE_ANSWER', QUEST_MEDIA: 'MEDIA_IN_MATERIAL', QUEST_EXPLAIN: 'AI_EXPLANATION'
};

/** What the learner sees while a quest is being prepared: reading the account, choosing a deck, checking the chosen deck, a refusal, a failure. */
export type QuestPhase = 'loading' | 'choose' | 'checking' | 'blocked' | 'error';

/** A deck the quest may run in; `sample` is the account's sample copy (a demonstration where the quest allows it). */
export interface QuestDeckOption {
    readonly deckId: string;
    readonly title: string;
    readonly sample: boolean;
}

/** What a refusal offers instead: the sample deck, or a link (a router address). */
export type QuestAction =
    | { readonly kind: 'sample'; readonly label: string; readonly deckId: string }
    | { readonly kind: 'link'; readonly label: string; readonly commands: readonly string[] };

/** Why a quest cannot start, said before the first step, with the way on. */
export interface QuestNotice {
    readonly text: string;
    readonly actions: readonly QuestAction[];
}
