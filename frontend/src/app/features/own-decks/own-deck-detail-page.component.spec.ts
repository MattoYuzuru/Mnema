import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import hubFixture from '../../../../../contracts/decks/hub.json';
import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { OwnDeck } from './own-deck.models';
import { DeckDetailState, DeckMutationState, OwnDecksStore } from './own-decks.store';
import { OwnDeckDetailPageComponent } from './own-deck-detail-page.component';
import { OwnDeckRecoveryService } from './own-deck-recovery.service';
import { AuthoringApiService } from '../authoring/authoring-api.service';
import { ItemPage } from '../authoring/authoring.models';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from '../authoring/capabilities-api.service';
import { ItemApiService } from '../authoring/item-api.service';
import { GenerationApiService } from '../generation/generation-api.service';
import { DeckHubApiService } from './hub/deck-hub-api.service';
import { DeckInsights } from './hub/deck-hub.models';
import { OwnDecksApiService } from './own-decks-api.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';

describe('OwnDeckDetailPageComponent', () => {
    let fixture: ComponentFixture<OwnDeckDetailPageComponent>;
    let store: SpyObj<OwnDecksStore>;
    let recovery: SpyObj<OwnDeckRecoveryService>;
    let decksApi: SpyObj<OwnDecksApiService>;
    let hub: SpyObj<DeckHubApiService>;
    let items: SpyObj<ItemApiService>;
    let capabilities: SpyObj<CapabilitiesApiService>;
    let generation: SpyObj<GenerationApiService>;
    const insights = hubFixture.insights.response as unknown as DeckInsights;
    const orderedPage = { ...hubFixture.items.orderedPageWithCounts.response, items: hubFixture.items.sortedPage.items } as unknown as ItemPage;
    const deck = metadataFixture.detail as unknown as OwnDeck;
    const detail = signal<DeckDetailState>({ phase: 'ready', deckId: deck.deckId, deck, failure: null });
    const mutation = signal<DeckMutationState>({ phase: 'idle' });

    beforeEach(async () => {
        detail.set({ phase: 'ready', deckId: deck.deckId, deck, failure: null });
        mutation.set({ phase: 'idle' });
        store = spyObj<OwnDecksStore>({
            openDeck: vi.fn().mockName("OwnDecksStore.openDeck"),
            retryDetail: vi.fn().mockName("OwnDecksStore.retryDetail"),
            startSave: vi.fn().mockName("OwnDecksStore.startSave"),
            retryMutation: vi.fn().mockName("OwnDecksStore.retryMutation"),
            retryAsNewCommand: vi.fn().mockName("OwnDecksStore.retryAsNewCommand"),
            useServerVersion: vi.fn().mockName("OwnDecksStore.useServerVersion"),
            reapplyConflict: vi.fn().mockName("OwnDecksStore.reapplyConflict"),
            clearMutation: vi.fn().mockName("OwnDecksStore.clearMutation"),
            recoverMutation: vi.fn().mockName("OwnDecksStore.recoverMutation")
        });
        recovery = spyObj<OwnDeckRecoveryService>({
            restore: vi.fn().mockName("OwnDeckRecoveryService.restore"),
            save: vi.fn().mockName("OwnDeckRecoveryService.save"),
            clear: vi.fn().mockName("OwnDeckRecoveryService.clear")
        });
        recovery.restore.mockReturnValue(null);
        const authoring = {
            listDeckCaptures: vi.fn().mockName("AuthoringApiService.listDeckCaptures")
        };
        authoring.listDeckCaptures.mockReturnValue(of({ items: [], nextCursor: null, total: 2 }));
        decksApi = spyObj<OwnDecksApiService>({
            delete: vi.fn().mockName("OwnDecksApiService.delete")
        });
        hub = spyObj<DeckHubApiService>({ insights: vi.fn().mockName('DeckHubApiService.insights') });
        hub.insights.mockReturnValue(of(insights));
        items = spyObj<ItemApiService>({ list: vi.fn().mockName('ItemApiService.list') });
        items.list.mockReturnValue(of(orderedPage));
        capabilities = spyObj<CapabilitiesApiService>({ read: vi.fn().mockName('CapabilitiesApiService.read') });
        capabilities.read.mockReturnValue(of(CAPABILITIES_UNAVAILABLE));
        generation = spyObj<GenerationApiService>({ listSessions: vi.fn().mockName('GenerationApiService.listSessions') });
        generation.listSessions.mockReturnValue(of({ items: [], nextCursor: null }));
        Object.defineProperty(store, 'detailState', { value: detail.asReadonly() });
        Object.defineProperty(store, 'mutationState', { value: mutation.asReadonly() });
        await TestBed.configureTestingModule({
            imports: [OwnDeckDetailPageComponent],
            providers: [
                provideRouter([{ path: '**', children: [] }]),
                { provide: OwnDeckRecoveryService, useValue: recovery },
                { provide: AuthoringApiService, useValue: authoring },
                { provide: OwnDecksApiService, useValue: decksApi },
                { provide: DeckHubApiService, useValue: hub },
                { provide: ItemApiService, useValue: items },
                { provide: CapabilitiesApiService, useValue: capabilities },
                { provide: GenerationApiService, useValue: generation },
                { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ deckId: deck.deckId })) } }
            ]
        }).overrideComponent(OwnDeckDetailPageComponent, {
            set: { providers: [{ provide: OwnDecksStore, useValue: store }] }
        }).compileComponents();
        fixture = TestBed.createComponent(OwnDeckDetailPageComponent);
        fixture.detectChanges();
    });

    it('asks for the active Workshops of the deck and keeps the block out of the page while there are none', () => {
        expect(generation.listSessions).toHaveBeenCalledWith(deck.deckId, { active: true });
        expect((fixture.nativeElement as HTMLElement).querySelector('app-deck-workshops section')).toBeNull();
    });

    it('opens the route identity and saves exact edited metadata against the loaded deck', () => {
        expect(store.openDeck).toHaveBeenCalledTimes(1);
        expect(store.openDeck).toHaveBeenCalledWith(deck.deckId);
        fixture.componentInstance.form.setValue({ title: '  Точное имя  ', description: 'строка 1\nстрока 2' });
        fixture.componentInstance.save(deck);

        expect(store.startSave).toHaveBeenCalledTimes(1);

        expect(store.startSave).toHaveBeenCalledWith(deck, {
            title: '  Точное имя  ', description: 'строка 1\nстрока 2'
        });
        expect(recovery.save).toHaveBeenCalledWith({ operation: 'save', deckId: deck.deckId }, { title: '  Точное имя  ', description: 'строка 1\nстрока 2' }, null);
    });

    it('shows both explicit 412 choices and keeps the local draft in the form', () => {
        fixture.componentInstance.form.setValue({ title: 'Мой ввод', description: 'мой текст' });
        fixture.componentInstance.syncDraft();
        mutation.set({
            phase: 'conflict',
            pending: {
                operation: 'save', deckId: deck.deckId, expectedVersion: deck.rowVersion,
                command: { commandId: '123e4567-e89b-42d3-a456-426614174000', metadata: { title: 'Мой ввод', description: 'мой текст' } }
            },
            latest: { ...deck, metadata: { title: 'Версия сервера', description: 'другой текст' } }
        });
        fixture.detectChanges();

        const root = fixture.nativeElement as HTMLElement;
        expect(root.textContent).toContain('Оставить текущую версию');
        expect(root.textContent).toContain('Применить мой прежний ввод поверх неё');
        expect(fixture.componentInstance.form.getRawValue()).toEqual({ title: 'Мой ввод', description: 'мой текст' });
        expect(root.querySelector<HTMLTextAreaElement>('#detail-title')?.readOnly).toBe(true);
    });

    it('makes an unknown-outcome save draft read-only until exact-command reconciliation', () => {
        mutation.set({
            phase: 'error',
            pending: {
                operation: 'save', deckId: deck.deckId, expectedVersion: deck.rowVersion,
                command: {
                    commandId: '123e4567-e89b-42d3-a456-426614174000',
                    metadata: { title: 'Первый ввод', description: 'не терять' }
                }
            },
            failure: { kind: 'network', status: 0, code: null },
            replayRefreshFailed: false,
            replayDeckId: null,
            conflictRefreshFailed: false
        });
        fixture.detectChanges();

        expect((fixture.nativeElement as HTMLElement)
            .querySelector<HTMLTextAreaElement>('#detail-title')?.readOnly).toBe(true);
    });

    it('makes Study the primary action inside the selected deck', () => {
        const root = fixture.nativeElement as HTMLElement;
        const study = [...root.querySelectorAll<HTMLAnchorElement>('a')].find(link => link.textContent?.trim() === 'Учить');
        expect(study).toBeDefined();
        expect(study?.classList).toContain('primary');
        expect(study?.getAttribute('href')).toBe(`/decks/${deck.deckId}/study`);
        expect(root.querySelector('.capture-badge')?.textContent?.trim()).toBe('2');
    });

    it('deletes only the opened deck after the hold control confirms', () => {
        decksApi.delete.mockReturnValue(of(void 0));
        fixture.componentInstance.deleteDeck(deck);

        expect(decksApi.delete).toHaveBeenCalledTimes(1);

        expect(decksApi.delete).toHaveBeenCalledWith(deck);
        expect(recovery.clear).toHaveBeenCalledWith({ operation: 'save', deckId: deck.deckId });
    });

    it('keeps the deck open when its delete request fails', () => {
        decksApi.delete.mockReturnValue(throwError(() => new Error('offline')));
        fixture.componentInstance.deleteDeck(deck);
        fixture.detectChanges();

        expect(fixture.componentInstance.deleting()).toBe(false);
        expect(fixture.componentInstance.deleteError()).toContain('Не удалось удалить колоду');
        expect((fixture.nativeElement as HTMLElement).textContent).toContain(deck.metadata.title);
    });

    describe('hub', () => {
        const root = () => fixture.nativeElement as HTMLElement;
        const link = (text: string) => [...root().querySelectorAll<HTMLAnchorElement>('a')].find(a => a.textContent?.trim().startsWith(text));

        it('reads first: the description and the four actions, with the form hidden behind «Изменить»', () => {
            expect(root().querySelector('app-deck-description')).not.toBeNull();
            expect(link('Учить')?.getAttribute('href')).toBe(`/decks/${deck.deckId}/study`);
            expect(link('Добавить материал')?.getAttribute('href')).toBe(`/decks/${deck.deckId}/materials/new`);
            expect(link('На потом')?.getAttribute('href')).toBe(`/decks/${deck.deckId}/capture`);
            const edit = [...root().querySelectorAll('button')].find(button => button.textContent?.trim() === 'Изменить')!;
            expect(edit.getAttribute('aria-expanded')).toBe('false');
            expect(root().querySelector('#detail-title')).toBeNull();
            expect(root().querySelector('app-deck-insights')).not.toBeNull();
            expect(root().querySelector('app-deck-materials')).not.toBeNull();
            // The old separate list route is gone from the hub.
            expect(root().innerHTML).not.toContain('/materials"');
        });

        it('opens the form from «Изменить», focuses the title, and closes on Esc keeping the draft', async () => {
            const edit = [...root().querySelectorAll('button')].find(button => button.textContent?.trim() === 'Изменить')!;
            document.body.append(root());
            edit.click();
            fixture.detectChanges();
            await Promise.resolve();
            expect(edit.getAttribute('aria-expanded')).toBe('true');
            expect(edit.getAttribute('aria-controls')).toBe('deck-editor');
            const title = root().querySelector<HTMLTextAreaElement>('#detail-title')!;
            expect(document.activeElement).toBe(title);
            fixture.componentInstance.form.patchValue({ title: 'Черновик' });
            fixture.componentInstance.syncDraft();
            title.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            fixture.detectChanges();
            await Promise.resolve();
            expect(root().querySelector('#detail-title')).toBeNull();
            expect(document.activeElement).toBe(edit);
            expect(fixture.componentInstance.form.getRawValue().title).toBe('Черновик');
            root().remove();
        });

        it('opens the form by itself when a draft was restored or a save needs a decision', () => {
            mutation.set({
                phase: 'error',
                pending: { operation: 'save', deckId: deck.deckId, expectedVersion: deck.rowVersion,
                    command: { commandId: '123e4567-e89b-42d3-a456-426614174000', metadata: { title: 'x', description: '' } } },
                failure: { kind: 'http', status: 400, code: null }, replayRefreshFailed: false, replayDeckId: null, conflictRefreshFailed: false
            });
            fixture.detectChanges();
            expect(fixture.componentInstance.editing()).toBe(true);
        });

        it('keeps the deck, the actions and the list usable when statistics fail, and retries on request', () => {
            hub.insights.mockReturnValueOnce(throwError(() => new Error('offline')));
            fixture.componentInstance.loadInsights();
            fixture.detectChanges();
            expect(root().textContent).toContain('Статистика сейчас недоступна');
            expect(root().textContent).toContain(deck.metadata.title);
            expect(link('Учить')).toBeDefined();
            expect(root().querySelectorAll('app-selectable-material-list li').length).toBeGreaterThan(0);
            const retry = [...root().querySelectorAll<HTMLButtonElement>('app-deck-insights button')].find(button => button.textContent?.trim() === 'Повторить')!;
            retry.click();
            fixture.detectChanges();
            expect(fixture.componentInstance.insights().phase).toBe('ready');
            expect(hub.insights).toHaveBeenCalledTimes(3);
        });

        it('shows the generation capability only when the server offers it, and fails closed on a failed read', () => {
            expect(fixture.componentInstance.generationAvailable()).toBe(false);
            const available: LearningCapabilities = { ...CAPABILITIES_UNAVAILABLE, aiGeneration: { available: true, reason: null } };
            capabilities.read.mockReturnValue(of(available));
            const second = TestBed.createComponent(OwnDeckDetailPageComponent);
            second.detectChanges();
            expect(second.componentInstance.generationAvailable()).toBe(true);
            capabilities.read.mockReturnValue(throwError(() => new Error('offline')));
            const third = TestBed.createComponent(OwnDeckDetailPageComponent);
            third.detectChanges();
            expect(third.componentInstance.generationAvailable()).toBe(false);
        });

        it('lists materials without exercises first when a statistics widget asks for them', () => {
            const withoutExercises = [...root().querySelectorAll<HTMLButtonElement>('app-deck-insights button')]
                .find(button => button.textContent?.includes('Показать материалы без упражнений'))!;
            withoutExercises.click();
            fixture.detectChanges();
            expect(items.list).toHaveBeenLastCalledWith(deck.deckId, { sort: 'exerciseCount', exerciseCount: true, limit: 50 });
        });
    });
});
