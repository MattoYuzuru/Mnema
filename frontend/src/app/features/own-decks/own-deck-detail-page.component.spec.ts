import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';

import hubFixture from '../../../../../contracts/decks/hub.json';
import forksFixture from '../../../../../contracts/decks/forks.json';
import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { ToastService } from '../../core/notifications/toast.service';
import { TourEngine } from '../../core/onboarding/tour-engine.service';
import { TourEvents } from '../../core/onboarding/tour-events';
import { paragraphs } from '../../core/onboarding/tour-rich-text';
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
import { ModerationApiService, parseDeckModeration } from './moderation/moderation-api.service';
import { ForkApiService } from './fork/fork-api.service';
import { UpdateSeenState } from './update/update-seen-state';
import type { DeckFork } from './fork/fork.models';
import { FORK_POLL_LIMIT } from './fork/fork-poll';
import { PublicationApiService } from './publication/publication-api.service';
import moderationFixture from '../../../../../contracts/moderation/complaints.json';
import { neverPublished, sharedState } from './publication/publication-test-data';
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
    let publication: SpyObj<PublicationApiService>;
    let moderation: SpyObj<ModerationApiService>;
    let forkApi: SpyObj<ForkApiService>;
    let toasts: SpyObj<ToastService>;
    const insights = hubFixture.insights.response as unknown as DeckInsights;
    const orderedPage = { ...hubFixture.items.orderedPageWithCounts.response, items: hubFixture.items.sortedPage.items } as unknown as ItemPage;
    const deck = metadataFixture.detail as unknown as OwnDeck;
    const detail = signal<DeckDetailState>({ phase: 'ready', deckId: deck.deckId, deck, failure: null });
    const mutation = signal<DeckMutationState>({ phase: 'idle' });
    const fragment = new BehaviorSubject<string | null>(null);

    beforeEach(async () => {
        detail.set({ phase: 'ready', deckId: deck.deckId, deck, failure: null });
        mutation.set({ phase: 'idle' });
        fragment.next(null);
        store = spyObj<OwnDecksStore>({
            openDeck: vi.fn().mockName("OwnDecksStore.openDeck"),
            retryDetail: vi.fn().mockName("OwnDecksStore.retryDetail"),
            startSave: vi.fn().mockName("OwnDecksStore.startSave"),
            retryMutation: vi.fn().mockName("OwnDecksStore.retryMutation"),
            retryAsNewCommand: vi.fn().mockName("OwnDecksStore.retryAsNewCommand"),
            useServerVersion: vi.fn().mockName("OwnDecksStore.useServerVersion"),
            reapplyConflict: vi.fn().mockName("OwnDecksStore.reapplyConflict"),
            clearMutation: vi.fn().mockName("OwnDecksStore.clearMutation"),
            recoverMutation: vi.fn().mockName("OwnDecksStore.recoverMutation"),
            refreshDetail: vi.fn().mockName('OwnDecksStore.refreshDetail').mockResolvedValue(undefined),
            applyFork: vi.fn().mockName('OwnDecksStore.applyFork')
        });
        forkApi = spyObj<ForkApiService>({ retry: vi.fn().mockName('ForkApiService.retry'), detach: vi.fn().mockName('ForkApiService.detach'), setAutoUpdate: vi.fn().mockName('ForkApiService.setAutoUpdate') });
        toasts = spyObj<ToastService>({ echo: vi.fn().mockName('ToastService.echo') });
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
        publication = spyObj<PublicationApiService>({ read: vi.fn().mockName('PublicationApiService.read'), save: vi.fn().mockName('PublicationApiService.save'), topics: vi.fn().mockName('PublicationApiService.topics') });
        publication.read.mockReturnValue(of(neverPublished({ deckId: deck.deckId })));
        moderation = spyObj<ModerationApiService>({ notice: vi.fn().mockName('ModerationApiService.notice') });
        moderation.notice.mockReturnValue(of(parseDeckModeration({ ...moderationFixture.ownerNotice.clean, deckId: deck.deckId })));
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
                { provide: PublicationApiService, useValue: publication },
                { provide: ModerationApiService, useValue: moderation },
                { provide: ForkApiService, useValue: forkApi },
                { provide: ToastService, useValue: toasts },
                { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ deckId: deck.deckId })), fragment } }
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

    it('reads the publication as its own request: a private deck gets the picker on «Приватная» and no level mark', () => {
        const root = fixture.nativeElement as HTMLElement;
        expect(publication.read).toHaveBeenCalledWith(deck.deckId);
        expect(root.querySelector('app-publication-block')!.textContent).toContain('Только вы.');
        expect(root.querySelector('.hub-level')).toBeNull();
    });

    it('shows the real access level in the header once the publication says so', async () => {
        publication.read.mockReturnValue(of(sharedState({ deckId: deck.deckId })));
        const shared = TestBed.createComponent(OwnDeckDetailPageComponent);
        shared.detectChanges();
        await shared.whenStable();
        shared.detectChanges();
        const root = shared.nativeElement as HTMLElement;
        expect(root.querySelector('.hub-level')!.textContent).toContain('Публичная');
        // The level follows the h1 (the title is read first).
        expect(root.querySelector('h1')!.compareDocumentPosition(root.querySelector('.hub-level')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        expect(root.querySelector('app-publication-block')!.textContent).toContain('Изменения для учеников не опубликованы (4)');
    });

    it('a failing publication read degrades only its block', async () => {
        publication.read.mockReturnValue(throwError(() => new Error('offline')));
        const broken = TestBed.createComponent(OwnDeckDetailPageComponent);
        broken.detectChanges();
        await broken.whenStable();
        broken.detectChanges();
        const root = broken.nativeElement as HTMLElement;
        expect(root.querySelector('app-publication-block')!.textContent).toContain('Не удалось загрузить сведения о доступе.');
        expect(root.querySelector('app-deck-insights')).not.toBeNull();
        expect(root.querySelector('h1')!.textContent).toBe(deck.metadata.title);
    });

    it('opens the editor on the description field for the checklist, and re-reads the publication after a saved revision', async () => {
        document.body.append(fixture.nativeElement);
        fixture.componentInstance.openEditor('description');
        fixture.detectChanges();
        await fixture.whenStable();
        expect(document.activeElement).toBe((fixture.nativeElement as HTMLElement).querySelector('#detail-description'));
        (fixture.nativeElement as HTMLElement).remove();
        expect(publication.read).toHaveBeenCalledTimes(1);
        detail.set({ phase: 'ready', deckId: deck.deckId, deck: { ...deck, revisionId: '33333333-3333-4333-8333-333333333333' }, failure: null });
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(publication.read).toHaveBeenCalledTimes(2);
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

        it('opens sharing from an explicit button, keeps publication in the dialog and restores focus on Escape', async () => {
            document.body.append(root());
            const share = root().querySelector<HTMLButtonElement>('button[aria-haspopup="dialog"]')!;
            const dialog = root().querySelector<HTMLDialogElement>('app-paper-dialog dialog')!;
            expect(share.textContent).toContain('Поделиться');
            expect(share.getAttribute('aria-label')).toContain('Приватная');
            expect(root().querySelector('app-action-menu')).toBeNull();
            expect(dialog.open).toBe(false);
            expect(dialog.querySelector('app-publication-block')).not.toBeNull();
            share.focus(); share.click();
            fixture.detectChanges();
            expect(dialog.open).toBe(true);
            expect(document.activeElement).toBe(dialog.querySelector('h2'));
            dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
            expect(dialog.open).toBe(false);
            expect(document.activeElement).toBe(share);
            root().remove();
        });

        it('opens sharing for the #access notification once per deck', async () => {
            (document.activeElement as HTMLElement | null)?.blur();
            fragment.next('access');
            document.body.append(root());
            fixture.detectChanges();
            await fixture.whenStable();
            const dialog = root().querySelector<HTMLDialogElement>('app-paper-dialog dialog')!;
            expect(dialog.open).toBe(true);
            expect(document.activeElement).toBe(dialog.querySelector('h2'));
            dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
            fixture.detectChanges();
            expect(dialog.open).toBe(false);
            expect(document.activeElement).toBe(root().querySelector('button[aria-haspopup="dialog"]'));
            root().remove();
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

        it('keeps statistics informational without a second materials action', () => {
            expect(root().querySelector('app-deck-insights')?.textContent).not.toContain('Показать материалы без упражнений');
        });
    });
    describe('moderation notice', () => {
        it('reads the notice of this deck and draws nothing for a clean one', () => {
            const root = fixture.nativeElement as HTMLElement;
            expect(moderation.notice).toHaveBeenCalledWith(deck.deckId);
            expect(root.querySelector('app-deck-moderation')).not.toBeNull();
            expect(root.querySelector('app-moderation-notice section')).toBeNull();
        });

        it('shows the hidden deck and the counts under the heading of the hub, before the actions', async () => {
            moderation.notice.mockReturnValue(of(parseDeckModeration({ ...moderationFixture.ownerNotice.answer, deckId: deck.deckId })));
            const flagged = TestBed.createComponent(OwnDeckDetailPageComponent);
            flagged.detectChanges();
            await flagged.whenStable();
            flagged.detectChanges();
            const root = flagged.nativeElement as HTMLElement;
            const notice = root.querySelector('app-moderation-notice section')!;
            expect(notice.textContent).toContain('Колода скрыта из Сообщества');
            expect(notice.textContent).toContain('Снято: 2 материала и 1 упражнение.');
            expect(root.querySelector('header')!.compareDocumentPosition(notice) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
            expect(notice.compareDocumentPosition(root.querySelector('nav[aria-label="Действия с колодой"]')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        });
    });

    describe('a copy of somebody else\'s deck', () => {
        const states = forksFixture.fork as unknown as Record<'preparing' | 'failed' | 'ready' | 'sourceClosed' | 'detached' | 'updateAvailable' | 'updatedAutomatically', DeckFork>;
        const root = () => fixture.nativeElement as HTMLElement;
        const live = () => root().querySelector('.deck-detail-page > p.visually-hidden[role=status]')!.textContent;
        const actions = () => [...root().querySelectorAll('nav.hub-actions > a, nav.hub-actions > button')].map(element => element.getAttribute('aria-label') ?? element.textContent?.trim());
        const menu = () => [...root().querySelectorAll('app-action-menu [role=menuitem]')].map(item => item.textContent?.trim());
        const button = (label: string) => [...root().querySelectorAll<HTMLButtonElement>('button')].find(element => element.textContent?.trim() === label)!;
        async function show(fork: DeckFork | null): Promise<void> {
            detail.set({ phase: 'ready', deckId: deck.deckId, failure: null, deck: { ...deck, memberCount: 40, exerciseCount: 12, fork } });
            fixture.detectChanges();
            await vi.advanceTimersByTimeAsync(0);
            fixture.detectChanges();
        }

        beforeEach(() => vi.useFakeTimers());
        afterEach(() => {
            vi.useRealTimers();
            delete (document as { visibilityState?: string }).visibilityState;
        });

        it('draws an ordinary deck exactly as before: no source line, no state block, only «Доступ…» in the menu', async () => {
            await show(null);
            expect(root().querySelector('app-copy-origin, app-copy-status')).toBeNull();
            expect(menu()).toEqual([]);
            expect(actions()).toEqual(['Учить', 'Добавить материал', 'На потом, заметок: 2', 'Изменить', 'Поделиться. Доступ: Приватная']);
            expect(live()).toBe('');
        });

        it('names the source under the title, links it, and offers «Отвязать от автора» in the menu of a ready copy', async () => {
            await show(states.ready);
            expect(root().querySelector('app-copy-origin')!.textContent!.replace(/\s+/gu, ' ').trim()).toBe('Копия колоды «Испанский язык: базовые глаголы»');
            expect(root().querySelector('app-copy-origin a')!.getAttribute('href')).toBe(`/d/${states.ready.upstreamCode}`);
            expect(menu()).toEqual(['Отвязать от автора']);
            expect(root().querySelector('app-copy-status')).toBeNull();
            expect(actions()).toContain('Учить');
        });

        it('links a ready, attached copy to the update review, and leaves it out for an ordinary deck, a detached copy and a copy being prepared', async () => {
            await show(states.ready);
            expect(actions()).toEqual(['Учить', 'Добавить материал', 'На потом, заметок: 2', 'Проверить обновления', 'Изменить', 'Поделиться. Доступ: Приватная']);
            const link = [...root().querySelectorAll<HTMLAnchorElement>('nav.hub-actions > a')].find(anchor => anchor.textContent?.trim() === 'Проверить обновления')!;
            expect(link.getAttribute('href')).toBe(`/decks/${deck.deckId}/update`);
            await show(states.detached);
            expect(actions()).not.toContain('Проверить обновления');
            await show(null);
            expect(actions()).not.toContain('Проверить обновления');
            await show(states.preparing);
            expect(root().querySelector('nav.hub-actions')).toBeNull();
        });

        it('says the approved sentence when the source is closed, and drops the menu item once the copy is detached', async () => {
            await show(states.sourceClosed);
            expect(root().querySelector('app-unavailable-note .title')!.textContent).toBe('Источник больше недоступен');
            expect(root().querySelector('app-unavailable-note .hint')!.textContent).toBe(forksFixture.texts.sourceClosed);
            expect(root().querySelector('app-copy-origin')).toBeNull();
            await show(states.detached);
            expect(menu()).toEqual([]);
            expect(root().querySelector('app-copy-origin .stamp')!.textContent).toBe('Отвязана от автора');
        });

        describe('an update from the author', () => {
            const link = () => root().querySelector<HTMLAnchorElement>('a[href$="/update"]');

            it('offers the update under the badge as its heading with one secondary link («Учить» stays the only primary), and has no second link to it', async () => {
                await show(states.updateAvailable);
                const offer = root().querySelector('section.update-offer')!;
                expect(offer.getAttribute('aria-labelledby')).toBe('update-offer-title');
                expect(offer.querySelector('h2 app-update-badge')!.textContent!.trim()).toBe('Есть обновление');
                expect(offer.querySelector('p')!.textContent).toBe('Автор опубликовал новое. Вы сами решаете, что взять, а ваши правки останутся.');
                const links = root().querySelectorAll<HTMLAnchorElement>('a[href$="/update"]');
                expect(links).toHaveLength(1);
                expect(links[0]!.textContent!.trim()).toBe('Посмотреть обновление');
                expect(links[0]!.classList.contains('secondary')).toBe(true);
                expect(links[0]!.classList.contains('primary')).toBe(false);
                expect([...root().querySelectorAll('.button.primary')].filter(item => !item.closest('dialog:not([open])')).map(item => item.textContent!.trim())).toEqual(['Учить']);
                expect(links[0]!.getAttribute('href')).toBe(`/decks/${deck.deckId}/update`);
                expect(offer.contains(links[0]!)).toBe(true);
                expect(actions()).not.toContain('Проверить обновления');
            });

            it('keeps «Проверить обновления» as a quiet link while nothing is waiting, and shows no badge', async () => {
                await show(states.ready);
                expect(root().querySelector('app-update-badge, section.update-offer')).toBeNull();
                expect(link()!.textContent!.trim()).toBe('Проверить обновления');
                expect(link()!.classList.contains('quiet')).toBe(true);
                expect(link()!.classList.contains('primary')).toBe(false);
            });

            it('shows neither the badge nor a link to the update for a detached copy, a closed source and a copy being prepared', async () => {
                for (const fork of [states.detached, states.sourceClosed, states.preparing]) {
                    await show({ ...fork, updateAvailable: false });
                    expect(root().querySelector('app-update-badge, section.update-offer'), JSON.stringify(fork)).toBeNull();
                    expect(link(), JSON.stringify(fork)).toBeNull();
                }
                // Whatever the flag says, a state that cannot receive an update does not offer one.
                await show({ ...states.detached, updateAvailable: true });
                expect(root().querySelector('app-update-badge')).toBeNull();
                expect(link()).toBeNull();
            });

            it('says «Источник больше недоступен» with app-unavailable-note and offers no update link and no setting', async () => {
                await show(states.sourceClosed);
                expect(root().querySelector('.hub-heading app-unavailable-note')).not.toBeNull();
                expect(link()).toBeNull();
                expect(root().querySelector('.auto-update')).toBeNull();
            });
        });

        describe('«Что нового» after an automatic update', () => {
            const news = () => root().querySelector('app-update-news');
            const links = () => [...root().querySelectorAll<HTMLAnchorElement>('app-update-news a')].map(anchor => [anchor.textContent!.trim(), anchor.getAttribute('href')]);
            const withNotice = (fork: DeckFork, counts: { applied: number; pending: number }): DeckFork => ({ ...fork, lastUpdate: { ...states.updatedAutomatically.lastUpdate!, counts } });
            const quiet = { ...states.updatedAutomatically, updateAvailable: false };

            it('says what was taken and what waits, under its own heading, with the time and a text link to the history (the contract example)', async () => {
                await show(quiet as DeckFork);
                expect(news()!.querySelector('h2')!.textContent).toBe('Что нового');
                expect(news()!.querySelector('.line')!.textContent).toBe('Автор обновил колоду, мы взяли 3 изменения автоматически. 2 ждут вашего решения.');
                expect(news()!.querySelector('time')!.getAttribute('datetime')).toBe(states.updatedAutomatically.lastUpdate!.appliedAt);
                expect(links()[0]).toEqual(['Посмотреть, что изменилось', `/decks/${deck.deckId}/update/history`]);
                expect(news()!.querySelector('button, .button.primary')).toBeNull();
                expect([...root().querySelectorAll('.button.primary')].filter(item => !item.closest('dialog:not([open])')).map(item => item.textContent!.trim())).toEqual(['Учить']);
            });

            it('sits under the actions, so «Учить» is not pushed down by it', async () => {
                await show(quiet as DeckFork);
                const nav = root().querySelector('nav.hub-actions')!;
                expect(nav.compareDocumentPosition(news()!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
                expect(news()!.compareDocumentPosition(nav) & Node.DOCUMENT_POSITION_PRECEDING).toBeTruthy();
            });

            it('is one block with the update offer, carrying its badge and its one link to the review screen, when the author has something waiting', async () => {
                await show(states.updatedAutomatically);
                expect(root().querySelector('section.update-offer')).toBeNull();
                expect(news()!.querySelectorAll('h2')).toHaveLength(1);
                expect(news()!.querySelector('app-update-badge')!.textContent!.trim()).toBe('Есть обновление');
                expect(links()).toEqual([['Посмотреть, что изменилось', `/decks/${deck.deckId}/update/history`], ['Посмотреть обновление', `/decks/${deck.deckId}/update`]]);
                expect(root().querySelectorAll('a[href$="/update"]')).toHaveLength(1);
                expect(root().querySelectorAll('app-update-badge')).toHaveLength(1);
                expect([...root().querySelectorAll('.button.primary')].filter(item => !item.closest('dialog:not([open])')).map(item => item.textContent!.trim())).toEqual(['Учить']);
            });

            it('links to the review screen itself when something waits and no offer is shown, and not when nothing waits', async () => {
                await show(withNotice(quiet as DeckFork, { applied: 1, pending: 1 }));
                expect(news()!.querySelector('.line')!.textContent).toBe('Автор обновил колоду, мы взяли 1 изменение автоматически. 1 ждёт вашего решения.');
                expect(links()).toEqual([['Посмотреть, что изменилось', `/decks/${deck.deckId}/update/history`], ['Решить, что взять', `/decks/${deck.deckId}/update`]]);
                await show(withNotice(quiet as DeckFork, { applied: 5, pending: 0 }));
                expect(news()!.querySelector('.line')!.textContent).toBe('Автор обновил колоду, мы взяли 5 изменений автоматически.');
                expect(links()).toHaveLength(1);
            });

            it('is absent once the server stops sending the notice (seen), for an ordinary deck, and for a copy that cannot have one', async () => {
                await show(states.updateAvailable);
                expect(news()).toBeNull();
                expect(root().querySelector('section.update-offer')).not.toBeNull();
                await show(null);
                expect(news()).toBeNull();
                for (const fork of [states.preparing, states.detached]) {
                    await show({ ...fork, lastUpdate: states.updatedAutomatically.lastUpdate });
                    expect(news(), JSON.stringify(fork)).toBeNull();
                }
            });

            it('hides at once when this tab has marked the entry seen, and shows the plain offer in its place', async () => {
                await show(states.updatedAutomatically);
                expect(news()).not.toBeNull();
                TestBed.inject(UpdateSeenState).mark(states.updatedAutomatically.lastUpdate!.deckRevisionId);
                fixture.detectChanges();
                expect(news()).toBeNull();
                expect(root().querySelector('section.update-offer')).not.toBeNull();
                TestBed.inject(UpdateSeenState).unmark(states.updatedAutomatically.lastUpdate!.deckRevisionId);
                fixture.detectChanges();
                expect(news()).not.toBeNull();
            });
        });

        describe('«Принимать безопасное автоматически»', () => {
            const box = () => root().querySelector<HTMLInputElement>('.auto-update input[type=checkbox]')!;
            const alert = () => root().querySelector('.auto-update [role=alert]')?.textContent ?? null;
            const answer = (autoUpdate: boolean) => ({ deckId: deck.deckId, autoUpdate });

            it('is a labelled check row with a short explanation, off by default and on when the copy stores it', async () => {
                await show(states.ready);
                expect(root().querySelector('.auto-update h2')!.textContent).toBe('Обновления от автора');
                expect(box().labels![0]!.classList.contains('check-row')).toBe(true);
                expect(box().labels![0]!.textContent!.trim()).toBe('Принимать безопасное автоматически');
                expect(box().checked).toBe(false);
                const tip = root().querySelector('.auto-update app-toggletip')!;
                expect(tip.querySelector('button')!.getAttribute('aria-label')).toBe('Подробнее: автоматический приём обновлений');
                expect(tip.querySelector('.bubble')!.textContent).toBe('Берём новое и исправления того, что вы не меняли. Конфликты и удаления ждут вашего решения. Принятое автоматически смотрите в «Истории обновлений». Материал или упражнение можно вернуть, пока вы сами не правили его после обновления.');
                await show(states.updateAvailable);
                expect(box().checked).toBe(true);
            });

            it('is offered to an attached, ready copy only', async () => {
                await show(null);
                expect(root().querySelector('.auto-update')).toBeNull();
                await show(states.detached);
                expect(root().querySelector('.auto-update')).toBeNull();
                await show(states.preparing);
                expect(root().querySelector('.auto-update')).toBeNull();
                await show(states.ready);
                expect(root().querySelector('.auto-update')).not.toBeNull();
            });

            it('switches on at once, saves, keeps the stored answer in the store and says it politely', async () => {
                const saved = new Subject<{ deckId: string; autoUpdate: boolean }>();
                forkApi.setAutoUpdate.mockReturnValue(saved);
                await show(states.ready);
                box().click();
                fixture.detectChanges();
                expect(forkApi.setAutoUpdate).toHaveBeenCalledWith(deck.deckId, true);
                expect(box().checked).toBe(true);
                expect(box().getAttribute('aria-disabled')).toBe('true');
                expect(box().getAttribute('aria-busy')).toBe('true');
                saved.next(answer(true));
                saved.complete();
                fixture.detectChanges();
                expect(store.applyFork).toHaveBeenCalledWith(deck.deckId, { autoUpdate: true });
                expect(box().checked).toBe(true);
                expect(box().getAttribute('aria-disabled')).toBeNull();
                expect(box().getAttribute('aria-busy')).toBeNull();
                expect(live()).toBe('Безопасное будет приниматься автоматически.');
                expect(alert()).toBeNull();
            });

            it('switches off again and says the updates are manual again', async () => {
                forkApi.setAutoUpdate.mockReturnValue(of(answer(false)));
                await show(states.updateAvailable);
                box().click();
                fixture.detectChanges();
                expect(forkApi.setAutoUpdate).toHaveBeenCalledWith(deck.deckId, false);
                expect(box().checked).toBe(false);
                expect(live()).toBe('Обновления снова принимаются только вручную.');
            });

            it('ignores a second click while the save is on its way', async () => {
                forkApi.setAutoUpdate.mockReturnValue(new Subject());
                await show(states.ready);
                box().click();
                fixture.detectChanges();
                box().click();
                fixture.detectChanges();
                expect(forkApi.setAutoUpdate).toHaveBeenCalledTimes(1);
                expect(box().checked).toBe(true);
            });

            it('puts the old value back and says why when the save fails', async () => {
                forkApi.setAutoUpdate.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
                await show(states.ready);
                box().click();
                fixture.detectChanges();
                expect(box().checked).toBe(false);
                expect(alert()).toBe('Не удалось сохранить настройку. Проверьте соединение и повторите. Настройка осталась выключенной.');
                expect(store.applyFork).not.toHaveBeenCalled();
                expect(store.refreshDetail).not.toHaveBeenCalled();
                // The next try starts clean and may succeed.
                forkApi.setAutoUpdate.mockReturnValue(of(answer(true)));
                box().click();
                fixture.detectChanges();
                expect(box().checked).toBe(true);
                expect(alert()).toBeNull();
            });

            it('rolls back a switch-off the same way', async () => {
                forkApi.setAutoUpdate.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
                await show(states.updateAvailable);
                box().click();
                fixture.detectChanges();
                expect(box().checked).toBe(true);
                expect(alert()).toBe('Не удалось сохранить настройку. Проверьте соединение и повторите. Настройка осталась включённой.');
            });

            it('on 409 DECK_PREPARING rolls back, says the copy is still preparing and reads the deck again', async () => {
                forkApi.setAutoUpdate.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'DECK_PREPARING' } })));
                await show(states.ready);
                box().click();
                fixture.detectChanges();
                expect(box().checked).toBe(false);
                expect(alert()).toBe('Копия ещё готовится. Настройку можно изменить, когда она будет готова. Настройка осталась выключенной.');
                expect(store.refreshDetail).toHaveBeenCalledTimes(1);
            });

            it('on 409 CAPABILITY_UNAVAILABLE DETACHED rolls back, says the copy is detached and reads the deck again', async () => {
                forkApi.setAutoUpdate.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'CAPABILITY_UNAVAILABLE', capability: 'forkAutoUpdate', reason: 'DETACHED' } })));
                await show(states.ready);
                box().click();
                fixture.detectChanges();
                expect(box().checked).toBe(false);
                expect(alert()).toBe('Копия отвязана от автора, поэтому настройка не действует. Настройка осталась выключенной.');
                expect(store.refreshDetail).toHaveBeenCalledTimes(1);
            });

            it('says a missing copy in words and does not read the deck again', async () => {
                forkApi.setAutoUpdate.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
                await show(states.ready);
                box().click();
                fixture.detectChanges();
                expect(alert()).toBe('Колода не найдена или больше недоступна. Настройка осталась выключенной.');
                expect(store.refreshDetail).not.toHaveBeenCalled();
            });
        });

        describe('while it is being prepared', () => {
            beforeEach(() => show(states.preparing));

            it('shows «Готовим колоду…» with what is coming, instead of the content that would answer 409', () => {
                expect(root().querySelector('app-copy-status h2')!.textContent).toBe('Готовим колоду…');
                expect(root().querySelector('app-copy-status .line')!.textContent).toBe('В копии будет: 40 материалов · 12 упражнений.');
                for (const absent of ['nav.hub-actions', 'app-publication-block', 'app-deck-workshops', 'app-deck-insights', 'app-deck-materials', '#deck-editor', 'app-action-menu']) {
                    expect(root().querySelector(absent), absent).toBeNull();
                }
                expect(root().textContent).not.toContain('Учить');
                expect(root().querySelector('app-copy-origin')).not.toBeNull();
                expect(live()).toBe('Готовим колоду…');
            });

            it('keeps the copy discardable: the hold-to-delete control is there, with what it removes', () => {
                const remove = root().querySelector('app-copy-status app-hold-to-delete-button')!;
                expect(remove.querySelector('.hold-label')!.textContent).toBe('Удалить колоду');
                expect(remove.querySelector('.consequence')!.textContent).toBe('Копия исчезнет из ваших колод. Колода автора не изменится.');
            });

            it('asks again at a bounded backoff, and stops the moment the copy is ready', async () => {
                const calls = () => store.refreshDetail.mock.calls.length;
                await vi.advanceTimersByTimeAsync(1499);
                expect(calls()).toBe(0);
                const steps = [[1, 1], [2000, 2], [3000, 3], [5000, 4], [8000, 5], [10_000, 6], [10_000, 7]];
                for (const [wait, expected] of steps) {
                    await vi.advanceTimersByTimeAsync(wait);
                    expect(calls()).toBe(expected);
                }
                await show(states.ready);
                await vi.advanceTimersByTimeAsync(120_000);
                expect(calls()).toBe(7);
            });

            it('says «Колода готова.» once, brings the content back, re-reads the statistics and keeps a lost focus on the title', async () => {
                document.body.append(root());
                const readsBefore = hub.insights.mock.calls.length;
                (document.activeElement as HTMLElement | null)?.blur();
                await show(states.ready);
                await vi.advanceTimersByTimeAsync(0);
                expect(live()).toBe('Колода готова.');
                expect(root().querySelector('app-copy-status')).toBeNull();
                expect(actions()).toContain('Учить');
                expect(hub.insights.mock.calls.length).toBe(readsBefore + 1);
                expect(document.activeElement).toBe(root().querySelector('h1'));
                await show({ ...states.ready });
                expect(live()).toBe('Колода готова.');
                expect(hub.insights.mock.calls.length).toBe(readsBefore + 1);
                root().remove();
            });

            it('does not ask while the tab is hidden, and goes on at the same pace when it is visible again', async () => {
                Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
                await vi.advanceTimersByTimeAsync(30_000);
                expect(store.refreshDetail).not.toHaveBeenCalled();
                delete (document as { visibilityState?: string }).visibilityState;
                await vi.advanceTimersByTimeAsync(10_000);
                expect(store.refreshDetail).toHaveBeenCalled();
            });

            it('stops asking by itself after a long wait, says so, and «Проверить снова» starts it over', async () => {
                await vi.advanceTimersByTimeAsync(20_000 + FORK_POLL_LIMIT * 10_000);
                expect(store.refreshDetail).toHaveBeenCalledTimes(FORK_POLL_LIMIT);
                fixture.detectChanges();
                expect(root().querySelector('app-copy-status .hint')!.textContent).toBe('Подготовка идёт дольше обычного. Страница больше не обновляется сама.');
                await vi.advanceTimersByTimeAsync(60_000);
                expect(store.refreshDetail).toHaveBeenCalledTimes(FORK_POLL_LIMIT);
                button('Проверить снова').click();
                await vi.advanceTimersByTimeAsync(0);
                fixture.detectChanges();
                expect(store.refreshDetail).toHaveBeenCalledTimes(FORK_POLL_LIMIT + 1);
                expect(root().querySelector('app-copy-status .hint')!.textContent).toBe('Страница обновится сама, когда колода будет готова.');
            });

            it('puts the focus on the heading when «Проверить снова» disappears', async () => {
                document.body.append(root());
                await vi.advanceTimersByTimeAsync(20_000 + FORK_POLL_LIMIT * 10_000);
                fixture.detectChanges();
                button('Проверить снова').focus();
                button('Проверить снова').click();
                await vi.advanceTimersByTimeAsync(0);
                fixture.detectChanges();
                await vi.advanceTimersByTimeAsync(0);
                expect(document.activeElement).toBe(root().querySelector('app-copy-status h2'));
                root().remove();
            });

            it('looks at once when the tab becomes visible again, no more often than the first backoff step', async () => {
                Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
                const calls = () => store.refreshDetail.mock.calls.length;
                await vi.advanceTimersByTimeAsync(100);
                document.dispatchEvent(new Event('visibilitychange'));
                await vi.advanceTimersByTimeAsync(0);
                expect(calls()).toBe(1);
                document.dispatchEvent(new Event('visibilitychange'));
                await vi.advanceTimersByTimeAsync(0);
                expect(calls()).toBe(1);
                await vi.advanceTimersByTimeAsync(1500);
                document.dispatchEvent(new Event('visibilitychange'));
                await vi.advanceTimersByTimeAsync(0);
                expect(calls()).toBeGreaterThanOrEqual(2);
                const before = calls();
                Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
                await vi.advanceTimersByTimeAsync(2000);
                document.dispatchEvent(new Event('visibilitychange'));
                await vi.advanceTimersByTimeAsync(0);
                expect(calls()).toBe(before);
            });

            it('puts the focus on the title when a copy is seen for the first time and nothing has the focus', async () => {
                document.body.append(root());
                (document.activeElement as HTMLElement | null)?.blur();
                const other = '33333333-3333-4333-8333-333333333333';
                detail.set({ phase: 'ready', deckId: other, failure: null, deck: { ...deck, deckId: other, fork: states.preparing } });
                fixture.detectChanges();
                await vi.advanceTimersByTimeAsync(0);
                fixture.detectChanges();
                expect(document.activeElement).toBe(root().querySelector('h1'));
                root().remove();
            });

            it('can be deleted through the shared deck delete, and the failure of the delete is said under the block', async () => {
                decksApi.delete.mockReturnValue(throwError(() => new Error('offline')));
                fixture.componentInstance.deleteDeck({ ...deck, fork: states.preparing });
                fixture.detectChanges();
                expect(decksApi.delete).toHaveBeenCalledTimes(1);
                expect(root().querySelector('app-copy-status ~ .field-error[role=alert]')!.textContent).toContain('Не удалось удалить колоду');
            });
        });

        describe('after it failed', () => {
            beforeEach(() => show(states.failed));

            it('says it in words, announces it once, stops asking, and offers «Повторить» next to the delete control', async () => {
                expect(root().querySelector('app-copy-status h2')!.textContent).toBe('Не удалось подготовить колоду');
                expect(root().querySelector('app-copy-status')!.querySelector('section')!.classList.contains('error')).toBe(true);
                expect(live()).toBe('Не удалось подготовить колоду.');
                expect(button('Повторить')).toBeTruthy();
                expect(root().querySelector('app-copy-status app-hold-to-delete-button')).not.toBeNull();
                await vi.advanceTimersByTimeAsync(60_000);
                expect(store.refreshDetail).not.toHaveBeenCalled();
            });

            it('retries: busy while the request is on its way, then reads the deck again and starts watching it', async () => {
                const answer = new Subject<{ deckId: string; preparing: boolean; failed: boolean }>();
                forkApi.retry.mockReturnValue(answer);
                button('Повторить').click();
                fixture.detectChanges();
                expect(forkApi.retry).toHaveBeenCalledWith(deck.deckId);
                expect(button('Повторяем…').getAttribute('aria-busy')).toBe('true');
                button('Повторяем…').click();
                expect(forkApi.retry).toHaveBeenCalledTimes(1);
                answer.next({ deckId: deck.deckId, preparing: true, failed: false });
                await vi.advanceTimersByTimeAsync(0);
                // The answer itself is shown at once; the read that follows confirms it.
                expect(store.applyFork).toHaveBeenCalledWith(deck.deckId, { preparing: true, failed: false });
                expect(store.refreshDetail).toHaveBeenCalledTimes(1);
                await show(states.preparing);
                expect(root().querySelector('app-copy-status h2')!.textContent).toBe('Готовим колоду…');
                expect(live()).toBe('Повторяем подготовку.');
                await vi.advanceTimersByTimeAsync(1500);
                expect(store.refreshDetail).toHaveBeenCalledTimes(2);
            });

            it('puts the focus on the heading of the block that replaced «Повторить», and leaves it alone when the button stays', async () => {
                document.body.append(root());
                forkApi.retry.mockReturnValueOnce(of({ deckId: deck.deckId, preparing: true, failed: false }));
                // As in the app: the read stores the new state before it resolves.
                store.refreshDetail.mockImplementationOnce(() => {
                    detail.set({ phase: 'ready', deckId: deck.deckId, failure: null, deck: { ...deck, memberCount: 40, exerciseCount: 12, fork: states.preparing } });
                    return Promise.resolve();
                });
                button('Повторить').focus();
                button('Повторить').click();
                await vi.advanceTimersByTimeAsync(0);
                fixture.detectChanges();
                await vi.advanceTimersByTimeAsync(0);
                expect(document.activeElement).toBe(root().querySelector('app-copy-status h2'));
                root().remove();
            });

            it('says plainly when the retry could not be sent, and lets the owner press it again', async () => {
                forkApi.retry.mockReturnValueOnce(throwError(() => new Error('offline'))).mockReturnValueOnce(of({ deckId: deck.deckId, preparing: true, failed: false }));
                button('Повторить').click();
                fixture.detectChanges();
                expect(root().querySelector('app-copy-status ~ .field-error[role=alert]')!.textContent).toBe('Не удалось повторить подготовку. Проверьте соединение и повторите.');
                button('Повторить').click();
                fixture.detectChanges();
                expect(forkApi.retry).toHaveBeenCalledTimes(2);
                expect(root().querySelector('app-copy-status ~ .field-error')).toBeNull();
            });
        });

        describe('«Отвязать от автора»', () => {
            beforeEach(async () => {
                document.body.append(root());
                await show(states.ready);
            });
            afterEach(() => root().remove());
            const panel = () => root().querySelector('section.detach-confirm');

            it('asks first, inline, naming what changes, and puts the focus on the question', async () => {
                fixture.componentInstance.onMenu('detach');
                fixture.detectChanges();
                await vi.advanceTimersByTimeAsync(0);
                expect(panel()!.getAttribute('aria-labelledby')).toBe('detach-title');
                // The confirm button is described by the consequence, so it is read together with the button's name.
                const confirm = [...panel()!.querySelectorAll('button')][0];
                expect(root().querySelector(`#${confirm.getAttribute('aria-describedby')}`)!.textContent).toBe(panel()!.querySelector('p')!.textContent);
                expect(panel()!.querySelector('h2')!.textContent).toBe('Отвязать от автора?');
                expect(panel()!.querySelector('p')!.textContent).toBe('Копия останется у вас со всем, что в ней есть. Обновления от автора приходить не будут, и вернуть связь потом нельзя.');
                expect([...panel()!.querySelectorAll('button')].map(item => item.textContent?.trim())).toEqual(['Отвязать от автора', 'Отмена']);
                expect(document.activeElement).toBe(panel()!.querySelector('h2'));
                expect(forkApi.detach).not.toHaveBeenCalled();
            });

            it('is cancelled by «Отмена» and by Esc, and the focus returns to the «⋯» button', async () => {
                for (const cancel of ['button', 'escape']) {
                    fixture.componentInstance.onMenu('detach');
                    fixture.detectChanges();
                    if (cancel === 'button') [...panel()!.querySelectorAll('button')].find(item => item.textContent?.trim() === 'Отмена')!.click();
                    else panel()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
                    fixture.detectChanges();
                    await vi.advanceTimersByTimeAsync(0);
                    expect(panel(), cancel).toBeNull();
                    expect(document.activeElement, cancel).toBe(root().querySelector('app-action-menu button.trigger'));
                }
                expect(forkApi.detach).not.toHaveBeenCalled();
            });

            it('detaches once confirmed: busy meanwhile, then re-reads the deck, says so politely and puts the focus on the title', async () => {
                const answer = new Subject<{ deckId: string; detached: true }>();
                forkApi.detach.mockReturnValue(answer);
                fixture.componentInstance.onMenu('detach');
                fixture.detectChanges();
                [...panel()!.querySelectorAll('button')][0].click();
                fixture.detectChanges();
                expect(forkApi.detach).toHaveBeenCalledWith(deck.deckId);
                const busy = [...panel()!.querySelectorAll('button')][0];
                expect(busy.textContent?.trim()).toBe('Отвязываем…');
                expect(busy.getAttribute('aria-busy')).toBe('true');
                busy.click();
                expect(forkApi.detach).toHaveBeenCalledTimes(1);
                answer.next({ deckId: deck.deckId, detached: true });
                await show(states.detached);
                await vi.advanceTimersByTimeAsync(0);
                fixture.detectChanges();
                expect(store.refreshDetail).toHaveBeenCalledTimes(1);
                expect(panel()).toBeNull();
                expect(toasts.echo).toHaveBeenCalledWith('Копия отвязана от автора.');
                expect(toasts.echo).toHaveBeenCalledTimes(1);
                expect(document.activeElement).toBe(root().querySelector('h1'));
                expect(menu()).toEqual([]);
            });

            it('says what went wrong and keeps the question open', async () => {
                forkApi.detach.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })))
                    .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 404 })));
                fixture.componentInstance.onMenu('detach');
                fixture.detectChanges();
                for (const sentence of ['Не удалось отвязать копию от автора. Повторите.', 'Колода не найдена или больше недоступна.']) {
                    [...panel()!.querySelectorAll('button')][0].click();
                    fixture.detectChanges();
                    expect(panel()!.querySelector('.field-error[role=alert]')!.textContent, sentence).toBe(sentence);
                }
                expect(store.refreshDetail).not.toHaveBeenCalled();
            });
        });
    });

    describe('for the onboarding tour', () => {
        const root = () => fixture.nativeElement as HTMLElement;
        const study = () => [...root().querySelectorAll<HTMLAnchorElement>('a')].find(link => link.textContent?.trim() === 'Учить')!;
        const states = forksFixture.fork as unknown as Record<'preparing' | 'ready', DeckFork>;

        it('marks «Учить» as the place the tour points at and says that the session begins when it is pressed', () => {
            const seen: string[] = [];
            TestBed.inject(TourEvents).listen(event => seen.push(event));
            expect(study().getAttribute('data-tour-anchor')).toBe('hub.start');
            study().addEventListener('click', event => event.preventDefault());
            study().click();
            expect(seen).toEqual(['study.started']);
        });

        it('tells a running tour that the copy is still being prepared, and that it is ready, instead of letting it time out', async () => {
            const engine = TestBed.inject(TourEngine);
            engine.start({ id: 't', branch: 'COMMUNITY', steps: [{ key: 'community.study', anchor: 'hub.start', title: 'Т', body: paragraphs('Текст'), gate: 'next' }] });
            detail.set({ phase: 'ready', deckId: deck.deckId, failure: null, deck: { ...deck, fork: states.preparing } });
            fixture.detectChanges();
            TestBed.tick();
            expect(engine.preparing()).toBe(true);
            detail.set({ phase: 'ready', deckId: deck.deckId, failure: null, deck: { ...deck, fork: states.ready } });
            fixture.detectChanges();
            TestBed.tick();
            expect(engine.preparing()).toBe(false);
            engine.stop();
        });

        it('lets go of the preparing note when the page is left', () => {
            const engine = TestBed.inject(TourEngine);
            engine.start({ id: 't', branch: 'COMMUNITY', steps: [{ key: 'community.study', anchor: 'hub.start', title: 'Т', body: paragraphs('Текст'), gate: 'next' }] });
            engine.setPreparing(true);
            fixture.destroy();
            expect(engine.preparing()).toBe(false);
            engine.stop();
        });
    });
});
