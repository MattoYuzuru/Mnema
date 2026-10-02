import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import hub from '../../../../../../contracts/decks/hub.json';
import { AuthoringProtocolError } from '../../authoring/authoring.models';
import { ItemApiService } from '../../authoring/item-api.service';
import { DeckHubApiService } from './deck-hub-api.service';
import {
    DeckInsights,
    DeletionSelection,
    MATERIAL_STATES,
    hubFailureOf,
    parseBulkDeleteResult,
    parseInsights,
    selectionKey
} from './deck-hub.models';
import { consequenceText, deletionOutcomeText, STALE_DECK_MESSAGE } from './deck-hub.text';

/**
 * contracts/decks/hub.json is the one wire contract of the Deck hub. Every fixture must parse, every request body
 * must serialize to its fixture, and the contract's own invariants and Russian client messages must hold here too.
 */
describe('Deck hub wire contract (contracts/decks/hub.json)', () => {
    const deckId = hub.insights.response.deckId;
    const headers = { 'Cache-Control': 'private, no-store' };
    let http: HttpTestingController;
    let api: DeckHubApiService;
    let items: ItemApiService;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
        api = TestBed.inject(DeckHubApiService);
        items = TestBed.inject(ItemApiService);
    });
    afterEach(() => http.verify());

    describe('insights', () => {
        it('parses the populated and the empty snapshot exactly', async () => {
            for (const fixture of [hub.insights.response, hub.insights.emptyDeck]) {
                const reading = firstValueFrom(api.insights(deckId));
                http.expectOne(`/api/decks/${deckId}/insights`).flush(fixture, { headers });
                expect(await reading).toEqual(fixture);
            }
        });

        it('satisfies the executable invariants of the contract', () => {
            const insights = parseInsights(hub.insights.response);
            const sumStates = MATERIAL_STATES.reduce((sum, state) => sum + insights.states[state], 0);
            expect(insights.coverage.total).toBe(insights.coverage.withExercises + insights.coverage.withoutExercises);
            expect(insights.coverage.total).toBe(sumStates);
            expect(insights.dueByDay).toHaveLength(hub.constants.dueDays);
            expect(insights.dueByDay.reduce((sum, day) => sum + day.materials, 0)).toBeLessThanOrEqual(insights.coverage.total);
            expect(Object.keys(insights.exercisesByMechanic)).toEqual([...hub.constants.mechanics]);
            expect(Object.keys(insights.states)).toEqual([...hub.constants.states]);
            // The same exercises counted by the material list: every item's exerciseCount adds up to the mechanic total.
            const listed = hub.items.sortedPage.items.reduce((sum, item) => sum + item.exerciseCount, 0);
            expect(listed).toBeLessThanOrEqual(Object.values(insights.exercisesByMechanic).reduce((sum, count) => sum + count, 0));
        });

        it('rejects a snapshot that breaks an invariant or the shape', () => {
            const good = hub.insights.response;
            const broken: unknown[] = [
                { ...good, coverage: { total: 11, withExercises: 7, withoutExercises: 3 } },
                { ...good, states: { ...good.states, DUE: 3 } },
                { ...good, dueByDay: good.dueByDay.slice(1) },
                { ...good, dueByDay: good.dueByDay.map((day, index) => index === 2 ? { ...day, date: '2026-10-09' } : day) },
                { ...good, dueByDay: good.dueByDay.map(day => ({ ...day, materials: 5 })) },
                { ...good, exercisesByMechanic: { SELF_CHECK: 1 } },
                { ...good, captures: { open: 0, oldestOpenCreatedAt: '2026-09-12T12:00:00Z' } },
                { ...good, captures: { open: 2, oldestOpenCreatedAt: null } },
                { ...good, timezone: '' },
                { ...good, vanityStreak: 12 }
            ];
            for (const value of broken) expect(() => parseInsights(value)).toThrowError(AuthoringProtocolError);
        });

        it('rejects a cacheable response and a snapshot of another deck', async () => {
            const cacheable = firstValueFrom(api.insights(deckId));
            http.expectOne(`/api/decks/${deckId}/insights`).flush(hub.insights.response);
            await expect(cacheable).rejects.toThrowError(AuthoringProtocolError);
            const foreign = firstValueFrom(api.insights(deckId));
            http.expectOne(`/api/decks/${deckId}/insights`).flush({ ...hub.insights.response, deckId: '99999999-9999-4999-8999-999999999999' }, { headers });
            await expect(foreign).rejects.toThrowError(AuthoringProtocolError);
        });

        it('exposes the not-found problem code to the caller', async () => {
            const reading = firstValueFrom(api.insights(deckId));
            http.expectOne(`/api/decks/${deckId}/insights`).flush(hub.insights.notFound, { status: 404, statusText: 'Not Found' });
            const error = await reading.catch((failure: unknown) => failure);
            expect(hubFailureOf(error)).toEqual({ status: 404, code: 'RESOURCE_NOT_FOUND' });
            expect(hubFailureOf(new Error('x'))).toBeNull();
        });

        it('names an action for every widget', () => {
            expect(Object.keys(hub.insights.widgetActions)).toEqual(['coverage', 'states', 'dueByDay', 'exercisesByMechanic', 'captures']);
        });
    });

    describe('material list', () => {
        it('sends the sort and include query and parses the sorted page with its exemplar budget', async () => {
            const loading = firstValueFrom(items.list(deckId, { sort: 'exerciseCount' }));
            http.expectOne(`/api/decks/${deckId}/items?limit=20&sort=exerciseCount&include=exerciseCount`)
                .flush(hub.items.sortedPage, { headers: { ...headers, ETag: '"7"' } });
            const page = await loading;
            expect(page.exemplars).toEqual({ count: 1, limit: hub.constants.exemplarLimit });
            // «Exercise count ascending, ties by ordinal ascending»: the invariant holds for the fixture as parsed.
            const order = page.items.map(item => [item.exerciseCount!, item.ordinal]);
            expect(order).toEqual([...order].sort((a, b) => a[0] - b[0] || a[1] - b[1]));
            // Every entry keeps its true ordinal and the exemplar flag.
            expect(page.items.map(item => item.ordinal)).toEqual([2, 5, 0]);
            expect(page.items.map(item => item.exemplar)).toEqual([false, true, false]);
        });

        it('parses the authoring-ordered page with counts', async () => {
            const loading = firstValueFrom(items.list(deckId, { exerciseCount: true }));
            http.expectOne(`/api/decks/${deckId}/items?limit=20&include=exerciseCount`)
                .flush(hub.items.orderedPageWithCounts.response, { headers: { ...headers, ETag: '"7"' } });
            expect((await loading).items.map(item => item.exerciseCount)).toEqual([1, 3]);
        });
    });

    describe('exemplar', () => {
        const { command, acknowledgement, unsetCommand } = hub.exemplar;
        const member = acknowledgement.memberKey;

        it('serializes the desired value and the item revision precondition, and parses the acknowledgement', async () => {
            const setting = firstValueFrom(api.setExemplar(deckId, member, command.expectedItemRevisionId, true, command.commandId));
            const request = http.expectOne(`/api/decks/${deckId}/items/${member}/exemplar`);
            expect(request.request.method).toBe('POST');
            expect(request.request.body).toEqual(command);
            expect(request.request.headers.has('If-Match')).toBe(false);
            request.flush(acknowledgement, { headers });
            expect(await setting).toEqual(acknowledgement);

            const clearing = firstValueFrom(api.setExemplar(deckId, member, unsetCommand.expectedItemRevisionId, false, unsetCommand.commandId));
            const second = http.expectOne(`/api/decks/${deckId}/items/${member}/exemplar`);
            expect(second.request.body).toEqual(unsetCommand);
            second.flush({ ...acknowledgement, commandId: unsetCommand.commandId, exemplar: false, exemplarCount: 0 }, { headers });
            expect((await clearing).exemplarCount).toBe(0);
        });

        it('keeps the count within the limit and reports the limit and stale problems by code', async () => {
            expect(acknowledgement.exemplarCount).toBeLessThanOrEqual(hub.constants.exemplarLimit);
            for (const [problem, status] of [[hub.exemplar.limitReached, 422], [hub.exemplar.staleItem, 412]] as const) {
                const request = firstValueFrom(api.setExemplar(deckId, member, command.expectedItemRevisionId, true, command.commandId));
                http.expectOne(`/api/decks/${deckId}/items/${member}/exemplar`).flush(problem, { status, statusText: problem.title });
                expect(hubFailureOf(await request.catch((failure: unknown) => failure))).toEqual({ status, code: problem.code });
            }
        });

        it('rejects an acknowledgement that answers another command or value', async () => {
            const request = firstValueFrom(api.setExemplar(deckId, member, command.expectedItemRevisionId, true, command.commandId));
            http.expectOne(`/api/decks/${deckId}/items/${member}/exemplar`).flush({ ...acknowledgement, exemplar: false }, { headers });
            await expect(request).rejects.toThrowError(AuthoringProtocolError);
        });
    });

    describe('bulk delete', () => {
        const base = hub.bulkDelete;
        const revision = base.command.expectedDeckRevisionId;

        it('previews exactly the selection and exposes the consequence counts', async () => {
            const selection: DeletionSelection = { itemIds: base.previewBody.itemIds };
            const previewing = firstValueFrom(api.previewDeletion(deckId, revision, selection));
            const request = http.expectOne(`/api/decks/${deckId}/items/deletions/preview`);
            expect(request.request.body).toEqual(base.previewBody);
            request.flush(base.previewResponse, { headers });
            const preview = await previewing;
            expect(preview.affectedExerciseCount).toBe(15);
            expect(consequenceText(preview)).toBe(base.clientMessages.holdToDeleteConsequence);
        });

        it('sends explicit ids and the all-in-deck form with the Deck version as If-Match', async () => {
            for (const [command, selection] of [
                [base.command, { itemIds: base.command.itemIds }],
                [base.commandAllInDeck, { allInDeck: true, except: base.commandAllInDeck.except }]
            ] as const) {
                const deleting = firstValueFrom(api.deleteMany(deckId, '7', revision, selection, command.commandId));
                const request = http.expectOne(`/api/decks/${deckId}/items/deletions`);
                expect(request.request.headers.get('If-Match')).toBe('"7"');
                expect(request.request.body).toEqual(command);
                request.flush({ ...base.completed, commandId: command.commandId }, { headers: { ...headers, ETag: '"8"' } });
                expect((await deleting).result.status).toBe('COMPLETED');
            }
        });

        it('parses the completed and the partial result and describes them in the contract\'s words', async () => {
            const completed = firstValueFrom(api.deleteMany(deckId, '7', revision, { itemIds: base.command.itemIds }, base.completed.commandId));
            http.expectOne(`/api/decks/${deckId}/items/deletions`).flush(base.completed, { headers: { ...headers, ETag: '"8"' } });
            const completedReceipt = await completed;
            expect(completedReceipt.result.status).toBe('COMPLETED');
            expect(deletionOutcomeText(completedReceipt.result)).toBe(base.clientMessages.completed);

            const partial = firstValueFrom(api.deleteMany(deckId, '7', revision, { allInDeck: true, except: base.commandAllInDeck.except }, base.partial.commandId));
            http.expectOne(`/api/decks/${deckId}/items/deletions`).flush(base.partial, { headers: { ...headers, ETag: '"8"' } });
            const partialReceipt = await partial;
            expect(partialReceipt.result.notDeleted).toEqual(base.partial.notDeleted);
            expect(deletionOutcomeText(partialReceipt.result)).toBe(base.clientMessages.partial);
            expect(STALE_DECK_MESSAGE).toBe(base.clientMessages.staleDeck);
        });

        it('accepts a replayed result only without an ETag and a fresh one only with the matching ETag', async () => {
            const replay = firstValueFrom(api.deleteMany(deckId, '7', revision, { itemIds: base.command.itemIds }, base.completed.commandId));
            http.expectOne(`/api/decks/${deckId}/items/deletions`).flush(base.completed, { headers: { ...headers, 'Idempotency-Replayed': 'true' } });
            expect((await replay).replayed).toBe(true);
            const wrong = firstValueFrom(api.deleteMany(deckId, '7', revision, { itemIds: base.command.itemIds }, base.completed.commandId));
            http.expectOne(`/api/decks/${deckId}/items/deletions`).flush(base.completed, { headers: { ...headers, ETag: '"9"' } });
            await expect(wrong).rejects.toThrowError(AuthoringProtocolError);
        });

        it('holds the result invariants of the contract and rejects results that break them', () => {
            for (const result of [base.completed, base.partial]) {
                const parsed = parseBulkDeleteResult(result);
                expect(parsed.requested).toBe(parsed.deleted + parsed.notDeleted.length);
                expect(parsed.status === 'COMPLETED').toBe(parsed.notDeleted.length === 0);
                if (parsed.status === 'PARTIAL') expect(parsed.deleted).toBeGreaterThan(0);
            }
            const broken: unknown[] = [
                { ...base.completed, deleted: 6 },
                { ...base.completed, notDeleted: ['44444444-4444-4444-8444-444444444441'] },
                { ...base.partial, deleted: 0, requested: 1 },
                { ...base.partial, stopReason: null },
                { ...base.partial, status: 'COMPLETED' },
                { ...base.partial, notDeleted: [base.partial.notDeleted[0], base.partial.notDeleted[0]], requested: 102 }
            ];
            for (const value of broken) expect(() => parseBulkDeleteResult(value)).toThrowError(AuthoringProtocolError);
        });

        it('reports the stale-deck and too-large problems by code', async () => {
            for (const [problem, status] of [[base.staleDeck, 412], [base.selectionTooLarge, 422]] as const) {
                const request = firstValueFrom(api.deleteMany(deckId, '7', revision, { itemIds: base.command.itemIds }, base.command.commandId));
                http.expectOne(`/api/decks/${deckId}/items/deletions`).flush(problem, { status, statusText: problem.title });
                expect(hubFailureOf(await request.catch((failure: unknown) => failure))).toEqual({ status, code: problem.code });
            }
            expect(base.selectionTooLarge.limit).toBe(hub.constants.bulkDeleteMaxSelection);
        });

        it('refuses selections the server would refuse before sending them', async () => {
            const ids = (count: number) => Array.from({ length: count }, (_, index) => `44444444-4444-4444-8444-${String(index).padStart(12, '0')}`);
            for (const selection of [{ itemIds: [] }, { itemIds: ids(hub.constants.listPageMax + 1) },
                { itemIds: [ids(1)[0], ids(1)[0]] }, { allInDeck: true as const, except: ids(hub.constants.bulkDeleteMaxSelection + 1) }]) {
                await expect(firstValueFrom(api.previewDeletion(deckId, revision, selection))).rejects.toThrowError(AuthoringProtocolError);
            }
            http.expectNone(`/api/decks/${deckId}/items/deletions/preview`);
            expect(selectionKey({ itemIds: ['b', 'a'] })).toBe(selectionKey({ itemIds: ['a', 'b'] }));
            expect(selectionKey({ allInDeck: true, except: ['a'] })).not.toBe(selectionKey({ itemIds: ['a'] }));
        });
    });

    it('keeps the contract constants this client relies on', () => {
        const typed: DeckInsights = parseInsights(hub.insights.response);
        expect(typed.dueByDay).toHaveLength(7);
        expect(hub.constants.bulkDeleteMaxSelection).toBe(500);
        expect(hub.constants.bulkDeleteChunkSize).toBe(100);
    });
});
