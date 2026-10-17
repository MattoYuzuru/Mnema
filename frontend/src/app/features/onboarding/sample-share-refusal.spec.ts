import { HttpErrorResponse } from '@angular/common/http';

import contract from '../../../../../contracts/onboarding/journey.json';
import { accessFailureOf } from '../own-decks/access/access.models';
import { accessProblemText } from '../own-decks/access/access.text';
import { forkFailureOf } from '../own-decks/fork/fork.models';
import { deckFailureMessage, failureOf } from '../own-decks/own-decks.store';
import { publicationFailureOf } from '../own-decks/publication/publication.models';
import { publicationFailureText } from '../own-decks/publication/publication.text';

const REFUSED = 'Учебной колодой поделиться нельзя. Создайте свою — ею можно.';
const answer = (status: number, code: string | null): HttpErrorResponse =>
    new HttpErrorResponse({ status, error: code === null ? null : { ...contract.problems.sampleNotShareable, status, code } });

/**
 * `contracts/decks`: publishing, sharing, inviting, approving a request and copying a sample deck are all 409 `SAMPLE_DECK_NOT_SHAREABLE`.
 * Wherever the client reads such an answer it says the one approved sentence, whichever request it was.
 */
describe('the refusal to share a sample deck', () => {
    const refusal = answer(409, 'SAMPLE_DECK_NOT_SHAREABLE');

    it('is said by the publication block and the access panel', () => {
        expect(publicationFailureText(publicationFailureOf(refusal))).toBe(REFUSED);
        expect(accessProblemText(accessFailureOf(refusal))).toBe(REFUSED);
    });

    it('is said by the hub for any request it makes', () => {
        expect(deckFailureMessage(failureOf(refusal))).toBe(REFUSED);
    });

    it('is its own kind of fork failure, for a copy of the template by its code', () => {
        expect(forkFailureOf(refusal).kind).toBe('sample');
        expect(forkFailureOf(answer(409, 'FORK_OWN_DECK')).kind).toBe('own-deck');
    });

    it('leaves every other 409 with its own words', () => {
        const other = answer(409, 'PUBLICATION_REQUIREMENTS');
        expect(publicationFailureText(publicationFailureOf(other))).not.toBe(REFUSED);
        expect(accessProblemText(accessFailureOf(answer(409, 'SOMETHING_ELSE')))).not.toBe(REFUSED);
        expect(deckFailureMessage(failureOf(answer(409, 'IDEMPOTENCY_CONFLICT')))).toBe('Это действие уже изменилось. Обновите страницу и попробуйте снова.');
        expect(forkFailureOf(answer(409, 'DECK_PREPARING')).kind).toBe('preparing');
    });

    it('is only a 409: the same code with another status is not the refusal', () => {
        expect(publicationFailureText(publicationFailureOf(answer(403, 'SAMPLE_DECK_NOT_SHAREABLE')))).not.toBe(REFUSED);
        expect(forkFailureOf(answer(403, 'SAMPLE_DECK_NOT_SHAREABLE')).kind).not.toBe('sample');
    });
});
