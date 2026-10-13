/**
 * The filters and the sorting of the community catalogue as a plain value, and the pure helpers around it: the lists the
 * controls are drawn from, the round trip to the address bar (so a search can be shared and survives a reload) and the
 * counters. The component {@link CatalogFiltersComponent} only draws this value and reports a new one.
 */

export const CATALOG_LEVELS = [
    { value: 'A1', label: 'A1' }, { value: 'A2', label: 'A2' }, { value: 'B1', label: 'B1' },
    { value: 'B2', label: 'B2' }, { value: 'C1', label: 'C1' }, { value: 'C2', label: 'C2' },
    { value: 'BEGINNER', label: 'Начальный' }, { value: 'INTERMEDIATE', label: 'Средний' }, { value: 'ADVANCED', label: 'Продвинутый' }
] as const;

/** «Размер»: by the number of materials. `month` is 50 to 300 inclusive, `course` is more than 300. */
export const CATALOG_SIZES = [
    { value: 'evening', label: 'На вечер', hint: 'Меньше 50 материалов' },
    { value: 'month', label: 'На месяц', hint: 'От 50 до 300 материалов' },
    { value: 'course', label: 'Большой курс', hint: 'Больше 300 материалов' }
] as const;

/** «Внутри». */
export const CATALOG_MEDIA = [
    { value: 'audio', label: 'Аудио' }, { value: 'image', label: 'Картинки' }, { value: 'video', label: 'Видео' }
] as const;

/** «Упражнения». */
export const CATALOG_MECHANICS = [
    { value: 'listening', label: 'На слух' }, { value: 'matching', label: 'Сопоставление' }, { value: 'written', label: 'Письменный ответ' },
    { value: 'choice', label: 'Выбор варианта' }, { value: 'order', label: 'По порядку' }
] as const;

/**
 * The sortings that are drawn. «Для вас» arrives with the recommendations (epic 4): it joins this list, and with it the type, the
 * address parameter and the default for a signed-in reader. Until then it is neither drawn nor accepted from the address.
 */
export const CATALOG_SORTS = [
    { value: 'popular', label: 'Популярные' }, { value: 'learning', label: 'Учат сейчас' },
    { value: 'new', label: 'Новые' }, { value: 'updated', label: 'Обновлённые' }
] as const;

/**
 * «По близости к запросу»: the order of a text search. It is not a wire sorting: with a text query it is the absence of `sort` in the address and in
 * the request, and it is offered (and valid) only while there is a text query.
 */
export const RELEVANCE_SORT = { value: 'relevance', label: 'По близости к запросу' } as const;

export type CatalogLevel = typeof CATALOG_LEVELS[number]['value'];
export type CatalogSize = typeof CATALOG_SIZES[number]['value'];
export type CatalogMedia = typeof CATALOG_MEDIA[number]['value'];
export type CatalogMechanic = typeof CATALOG_MECHANICS[number]['value'];
export type CatalogWireSort = typeof CATALOG_SORTS[number]['value'];
export type CatalogSort = CatalogWireSort | typeof RELEVANCE_SORT.value | 'for-you';

/** The sorting of the browse list: no `sort` in the address, no `sort` unless it is chosen. */
export const DEFAULT_SORT: CatalogWireSort = 'popular';

export interface CatalogFilters {
    /** A topic id, a leaf or a parent. */
    readonly topic: string | null;
    /** «Что учим»: the primary subtag of a BCP 47 language code (`es`). */
    readonly learn: string | null;
    readonly level: string | null;
    readonly size: CatalogSize | null;
    readonly media: readonly CatalogMedia[];
    readonly mechanics: readonly CatalogMechanic[];
    /** «Живая»: updated within the last month. */
    readonly fresh: boolean;
    readonly sort: CatalogSort;
}

export const NO_FILTERS: CatalogFilters = { topic: null, learn: null, level: null, size: null, media: [], mechanics: [], fresh: false, sort: DEFAULT_SORT };

/** A topic as the page knows it. A child names its parent, and the topic list draws the parent as a group heading. */
export interface TopicOption { readonly id: string; readonly name: string; readonly parentName?: string }
export interface LanguageOption { readonly code: string; readonly name: string }

/** The number of filters that are on: every chosen value counts, the sorting does not. */
export function activeCount(filters: CatalogFilters): number {
    return (filters.topic === null ? 0 : 1) + (filters.learn === null ? 0 : 1) + (filters.level === null ? 0 : 1) + (filters.size === null ? 0 : 1)
        + filters.media.length + filters.mechanics.length + (filters.fresh ? 1 : 0);
}

/** One removable filter, as a chip: what to remove and what the chip says. */
export type FilterRef =
    | { readonly kind: 'topic' | 'learn' | 'level' | 'size' | 'fresh' }
    | { readonly kind: 'media'; readonly value: CatalogMedia }
    | { readonly kind: 'mechanics'; readonly value: CatalogMechanic };

