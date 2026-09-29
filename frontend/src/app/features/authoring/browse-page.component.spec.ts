import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { ItemPage, ItemSummary } from './authoring.models';
import { BrowsePageComponent } from './browse-page.component';
import { ItemApiService } from './item-api.service';

describe('BrowsePageComponent', () => {
    const deckId = '00000000-0000-4000-8000-000000000001';
    const revisionId = '00000000-0000-4000-8000-000000000002';
    const deck = { deckId, metadata: { title: 'Моя колода', description: '' } } as OwnDeck;
    const item = (ordinal: number): ItemSummary => ({
        memberKey: `00000000-0000-4000-8000-${String(ordinal + 10).padStart(12, '0')}`,
        itemRevisionId: revisionId, itemVersion: '1', formatVersion: 1, ordinal,
        createdAt: '2026-09-29T10:00:00Z', updatedAt: '2026-09-29T10:00:00Z'
    });
    const first: ItemPage = { deckId, deckRevisionId: revisionId, deckVersion: '1', total: 2,
        items: [item(0)], nextCursor: 'next-page' };
    const second: ItemPage = { ...first, items: [item(1)], nextCursor: null };
    let api: jasmine.SpyObj<ItemApiService>;
    let fixture: ComponentFixture<BrowsePageComponent>;
    let onIntersection: IntersectionObserverCallback;
    const originalObserver = window.IntersectionObserver;

    afterEach(() => { window.IntersectionObserver = originalObserver; });

    beforeEach(() => {
        api = jasmine.createSpyObj<ItemApiService>('ItemApiService', ['list', 'read']);
        const decks = jasmine.createSpyObj<OwnDecksApiService>('OwnDecksApiService', ['detail']);
        decks.detail.and.returnValue(of(deck));
        window.IntersectionObserver = class {
            constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
                onIntersection = callback;
                expect(options?.rootMargin).toBe('0px 0px 800px 0px');
            }
            observe(): void { /* Observed through the saved callback below. */ }
            disconnect(): void { /* No browser resource is allocated in this test. */ }
        } as unknown as typeof IntersectionObserver;
        TestBed.configureTestingModule({ providers: [
            provideRouter([]),
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId }),
                queryParamMap: convertToParamMap({}) } } },
            { provide: OwnDecksApiService, useValue: decks },
            { provide: ItemApiService, useValue: api }
        ] });
    });

    it('prefetches the next page while preserving the visible first page', () => {
        const nextPage = new Subject<ItemPage>();
        api.list.and.returnValues(of(first), nextPage.asObservable());
        fixture = TestBed.createComponent(BrowsePageComponent);
        fixture.detectChanges();
        onIntersection([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver);
        fixture.detectChanges();
        expect(api.list.calls.count()).toBe(2);
        expect(fixture.nativeElement.textContent).toContain('Материал 1');
        expect(fixture.nativeElement.textContent).toContain('Загружаем следующие материалы');

        nextPage.next(second);
        nextPage.complete();
        fixture.detectChanges();
        expect(fixture.componentInstance.page()?.items.map(entry => entry.ordinal)).toEqual([0, 1]);
        expect(fixture.nativeElement.textContent).not.toContain('Показать ещё');
    });

    it('keeps loaded materials and offers retry after a later page fails', () => {
        api.list.and.returnValues(of(first), throwError(() => new Error('offline')));
        fixture = TestBed.createComponent(BrowsePageComponent);
        fixture.detectChanges();
        onIntersection([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver);
        fixture.detectChanges();
        expect(fixture.componentInstance.page()?.items).toEqual([item(0)]);
        expect(fixture.nativeElement.textContent).toContain('Не удалось загрузить следующие материалы');
        expect(fixture.nativeElement.querySelector('.list-sentinel button')?.textContent).toContain('Повторить');
    });
});
