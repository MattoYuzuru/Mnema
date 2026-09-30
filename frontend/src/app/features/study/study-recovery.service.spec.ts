import { TestBed } from '@angular/core/testing';
import { AUTH_BROWSER } from '../../auth-browser';
import { AuthService } from '../../auth.service';
import { StudyRecoveryService } from './study-recovery.service';
import { AttemptCommand } from './study.models';

describe('StudyRecoveryService', () => {
    const id = '11111111-1111-4111-8111-111111111111';
    const key = 'mnema_study_recovery_v1';
    let storage: Map<string, string>;
    let service: StudyRecoveryService;
    const pending: AttemptCommand = { attemptId: id, presentationId: id, nonce: 'nonce',
        response: { kind: 'CHOICE', optionIds: [id] }, hintsUsed: [], confidence: null, durationMs: 1000 };
    beforeEach(() => {
        storage = new Map();
        TestBed.configureTestingModule({ providers: [
            { provide: AuthService, useValue: { user: () => ({ accountId: id }) } },
            { provide: AUTH_BROWSER, useValue: { now: () => 10000, storage: {
                getItem: (key: string) => storage.get(key) ?? null,
                setItem: (key: string, value: string) => storage.set(key, value),
                removeItem: (key: string) => storage.delete(key)
            } } }
        ] });
        service = TestBed.inject(StudyRecoveryService);
    });
    it('restores the exact canonical option set', () => {
        service.save({ deckId: id, sessionId: id, pending });
        expect(service.restore(id)?.pending).toEqual(pending);
    });
    it('invalidates old scalar responses and malformed current snapshots', () => {
        for (const version of [1, 2]) {
            storage.set(key, JSON.stringify({ version, accountId: id, deckId: id, sessionId: id, updatedAt: 10000,
                pending: { ...pending, response: { kind: 'CHOICE', optionId: id } } }));
            expect(service.restore(id)).toBeNull(); expect(storage.has(key)).toBeFalse();
        }
    });
    it('rejects repeated option IDs', () => {
        service.save({ deckId: id, sessionId: id, pending: { ...pending, response: { kind: 'CHOICE', optionIds: [id, id] } } });
        expect(service.restore(id)).toBeNull();
    });
});
