import { TestBed } from '@angular/core/testing';

import { AUTH_BROWSER, AuthBrowser } from '../../auth-browser';
import { AuthService, AuthUser } from '../../auth.service';
import {
    OWN_DECK_RECOVERY_STORAGE_KEY,
    OwnDeckRecoveryService
} from './own-deck-recovery.service';
import { PendingDeckCommand } from './own-decks.store';

describe('OwnDeckRecoveryService', () => {
    let service: OwnDeckRecoveryService;
    let auth: jasmine.SpyObj<AuthService>;
    let now: number;
    const storage = sessionStorage;
    const user: AuthUser = {
        accountId: '11111111-1111-4111-8111-111111111111',
        email: 'reader@example.test',
        emailVerified: true,
        profileUsername: 'reader',
        displayName: 'Читатель',
        hasPassword: true
    };
    const pending: PendingDeckCommand = {
        operation: 'save',
        deckId: '22222222-2222-4222-8222-222222222222',
        expectedVersion: '7',
        command: {
            commandId: '123e4567-e89b-42d3-a456-426614174000',
            metadata: { title: '  Точный ввод  ', description: 'строка 1\nстрока 2' }
        }
    };

    beforeEach(() => {
        storage.removeItem(OWN_DECK_RECOVERY_STORAGE_KEY);
        now = 1_800_000_000_000;
        auth = jasmine.createSpyObj<AuthService>('AuthService', ['user']);
        auth.user.and.returnValue(user);
        const browser: AuthBrowser = {
            origin: 'https://localhost:18443',
            pathname: '/decks',
            search: '',
            storage,
            now: () => now,
            random: () => 'unused',
            challenge: async () => 'unused',
            navigate: () => undefined,
            clearQuery: () => undefined
        };
        TestBed.configureTestingModule({
            providers: [
                OwnDeckRecoveryService,
                { provide: AuthService, useValue: auth },
                { provide: AUTH_BROWSER, useValue: browser }
            ]
        });
        service = TestBed.inject(OwnDeckRecoveryService);
    });

    afterEach(() => storage.removeItem(OWN_DECK_RECOVERY_STORAGE_KEY));

    it('restores exact dirty metadata and unresolved command for the verified account', () => {
        const context = { operation: 'save' as const, deckId: pending.deckId };
        service.save(context, pending.command.metadata, pending);

        expect(service.restore(context)).toEqual({ draft: pending.command.metadata, pending });
        const raw = storage.getItem(OWN_DECK_RECOVERY_STORAGE_KEY)!;
        expect(raw).not.toContain('Bearer');
        expect(raw).not.toContain('accessToken');
    });

    it('fails closed and clears recovery when the verified account changes', () => {
        service.save({ operation: 'create' }, { title: 'Личная колода', description: '' }, null);
        auth.user.and.returnValue({ ...user, accountId: '33333333-3333-4333-8333-333333333333' });

        expect(service.restore({ operation: 'create' })).toBeNull();
        expect(storage.getItem(OWN_DECK_RECOVERY_STORAGE_KEY)).toBeNull();
    });

    it('rejects corrupt, oversized, and expired payloads', () => {
        storage.setItem(OWN_DECK_RECOVERY_STORAGE_KEY, '{broken');
        expect(service.restore({ operation: 'create' })).toBeNull();

        storage.setItem(OWN_DECK_RECOVERY_STORAGE_KEY, 'x'.repeat(65 * 1024));
        expect(service.restore({ operation: 'create' })).toBeNull();

        service.save({ operation: 'create' }, { title: 'Черновик', description: '' }, null);
        now += 24 * 60 * 60 * 1000 + 1;
        expect(service.restore({ operation: 'create' })).toBeNull();
        expect(storage.getItem(OWN_DECK_RECOVERY_STORAGE_KEY)).toBeNull();
    });

    it('keeps only five most recently touched contexts', () => {
        for (let index = 0; index < 6; index += 1) {
            const suffix = (index + 1).toString().padStart(12, '0');
            service.save(
                { operation: 'save', deckId: `22222222-2222-4222-8222-${suffix}` },
                { title: `Колода ${index}`, description: '' },
                null
            );
            now += 1;
        }

        const raw = storage.getItem(OWN_DECK_RECOVERY_STORAGE_KEY)!;
        const parsed = JSON.parse(raw) as { entries: unknown[] };
        expect(parsed.entries.length).toBe(5);
        expect(service.restore({ operation: 'save', deckId: '22222222-2222-4222-8222-000000000001' })).toBeNull();
        expect(service.restore({ operation: 'save', deckId: '22222222-2222-4222-8222-000000000006' })?.draft.title)
            .toBe('Колода 5');
    });
});
