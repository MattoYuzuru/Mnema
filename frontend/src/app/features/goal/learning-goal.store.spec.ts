import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { LearningGoalStore } from './learning-goal.store';
import { LearningProfileApiService } from './learning-profile-api.service';

describe('LearningGoalStore', () => {
    let api: SpyObj<LearningProfileApiService>;
    let store: LearningGoalStore;

    beforeEach(() => {
        api = spyObj<LearningProfileApiService>({ load: vi.fn().mockName('load'), answer: vi.fn().mockName('answer') });
        TestBed.configureTestingModule({ providers: [{ provide: LearningProfileApiService, useValue: api }] });
        store = TestBed.inject(LearningGoalStore);
    });

    it('needs no API until something loads', () => {
        expect(store.goal()).toBeNull();
        expect(store.state()).toBe('idle');
        expect(api.load).not.toHaveBeenCalled();
    });

    it('loads once and joins a load in flight', async () => {
        api.load.mockReturnValue(of({ goal: 'EXAMS', skipped: false, answeredAt: '2026-10-05T09:00:00Z' }));
        await Promise.all([store.load(), store.load()]);
        await store.load();
        expect(api.load).toHaveBeenCalledTimes(1);
        expect(store.goal()).toBe('EXAMS');
        expect(store.answered()).toBe(true);
        expect(store.state()).toBe('ready');
    });

    it('asks while the profile is unanswered and fails soft', async () => {
        api.load.mockReturnValueOnce(throwError(() => new Error('down')));
        await store.load();
        expect(store.state()).toBe('error');
        api.load.mockReturnValue(of({ goal: null, skipped: false, answeredAt: null }));
        await store.load();
        expect(store.state()).toBe('ready');
        expect(store.answered()).toBe(false);
    });

    it('stores a goal and a skip, and reports a failure', async () => {
        api.answer.mockReturnValueOnce(of({ goal: 'SELF', skipped: false, answeredAt: '2026-10-05T09:00:00Z' }));
        expect(await store.answer('SELF')).toBe(true);
        expect(store.goal()).toBe('SELF');
        expect(store.answered()).toBe(true);
        api.answer.mockReturnValueOnce(throwError(() => new Error('down')));
        expect(await store.answer(null)).toBe(false);
        expect(store.saveFailed()).toBe(true);
        api.answer.mockReturnValueOnce(of({ goal: null, skipped: true, answeredAt: '2026-10-05T09:00:00Z' }));
        expect(await store.answer(null)).toBe(true);
        expect(store.goal()).toBeNull();
        expect(store.saveFailed()).toBe(false);
    });

    it('forgets the account on reset', async () => {
        api.answer.mockReturnValue(of({ goal: 'WORK', skipped: false, answeredAt: '2026-10-05T09:00:00Z' }));
        await store.answer('WORK');
        store.reset();
        expect(store.goal()).toBeNull();
        expect(store.answered()).toBe(false);
        expect(store.state()).toBe('idle');
    });
});
