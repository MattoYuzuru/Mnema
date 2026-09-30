import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { ItemDetail, ItemPage, ItemSummary } from './authoring.models';
import { createEmptyNativeDocument } from '../../content/editing/native-editor-adapter';
import { BrowsePageComponent } from './browse-page.component';
import { ItemApiService } from './item-api.service';
import { AuthoringApiService } from './authoring-api.service';

describe('BrowsePageComponent', () => {
    const deckId = '00000000-0000-4000-8000-000000000001';
    const revisionId = '00000000-0000-4000-8000-000000000002';
    const deck = { deckId, metadata: { title: 'Моя колода', description: '' } } as OwnDeck;
    const item = (ordinal: number): ItemSummary => ({
        memberKey: `00000000-0000-4000-8000-${String(ordinal + 10).padStart(12, '0')}`,
        title: 'Париж — столица Франции', itemRevisionId: revisionId, itemVersion: '1', formatVersion: 1, ordinal,
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
        api = jasmine.createSpyObj<ItemApiService>('ItemApiService', ['list', 'read', 'delete']);
        const decks = jasmine.createSpyObj<OwnDecksApiService>('OwnDecksApiService', ['detail']);
        decks.detail.and.returnValue(of(deck));
        const authoring = jasmine.createSpyObj<AuthoringApiService>('AuthoringApiService', ['listDeckCaptures']);
        authoring.listDeckCaptures.and.returnValue(of({ items: [], nextCursor: null, total: 3 }));
        window.IntersectionObserver = class {
            constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
                onIntersection = callback;
                expect(options?.rootMargin).toBe('0px 0px 800px 0px');
            }
            observe(): void { /* Observed through the saved callback below. */ }
            disconnect(): void { /* No browser resource is allocated in this test. */ }
        } as unknown as typeof IntersectionObserver;
        TestBed.configureTestingModule({ providers: [
            provideRouter([]), provideHttpClient(),
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId }),
                queryParamMap: convertToParamMap({}) } } },
            { provide: OwnDecksApiService, useValue: decks },
            { provide: AuthoringApiService, useValue: authoring },
            { provide: ItemApiService, useValue: api }
        ] });
    });

    function openMaterial() {
        const current = item(1);
        const detail: ItemDetail = { ...current, deckId, deckRevisionId: revisionId, deckVersion: '1',
            ordinal: 99_999, document: createEmptyNativeDocument() };
        TestBed.overrideProvider(ActivatedRoute, { useValue: { snapshot: {
            paramMap: convertToParamMap({ deckId, memberKey: detail.memberKey }),
            queryParamMap: convertToParamMap({ ordinal: '999' })
        } } });
        api.read.and.returnValue(of(detail));
        fixture = TestBed.createComponent(BrowsePageComponent);
        fixture.detectChanges();
        return { detail, component: fixture.componentInstance };
    }

    it('resolves a server ordinal and retries uncertain deletion with exactly the same command', () => {
        const { detail, component } = openMaterial();
        expect(component.selectedOrdinal()).toBe(99_999);
        expect(api.list).not.toHaveBeenCalled();
        const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
        api.delete.and.returnValues(throwError(() => new HttpErrorResponse({ status: 0 })), of({
            replayed: true, acknowledgement: { commandId: revisionId, deckId, deckRevisionId: revisionId,
                deckVersion: '2', memberCount: 1, changes: [] }
        }));
        component.deleteItem();
        expect(component.deleteMessage()).toContain('та же команда');
        component.deleteItem();
        const command = api.delete.calls.first().args;
        expect(command.slice(0, 6)).toEqual([deckId, detail.memberKey, '1', revisionId, detail.itemRevisionId, 99_999]);
        expect(api.delete.calls.mostRecent().args).toEqual(command);
        expect(navigate).toHaveBeenCalledWith(['/decks', deckId, 'materials']);
        fixture.destroy();
    });

    it('blocks repeated deletion after a stale snapshot until it is refreshed', () => {
        const { component } = openMaterial();
        api.delete.and.returnValue(throwError(() => new HttpErrorResponse({ status: 412 })));
        component.deleteItem(); component.deleteItem(); fixture.detectChanges();
        expect(api.delete).toHaveBeenCalledTimes(1);
        expect(component.selectedOrdinal()).toBeNull();
        expect(component.positionError()).toBeTrue();
        expect(fixture.nativeElement.querySelector('app-hold-to-delete-button button').disabled).toBeTrue();
        fixture.destroy();
    });

    it('never trusts a route position when the server position is unavailable', () => {
        const { detail, component } = openMaterial();
        api.read.and.returnValue(of({ ...detail, ordinal: null }));
        component.load(); component.deleteItem();
        expect(component.positionError()).toBeTrue();
        expect(component.selectedOrdinal()).toBeNull();
        expect(api.list).not.toHaveBeenCalled();
        expect(api.delete).not.toHaveBeenCalled();
        fixture.destroy();
    });

    it('prefetches the next page while preserving the visible first page', () => {
        const nextPage = new Subject<ItemPage>();
        api.list.and.returnValues(of(first), nextPage.asObservable());
        fixture = TestBed.createComponent(BrowsePageComponent);
        fixture.detectChanges();
        onIntersection([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver);
        fixture.detectChanges();
        expect(api.list.calls.count()).toBe(2);
        expect(fixture.nativeElement.textContent).toContain('Париж — столица Франции');
        expect((fixture.nativeElement as HTMLElement).querySelector('.capture-badge')?.textContent?.trim()).toBe('3');
        fixture.componentInstance.captureCount.set(1_000);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('.capture-badge')?.textContent?.trim()).toBe('999+');
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
