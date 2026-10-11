import { DestroyRef, Injector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';

import { AccessIdentities } from './access-identities';
import { focusNearestAction } from './access-focus';
import { AccessList } from './access-list';
import { KeysetPage } from './access.models';

interface Row { readonly id: string }
const row = (id: string): Row => ({ id });
const page = (ids: readonly string[], nextCursor: string | null = null): KeysetPage<Row> => ({ items: ids.map(row), nextCursor });

describe('AccessList', () => {
    let fetch: ReturnType<typeof vi.fn<(cursor: string | null) => Observable<KeysetPage<Row>>>>;
    let list: AccessList<Row>;

    beforeEach(() => {
        fetch = vi.fn<(cursor: string | null) => Observable<KeysetPage<Row>>>();
        list = runInInjectionContext(TestBed.inject(Injector), () => new AccessList<Row>(fetch, item => item.id, TestBed.inject(DestroyRef), 'more failed'));
    });

    it('loads the first page, then appends the next without duplicates and stops at the end', () => {
        fetch.mockReturnValueOnce(of(page(['a', 'b'], 'c1')));
        list.reload();
        expect(list.phase()).toBe('ready');
        expect(list.items().map(item => item.id)).toEqual(['a', 'b']);
        fetch.mockReturnValueOnce(of(page(['b', 'c'])));
        list.more();
        expect(fetch).toHaveBeenLastCalledWith('c1');
        expect(list.items().map(item => item.id)).toEqual(['a', 'b', 'c']);
        expect(list.next()).toBeNull();
        list.more();
        expect(fetch).toHaveBeenCalledTimes(2);
    });

    it('treats a repeated cursor as the end instead of looping', () => {
        fetch.mockReturnValueOnce(of(page(['a'], 'same')));
        list.reload();
        fetch.mockReturnValueOnce(of(page(['b'], 'same')));
        list.more();
        expect(list.next()).toBeNull();
    });

    it('shows an error for a failed first page, and keeps the rows for a failed reload or continuation', () => {
        fetch.mockReturnValueOnce(throwError(() => new Error('x')));
        list.reload();
        expect(list.phase()).toBe('error');
        fetch.mockReturnValueOnce(of(page(['a'], 'c1')));
        list.reload();
        expect(list.phase()).toBe('ready');
        fetch.mockReturnValueOnce(throwError(() => new Error('x')));
        list.more();
        expect(list.moreError()).toBe('more failed');
        expect(list.loadingMore()).toBe(false);
        expect(list.items()).toHaveLength(1);
        fetch.mockReturnValueOnce(throwError(() => new Error('x')));
        list.reload();
        expect(list.phase()).toBe('ready');
        expect(list.items()).toHaveLength(1);
        expect(list.moreError()).toBeNull();
    });

    it('discards the answer of an older load', () => {
        const first = new Subject<KeysetPage<Row>>();
        fetch.mockReturnValueOnce(first);
        list.reload();
        fetch.mockReturnValueOnce(of(page(['new'])));
        list.reload();
        first.next(page(['old']));
        expect(list.items().map(item => item.id)).toEqual(['new']);
        const before = list.context();
        fetch.mockReturnValueOnce(of(page(['x'])));
        list.reload();
        expect(list.context()).toBe(before + 1);
    });

    it('removes and replaces rows in place', () => {
        fetch.mockReturnValueOnce(of(page(['a', 'b'])));
        list.reload();
        list.replace({ id: 'b' });
        list.remove('a');
        expect(list.items().map(item => item.id)).toEqual(['b']);
    });
});

describe('AccessIdentities', () => {
    const ID = '0192f3a4-5b6c-7d8e-9f01-23456789abcd';
    const OTHER = '0192f3a4-5b6c-7d8e-9f01-23456789abce';

    it('resolves a card, a missing card and a failure, and asks again only for the failed one', async () => {
        const cards = {
            cards: vi.fn().mockResolvedValueOnce(new Map([[ID, { accountId: ID, profileUsername: 'anna.k', displayName: null, bio: null, avatarPresent: true }]])),
            avatarUrl: vi.fn().mockReturnValue('/avatar')
        };
        const identities = new AccessIdentities(cards);
        expect(identities.of(ID)).toEqual({ state: 'loading' });
        identities.resolve([ID, OTHER, ID]);
        expect(cards.cards).toHaveBeenCalledWith([ID, OTHER]);
        await Promise.resolve();
        await Promise.resolve();
        expect(identities.of(ID)).toEqual({ state: 'card', username: 'anna.k', avatarSrc: '/avatar' });
        expect(identities.of(OTHER)).toEqual({ state: 'none' });
        identities.resolve([ID, OTHER]);
        expect(cards.cards).toHaveBeenCalledTimes(1);

        cards.cards.mockRejectedValueOnce(new Error('offline'));
        identities.resolve(['0192f3a4-5b6c-7d8e-9f01-23456789abcf']);
        await Promise.resolve();
        await Promise.resolve();
        expect(identities.of('0192f3a4-5b6c-7d8e-9f01-23456789abcf')).toEqual({ state: 'failed' });
        cards.cards.mockResolvedValueOnce(new Map());
        identities.resolve(['0192f3a4-5b6c-7d8e-9f01-23456789abcf']);
        expect(cards.cards).toHaveBeenCalledTimes(3);
    });
});

describe('focusNearestAction', () => {
    function host(html: string): HTMLElement {
        const element = document.createElement('div');
        element.innerHTML = html;
        document.body.append(element);
        return element;
    }

    it('focuses the action of the row that took the place, else the closest before, else the fallback', () => {
        const element = host('<ul><li class="roster-row"><button id="a">a</button></li><li class="roster-row"><button id="b" aria-disabled="true">b</button></li><li class="roster-row">none</li></ul><h3 tabindex="-1" id="h">h</h3>');
        const fallback = element.querySelector<HTMLElement>('#h')!;
        focusNearestAction(element, 1, fallback);
        expect(document.activeElement?.id).toBe('a');
        element.querySelector('#a')!.remove();
        focusNearestAction(element, 0, fallback);
        expect(document.activeElement).toBe(fallback);
        element.remove();
    });
});
