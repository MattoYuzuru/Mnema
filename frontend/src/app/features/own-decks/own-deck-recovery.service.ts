import { Injectable, inject } from '@angular/core';

import { AUTH_BROWSER } from '../../auth-browser';
import { AuthService } from '../../auth.service';
import {
    DeckMetadata,
    isCanonicalCommandId,
    isCanonicalEntityId,
    isCanonicalVersion,
    validateDeckMetadata
} from './own-deck.models';
import { PendingDeckCommand } from './own-decks.store';

export const OWN_DECK_RECOVERY_STORAGE_KEY = 'mnema_own_deck_recovery_v1';
const RECOVERY_VERSION = 1;
const RECOVERY_TTL_MS = 24 * 60 * 60 * 1000;
const MAX_RECOVERY_ENTRIES = 5;
const MAX_RECOVERY_BYTES = 64 * 1024;
const MAX_DRAFT_BYTES = 16 * 1024;
const encoder = new TextEncoder();

export type DeckRecoveryContext =
    | { readonly operation: 'create' }
    | { readonly operation: 'save'; readonly deckId: string };

export interface DeckRecoverySnapshot {
    readonly draft: DeckMetadata;
    readonly pending: PendingDeckCommand | null;
}

interface RecoveryEntry extends DeckRecoverySnapshot {
    readonly context: DeckRecoveryContext;
    readonly updatedAt: number;
}

interface RecoveryEnvelope {
    readonly version: typeof RECOVERY_VERSION;
    readonly accountId: string;
    readonly entries: readonly RecoveryEntry[];
}

@Injectable({ providedIn: 'root' })
export class OwnDeckRecoveryService {
    private readonly auth = inject(AuthService);
    private readonly browser = inject(AUTH_BROWSER);

    restore(context: DeckRecoveryContext): DeckRecoverySnapshot | null {
        const accountId = this.accountId();
        if (accountId === null || !isContext(context)) return null;
        const envelope = this.read(accountId);
        if (envelope === null) return null;

        const now = this.browser.now();
        const active = envelope.entries.filter(entry => {
            const age = now - entry.updatedAt;
            return age >= 0 && age <= RECOVERY_TTL_MS;
        });
        if (active.length !== envelope.entries.length) this.write(accountId, active);
        const match = active.find(entry => contextKey(entry.context) === contextKey(context));
        return match === undefined ? null : { draft: { ...match.draft }, pending: clonePending(match.pending) };
    }

    save(context: DeckRecoveryContext, draft: DeckMetadata, pending: PendingDeckCommand | null): void {
        const accountId = this.accountId();
        if (accountId === null || !isContext(context) || !isDraft(draft) || !isPendingForContext(pending, context, draft)) return;
        const previous = this.read(accountId)?.entries ?? [];
        const key = contextKey(context);
        const entry: RecoveryEntry = {
            context: cloneContext(context),
            draft: { ...draft },
            pending: clonePending(pending),
            updatedAt: this.browser.now()
        };
        const entries = [entry, ...previous.filter(candidate => contextKey(candidate.context) !== key)]
            .slice(0, MAX_RECOVERY_ENTRIES);
        this.write(accountId, entries);
    }

    clear(context: DeckRecoveryContext): void {
        const accountId = this.accountId();
        if (accountId === null || !isContext(context)) return;
        const envelope = this.read(accountId);
        if (envelope === null) return;
        const key = contextKey(context);
        this.write(accountId, envelope.entries.filter(entry => contextKey(entry.context) !== key));
    }

    private accountId(): string | null {
        const accountId = this.auth.user()?.accountId;
        return typeof accountId === 'string' && isAccountId(accountId) ? accountId.toLowerCase() : null;
    }

    private read(accountId: string): RecoveryEnvelope | null {
        try {
            const raw = this.browser.storage.getItem(OWN_DECK_RECOVERY_STORAGE_KEY);
            if (raw === null) return null;
            if (encoder.encode(raw).length > MAX_RECOVERY_BYTES) throw new Error('oversized recovery state');
            const envelope = parseEnvelope(JSON.parse(raw));
            if (envelope.accountId !== accountId) {
                this.browser.storage.removeItem(OWN_DECK_RECOVERY_STORAGE_KEY);
                return null;
            }
            return envelope;
        } catch {
            try { this.browser.storage.removeItem(OWN_DECK_RECOVERY_STORAGE_KEY); } catch { /* fail closed */ }
            return null;
        }
    }

    private write(accountId: string, entries: readonly RecoveryEntry[]): void {
        try {
            if (entries.length === 0) {
                this.browser.storage.removeItem(OWN_DECK_RECOVERY_STORAGE_KEY);
                return;
            }
            let bounded = [...entries].slice(0, MAX_RECOVERY_ENTRIES);
            let raw = JSON.stringify({ version: RECOVERY_VERSION, accountId, entries: bounded });
            while (bounded.length > 0 && encoder.encode(raw).length > MAX_RECOVERY_BYTES) {
                bounded = bounded.slice(0, -1);
                raw = JSON.stringify({ version: RECOVERY_VERSION, accountId, entries: bounded });
            }
            if (bounded.length === 0) this.browser.storage.removeItem(OWN_DECK_RECOVERY_STORAGE_KEY);
            else this.browser.storage.setItem(OWN_DECK_RECOVERY_STORAGE_KEY, raw);
        } catch { /* The page remains usable when session storage is unavailable. */ }
    }
}

