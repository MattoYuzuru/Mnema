import { PreferencesService } from './preferences.service';

describe('PreferencesService', () => {
    const storageKey = 'mnema_preferences';

    afterEach(() => {
        localStorage.removeItem(storageKey);
    });

    it('loads defaults when storage is empty', () => {
        const service = new PreferencesService();

        expect(service.hideFieldLabels).toBeFalse();
        expect(service.showFrontSideAfterFlip).toBeTrue();
        expect(service.autoPlayCardAudioSequence).toBeFalse();
        expect(service.mobileReviewButtonsMode).toBe('swipe-column');
        expect(service.mobileReviewButtonsSide).toBe('left');
    });

    it('sanitizes invalid persisted enum values', () => {
        localStorage.setItem(storageKey, JSON.stringify({
            hideFieldLabels: true,
            mobileReviewButtonsMode: 'invalid',
            mobileReviewButtonsSide: 'wrong'
        }));

        const service = new PreferencesService();

        expect(service.hideFieldLabels).toBeTrue();
        expect(service.mobileReviewButtonsMode).toBe('classic');
        expect(service.mobileReviewButtonsSide).toBe('left');
    });

    it('persists updates', () => {
        const service = new PreferencesService();

        service.setAutoPlayCardAudioSequence(true);
        service.setMobileReviewButtonsSide('right');

        const saved = JSON.parse(localStorage.getItem(storageKey) ?? '{}') as Record<string, unknown>;
        expect(saved['autoPlayCardAudioSequence']).toBeTrue();
        expect(saved['mobileReviewButtonsSide']).toBe('right');
    });
});