/** The same filters without one of them; the sorting stays. */
export function withoutFilter(filters: CatalogFilters, ref: FilterRef): CatalogFilters {
    switch (ref.kind) {
        case 'topic': return { ...filters, topic: null };
        case 'learn': return { ...filters, learn: null };
        case 'level': return { ...filters, level: null };
        case 'size': return { ...filters, size: null };
        case 'fresh': return { ...filters, fresh: false };
        case 'media': return { ...filters, media: filters.media.filter(value => value !== ref.value) };
        case 'mechanics': return { ...filters, mechanics: filters.mechanics.filter(value => value !== ref.value) };
    }
}

/** Every filter off; the sorting stays as the reader chose it. */
export function withoutFilters(filters: CatalogFilters): CatalogFilters { return { ...NO_FILTERS, sort: filters.sort }; }

/** A value of a list in the canonical order of that list, without repeats. */
function canonical<T extends string>(list: readonly { readonly value: T }[], values: readonly string[]): T[] {
    return list.map(item => item.value).filter(value => values.includes(value));
}

/**
 * The address parameters of the filters, in a fixed key order; what is off or empty is left out. The sorting: «По близости» is never written (it is the
 * absence of `sort` with a text query); without a text query the default `popular` is left out; with a text query every other sorting, `popular` too,
 * is written, because a missing `sort` there would mean relevance.
 */
export function toQueryParams(filters: CatalogFilters, searching = false): Record<string, string | string[]> {
    const params: Record<string, string | string[]> = {};
    if (filters.topic !== null) params['topic'] = filters.topic;
    if (filters.learn !== null) params['learn'] = filters.learn;
    if (filters.level !== null) params['level'] = filters.level;
    if (filters.size !== null) params['size'] = filters.size;
    const media = canonical(CATALOG_MEDIA, filters.media);
    if (media.length > 0) params['media'] = media;
    const mechanics = canonical(CATALOG_MECHANICS, filters.mechanics);
    if (mechanics.length > 0) params['mechanics'] = mechanics;
    if (filters.fresh) params['fresh'] = 'true';
    if (filters.sort !== RELEVANCE_SORT.value && (searching || filters.sort !== DEFAULT_SORT)) params['sort'] = filters.sort;
    return params;
}

/** Anything that reads like Angular's `ParamMap` or like a plain `Params` object. */
export type QueryParamsSource = { getAll(name: string): string[] } | Readonly<Record<string, unknown>>;

function all(source: QueryParamsSource, name: string): string[] {
    if ('getAll' in source && typeof source.getAll === 'function') return source.getAll(name);
    const value = (source as Readonly<Record<string, unknown>>)[name];
    const list = Array.isArray(value) ? value : [value];
    return list.filter((item): item is string => typeof item === 'string');
}

const TOPIC_ID = /^[\p{L}\p{N}][\p{L}\p{N}_.:-]{0,63}$/u;
const LANGUAGE = /^[a-z]{2,3}$/;

/**
 * The filters of an address. Strict: a value that is not one of the known ones is dropped (it never reaches the server),
 * a repeated single value takes the first, a repeated list keeps each known value once in the order of the list. No (or an unknown) `sort` is
 * «По близости к запросу» with a text query `q` and `popular` without one.
 */
export function fromQueryParams(source: QueryParamsSource): CatalogFilters {
    const first = (name: string): string | null => all(source, name)[0] ?? null;
    const topic = first('topic');
    const learn = first('learn');
    const level = first('level');
    const size = first('size');
    const sort = first('sort');
    const searching = (first('q') ?? '').trim() !== '';
    return {
        topic: topic !== null && TOPIC_ID.test(topic) ? topic : null,
        learn: learn !== null && LANGUAGE.test(learn) ? learn : null,
        level: CATALOG_LEVELS.find(item => item.value === level)?.value ?? null,
        size: CATALOG_SIZES.find(item => item.value === size)?.value ?? null,
        media: canonical(CATALOG_MEDIA, all(source, 'media')),
        mechanics: canonical(CATALOG_MECHANICS, all(source, 'mechanics')),
        fresh: first('fresh') === 'true',
        sort: sort === 'for-you' ? 'for-you' : CATALOG_SORTS.find(item => item.value === sort)?.value ?? (searching ? RELEVANCE_SORT.value : DEFAULT_SORT)
    };
}

/** The filters are the same value (lists compared in order; both sides come from {@link fromQueryParams} or the component). */
export function sameFilters(a: CatalogFilters, b: CatalogFilters): boolean {
    return a.sort === b.sort && JSON.stringify(toQueryParams(a)) === JSON.stringify(toQueryParams(b));
}
