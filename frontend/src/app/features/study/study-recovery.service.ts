import { Injectable, inject } from '@angular/core';

import { AUTH_BROWSER } from '../../auth-browser';
import { AuthService } from '../../auth.service';
import { AttemptCommand, StudyRecoverySnapshot } from './study.models';

const KEY = 'mnema_study_recovery_v1';
const TTL_MS = 24 * 60 * 60 * 1000;
const encoder = new TextEncoder();

@Injectable({ providedIn: 'root' })
export class StudyRecoveryService {
    private readonly auth = inject(AuthService);
    private readonly browser = inject(AUTH_BROWSER);

    restore(deckId: string): StudyRecoverySnapshot | null {
        try {
            const raw = this.browser.storage.getItem(KEY);
            if (raw === null || encoder.encode(raw).length > 16_384) return null;
            const value = JSON.parse(raw) as Record<string, unknown>;
            if (value['version'] !== 1 || value['accountId'] !== this.accountId() || value['deckId'] !== deckId
                || typeof value['updatedAt'] !== 'number' || this.browser.now() - value['updatedAt'] > TTL_MS
                || !id(value['sessionId'])) throw new Error();
            return { deckId, sessionId: value['sessionId'], pending: parsePending(value['pending']) };
        } catch {
            this.clear();
            return null;
        }
    }

    save(snapshot: StudyRecoverySnapshot): void {
        const accountId = this.accountId();
        if (accountId === null || !id(snapshot.deckId) || !id(snapshot.sessionId)) return;
        try {
            this.browser.storage.setItem(KEY, JSON.stringify({ version: 1, accountId, ...snapshot,
                updatedAt: this.browser.now() }));
        } catch { /* Session remains usable without browser storage. */ }
    }

    clear(): void { try { this.browser.storage.removeItem(KEY); } catch { /* fail closed */ } }
    now(): number { return this.browser.now(); }

    private accountId(): string | null {
        const value = this.auth.user()?.accountId;
        return typeof value === 'string' && id(value) ? value.toLowerCase() : null;
    }
}

function parsePending(value: unknown): AttemptCommand | null {
    if (value === null) return null;
    if (typeof value !== 'object' || Array.isArray(value)) throw new Error();
    const command = value as Record<string, unknown>;
    if (!id(command['attemptId']) || !id(command['presentationId']) || typeof command['nonce'] !== 'string'
        || typeof command['durationMs'] !== 'number' || !Array.isArray(command['hintsUsed'])
        || !('response' in command)) throw new Error();
    return command as unknown as AttemptCommand;
}

function id(value: unknown): value is string {
    return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(value);
}
