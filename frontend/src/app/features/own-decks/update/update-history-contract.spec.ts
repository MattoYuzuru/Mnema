import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import forks from '../../../../../../contracts/decks/forks.json';
import updates from '../../../../../../contracts/decks/updates.json';
import { UPDATE_TEXT } from '../../../shared/copy-text';
import { OwnDeckProtocolError, isCanonicalCommandId } from '../own-deck.models';
import { parseDeckFork } from '../fork/fork.models';
import { UpdateHistoryApiService } from './update-history-api.service';
import {
    HISTORY_CHANGES,
    HISTORY_DEFAULT_LIMIT,
    HISTORY_MAX_LIMIT,
    HISTORY_MAX_UNITS,
    HISTORY_MODES,
    HISTORY_UNIT_STATES,
    REVERTIBLE_KINDS,
    REVERT_MAX_WITH,
    REVERT_REASONS,
    newRevertCommandId,
    parseReverted,
    parseSeen,
    parseUpdateHistory,
    relativeWhen,
    revertFailureOf,
    splitUnitId
} from './update-history.models';
import { WIRE_KINDS } from './update-plan.models';

/**
 * contracts/decks/updates.json (`history`, `seen`, `revert`) and the `fork.lastUpdate` of contracts/decks/forks.json are the wire contract of «Что нового»
 * (Updates/6 part B). Every fixture must parse, every request must be the one the contract names, the constants must be the ones the client mirrors and the
 * problems must be what the client does.
 */
