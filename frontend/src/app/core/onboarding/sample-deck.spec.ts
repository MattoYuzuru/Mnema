import contract from '../../../../../contracts/onboarding/journey.json';
import { OnboardingView } from './onboarding.models';
import { isSampleDeck, isSampleNotShareable, sampleCheckHint, sampleShareRefusedText } from './sample-deck';

const ready = contract.views.sampleReady as OnboardingView;
const SAMPLE = ready.sampleDeck.deckId!;
const withChecks = (freeChecksLeft: number): OnboardingView => ({ ...ready, sampleDeck: { ...ready.sampleDeck, freeChecksLeft } });

describe('the sample deck of the onboarding', () => {
    describe('who is the sample deck', () => {
        it('is the live copy the view names, whatever the case of the id, and no other deck', () => {
            expect(isSampleDeck(ready, SAMPLE)).toBe(true);
            expect(isSampleDeck(ready, SAMPLE.toUpperCase())).toBe(true);
            expect(isSampleDeck(ready, '44444444-4444-4444-8444-444444444444')).toBe(false);
        });

        it('is nobody before the view arrives, when there is no copy, or for an unknown deck', () => {
            expect(isSampleDeck(null, SAMPLE)).toBe(false);
            expect(isSampleDeck(contract.views.offered as OnboardingView, SAMPLE)).toBe(false);
            expect(isSampleDeck(ready, null)).toBe(false);
        });
    });

    describe('the refusal to share', () => {
        it('is 409 with the code of the contract, and nothing else', () => {
            const { status, code } = contract.problems.sampleNotShareable;
            expect(isSampleNotShareable(status, code)).toBe(true);
            expect(isSampleNotShareable(403, code)).toBe(false);
            expect(isSampleNotShareable(409, 'PUBLICATION_REQUIREMENTS')).toBe(false);
            expect(isSampleNotShareable(409, null)).toBe(false);
        });

        it('says the approved sentence', () => {
            expect(sampleShareRefusedText()).toBe('Учебной колодой поделиться нельзя. Создайте свою — ею можно.');
        });
    });

    describe('the hint before an AI check', () => {
        it.each([[3], [2], [1]])('says how many of the free checks are left: %i', left => {
            expect(sampleCheckHint(withChecks(left), SAMPLE)).toBe(`В учебной колоде 3 проверки ИИ бесплатны. Осталось: ${left}.`);
        });

        it('says they are spent, before the attempt, when none is left', () => {
            expect(sampleCheckHint(withChecks(0), SAMPLE)).toBe('Бесплатные проверки в учебной колоде закончились. Дальше проверка расходует ваш ИИ-бюджет.');
        });

        it('says nothing outside the sample deck, or without the view', () => {
            expect(sampleCheckHint(withChecks(2), '44444444-4444-4444-8444-444444444444')).toBeNull();
            expect(sampleCheckHint(withChecks(0), '44444444-4444-4444-8444-444444444444')).toBeNull();
            expect(sampleCheckHint(null, SAMPLE)).toBeNull();
            expect(sampleCheckHint(contract.views.offered as OnboardingView, SAMPLE)).toBeNull();
        });
    });
});
