import { convertToParamMap } from '@angular/router';

import {
    CATALOG_SORTS, CatalogFilters, NO_FILTERS, RELEVANCE_SORT, activeCount, fromQueryParams, sameFilters, toQueryParams, withoutFilter, withoutFilters
} from './catalog-filters';

const FULL: CatalogFilters = {
    topic: 'languages', learn: 'es', level: 'B1', size: 'month', media: ['audio', 'video'], mechanics: ['listening', 'order'], fresh: true, sort: 'new'
};

describe('catalog filters', () => {
    it('counts every chosen value, not the sorting', () => {
        expect(activeCount(NO_FILTERS)).toBe(0);
        expect(activeCount({ ...NO_FILTERS, sort: 'updated' })).toBe(0);
        expect(activeCount(FULL)).toBe(9);
        expect(activeCount({ ...NO_FILTERS, media: ['image'], fresh: true })).toBe(2);
    });

    it('draws only the four sortings that exist until «Для вас» arrives', () => {
        expect(CATALOG_SORTS.map(sort => sort.label)).toEqual(['Популярные', 'Учат сейчас', 'Новые', 'Обновлённые']);
    });

    it('knows «По близости к запросу» apart from the four wire sortings', () => {
        expect(RELEVANCE_SORT).toEqual({ value: 'relevance', label: 'По близости к запросу' });
        expect(CATALOG_SORTS.map(sort => sort.value)).not.toContain('relevance');
    });

    describe('the sorting behind a text query', () => {
        it('never writes «По близости»: it is the absence of sort with a text', () => {
            expect(toQueryParams({ ...NO_FILTERS, sort: 'relevance' }, true)).toEqual({});
            expect(toQueryParams({ ...NO_FILTERS, sort: 'relevance' })).toEqual({});
        });

        it('writes every wire sorting with a text, the default popular too, because a missing sort there means closeness', () => {
            expect(toQueryParams({ ...NO_FILTERS, sort: 'popular' }, true)).toEqual({ sort: 'popular' });
            expect(toQueryParams({ ...NO_FILTERS, sort: 'new' }, true)).toEqual({ sort: 'new' });
            expect(toQueryParams({ ...NO_FILTERS, sort: 'popular' }, false)).toEqual({});
        });

        it('reads a missing or unknown sort as closeness with a text and as popular without one', () => {
            expect(fromQueryParams({ q: 'кот' }).sort).toBe('relevance');
            expect(fromQueryParams({ q: 'кот', sort: 'bogus' }).sort).toBe('relevance');
            expect(fromQueryParams({ q: 'кот', sort: 'relevance' }).sort).toBe('relevance');
            expect(fromQueryParams({ q: 'кот', sort: 'popular' }).sort).toBe('popular');
            expect(fromQueryParams({ q: 'кот', sort: 'new' }).sort).toBe('new');
            expect(fromQueryParams({ q: '   ' }).sort).toBe('popular');
            expect(fromQueryParams({ sort: 'relevance' }).sort).toBe('popular');
            expect(fromQueryParams(convertToParamMap({ q: 'кот' })).sort).toBe('relevance');
        });

        it('round-trips both through a ParamMap', () => {
            for (const sort of ['relevance', 'popular', 'learning', 'new', 'updated'] as const) {
                const filters = { ...FULL, sort };
                expect(fromQueryParams(convertToParamMap({ q: 'кот', ...toQueryParams(filters, true) }))).toEqual(filters);
            }
            expect(fromQueryParams(convertToParamMap(toQueryParams({ ...FULL, sort: 'popular' })))).toEqual({ ...FULL, sort: 'popular' });
        });

        it('tells closeness from popular, though neither is written without a text', () => {
            expect(sameFilters({ ...NO_FILTERS, sort: 'relevance' }, { ...NO_FILTERS, sort: 'popular' })).toBe(false);
            expect(sameFilters({ ...NO_FILTERS, sort: 'relevance' }, { ...NO_FILTERS, sort: 'relevance' })).toBe(true);
        });
    });

    describe('query parameters', () => {
        it('writes the keys in a fixed order and leaves out what is off, empty or the default sorting', () => {
            expect(toQueryParams(NO_FILTERS)).toEqual({});
            expect(Object.keys(toQueryParams(FULL))).toEqual(['topic', 'learn', 'level', 'size', 'media', 'mechanics', 'fresh', 'sort']);
            expect(toQueryParams({ ...FULL, sort: 'popular' })).not.toHaveProperty('sort');
            expect(toQueryParams({ ...NO_FILTERS, fresh: true })).toEqual({ fresh: 'true' });
        });

        it('writes lists in the order of the catalogue lists and without repeats or unknown values', () => {
            const messy = { ...NO_FILTERS, media: ['video', 'audio', 'audio'], mechanics: ['order', 'listening'] } as unknown as CatalogFilters;
            expect(toQueryParams(messy)).toEqual({ media: ['audio', 'video'], mechanics: ['listening', 'order'] });
        });

        it('round-trips every combination through a ParamMap', () => {
            const params = toQueryParams(FULL);
            expect(fromQueryParams(convertToParamMap(params))).toEqual(FULL);
            expect(fromQueryParams(convertToParamMap(toQueryParams(NO_FILTERS)))).toEqual(NO_FILTERS);
            expect(fromQueryParams(params)).toEqual(FULL);
        });

        it('reads repeated parameters', () => {
            const filters = fromQueryParams(convertToParamMap({ media: ['video', 'image', 'video'], mechanics: ['written', 'choice'] }));
            expect(filters.media).toEqual(['image', 'video']);
            expect(filters.mechanics).toEqual(['written', 'choice']);
        });

        it('takes the first of a repeated single value', () => {
            const filters = fromQueryParams(convertToParamMap({ learn: ['de', 'es'], size: ['course', 'evening'] }));
            expect(filters.learn).toBe('de');
            expect(filters.size).toBe('course');
        });

        it('round-trips the private for-you choice; the page separately gates access', () => {
            const filters = fromQueryParams({ sort: 'for-you' });
            expect(filters.sort).toBe('for-you'); expect(toQueryParams(filters)).toEqual({ sort: 'for-you' });
        });

        it('drops everything unknown instead of passing it on', () => {
            const filters = fromQueryParams({
                topic: '<script>', learn: 'Spanish', level: 'D9', size: 'huge', media: ['audio', 'smell'], mechanics: ['telepathy'], fresh: '1', sort: 'unknown-sort'
            });
            expect(filters).toEqual({ ...NO_FILTERS, media: ['audio'] });
            expect(fromQueryParams({ topic: 'a'.repeat(65) }).topic).toBeNull();
            expect(fromQueryParams({ topic: '' }).topic).toBeNull();
            expect(fromQueryParams({ topic: 42, media: 7 })).toEqual(NO_FILTERS);
            expect(fromQueryParams({ topic: 'languages.spanish-1' }).topic).toBe('languages.spanish-1');
        });
    });

    describe('removing', () => {
        it('removes one filter and keeps the rest and the sorting', () => {
            expect(withoutFilter(FULL, { kind: 'topic' }).topic).toBeNull();
            expect(withoutFilter(FULL, { kind: 'learn' }).learn).toBeNull();
            expect(withoutFilter(FULL, { kind: 'level' }).level).toBeNull();
            expect(withoutFilter(FULL, { kind: 'size' }).size).toBeNull();
            expect(withoutFilter(FULL, { kind: 'fresh' }).fresh).toBe(false);
            expect(withoutFilter(FULL, { kind: 'media', value: 'audio' }).media).toEqual(['video']);
            expect(withoutFilter(FULL, { kind: 'mechanics', value: 'order' }).mechanics).toEqual(['listening']);
            expect(withoutFilter(FULL, { kind: 'topic' }).sort).toBe('new');
        });

        it('removes them all but keeps the sorting', () => {
            expect(withoutFilters(FULL)).toEqual({ ...NO_FILTERS, sort: 'new' });
        });
    });

    it('compares two values by what they say', () => {
        expect(sameFilters(FULL, { ...FULL, media: ['audio', 'video'] })).toBe(true);
        expect(sameFilters(FULL, { ...FULL, fresh: false })).toBe(false);
        expect(sameFilters(NO_FILTERS, { ...NO_FILTERS, sort: 'popular' })).toBe(true);
    });
});