describe('Update history wire contract (contracts/decks/updates.json)', () => {
    const headers = { 'Cache-Control': 'private, no-store' };
    const history = updates.history.body;
    const deckId = history.deckId;
    const entry = history.entries[0]!;
    const unit = entry.units[0]!;
    const revertRequest = updates.revert.request;
    const commandId = revertRequest.body.commandId;
    let http: HttpTestingController;
    let api: UpdateHistoryApiService;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
        api = TestBed.inject(UpdateHistoryApiService);
    });
    afterEach(() => http.verify());

    const problem = (status: number, body: unknown) => new HttpErrorResponse({ status, error: body });

    describe('constants', () => {
        it('mirrors the modes, changes, unit states, revertible kinds, reasons and limits of the contract', () => {
            expect([...HISTORY_MODES]).toEqual(updates.constants.modes);
            expect([...HISTORY_CHANGES]).toEqual(updates.constants.changes);
            expect([...HISTORY_UNIT_STATES]).toEqual(updates.constants.unitStates);
            expect([...REVERTIBLE_KINDS]).toEqual(updates.constants.revertibleKinds);
            expect([...REVERT_REASONS]).toEqual(updates.constants.revertReasons);
            expect([...WIRE_KINDS]).toEqual(updates.constants.kinds);
            expect(HISTORY_DEFAULT_LIMIT).toBe(updates.constants.historyDefaultLimit);
            expect(HISTORY_MAX_LIMIT).toBe(updates.constants.historyMaxLimit);
            expect(HISTORY_MAX_UNITS).toBe(updates.constants.historyMaxUnits);
            expect(REVERT_MAX_WITH).toBe(updates.constants.revertMaxWith);
            expect(updates.constants.historyPath).toBe('/api/decks/{forkDeckId}/update-history?limit=');
            expect(updates.constants.seenPath).toBe('/api/decks/{forkDeckId}/update-history/{deckRevisionId}:seen');
            expect(updates.constants.revertPath).toBe('/api/decks/{forkDeckId}/update-history/{deckRevisionId}/units/{kind}/{key}:revert');
        });

        it('has a word for every mode, change and unit state the contract names, and for no other', () => {
            expect(Object.keys(UPDATE_TEXT.modes)).toEqual(updates.constants.modes);
            expect(Object.keys(UPDATE_TEXT.changes)).toEqual(updates.constants.changes);
            expect(Object.keys(UPDATE_TEXT.unitStates)).toEqual(updates.constants.unitStates.filter(state => state !== 'CURRENT'));
        });
    });

    describe('the history', () => {
        it('parses the contract example into the entry, its counts and its units', () => {
            const parsed = parseUpdateHistory(history, deckId, 5);
            expect(parsed.deckId).toBe(deckId);
            expect(parsed.entries).toHaveLength(1);
            expect(parsed.entries[0]).toMatchObject({ deckRevisionId: entry.deckRevisionId, mode: 'AUTO', seen: false, counts: { applied: 3, pending: 2 }, unitsTruncated: false });
            expect(parsed.entries[0]!.units[0]).toEqual({
                unitId: unit.unitId, kind: 'MATERIAL', key: unit.key, change: 'CHANGED', title: 'hola', state: 'CURRENT', revertible: true
            });
        });

        it('reads the contract request with the default limit, and refuses a limit outside 1..20 before sending', async () => {
            const reading = firstValueFrom(api.history(deckId));
            const request = http.expectOne(candidate => candidate.url === `/api/decks/${deckId}/update-history`);
            expect(request.request.method).toBe('GET');
            expect(request.request.params.get('limit')).toBe(String(updates.constants.historyDefaultLimit));
            request.flush(history, { headers });
            expect((await reading).entries).toHaveLength(1);
            for (const limit of [0, 21, 1.5, -1]) await expect(firstValueFrom(api.history(deckId, limit))).rejects.toThrowError(OwnDeckProtocolError);
        });

        it('parses an empty history and refuses more entries than asked for, a repeated entry, another copy and a cacheable answer', async () => {
            expect(parseUpdateHistory({ deckId, entries: [] }, deckId, 5).entries).toEqual([]);
            expect(() => parseUpdateHistory({ deckId, entries: [entry, { ...entry, deckRevisionId: '44444444-4444-4444-8444-444444444445' }] }, deckId, 1)).toThrowError(OwnDeckProtocolError);
            expect(() => parseUpdateHistory({ deckId, entries: [entry, entry] }, deckId, 5)).toThrowError(OwnDeckProtocolError);
            expect(() => parseUpdateHistory(history, '55555555-5555-4555-8555-555555555555', 5)).toThrowError(OwnDeckProtocolError);
            const reading = firstValueFrom(api.history(deckId));
            http.expectOne(candidate => candidate.url === `/api/decks/${deckId}/update-history`).flush(history, { headers: { 'Cache-Control': 'public' } });
            await expect(reading).rejects.toThrowError(OwnDeckProtocolError);
        });

        it('refuses an entry or a unit that breaks the shape or the invariants of the contract', () => {
            const orderUnit = { unitId: 'ORDER:33333333-3333-4333-8333-333333333333', kind: 'ORDER', key: '33333333-3333-4333-8333-333333333333', change: 'REORDERED', title: null, previousRevisionId: null, revisionId: null, state: null, revertible: false };
            const entryWith = (units: readonly unknown[]) => ({ deckId, entries: [{ ...entry, units }] });
            expect(parseUpdateHistory(entryWith([unit, orderUnit]), deckId, 5).entries[0]!.units).toHaveLength(2);
            const { counts: _counts, ...withoutCounts } = entry;
            const addedWithoutRevision = { ...unit, change: 'ADDED', revisionId: null, previousRevisionId: null };
            for (const broken of [
                { deckId, entries: [{ ...withoutCounts }] },
                { deckId, entries: [{ ...entry, extra: 1 }] },
                { deckId, entries: [{ ...entry, mode: 'BOTH' }] },
                { deckId, entries: [{ ...entry, appliedAt: 'yesterday' }] },
                { deckId, entries: [{ ...entry, seen: 'no' }] },
                { deckId, entries: [{ ...entry, counts: { applied: -1, pending: 0 } }] },
                { deckId, entries: [{ ...entry, counts: { applied: 1 } }] },
                { deckId, entries: [{ ...entry, unitsTruncated: null }] },
                { deckId, entries: [{ ...entry, deckRevisionId: 'x' }] },
                { deckId, entries: [{ ...entry, upstreamRevisionId: null }] },
                entryWith([{ ...unit, extra: 1 }]),
                entryWith([{ ...unit, unitId: 'MATERIAL:cccccccc-cccc-4ccc-8ccc-cccccccccccd' }]),
                entryWith([{ ...unit, kind: 'EXERCISE' }]),
                entryWith([{ ...unit, change: 'MOVED' }]),
                entryWith([{ ...unit, state: 'GONE' }]),
                entryWith([{ ...unit, state: null }]),
                entryWith([{ ...unit, revertible: 'yes' }]),
                entryWith([{ ...unit, title: 7 }]),
                entryWith([{ ...unit, title: 'x'.repeat(161) }]),
                entryWith([{ ...unit, title: null }]),
                entryWith([addedWithoutRevision]),
                entryWith([{ ...unit, change: 'ADDED' }]),
                entryWith([{ ...unit, revisionId: null }]),
                entryWith([{ ...unit, change: 'REORDERED' }]),
                entryWith([{ ...orderUnit, change: 'CHANGED' }]),
                entryWith([{ ...orderUnit, previousRevisionId: unit.previousRevisionId }]),
                entryWith([{ ...orderUnit, state: 'CURRENT' }]),
                entryWith([{ ...orderUnit, revertible: true }]),
                entryWith([{ ...unit, state: 'RETURNED', revertible: true }]),
                entryWith([{ ...unit, kind: 'OBJECTIVE', unitId: `OBJECTIVE:${unit.key}`, revertible: true }]),
                entryWith([unit, unit]),
                entryWith(Array.from({ length: HISTORY_MAX_UNITS + 1 }, (_, index) => ({ ...unit, key: `cccccccc-cccc-4ccc-8ccc-${index.toString().padStart(12, '0')}`, unitId: `MATERIAL:cccccccc-cccc-4ccc-8ccc-${index.toString().padStart(12, '0')}` }))),
                'history', null, []
            ]) expect(() => parseUpdateHistory(broken, deckId, 5), JSON.stringify(broken).slice(0, 160)).toThrowError(OwnDeckProtocolError);
        });
    });

    describe(':seen', () => {
        const path = `/api/decks/${deckId}/update-history/${entry.deckRevisionId}:seen`;

        it('posts nothing to the contract path and reads the contract answer', async () => {
            const marking = firstValueFrom(api.markSeen(deckId, entry.deckRevisionId));
            const request = http.expectOne(path);
            expect(request.request.method).toBe('POST');
            expect(request.request.body).toBeNull();
            request.flush(updates.seen.body, { headers });
            expect(await marking).toBe(true);
            expect(updates.seen.request).toBe(`POST ${path}`);
        });

        it('refuses another entry, another copy, a false flag and an extra member', () => {
            expect(parseSeen(updates.seen.body, deckId, entry.deckRevisionId)).toBe(true);
            for (const broken of [
                { ...updates.seen.body, seen: false }, { ...updates.seen.body, deckRevisionId: '44444444-4444-4444-8444-444444444445' },
                { ...updates.seen.body, deckId: '55555555-5555-4555-8555-555555555555' }, { ...updates.seen.body, extra: 1 }, { deckId }, 'seen'
            ]) expect(() => parseSeen(broken, deckId, entry.deckRevisionId), JSON.stringify(broken)).toThrowError(OwnDeckProtocolError);
        });

        it('refuses to send a malformed entry id', async () => {
            await expect(firstValueFrom(api.markSeen(deckId, 'x'))).rejects.toThrowError(OwnDeckProtocolError);
        });
    });

    describe(':revert', () => {
        const pathOf = `/api/decks/${deckId}/update-history/${entry.deckRevisionId}/units/${unit.kind}/${unit.key}:revert`;
        const other = revertRequest.body.with[0]!;

        it('sends the command of the contract example (with the other units of the group) to the contract path', async () => {
            expect(revertRequest.path).toBe(pathOf);
            const reverting = firstValueFrom(api.revert(deckId, entry.deckRevisionId, unit.unitId, { commandId, with: [other] }));
            const request = http.expectOne(pathOf);
            expect(request.request.method).toBe('POST');
            expect(request.request.body).toEqual(revertRequest.body);
            request.flush(updates.revert.reverted.body, { headers });
            expect(await reverting).toEqual({ commandId, deckRevisionId: updates.revert.reverted.body.deckRevisionId, units: updates.revert.reverted.body.units });
        });

        it('sends no `with` member when nothing comes along, and reads the stored answer of the same command again', async () => {
            const reverting = firstValueFrom(api.revert(deckId, entry.deckRevisionId, unit.unitId, { commandId, with: [] }));
            const request = http.expectOne(pathOf);
            expect(request.request.body).toEqual({ commandId });
            request.flush(updates.revert.reverted.body, { headers: { ...headers, 'Idempotency-Replayed': 'true' } });
            expect((await reverting).units[0]).toBe(unit.unitId);
        });

        it('refuses a command that breaks the contract before sending it', async () => {
            const send = (unitId: string, withUnits: readonly string[], id = commandId) => firstValueFrom(api.revert(deckId, entry.deckRevisionId, unitId, { commandId: id, with: withUnits }));
            await expect(send(unit.unitId, [], 'x')).rejects.toThrowError(OwnDeckProtocolError);
            await expect(send(`OBJECTIVE:${unit.key}`, [])).rejects.toThrowError(OwnDeckProtocolError);
            await expect(send(`ORDER:${unit.key}`, [])).rejects.toThrowError(OwnDeckProtocolError);
            await expect(send('x', [])).rejects.toThrowError(OwnDeckProtocolError);
            await expect(send(unit.unitId, ['x'])).rejects.toThrowError(OwnDeckProtocolError);
            await expect(send(unit.unitId, Array.from({ length: REVERT_MAX_WITH + 1 }, () => other))).rejects.toThrowError(OwnDeckProtocolError);
            expect(isCanonicalCommandId(newRevertCommandId())).toBe(true);
        });

        it('reads the contract answer and refuses one that is for another command, another unit or has another shape', () => {
            const body = updates.revert.reverted.body;
            expect(parseReverted(body, 200, commandId, unit.unitId).units).toEqual(body.units);
            expect(parseReverted({ ...body, commandId: commandId.toUpperCase() }, 200, commandId, unit.unitId).commandId).toBe(commandId);
            for (const [broken, status] of [
                [{ ...body, commandId: '1b6e0a58-3c5e-4d1b-8f55-2e4b79f1b5a1' }, 200], [body, 202], [{ ...body, state: 'DONE' }, 200], [{ ...body, units: [] }, 200],
                [{ ...body, units: [body.units[1], body.units[0]] }, 200], [{ ...body, units: [body.units[0], body.units[0]] }, 200], [{ ...body, units: ['x'] }, 200],
                [{ ...body, deckRevisionId: null }, 200], [{ ...body, extra: 1 }, 200], ['x', 200]
            ] as const) expect(() => parseReverted(broken, status, commandId, unit.unitId), JSON.stringify(broken)).toThrowError(OwnDeckProtocolError);
        });

        it('says what the contract problems mean', () => {
            const needed = updates.problems.unitNeeded.body;
            expect(revertFailureOf(problem(400, needed))).toEqual({ kind: 'unit-needed', unitId: needed.unitId, units: needed.units });
            expect(revertFailureOf(problem(400, { ...needed, units: [needed.units[0], needed.units[0], 'x'] }))).toEqual({ kind: 'unit-needed', unitId: needed.unitId, units: [needed.units[0]] });
            expect(revertFailureOf(problem(400, { code: 'INVALID_REQUEST', reason: 'material_still_used', unitId: unit.unitId, dependency: other })))
                .toEqual({ kind: 'still-used', unitId: unit.unitId, dependency: other });
            expect(revertFailureOf(problem(400, { code: 'INVALID_REQUEST', reason: 'unit_not_returnable' }))).toEqual({ kind: 'not-returnable' });
            for (const reason of ['duplicate_unit', 'publication_refused', undefined]) expect(revertFailureOf(problem(400, { code: 'INVALID_REQUEST', reason }))).toEqual({ kind: 'refused' });
            expect(revertFailureOf(problem(409, updates.problems.revertStale.body))).toEqual({ kind: 'stale', unitId: updates.problems.revertStale.body.unitId });
            const { unitId: _unitId, ...moved } = updates.problems.revertStale.body;
            expect(revertFailureOf(problem(409, moved))).toEqual({ kind: 'stale', unitId: null });
            expect(revertFailureOf(problem(409, updates.problems.detached.body))).toEqual({ kind: 'detached' });
            expect(revertFailureOf(problem(409, updates.problems.preparing.body))).toEqual({ kind: 'preparing' });
            expect(revertFailureOf(problem(409, updates.problems.idempotencyConflict.body))).toEqual({ kind: 'other' });
            expect(revertFailureOf(problem(404, updates.problems.notFound.body))).toEqual({ kind: 'not-found' });
            expect(revertFailureOf(problem(429, null))).toEqual({ kind: 'rate-limited' });
            expect(revertFailureOf(problem(503, updates.problems.busy.body))).toEqual({ kind: 'unknown' });
            expect(revertFailureOf(problem(0, null))).toEqual({ kind: 'unknown' });
            expect(revertFailureOf(new OwnDeckProtocolError('x'))).toEqual({ kind: 'unknown' });
            expect(revertFailureOf(problem(403, null))).toEqual({ kind: 'other' });
        });

        it('splits a unit id and tells a returnable kind', () => {
            expect(splitUnitId(unit.unitId)).toEqual({ kind: 'MATERIAL', key: unit.key });
            expect(splitUnitId('x')).toBeNull();
            expect(splitUnitId('MATERIAL:x')).toBeNull();
            expect(splitUnitId('NOPE:cccccccc-cccc-4ccc-8ccc-cccccccccccc')).toBeNull();
        });
    });

    describe('fork.lastUpdate (contracts/decks/forks.json)', () => {
        const states = forks.fork;
        const last = states.updatedAutomatically.lastUpdate;

        it('parses the contract example into the notice, and a copy without it as one without a notice', () => {
            expect(parseDeckFork(states.updatedAutomatically)!.lastUpdate).toEqual(last);
            expect(parseDeckFork(states.updatedAutomatically)).toEqual(states.updatedAutomatically);
            expect('lastUpdate' in parseDeckFork(states.updateAvailable)!).toBe(false);
            expect(last.counts).toEqual(history.entries[0]!.counts);
            expect(last.deckRevisionId).toBe(entry.deckRevisionId);
        });

        it('refuses a notice that is seen, not automatic, null, malformed, or on a copy that cannot have one', () => {
            const copy = states.updatedAutomatically;
            for (const broken of [
                { ...copy, lastUpdate: null },
                { ...copy, lastUpdate: { ...last, seen: true } },
                { ...copy, lastUpdate: { ...last, mode: 'MANUAL' } },
                { ...copy, lastUpdate: { ...last, appliedAt: 'x' } },
                { ...copy, lastUpdate: { ...last, deckRevisionId: 'x' } },
                { ...copy, lastUpdate: { ...last, counts: { applied: 1 } } },
                { ...copy, lastUpdate: { ...last, counts: { applied: 1.5, pending: 0 } } },
                { ...copy, lastUpdate: { ...last, extra: 1 } },
                { ...copy, lastUpdate: 'new' },
                { ...states.detached, lastUpdate: last },
                { ...states.preparing, lastUpdate: last }
            ]) expect(() => parseDeckFork(broken), JSON.stringify(broken)).toThrowError(OwnDeckProtocolError);
        });
    });

    describe('relativeWhen', () => {
        const at = Date.parse('2026-10-12T12:00:00Z');
        const ago = (milliseconds: number) => new Date(at - milliseconds).toISOString();
        const MINUTE = 60_000;

        it('says «только что», minutes, hours and days in Russian, and nothing for a week or a clock that is ahead', () => {
            expect(relativeWhen(ago(10_000), at)).toBe('только что');
            expect(relativeWhen(ago(5 * MINUTE), at)).toBe('5 минут назад');
            expect(relativeWhen(ago(2 * 60 * MINUTE), at)).toBe('2 часа назад');
            expect(relativeWhen(ago(24 * 60 * MINUTE), at)).toBe('вчера');
            expect(relativeWhen(ago(3 * 24 * 60 * MINUTE), at)).toBe('3 дня назад');
            expect(relativeWhen(ago(7 * 24 * 60 * MINUTE), at)).toBeNull();
            expect(relativeWhen(ago(-5 * MINUTE), at)).toBeNull();
            expect(relativeWhen('x', at)).toBeNull();
        });
    });
});
