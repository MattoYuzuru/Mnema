import { TestBed } from '@angular/core/testing';

import { DURING_STUDY_STORAGE_KEY, NotificationPreferences } from './notification-preferences';

describe('NotificationPreferences', () => {
    beforeEach(() => localStorage.removeItem(DURING_STUDY_STORAGE_KEY));
    afterEach(() => localStorage.removeItem(DURING_STUDY_STORAGE_KEY));

    it('defaults to «в паузах» and persists the choice', () => {
        expect(TestBed.inject(NotificationPreferences).duringStudy()).toBe('AT_PAUSES');
        TestBed.inject(NotificationPreferences).setDuringStudy('BADGE_ONLY');
        expect(localStorage.getItem(DURING_STUDY_STORAGE_KEY)).toBe('BADGE_ONLY');

        TestBed.resetTestingModule();
        expect(TestBed.inject(NotificationPreferences).duringStudy()).toBe('BADGE_ONLY');
    });

    it('ignores a stored value it does not know', () => {
        localStorage.setItem(DURING_STUDY_STORAGE_KEY, 'whenever');
        expect(TestBed.inject(NotificationPreferences).duringStudy()).toBe('AT_PAUSES');
    });

    it('keeps working in memory when storage throws', () => {
        vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
        const preferences = TestBed.inject(NotificationPreferences);
        expect(preferences.duringStudy()).toBe('AT_PAUSES');
        preferences.setDuringStudy('IMMEDIATE');
        expect(preferences.duringStudy()).toBe('IMMEDIATE');
    });
});