function parseEnvelope(value: unknown): RecoveryEnvelope {
    const envelope = exactObject(value, ['version', 'accountId', 'entries']);
    if (envelope['version'] !== RECOVERY_VERSION || typeof envelope['accountId'] !== 'string'
        || !isAccountId(envelope['accountId']) || !Array.isArray(envelope['entries'])
        || envelope['entries'].length > MAX_RECOVERY_ENTRIES) throw new Error('invalid recovery envelope');
    return {
        version: RECOVERY_VERSION,
        accountId: envelope['accountId'].toLowerCase(),
        entries: envelope['entries'].map(parseEntry)
    };
}

function parseEntry(value: unknown): RecoveryEntry {
    const entry = exactObject(value, ['context', 'draft', 'pending', 'updatedAt']);
    const context = parseContext(entry['context']);
    const draft = parseDraft(entry['draft']);
    const pending = parsePending(entry['pending']);
    if (!Number.isSafeInteger(entry['updatedAt']) || (entry['updatedAt'] as number) < 0
        || !isPendingForContext(pending, context, draft)) throw new Error('invalid recovery entry');
    return { context, draft, pending, updatedAt: entry['updatedAt'] as number };
}

function parseContext(value: unknown): DeckRecoveryContext {
    const context = exactObject(value, value !== null && typeof value === 'object'
        && (value as Record<string, unknown>)['operation'] === 'save' ? ['operation', 'deckId'] : ['operation']);
    if (context['operation'] === 'create') return { operation: 'create' };
    if (context['operation'] === 'save' && typeof context['deckId'] === 'string' && isCanonicalEntityId(context['deckId'])) {
        return { operation: 'save', deckId: context['deckId'].toLowerCase() };
    }
    throw new Error('invalid recovery context');
}

function parseDraft(value: unknown): DeckMetadata {
    const draft = exactObject(value, ['title', 'description']);
    if (typeof draft['title'] !== 'string' || typeof draft['description'] !== 'string') throw new Error('invalid draft');
    const parsed = { title: draft['title'], description: draft['description'] };
    if (!isDraft(parsed)) throw new Error('invalid draft');
    return parsed;
}

function parsePending(value: unknown): PendingDeckCommand | null {
    if (value === null) return null;
    const operation = value !== null && typeof value === 'object'
        ? (value as Record<string, unknown>)['operation'] : null;
    if (operation === 'create') {
        const pending = exactObject(value, ['operation', 'command']);
        return { operation: 'create', command: parseCommand(pending['command']) };
    }
    if (operation === 'save') {
        const pending = exactObject(value, ['operation', 'deckId', 'expectedVersion', 'command']);
        if (typeof pending['deckId'] !== 'string' || !isCanonicalEntityId(pending['deckId'])
            || typeof pending['expectedVersion'] !== 'string' || !isCanonicalVersion(pending['expectedVersion'])) {
            throw new Error('invalid pending save');
        }
        return {
            operation: 'save',
            deckId: pending['deckId'].toLowerCase(),
            expectedVersion: pending['expectedVersion'],
            command: parseCommand(pending['command'])
        };
    }
    throw new Error('invalid pending operation');
}

function parseCommand(value: unknown) {
    const command = exactObject(value, ['commandId', 'metadata']);
    const metadata = parseDraft(command['metadata']);
    if (typeof command['commandId'] !== 'string' || !isCanonicalCommandId(command['commandId'])
        || !validateDeckMetadata(metadata).valid) throw new Error('invalid pending command');
    return { commandId: command['commandId'].toLowerCase(), metadata };
}

function isContext(context: DeckRecoveryContext): boolean {
    return context.operation === 'create' || (context.operation === 'save' && isCanonicalEntityId(context.deckId));
}

function isDraft(draft: DeckMetadata): boolean {
    return typeof draft.title === 'string' && typeof draft.description === 'string'
        && encoder.encode(JSON.stringify(draft)).length <= MAX_DRAFT_BYTES;
}

function isPendingForContext(
    pending: PendingDeckCommand | null,
    context: DeckRecoveryContext,
    draft: DeckMetadata
): boolean {
    if (pending === null) return true;
    if (!isCanonicalCommandId(pending.command.commandId) || !validateDeckMetadata(pending.command.metadata).valid
        || pending.command.metadata.title !== draft.title || pending.command.metadata.description !== draft.description) return false;
    if (context.operation === 'create') return pending.operation === 'create';
    return pending.operation === 'save' && pending.deckId.toLowerCase() === context.deckId.toLowerCase()
        && isCanonicalVersion(pending.expectedVersion);
}

function contextKey(context: DeckRecoveryContext): string {
    return context.operation === 'create' ? 'create' : `save:${context.deckId.toLowerCase()}`;
}

function cloneContext(context: DeckRecoveryContext): DeckRecoveryContext {
    return context.operation === 'create' ? { operation: 'create' } : { operation: 'save', deckId: context.deckId.toLowerCase() };
}

function clonePending(pending: PendingDeckCommand | null): PendingDeckCommand | null {
    if (pending === null) return null;
    const command = { commandId: pending.command.commandId, metadata: { ...pending.command.metadata } };
    return pending.operation === 'create' ? { operation: 'create', command } : {
        operation: 'save', deckId: pending.deckId, expectedVersion: pending.expectedVersion, command
    };
}

function exactObject(value: unknown, keys: readonly string[]): Record<string, unknown> {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error('expected object');
    const object = value as Record<string, unknown>;
    const actual = Object.keys(object);
    if (actual.length !== keys.length || actual.some(key => !keys.includes(key))) throw new Error('unexpected object shape');
    return object;
}

function isAccountId(value: string): boolean {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(value);
}
