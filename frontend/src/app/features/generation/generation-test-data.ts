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

// --- Fixtures of the #290 contract additions (notes as sources); they move into `contracts/generation/http.json` with the backend ---

export const noteIds = {
    first: '20700000-0000-4000-8000-000000000001',
    second: '20700000-0000-4000-8000-000000000002',
    third: '20700000-0000-4000-8000-000000000003'
};

/** `sessionDetail.notes` (decision B): used notes and how many «Архивировать использованные» would archive now. */
export function sessionWithNotes(notes: { used: number; archivable: number } | null, artifacts: readonly Record<string, unknown>[] | null = null,
                                 overrides: Record<string, unknown> = {}): Record<string, any> {
    const base = clone(examples['sessionDetail']);
    return { ...base, ...(artifacts === null ? {} : { artifacts }), ...(notes === null ? {} : { notes }), ...overrides };
}

/** `getArtifact` of a NOTE-sourced artifact with the read-time `status` of decision C (omitted when `null`). */
export function artifactDetailWithNote(status: string | null, noteId = noteIds.first): Record<string, any> {
    const detail = clone(examples['artifactDetailItem']);
    detail['sourceRefs'] = [{ type: 'NOTE', noteId, noteRowVersion: '3', ...(status === null ? {} : { status }) }];
    return detail;
}

/** The answer of `archiveUsedNotes` (decision B). */
export function noteArchiveAnswer(archived: readonly string[], skipped: readonly { noteId: string; reason: string }[] = []): Record<string, unknown> {
    return { archived: archived.map(noteId => ({ noteId })), skipped };
}

// --- The plan of a plan-first session (AI-14, #295; `http.json` `sessionDetailPlanReady` / `sessionDetailPlanApproved`) ---

export const planTargets = { first: '44444444-4444-4444-8444-444444444444', second: '44444444-4444-4444-8444-444444444445' };

/** The contract's `PLAN_READY` session of exercises (two materials, six exercises), with `change` applied to a copy. */
export function planReadySession(change: (session: any) => void = () => undefined): Record<string, any> {
    const session = clone(examples['sessionDetailPlanReady']);
    change(session);
    return session;
}

/** The contract's session right after the launch: `RUNNING`, six `QUEUED` exercises, `plan.approved`. */
export function planApprovedSession(change: (session: any) => void = () => undefined): Record<string, any> {
    const session = clone(examples['sessionDetailPlanApproved']);
    change(session);
    return session;
}

/** A `PLAN_READY` Materials session (`schemas.plan` for MATERIALS; the contract has no example of one): two notes, one source-less row. */
export function materialsPlanSession(change: (session: any) => void = () => undefined): Record<string, any> {
    const credits = { SHORT: 4, MEDIUM: 10, DETAILED: 22 };
    const session = {
        ...clone(examples['sessionDetail']), state: 'PLAN_READY', rowVersion: '3', artifacts: [], approvableCount: 0, usage: { reservedCredits: 30, spentCredits: 20 },
        spec: { kind: 'MATERIALS', outputLanguage: 'ru', prompt: 'Объясни планировщик', sources: [], settings: { effort: 'AUTO', notesMode: 'ONE_PER_NOTE', planFirst: true } },
        plan: {
            kind: 'MATERIALS', approved: false,
            items: [{ source: noteIds.first, title: 'Seq Scan: когда он быстрее', effort: 'SHORT', why: 'Короткая заметка.', creditsByEffort: credits },
                { source: noteIds.second, title: 'Статистика и ANALYZE', effort: 'MEDIUM', why: 'Есть что объяснить.', creditsByEffort: credits },
                { source: null, title: 'Общая картина планировщика', effort: 'DETAILED', why: '', creditsByEffort: credits }],
            sources: [{ noteId: noteIds.first, label: 'Seq Scan читает всю таблицу' }, { noteId: noteIds.second, label: 'ANALYZE обновляет статистику' }],
            totals: { items: 3, artifacts: 3 }, cost: { planCredits: 20, batchCredits: 36, holdCredits: 40, barCredits: 360, holdActive: true },
            rates: { note: 'creditsByEffort of each item' }, limits: { maxArtifactsPerSession: 20 }, notes: []
        }
    };
    change(session);
    return session;
}
