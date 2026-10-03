import { HttpErrorResponse } from '@angular/common/http';

import errorsFixture from '../../../../../contracts/generation/errors.json';
import eventsFixture from '../../../../../contracts/generation/events.json';
import httpFixture from '../../../../../contracts/generation/http.json';
import statesFixture from '../../../../../contracts/generation/states.json';
import usageFixture from '../../../../../contracts/usage/usage.json';

/** Canonical wire fixtures shared with the backend (`contracts/generation`, `contracts/usage`). Specs read them; production code never does. */
export const httpContract = httpFixture as unknown as Record<string, any>;
export const eventsContract = eventsFixture as unknown as Record<string, any>;
export const statesContract = statesFixture as unknown as Record<string, any>;
export const errorsContract = errorsFixture as unknown as Record<string, any>;
export const usageContract = usageFixture as unknown as Record<string, any>;
export const examples = httpFixture.examples as unknown as Record<string, any>;

export function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T; }

export const privateHeaders = { 'Cache-Control': 'private, no-store' };

export const ids = {
    deckId: '11111111-1111-4111-8111-111111111111',
    sessionId: '5e550000-0000-4000-8000-000000000001',
    first: 'a7a70000-0000-4000-8000-000000000001',
    second: 'a7a70000-0000-4000-8000-000000000002',
    revision: '4e700000-0000-4000-8000-000000000001',
    deckRevision: '22222222-2222-4222-8222-222222222222',
    command: '018f1d98-5c10-7abc-8abc-0123456789b1'
};

/** The operation of `http.json` by id. */
export function operation(operationId: string): { method: string; path: string } {
    const found = (httpContract['endpoints'] as { operationId: string; method: string; path: string }[])
        .find(endpoint => endpoint.operationId === operationId);
    if (found === undefined) throw new Error(`No such operation: ${operationId}`);
    return found;
}

/** The path of an operation with its `{placeholders}` filled in. */
export function pathOf(operationId: string, values: Record<string, string> = {}): string {
    const all = { deckId: ids.deckId, sessionId: ids.sessionId, artifactId: ids.first, ...values };
    return operation(operationId).path.replace(/\{(\w+)\}/g, (_, name: string) => all[name as keyof typeof all]);
}

/** `sessionDetail` with the given artifacts (states) and session fields replaced. */
export function sessionWith(artifacts: readonly Record<string, unknown>[], overrides: Record<string, unknown> = {}): Record<string, any> {
    const base = clone(examples['sessionDetail']);
    return { ...base, artifacts, ...overrides };
}

export function artifactWith(id: string, ordinal: number, state: string, overrides: Record<string, unknown> = {}): Record<string, any> {
    return { ...clone(examples['artifactSummaryProposed']), artifactId: id, ordinal, state, title: `Материал ${ordinal + 1}`,
        rowVersion: '4', ...overrides };
}

/** A problem+json answer as `HttpClient` reports it. */
export function problemResponse(status: number, body: Record<string, unknown> = {}): HttpErrorResponse {
    return new HttpErrorResponse({ status, error: { type: 'urn:mnema:problem:x', title: 'x', status, detail: 'x', instance: '/api/x', ...body } });
}

/** A polling envelope around the given events (wire shape). */
export function eventsEnvelope(events: readonly Record<string, unknown>[], cursor: string, session: { state: string; rowVersion: string } = { state: 'RUNNING', rowVersion: '12' },
                               activeSteps: readonly Record<string, unknown>[] = []): Record<string, unknown> {
    return { events, cursor, session, activeSteps };
}

export function wireEvent(seq: number, type: string, payload: Record<string, unknown>, artifactId: string | null = ids.second): Record<string, unknown> {
    return { seq: String(seq), type, sessionId: ids.sessionId, artifactId, occurredAt: '2026-10-02T09:00:01Z', payload };
}

export function wireBlocks(seq: number, startIndex: number, generation: number, ...texts: string[]): Record<string, unknown> {
    return wireEvent(seq, 'BLOCKS_APPENDED', { generation, startIndex, blocks: texts.map((text, position) => ({
        id: `00000000-0000-4000-8000-${String(seq * 100 + position * 2 + 1).padStart(12, '0')}`, type: 'paragraph', version: 1, attrs: {},
        content: [{ id: `00000000-0000-4000-8000-${String(seq * 100 + position * 2 + 2).padStart(12, '0')}`, type: 'text', version: 1,
            attrs: { text, marks: [] }, content: [] }] })) });
}

export const activeStep = { stepId: '57e70000-0000-4000-8000-000000000001', artifactId: ids.second, kind: 'TEXT_DRAFT', state: 'RUNNING',
    startedAt: '2026-10-02T09:00:42Z' };

/** The deck as approvals pin it. */
export const deckFixture = {
    deckId: ids.deckId, revisionId: ids.deckRevision, rowVersion: '8', sequence: '1', metadata: { title: 'Японский N4', description: '' },
    visibility: 'private' as const, createdAt: '2026-10-01T09:00:00Z', updatedAt: '2026-10-01T09:00:00Z', memberCount: 3, exerciseCount: 0
};
