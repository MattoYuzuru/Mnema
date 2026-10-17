import { OnboardingView } from './onboarding.models';
import { onboardingText } from './onboarding-texts';

/** The problem code of `PUBLICATION`, access, invitation and copy requests for a sample deck or its template (`contracts/decks`). */
export const SAMPLE_NOT_SHAREABLE_CODE = 'SAMPLE_DECK_NOT_SHAREABLE';

/** Whether an answer is the refusal to publish, share or copy a sample deck: 409 with that code. */
export function isSampleNotShareable(status: number, code: string | null): boolean {
    return status === 409 && code === SAMPLE_NOT_SHAREABLE_CODE;
}

/** «Учебной колодой поделиться нельзя. Создайте свою — ею можно.»: said wherever a request for sharing was refused with that code. */
export function sampleShareRefusedText(): string {
    return onboardingText('sample.share.refused');
}

/** Whether `deckId` is the account's live sample copy, according to the view of the onboarding (not known before the view arrives). */
export function isSampleDeck(view: OnboardingView | null, deckId: string | null): boolean {
    const sample = view?.sampleDeck.deckId ?? null;
    return sample !== null && deckId !== null && sample.toLowerCase() === deckId.toLowerCase();
}

/**
 * What the sample deck says before an AI-checked attempt: how many of the free checks are left, or that they are spent and the next
 * check uses the learner's own AI budget. `null` outside the sample deck: any other deck says nothing of the kind.
 */
export function sampleCheckHint(view: OnboardingView | null, deckId: string | null): string | null {
    if (view === null || !isSampleDeck(view, deckId)) return null;
    const left = view.sampleDeck.freeChecksLeft;
    return left > 0 ? onboardingText('sample.ai.free', { n: left }) : onboardingText('sample.ai.spent');
}
