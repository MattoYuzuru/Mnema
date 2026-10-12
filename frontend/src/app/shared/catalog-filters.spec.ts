import { convertToParamMap } from '@angular/router';

import {
    CATALOG_SORTS, CatalogFilters, NO_FILTERS, activeCount, fromQueryParams, sameFilters, toQueryParams, withoutFilter, withoutFilters
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

        it('drops everything unknown instead of passing it on', () => {
            const filters = fromQueryParams({
                topic: '<script>', learn: 'Spanish', level: 'D9', size: 'huge', media: ['audio', 'smell'], mechanics: ['telepathy'], fresh: '1', sort: 'for-you'
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
