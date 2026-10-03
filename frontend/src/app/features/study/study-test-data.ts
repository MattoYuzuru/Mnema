import { Observable, of } from 'rxjs';

import assessmentFixture from '../../../../../contracts/study/assessment.json';
import mechanicsFixture from '../../../../../contracts/study/mechanics.json';
import previewFixture from '../../../../../contracts/study/preview.json';

/** Canonical wire fixtures shared with the backend. Specs read them; production code never imports this file. */
export const mechanics = mechanicsFixture as unknown as Record<string, any>;

/** AI assessment wire fixtures (contracts/study/assessment.json, #292), shared with the backend. */
export const assessment = assessmentFixture as unknown as Record<string, any>;

/** Author preview wire fixtures (contracts/study/preview.json), shared with the backend. */
export const previews = previewFixture as unknown as Record<string, any>;

export function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T; }

export const ids = {
    deckId: '11111111-1111-4111-8111-111111111111',
    sessionId: '99999999-9999-4999-8999-999999999991',
    reducerConfigId: '99999999-9999-4999-8999-999999999992',
    commandId: '018f1d98-5c10-7abc-8abc-012345678900'
};

export const privateHeaders = { 'Cache-Control': 'private, no-store' };

export const configHash = `sha256:${'a'.repeat(64)}`;

/** A ready session envelope around the given wire presentations. */
export function readySession(presentations: readonly unknown[], overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        sessionId: ids.sessionId, deckId: ids.deckId, mode: 'SCHEDULED', status: 'ACTIVE', timezone: 'Europe/Moscow',
        localStudyDate: '2026-10-01', deckRevisionId: '33333333-3333-4333-8333-333333333333',
        exerciseGenerationId: '99999999-9999-4999-8999-999999999993', selectionPolicyVersion: 'deck-due-new-v2',
        budget: { maxPresentations: 20, maxNewObjectives: 5 }, issuedCount: presentations.length,
        reducer: { id: 'mnema-baseline', version: '1', configId: ids.reducerConfigId, configHash },
        seed: '42', nextCursor: null, expiresAt: '2026-10-02T10:00:00Z', presentations, ...overrides
    };
}

/** An assessed scheduled outcome envelope around a feedback fixture. */
export function assessedOutcome(command: { attemptId: string; presentationId: string }, feedback: unknown): Record<string, unknown> {
    return {
        attemptId: command.attemptId, presentationId: command.presentationId, mode: 'SCHEDULED', status: 'ASSESSED',
        evidence: mechanics['evidence']['clozeHinted'], feedback,
        transition: { learningEpoch: '0', sequence: '1', beforeLevel: 1, afterLevel: 2, acceptedAt: '2026-10-01T10:00:00Z',
            nextDue: '2026-10-02T10:00:00Z', reducerId: 'mnema-baseline', reducerVersion: '1', configId: ids.reducerConfigId, configHash }
    };
}

/**
 * Removed names, spelled indirectly so the repository can be searched for them with zero hits. Specs use these
 * only to prove that every parser and renderer rejects them.
 */
export const removed = {
    typed: 'TYP' + 'ED', listenType: 'LISTEN_' + 'TYPE', clozeSingle: 'CLOZE_' + 'SINGLE', singleChoice: 'SINGLE_' + 'CHOICE',
    listenChoice: 'LISTEN_' + 'CHOICE', audioTextMatch: 'AUDIO_TEXT_' + 'MATCH'
};
export const removedNames: readonly string[] = Object.values(removed);

/** Tiny valid media so playback tests never hit the test server (a 404 would raise late error events). */
export const SILENT_WAV = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=';
export const PIXEL_PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';

/** Playback resolver stub: assets whose id ends in 0003 are images, everything else plays a silent clip. */
export function fakePlayback(assetId: string): Observable<{ url: string; expiresAt: string; mimeType: string }> {
    const image = assetId.endsWith('0003');
    return of({ url: image ? PIXEL_PNG : SILENT_WAV, expiresAt: '2999-01-01T00:00:00Z', mimeType: image ? 'image/png' : 'audio/wav' });
}
