import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { ItemDetail, ItemSummary } from './authoring.models';
import { createEmptyNativeDocument } from '../../content/editing/native-editor-adapter';
import { BrowsePageComponent } from './browse-page.component';
import { ItemApiService } from './item-api.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';

describe('BrowsePageComponent', () => {
    const deckId = '00000000-0000-4000-8000-000000000001';
    const revisionId = '00000000-0000-4000-8000-000000000002';
    const deck = { deckId, metadata: { title: 'Моя колода', description: '' } } as OwnDeck;
    const item = (ordinal: number): ItemSummary => ({
        memberKey: `00000000-0000-4000-8000-${String(ordinal + 10).padStart(12, '0')}`,
        title: 'Париж — столица Франции', itemRevisionId: revisionId, itemVersion: '1', formatVersion: 1, ordinal,
        createdAt: '2026-09-29T10:00:00Z', updatedAt: '2026-09-29T10:00:00Z', exerciseCount: null, exemplar: false
    });
    let api: SpyObj<ItemApiService>;
    let fixture: ComponentFixture<BrowsePageComponent>;

    beforeEach(() => {
        api = spyObj<ItemApiService>({
            read: vi.fn().mockName("ItemApiService.read"),
            delete: vi.fn().mockName("ItemApiService.delete")
        });
        const decks = {
            detail: vi.fn().mockName("OwnDecksApiService.detail")
        };
        decks.detail.mockReturnValue(of(deck));
        TestBed.configureTestingModule({ providers: [
                provideRouter([]), provideHttpClient(),
                { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId }),
                            queryParamMap: convertToParamMap({}) } } },
                { provide: OwnDecksApiService, useValue: decks },
                { provide: ItemApiService, useValue: api }
            ] });
    });

    function openMaterial() {
        const current = item(1);
        const detail: ItemDetail = { ...current, deckId, deckRevisionId: revisionId, deckVersion: '1',
            ordinal: 99999, exemplar: false, document: createEmptyNativeDocument() };
        TestBed.overrideProvider(ActivatedRoute, { useValue: { snapshot: {
                    paramMap: convertToParamMap({ deckId, memberKey: detail.memberKey }),
                    queryParamMap: convertToParamMap({ ordinal: '999' })
                } } });
        api.read.mockReturnValue(of(detail));
        fixture = TestBed.createComponent(BrowsePageComponent);
        fixture.detectChanges();
        return { detail, component: fixture.componentInstance };
    }

    it('resolves a server ordinal and retries uncertain deletion with exactly the same command', () => {
        const { detail, component } = openMaterial();
        expect(component.selectedOrdinal()).toBe(99999);
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        api.delete.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 }))).mockReturnValueOnce(of({
            replayed: true, acknowledgement: { commandId: revisionId, deckId, deckRevisionId: revisionId,
                deckVersion: '2', memberCount: 1, changes: [] }
        }));
        component.deleteItem();
        expect(component.deleteMessage()).toContain('та же команда');
        component.deleteItem();
        const command = vi.mocked(api.delete).mock.calls[0];
        expect(command.slice(0, 6)).toEqual([deckId, detail.memberKey, '1', revisionId, detail.itemRevisionId, 99999]);
        expect(vi.mocked(api.delete).mock.lastCall).toEqual(command);
        expect(navigate).toHaveBeenCalledWith(['/decks', deckId]);
        fixture.destroy();
    });

    it('blocks repeated deletion after a stale snapshot until it is refreshed', () => {
        const { component } = openMaterial();
        api.delete.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 412 })));
        component.deleteItem();
        component.deleteItem();
        fixture.detectChanges();
        expect(api.delete).toHaveBeenCalledTimes(1);
        expect(component.selectedOrdinal()).toBeNull();
        expect(component.positionError()).toBe(true);
        expect(fixture.nativeElement.querySelector('app-hold-to-delete-button button').disabled).toBe(true);
        fixture.destroy();
    });

    it('never trusts a route position when the server position is unavailable', () => {
        const { detail, component } = openMaterial();
        api.read.mockReturnValue(of({ ...detail, ordinal: null }));
        component.load();
        component.deleteItem();
        expect(component.positionError()).toBe(true);
        expect(component.selectedOrdinal()).toBeNull();
        expect(api.delete).not.toHaveBeenCalled();
        fixture.destroy();
    });

    it('returns to the deck hub, which now lists the materials', () => {
        openMaterial();
        const back = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a.back-link');
        expect(back?.getAttribute('href')).toBe(`/decks/${deckId}`);
        expect(back?.textContent).toContain('К колоде');
        fixture.destroy();
    });

    it('shows a retryable failure when the material cannot be read', () => {
        api.read.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
        TestBed.overrideProvider(ActivatedRoute, { useValue: { snapshot: {
                    paramMap: convertToParamMap({ deckId, memberKey: item(1).memberKey }), queryParamMap: convertToParamMap({}) } } });
        fixture = TestBed.createComponent(BrowsePageComponent);
        fixture.detectChanges();
        expect(fixture.componentInstance.failure()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('Не удалось загрузить материал');
        fixture.destroy();
    });
});
