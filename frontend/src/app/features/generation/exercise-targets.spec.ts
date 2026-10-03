import { HttpErrorResponse } from '@angular/common/http';
import { convertToParamMap } from '@angular/router';
import { firstValueFrom, of, throwError } from 'rxjs';

import { ItemDetail, ItemPage, ItemSummary } from '../authoring/authoring.models';
import { ItemApiService } from '../authoring/item-api.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { documentOf } from './exercise-test-data';
import { MAX_EXPLICIT_TARGETS, parseTargetRequest, resolveTargets, serializeIds } from './exercise-targets';
import { ids } from './generation-test-data';

const key = (index: number) => `44444444-4444-4444-8444-${String(index).padStart(12, '0')}`;
const revision = (index: number) => `55555555-5555-4555-8555-${String(index).padStart(12, '0')}`;
const row = (index: number, exerciseCount: number | null = 0): ItemSummary => ({ memberKey: key(index), itemRevisionId: revision(index), itemVersion: '1',
    ordinal: index, formatVersion: 1, createdAt: '2026-09-12T12:00:00Z', updatedAt: '2026-09-12T12:00:00Z', title: `Материал ${index}`, exerciseCount, exemplar: false });
const page = (from: number, count: number, total: number): ItemPage => ({ deckId: ids.deckId, deckRevisionId: ids.deckRevision, deckVersion: '7', total,
    exemplars: { count: 0, limit: 10 }, items: Array.from({ length: count }, (_, index) => row(from + index)), nextCursor: from + count < total ? `c${from + count}` : null });
const detail = (index: number, text = `Текст ${index}`): ItemDetail => ({ memberKey: key(index), itemRevisionId: revision(index), itemVersion: '1', formatVersion: 1,
    createdAt: '2026-09-12T12:00:00Z', updatedAt: '2026-09-12T12:00:00Z', ordinal: index, exemplar: false, deckId: ids.deckId, deckRevisionId: ids.deckRevision,
    deckVersion: '7', document: documentOf(text) });

describe('exercise targets', () => {
    describe('the address', () => {
        const query = (params: Record<string, string>) => convertToParamMap(params);

        it('reads the members once each, lower-cased, and counts what is not a member key', () => {
            const upper = key(1).toUpperCase();
            expect(parseTargetRequest(query({ members: `${upper},${key(2)},${key(1)},nope, ` }))).toEqual({ members: [key(1), key(2)], all: false, except: [], rejected: 2 });
        });

        it('reads «all except» and ignores members then', () => {
            expect(parseTargetRequest(query({ all: '1', except: `${key(3)},bad`, members: key(1) }))).toEqual({ members: [], all: true, except: [key(3)], rejected: 1 });
            expect(parseTargetRequest(query({ all: '1' }))).toEqual({ members: [], all: true, except: [], rejected: 0 });
        });

        it('has no members when nothing is asked for', () => {
            expect(parseTargetRequest(query({}))).toEqual({ members: [], all: false, except: [], rejected: 0 });
            expect(parseTargetRequest(query({ all: '0' })).all).toBe(false);
            expect(serializeIds([key(1), key(2)])).toBe(`${key(1)},${key(2)}`);
            expect(MAX_EXPLICIT_TARGETS).toBe(100);
        });
    });

    describe('resolving the current revisions', () => {
        let items: SpyObj<ItemApiService>;
        beforeEach(() => { items = spyObj<ItemApiService>({ read: vi.fn(), list: vi.fn() }); });
        const run = (request: Parameters<typeof resolveTargets>[2]) => firstValueFrom(resolveTargets(items, ids.deckId, request));

        it('reads a few materials one by one: the current revision and the first line as the title', async () => {
            items.read.mockImplementation((_deck, member) => of(detail(member === key(1) ? 1 : 2)));
            const result = await run({ members: [key(1), key(2)], all: false, except: [], rejected: 0 });
            expect(result.targets).toEqual([{ memberKey: key(1), itemRevisionId: revision(1), title: 'Текст 1', exerciseCount: null },
                { memberKey: key(2), itemRevisionId: revision(2), title: 'Текст 2', exerciseCount: null }]);
            expect(items.read).toHaveBeenCalledWith(ids.deckId, key(1));
            expect(items.list).not.toHaveBeenCalled();
            expect(result).toMatchObject({ missing: 0, truncated: false });
        });

        it('counts a material that cannot be read as missing, and names a material without text', async () => {
            items.read.mockImplementation((_deck, member) => member === key(1) ? throwError(() => new HttpErrorResponse({ status: 404 })) : of({ ...detail(2), document: documentOf() }));
            const result = await run({ members: [key(1), key(2)], all: false, except: [], rejected: 0 });
            expect(result.missing).toBe(1);
            expect(result.targets).toEqual([{ memberKey: key(2), itemRevisionId: revision(2), title: 'Материал без текста', exerciseCount: null }]);
        });

        it('finds a larger selection by paging the list without exercises first, with the counts', async () => {
            items.list.mockImplementation((_deck, options) => of(options?.cursor == null ? page(1, 20, 45) : options.cursor === 'c21' ? page(21, 20, 45) : page(41, 5, 45)));
            const wanted = [key(2), key(25), key(44), key(900)];
            const result = await run({ members: [...wanted, key(3), key(4)], all: false, except: [], rejected: 0 });
            expect(items.list).toHaveBeenCalledTimes(3);
            expect(items.list).toHaveBeenCalledWith(ids.deckId, { sort: 'exerciseCount' });
            expect(result.targets.map(target => target.memberKey)).toEqual([key(2), key(3), key(4), key(25), key(44)]);
            expect(result.targets[0]).toEqual({ memberKey: key(2), itemRevisionId: revision(2), title: 'Материал 2', exerciseCount: 0 });
            expect(result.missing).toBe(1);
        });

        it('takes the whole deck minus the exceptions, and says when the deck is longer than it reads', async () => {
            items.list.mockImplementation((_deck, options) => {
                const from = options?.cursor == null ? 0 : Number(options.cursor.slice(1));
                return of(page(from, 20, 400));
            });
            const result = await run({ members: [], all: true, except: [key(2)], rejected: 0 });
            expect(items.list.mock.calls.length).toBeLessThanOrEqual(12);
            expect(result.targets.length).toBeLessThanOrEqual(200);
            expect(result.targets.some(target => target.memberKey === key(2))).toBe(false);
            expect(result.truncated).toBe(true);
            expect(result.missing).toBe(0);
        });

        it('takes a short deck whole', async () => {
            items.list.mockReturnValue(of(page(0, 3, 3)));
            const result = await run({ members: [], all: true, except: [], rejected: 0 });
            expect(result.targets).toHaveLength(3);
            expect(result.truncated).toBe(false);
        });
    });
});
