import { Observable, catchError, expand, forkJoin, map, of, reduce, EMPTY, take } from 'rxjs';

import { isCanonicalEntityId } from '../own-decks/own-deck.models';
import { ItemApiService } from '../authoring/item-api.service';
import { textProjections } from '../authoring/exercise.models';
import { BuilderTarget } from './exercise-builder';

/** Query parameter that carries the chosen materials: a comma list of member keys. */
export const MEMBERS_QUERY_PARAM = 'members';
/** «Все материалы колоды, кроме…»: `all=1` with the exceptions in `except`. */
export const ALL_QUERY_PARAM = 'all';
export const EXCEPT_QUERY_PARAM = 'except';

/** A longer list does not fit a URL a proxy accepts; the hub asks for a smaller selection. */
export const MAX_EXPLICIT_TARGETS = 100;
/** The scan of a whole deck stops here: ten sessions of 20 are already far more than three active sessions allow. */
const MAX_SCANNED_ITEMS = 200;
const MAX_SCANNED_PAGES = 12;
/** Up to this many materials are read one by one; more are found by paging the list, which also brings the counts. */
const READ_ONE_BY_ONE = 5;
const NO_TEXT = 'Материал без текста';

export interface TargetRequest {
    readonly members: readonly string[];
    readonly all: boolean;
    readonly except: readonly string[];
    /** Entries of the address that were not member keys: said to the user, never sent. */
    readonly rejected: number;
}

interface QueryReader { get(name: string): string | null; }

function readIds(value: string | null): { readonly ids: readonly string[]; readonly rejected: number } {
    if (value === null || value.trim() === '') return { ids: [], rejected: 0 };
    const seen = new Set<string>();
    let rejected = 0;
    for (const part of value.split(',')) {
        const id = part.trim().toLowerCase();
        if (isCanonicalEntityId(id)) seen.add(id); else rejected += 1;
    }
    return { ids: [...seen], rejected };
}

/** What the address asks for. Nothing in it is trusted: the revisions are looked up again by the page. */
export function parseTargetRequest(query: QueryReader): TargetRequest {
    const all = query.get(ALL_QUERY_PARAM) === '1';
    const members = readIds(query.get(MEMBERS_QUERY_PARAM));
    const except = readIds(query.get(EXCEPT_QUERY_PARAM));
    return { members: all ? [] : members.ids, all, except: all ? except.ids : [], rejected: members.rejected + except.rejected };
}

export function serializeIds(ids: readonly string[]): string {
    return ids.join(',');
}

export interface ResolvedTargets {
    readonly targets: readonly BuilderTarget[];
    /** Requested materials that are gone, or could not be read. */
    readonly missing: number;
    /** A whole-deck selection was longer than the scan reads. */
    readonly truncated: boolean;
}

/**
 * The current revision of every requested material, read now: a revision from a stale page would be refused by the server
 * (`SOURCE_UNAVAILABLE`), and the page must never present an old one. A few materials are read one by one; a larger selection is
 * found in the list, newest information first (`sort=exerciseCount`), which also brings how many exercises each already has.
 */
export function resolveTargets(items: Pick<ItemApiService, 'read' | 'list'>, deckId: string, request: TargetRequest): Observable<ResolvedTargets> {
    if (!request.all && request.members.length <= READ_ONE_BY_ONE) {
        return forkJoin(request.members.map(member => items.read(deckId, member).pipe(
            map((item): BuilderTarget => ({ memberKey: item.memberKey, itemRevisionId: item.itemRevisionId,
                title: textProjections(item.document)[0]?.label ?? NO_TEXT, exerciseCount: null })),
            catchError(() => of(null))
        ))).pipe(map(found => {
            const targets = found.filter((target): target is BuilderTarget => target !== null);
            return { targets, missing: found.length - targets.length, truncated: false };
        }));
    }
    const wanted = new Set(request.members);
    const except = new Set(request.except);
    return items.list(deckId, { sort: 'exerciseCount' }).pipe(
        expand((page, index) => page.nextCursor === null || index + 1 >= MAX_SCANNED_PAGES ? EMPTY
            : items.list(deckId, { sort: 'exerciseCount', cursor: page.nextCursor })),
        take(MAX_SCANNED_PAGES),
        reduce((state, page) => {
            for (const item of page.items) {
                const chosen = request.all ? !except.has(item.memberKey) : wanted.has(item.memberKey);
                if (chosen && state.targets.length < MAX_SCANNED_ITEMS) {
                    state.targets.push({ memberKey: item.memberKey, itemRevisionId: item.itemRevisionId,
                        title: item.title || NO_TEXT, exerciseCount: item.exerciseCount });
                } else if (chosen) {
                    state.truncated = true;
                }
            }
            state.more = page.nextCursor !== null;
            return state;
        }, { targets: [] as BuilderTarget[], truncated: false, more: false }),
        map(state => ({
            targets: state.targets,
            missing: request.all ? 0 : request.members.length - state.targets.length,
            truncated: state.truncated || (request.all && state.more)
        }))
    );
}
