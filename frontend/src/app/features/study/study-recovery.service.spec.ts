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
        response: { kind: 'CHOICE', optionIds: [id] }, confidence: null, durationMs: 1000 };
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
    it('restores a cloze response with its blank ids and a match response with issued pair ids', () => {
        const cloze: AttemptCommand = { ...pending, response: { kind: 'CLOZE', blanks: [{ blankId: id, text: 'map' }] } };
        service.save({ deckId: id, sessionId: id, pending: cloze });
        expect(service.restore(id)?.pending).toEqual(cloze);
        const match: AttemptCommand = { ...pending, response: { kind: 'MATCH', pairs: [{ leftId: id, rightId: id }] } };
        service.save({ deckId: id, sessionId: id, pending: match });
        expect(service.restore(id)?.pending).toEqual(match);
    });
    it('restores an order and a categorize response, and drops a repeated or malformed one', () => {
        const other = id.replace(/.$/, '2');
        const order: AttemptCommand = { ...pending, response: { kind: 'ORDER', sequence: [id, other] } };
        service.save({ deckId: id, sessionId: id, pending: order });
        expect(service.restore(id)?.pending).toEqual(order);
        const categorize: AttemptCommand = { ...pending, response: { kind: 'CATEGORIZE', assignments: [{ itemId: id, categoryId: other }, { itemId: other, categoryId: other }] } };
        service.save({ deckId: id, sessionId: id, pending: categorize });
        expect(service.restore(id)?.pending).toEqual(categorize);
        for (const broken of [{ kind: 'ORDER', sequence: [id, id] }, { kind: 'ORDER', sequence: [id] },
            { kind: 'CATEGORIZE', assignments: [{ itemId: id, categoryId: other }, { itemId: id, categoryId: other }] },
            { kind: 'CATEGORIZE', assignments: [{ itemId: id, groupId: other }, { itemId: other, groupId: other }] }]) {
            storage.set(key, JSON.stringify({ version: 3, accountId: id, deckId: id, sessionId: id, updatedAt: 10000,
                pending: { ...pending, response: broken } }));
            expect(service.restore(id), JSON.stringify(broken)).toBeNull();
        }
    });
    it('invalidates older snapshots, old scalar responses and any command that still carries hintsUsed', () => {
        for (const version of [1, 2]) {
            storage.set(key, JSON.stringify({ version, accountId: id, deckId: id, sessionId: id, updatedAt: 10000,
                pending: { ...pending, hintsUsed: [], response: { kind: 'CHOICE', optionId: id } } }));
            expect(service.restore(id)).toBeNull();
            expect(storage.has(key)).toBe(false);
        }
        storage.set(key, JSON.stringify({ version: 3, accountId: id, deckId: id, sessionId: id, updatedAt: 10000,
            pending: { ...pending, hintsUsed: [] } }));
        expect(service.restore(id)).toBeNull();
        expect(storage.has(key)).toBe(false);
    });
    it('rejects repeated option IDs and the legacy cue and option pair fields', () => {
        service.save({ deckId: id, sessionId: id, pending: { ...pending, response: { kind: 'CHOICE', optionIds: [id, id] } } });
        expect(service.restore(id)).toBeNull();
        storage.set(key, JSON.stringify({ version: 3, accountId: id, deckId: id, sessionId: id, updatedAt: 10000,
            pending: { ...pending, response: { kind: 'MATCH', pairs: [{ cueId: id, optionId: id }] } } }));
        expect(service.restore(id)).toBeNull();
    });
});
