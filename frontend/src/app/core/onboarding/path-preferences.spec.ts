import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { AuthService } from '../../auth.service';
import { PATH_HIDDEN_STORAGE_PREFIX, PATH_NOTICE_STORAGE_PREFIX, PATH_REWARD_STORAGE_PREFIX, PathPreferences } from './path-preferences';

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

    describe('the congratulation of the reward', () => {
        const issued = '2026-10-22T18:41:00Z';

        it('is not shown yet, then shown once per account and per reward, in the storage of this browser', () => {
            expect(preferences.rewardShown(issued)).toBe(false);
            preferences.markRewardShown(issued);
            expect(preferences.rewardShown(issued)).toBe(true);
            expect(localStorage.getItem(`${PATH_REWARD_STORAGE_PREFIX}acc-1`)).toBe(issued);
            // Another reward (a later issue) is not the one that was shown; another account starts clean.
            expect(preferences.rewardShown('2026-11-01T10:00:00Z')).toBe(false);
            user.set({ accountId: 'acc-2' });
            expect(preferences.rewardShown(issued)).toBe(false);
            user.set({ accountId: 'acc-1' });
            expect(preferences.rewardShown(issued)).toBe(true);
        });

        it('reads what an earlier visit stored', () => {
            localStorage.setItem(`${PATH_REWARD_STORAGE_PREFIX}acc-1`, issued);
            TestBed.resetTestingModule();
            TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { user } }] });
            expect(TestBed.inject(PathPreferences).rewardShown(issued)).toBe(true);
        });

        it('lasts for the page when the storage throws, and is shown again in a page that cannot read it', () => {
            vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
            vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
            expect(preferences.rewardShown(issued)).toBe(false);
            preferences.markRewardShown(issued);
            expect(preferences.rewardShown(issued)).toBe(true);
        });

        it('has nothing to show to nobody signed in', () => {
            user.set(null);
            expect(preferences.rewardShown(issued)).toBe(true);
            preferences.markRewardShown(issued);
            expect(localStorage.length).toBe(0);
        });
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
