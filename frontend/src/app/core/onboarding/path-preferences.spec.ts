import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { AuthService } from '../../auth.service';
import { PATH_HIDDEN_STORAGE_PREFIX, PATH_NOTICE_STORAGE_PREFIX, PathPreferences } from './path-preferences';

describe('PathPreferences', () => {
    const user = signal<{ accountId: string } | null>({ accountId: 'acc-1' });
    let preferences: PathPreferences;

    beforeEach(() => {
        localStorage.clear();
        user.set({ accountId: 'acc-1' });
        TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { user } }] });
        preferences = TestBed.inject(PathPreferences);
    });
    afterEach(() => localStorage.clear());

    it('starts shown, with the notice not yet seen', () => {
        expect(preferences.hidden()).toBe(false);
        expect(preferences.noticeSeen()).toBe(false);
    });

    it('keeps «Скрыть «Путь»» per account in the storage of this browser', () => {
        preferences.setHidden(true);
        expect(preferences.hidden()).toBe(true);
        expect(localStorage.getItem(`${PATH_HIDDEN_STORAGE_PREFIX}acc-1`)).toBe('1');
        user.set({ accountId: 'acc-2' });
        expect(preferences.hidden()).toBe(false);
        user.set({ accountId: 'acc-1' });
        expect(preferences.hidden()).toBe(true);
        preferences.setHidden(false);
        expect(preferences.hidden()).toBe(false);
        expect(localStorage.getItem(`${PATH_HIDDEN_STORAGE_PREFIX}acc-1`)).toBeNull();
    });

    it('reads what an earlier visit stored', () => {
        localStorage.setItem(`${PATH_HIDDEN_STORAGE_PREFIX}acc-1`, '1');
        localStorage.setItem(`${PATH_NOTICE_STORAGE_PREFIX}acc-1`, '1');
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { user } }] });
        const later = TestBed.inject(PathPreferences);
        expect(later.hidden()).toBe(true);
        expect(later.noticeSeen()).toBe(true);
    });

    it('marks the notice seen once per account', () => {
        preferences.markNoticeSeen();
        expect(preferences.noticeSeen()).toBe(true);
        expect(localStorage.getItem(`${PATH_NOTICE_STORAGE_PREFIX}acc-1`)).toBe('1');
        user.set({ accountId: 'acc-2' });
        expect(preferences.noticeSeen()).toBe(false);
    });

    it('has nothing to keep for nobody signed in', () => {
        user.set(null);
        preferences.setHidden(true);
        preferences.markNoticeSeen();
        expect(preferences.hidden()).toBe(false);
        expect(localStorage.length).toBe(0);
    });

    it('lasts for the page when the storage throws, and shows the default when it cannot be read', () => {
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
        vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
        vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('blocked'); });
        expect(preferences.hidden()).toBe(false);
        preferences.setHidden(true);
        preferences.markNoticeSeen();
        expect(preferences.hidden()).toBe(true);
        expect(preferences.noticeSeen()).toBe(true);
        preferences.setHidden(false);
        expect(preferences.hidden()).toBe(false);
    });
});
