import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';

import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { ItemDetail, ItemSummary } from './authoring.models';
import { createEmptyNativeDocument } from '../../content/editing/native-editor-adapter';
import { BrowsePageComponent } from './browse-page.component';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from './capabilities-api.service';
import { MaterialNavigationApiService } from './material-navigation-api.service';
import { ItemApiService } from './item-api.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';

describe('BrowsePageComponent', () => {
    const deckId = '00000000-0000-4000-8000-000000000001';
    const revisionId = '00000000-0000-4000-8000-000000000002';
    const deck = { deckId, metadata: { title: 'Моя колода', description: '' } } as OwnDeck;
    const item = (ordinal: number): ItemSummary => ({
        memberKey: `00000000-0000-4000-8000-${String(ordinal + 10).padStart(12, '0')}`,
        title: 'Париж — столица Франции', itemRevisionId: revisionId, itemVersion: '1', formatVersion: 1, ordinal,
        createdAt: '2026-09-29T10:00:00Z', updatedAt: '2026-09-29T10:00:00Z', exerciseCount: null, exemplar: false, unavailable: false
    });
    let navigationApi: { read: ReturnType<typeof vi.fn> };
    let api: SpyObj<ItemApiService>;
    let fixture: ComponentFixture<BrowsePageComponent>;
    let capabilities: LearningCapabilities | 'error' = CAPABILITIES_UNAVAILABLE;

    beforeEach(() => {
        capabilities = CAPABILITIES_UNAVAILABLE;
        navigationApi = { read: vi.fn().mockImplementation((deckId: string, memberKey: string) => of({ deckId, memberKey,
            deckRevisionId: revisionId, deckVersion: '1', ordinal: 99999, total: 100000,
            previousMemberKey: item(0).memberKey, nextMemberKey: null })) };
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
                { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ deckId })), snapshot: { paramMap: convertToParamMap({ deckId }),
                            queryParamMap: convertToParamMap({}) } } },
                { provide: OwnDecksApiService, useValue: decks },
                { provide: CapabilitiesApiService, useValue: { read: () => capabilities === 'error' ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(capabilities) } },
                { provide: MaterialNavigationApiService, useValue: navigationApi },
                { provide: ItemApiService, useValue: api }
            ] });
    });

    function openMaterial() {
        const current = item(1);
        const detail: ItemDetail = { ...current, deckId, deckRevisionId: revisionId, deckVersion: '1',
            ordinal: 99999, exemplar: false, document: createEmptyNativeDocument() };
        TestBed.overrideProvider(ActivatedRoute, { useValue: { paramMap: of(convertToParamMap({ deckId })), snapshot: {
                    paramMap: convertToParamMap({ deckId, memberKey: detail.memberKey }),
                    queryParamMap: convertToParamMap({ ordinal: '999' })
                } } });
        api.read.mockReturnValue(of(detail));
        fixture = TestBed.createComponent(BrowsePageComponent);
        fixture.detectChanges();
        return { detail, component: fixture.componentInstance };
    }

    it('shows snapshot-bound neighbours and omits a non-existent next material', () => {
        const { detail } = openMaterial();
        const root = fixture.nativeElement as HTMLElement;
        expect(navigationApi.read).toHaveBeenCalledWith(deckId, detail.memberKey);
        expect(root.querySelector('a[rel="prev"]')?.getAttribute('href')).toBe(`/decks/${deckId}/materials/${item(0).memberKey}`);
        expect(root.querySelector('a[rel="next"]')).toBeNull();
        fixture.destroy();
    });

    it('keeps the document while hiding neighbours from a changed or unavailable order', () => {
        navigationApi.read.mockReturnValue(of({ deckId, memberKey: item(1).memberKey, deckRevisionId: revisionId,
            deckVersion: '2', ordinal: 99999, total: 100000, previousMemberKey: item(0).memberKey, nextMemberKey: null }));
        openMaterial();
        expect(fixture.componentInstance.navigationFailure()).toBe(true);
        expect((fixture.nativeElement as HTMLElement).querySelector('article.paper-surface')).not.toBeNull();
        expect((fixture.nativeElement as HTMLElement).querySelector('a[rel="prev"]')).toBeNull();
        navigationApi.read.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
        fixture.componentInstance.load(); fixture.detectChanges();
        expect(fixture.componentInstance.failure()).toBe(false);
        expect(fixture.componentInstance.navigationFailure()).toBe(true);
        fixture.destroy();
    });

    it('reloads a reused route and ignores late data from the previous material', () => {
        const first = { ...item(0), deckId, deckRevisionId: revisionId, deckVersion: '1', document: createEmptyNativeDocument() };
        const second = { ...item(1), deckId, deckRevisionId: revisionId, deckVersion: '1', document: createEmptyNativeDocument() };
        const pending = new Subject<ItemDetail>();
        const params = new BehaviorSubject(convertToParamMap({ deckId, memberKey: first.memberKey }));
        const route = { paramMap: params, snapshot: { paramMap: params.value } };
        TestBed.overrideProvider(ActivatedRoute, { useValue: route });
        api.read.mockReturnValueOnce(pending).mockReturnValueOnce(of(second));
        fixture = TestBed.createComponent(BrowsePageComponent); fixture.detectChanges();
        route.snapshot.paramMap = convertToParamMap({ deckId, memberKey: second.memberKey });
        params.next(route.snapshot.paramMap); fixture.detectChanges();
        expect(fixture.componentInstance.item()?.memberKey).toBe(second.memberKey);
        pending.next(first); fixture.detectChanges();
        expect(fixture.componentInstance.item()?.memberKey).toBe(second.memberKey);
        fixture.destroy();
    });

    it('keeps an uncertain deletion retry on its own material while browser history can open another', () => {
        const first = { ...item(0), deckId, deckRevisionId: revisionId, deckVersion: '1', document: createEmptyNativeDocument() };
        const second = { ...item(1), deckId, deckRevisionId: revisionId, deckVersion: '1', document: createEmptyNativeDocument() };
        const params = new BehaviorSubject(convertToParamMap({ deckId, memberKey: first.memberKey }));
        const route = { paramMap: params, snapshot: { paramMap: params.value } };
        TestBed.overrideProvider(ActivatedRoute, { useValue: route });
        api.read.mockReturnValueOnce(of(first)).mockReturnValueOnce(of(second));
        navigationApi.read.mockReturnValue(of({ deckId, memberKey: first.memberKey, deckRevisionId: revisionId,
            deckVersion: '1', ordinal: 0, total: 2, previousMemberKey: null, nextMemberKey: second.memberKey }));
        api.delete.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
        fixture = TestBed.createComponent(BrowsePageComponent); fixture.detectChanges();
        fixture.componentInstance.deleteItem(); fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('a[rel="next"]')).toBeNull();
        fixture.componentInstance.load();
        expect(api.read).toHaveBeenCalledTimes(1);
        route.snapshot.paramMap = convertToParamMap({ deckId, memberKey: second.memberKey });
        params.next(route.snapshot.paramMap); fixture.detectChanges();
        expect(fixture.componentInstance.item()?.memberKey).toBe(second.memberKey);
        expect(fixture.componentInstance.deleteMessage()).toBeNull();
        fixture.destroy();
    });

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

    describe('«Упражнения с ИИ» (AI-13)', () => {
        const aiLink = () => (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a[data-ai-exercises]');

        it('is offered next to «Упражнения» only when the server offers generation, and opens the builder for this material', () => {
            capabilities = { ...CAPABILITIES_UNAVAILABLE, aiGeneration: { available: true, reason: null } };
            const { detail } = openMaterial();
            expect(aiLink()?.textContent).toBe('Упражнения с ИИ');
            expect(aiLink()?.getAttribute('href')).toBe(`/decks/${deckId}/exercises/generate?members=${detail.memberKey}`);
            fixture.destroy();
        });

        it('is not there when generation is off: no dead button', () => {
            openMaterial();
            expect(aiLink()).toBeNull();
            expect((fixture.nativeElement as HTMLElement).textContent).toContain('Упражнения');
            fixture.destroy();
        });

        it('is not there when the capabilities cannot be read (fail closed)', () => {
            capabilities = 'error';
            openMaterial();
            expect(aiLink()).toBeNull();
            fixture.destroy();
        });
    });

    describe('«Попросить Мнему…» (AI-16)', () => {
        const ask = () => (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('app-ask-mnema');

        it('is one collapsed button under the material actions when the server offers generation, and about this material', () => {
            capabilities = { ...CAPABILITIES_UNAVAILABLE, aiGeneration: { available: true, reason: null } };
            const { detail } = openMaterial();
            expect(ask()?.querySelector('.ask-trigger')?.textContent).toContain('Попросить Мнему…');
            expect(ask()?.querySelector('textarea')).toBeNull();
            // between the header and the sheet
            const sheet = (fixture.nativeElement as HTMLElement).querySelector('article.paper-surface')!;
            expect(ask()!.compareDocumentPosition(sheet) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
            ask()!.querySelector<HTMLButtonElement>('.ask-trigger')!.click();
            fixture.detectChanges();
            expect(ask()?.querySelector('textarea')).not.toBeNull();
            expect(ask()?.textContent).toContain('Сделай все типы упражнений по 3');
            void detail;
            fixture.destroy();
        });

        it('is not there when generation is off', () => {
            openMaterial();
            expect(ask()).toBeNull();
            fixture.destroy();
        });

        it('is not there when the capabilities cannot be read (fail closed)', () => {
            capabilities = 'error';
            openMaterial();
            expect(ask()).toBeNull();
            fixture.destroy();
        });
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
        TestBed.overrideProvider(ActivatedRoute, { useValue: { paramMap: of(convertToParamMap({ deckId })), snapshot: {
                    paramMap: convertToParamMap({ deckId, memberKey: item(1).memberKey }), queryParamMap: convertToParamMap({}) } } });
        fixture = TestBed.createComponent(BrowsePageComponent);
        fixture.detectChanges();
        expect(fixture.componentInstance.failure()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('Не удалось загрузить материал');
        fixture.destroy();
    });

    it('shows a calm screen, not a failure, for a material taken down after a complaint (410)', () => {
        api.read.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 410 })));
        TestBed.overrideProvider(ActivatedRoute, { useValue: { paramMap: of(convertToParamMap({ deckId })), snapshot: {
                    paramMap: convertToParamMap({ deckId, memberKey: item(1).memberKey }), queryParamMap: convertToParamMap({}) } } });
        fixture = TestBed.createComponent(BrowsePageComponent);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(fixture.componentInstance.failure()).toBe(false);
        expect(fixture.componentInstance.takenDown()).toBe(true);
        expect(root.querySelector('h1')!.textContent).toBe('Материал недоступен');
        expect(root.textContent).toContain('снят по жалобе');
        expect(root.textContent).not.toContain('Не удалось загрузить');
        expect(root.querySelector('[role=alert]')).toBeNull();
        expect([...root.querySelectorAll('a')].map(link => link.getAttribute('href'))).toEqual([`/decks/${deckId}`, `/decks/${deckId}`]);
        expect(root.querySelector('a.button.primary')!.textContent).toContain('К материалам колоды');
        fixture.destroy();
    });
});
