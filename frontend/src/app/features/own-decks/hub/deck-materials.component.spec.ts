import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Router, provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';

import hub from '../../../../../../contracts/decks/hub.json';
import { ItemPage, ItemSummary } from '../../authoring/authoring.models';
import { ItemApiService } from '../../authoring/item-api.service';
import { spyObj, type SpyObj } from '../../../../testing/mocks';
import { BulkActionBarComponent } from './bulk-action-bar.component';
import { DeckHubApiService } from './deck-hub-api.service';
import { BulkDeleteReceipt, BulkDeleteResult, DeletionPreview, ExemplarAcknowledgement } from './deck-hub.models';
import { DeckMaterialsComponent } from './deck-materials.component';

describe('DeckMaterialsComponent', () => {
    const deckId = hub.insights.response.deckId;
    const revisionId = hub.insights.response.deckRevisionId;
    const key = (index: number) => `44444444-4444-4444-8444-${String(index).padStart(12, '0')}`;
    const row = (index: number, overrides: Partial<ItemSummary> = {}): ItemSummary => ({
        memberKey: key(index), itemRevisionId: `55555555-5555-4555-8555-${String(index).padStart(12, '0')}`, itemVersion: '0',
        ordinal: index, formatVersion: 1, createdAt: '2026-09-12T12:00:00Z', updatedAt: '2026-09-12T12:00:00Z',
        title: `Материал ${index + 1}`, exerciseCount: index % 2, exemplar: false, ...overrides
    });
    const page = (from: number, count: number, total: number, overrides: Partial<ItemPage> = {}): ItemPage => ({
        deckId, deckRevisionId: revisionId, deckVersion: '7', total, exemplars: { count: 1, limit: 10 },
        items: Array.from({ length: count }, (_, index) => row(from + index)),
        nextCursor: from + count < total ? `cursor-${from + count}` : null, ...overrides
    });
    const preview: DeletionPreview = { deckId, deckRevisionId: revisionId, deckVersion: '7', materialCount: 2, affectedExerciseCount: 15 };
    const result = (overrides: Partial<BulkDeleteResult> = {}): BulkDeleteResult => ({
        commandId: '018f1d98-5c10-7abc-8abc-0123456789af', deckId, status: 'COMPLETED', requested: 2, deleted: 2, notDeleted: [],
        stopReason: null, deckRevisionId: '66666666-6666-4666-8666-666666666666', deckVersion: '8', memberCount: 48, ...overrides
    });
    const receipt = (value: BulkDeleteResult): BulkDeleteReceipt => ({ result: value, replayed: false });
    const http = (status: number, code: string | null = null) =>
        new HttpErrorResponse({ status, error: code === null ? null : { code } });

    let items: SpyObj<ItemApiService>;
    let api: SpyObj<DeckHubApiService>;
    let fixture: ComponentFixture<DeckMaterialsComponent>;
    const root = () => fixture.nativeElement as HTMLElement;
    const rows = () => Array.from(root().querySelectorAll('li.item-row'));
    const checkbox = (index: number) => root().querySelectorAll<HTMLInputElement>('li .check input')[index];
    const bar = () => root().querySelector('app-bulk-action-bar');
    const buttonWith = (text: string) => Array.from(root().querySelectorAll('button')).find(button => button.textContent?.includes(text));

    async function settle(ms = 0) {
        await vi.advanceTimersByTimeAsync(ms);
        fixture.detectChanges();
        await Promise.resolve();
        fixture.detectChanges();
    }

    async function open(first: Observable<ItemPage> = of(page(0, 20, 50)), inputs: Record<string, unknown> = {}) {
        items.list.mockReturnValueOnce(first);
        fixture = TestBed.createComponent(DeckMaterialsComponent);
        fixture.componentRef.setInput('deckId', deckId);
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        fixture.detectChanges();
        await settle();
        return fixture.componentInstance;
    }

    /** Selects materials through the checkboxes and waits for the preview the bar needs. */
    async function select(...indexes: number[]) {
        for (const index of indexes) checkbox(index).click();
        await settle(300);
    }

    function confirmDeletion() {
        fixture.debugElement.query(By.directive(BulkActionBarComponent)).triggerEventHandler('deleteConfirmed', undefined);
        fixture.detectChanges();
    }

    beforeEach(() => {
        vi.useFakeTimers();
        items = spyObj<ItemApiService>({ list: vi.fn().mockName('ItemApiService.list') });
        api = spyObj<DeckHubApiService>({
            setExemplar: vi.fn().mockName('setExemplar'), previewDeletion: vi.fn().mockName('previewDeletion'),
            deleteMany: vi.fn().mockName('deleteMany')
        });
        api.previewDeletion.mockReturnValue(of(preview));
        TestBed.configureTestingModule({ imports: [DeckMaterialsComponent], providers: [
            provideRouter([]), { provide: ItemApiService, useValue: items }, { provide: DeckHubApiService, useValue: api }] });
    });

    afterEach(() => {
        fixture?.destroy();
        vi.useRealTimers();
    });

    describe('loading', () => {
        it('lists the first page in authoring order with exercise counts and the total in the heading', async () => {
            await open();
            expect(items.list).toHaveBeenCalledWith(deckId, { sort: 'ordinal', exerciseCount: true, limit: 50 });
            expect(root().querySelector('h2')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Материалы · 50');
            expect(rows()).toHaveLength(20);
            expect(root().querySelector('app-auto-load button')).toBeNull();
        });

        it('explains an empty deck and offers the first material', async () => {
            await open(of(page(0, 0, 0)));
            expect(root().textContent).toContain('Здесь пока пусто');
            expect(root().querySelector('a.button')?.getAttribute('href')).toBe(`/decks/${deckId}/materials/new`);
            expect(root().querySelector('app-segmented-choice')).toBeNull();
        });

        it('shows the loading state, then a retryable failure that does not touch anything else', async () => {
            const pending = new Subject<ItemPage>();
            await open(pending);
            expect(root().textContent).toContain('Загружаем материалы');
            pending.error(new Error('offline'));
            await settle();
            expect(root().querySelector('[role="alert"]')?.textContent).toContain('Не удалось загрузить материалы');
            items.list.mockReturnValueOnce(of(page(0, 3, 3)));
            buttonWith('Повторить')!.click();
            await settle();
            expect(rows()).toHaveLength(3);
        });
    });

    describe('sort', () => {
        it('switches to «Без упражнений сначала», reloads from the first page and drops the selection', async () => {
            await open();
            await select(1);
            expect(bar()).not.toBeNull();
            items.list.mockReturnValueOnce(of(page(0, 20, 50, { items: [row(4, { exerciseCount: 0 })] })));
            const radio = Array.from(root().querySelectorAll<HTMLInputElement>('app-segmented-choice input'))
                .find(input => input.closest('label')?.textContent?.includes('Без упражнений'))!;
            radio.click();
            radio.dispatchEvent(new Event('change', { bubbles: true }));
            await settle();
            expect(items.list).toHaveBeenLastCalledWith(deckId, { sort: 'exerciseCount', exerciseCount: true, limit: 50 });
            expect(root().querySelector('app-segmented-choice .hint')?.textContent).toContain('Сначала материалы без упражнений');
            expect(bar()).toBeNull();
            expect(rows()).toHaveLength(1);
        });

        it('lists materials without exercises first and focuses the list when a statistics widget asks', async () => {
            const component = await open();
            items.list.mockReturnValueOnce(of(page(0, 2, 2)));
            document.body.append(root());
            component.showMissingFirst();
            await settle();
            expect(items.list).toHaveBeenLastCalledWith(deckId, { sort: 'exerciseCount', exerciseCount: true, limit: 50 });
            expect(document.activeElement).toBe(root().querySelector('h2'));
            root().remove();
        });
    });

    describe('paging', () => {
        it('admits one next-page request and ignores it after the sort context changes', async () => {
            await open();
            const pending = new Subject<ItemPage>();
            items.list.mockReturnValueOnce(pending);
            const next = () => fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
            next(); next();
            expect(items.list).toHaveBeenCalledTimes(2);
            items.list.mockReturnValueOnce(of(page(0, 2, 50)));
            fixture.debugElement.query(By.css('app-segmented-choice')).triggerEventHandler('valueChange', 'exerciseCount');
            await settle();
            pending.next(page(20, 20, 50)); pending.complete(); await settle();
            expect(rows()).toHaveLength(2);
            expect(items.list).toHaveBeenLastCalledWith(deckId, { sort: 'exerciseCount', exerciseCount: true, limit: 50 });
        });
        it('appends the next page by cursor in the same sort and never re-requests the first one', async () => {
            await open();
            items.list.mockReturnValueOnce(of(page(20, 20, 50)));
            fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
            await settle();
            expect(items.list).toHaveBeenLastCalledWith(deckId, { cursor: 'cursor-20', sort: 'ordinal', exerciseCount: true, limit: 50 });
            expect(rows()).toHaveLength(40);
            expect(rows()[20].querySelector('.folio')?.textContent?.trim()).toBe('21');
            items.list.mockReturnValueOnce(of(page(40, 10, 50)));
            fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
            await settle();
            expect(rows()).toHaveLength(50);
            expect(root().textContent).not.toContain('Показать ещё');
            expect(items.list).toHaveBeenCalledTimes(3);
        });

        it('keeps the loaded rows and offers a retry when a later page fails', async () => {
            await open();
            items.list.mockReturnValueOnce(throwError(() => new Error('offline')));
            fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
            await settle();
            expect(rows()).toHaveLength(20);
            expect(root().querySelector('app-auto-load [role=alert]')).not.toBeNull();
            items.list.mockReturnValueOnce(of(page(20, 20, 50)));
            fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
            await settle();
            expect(rows()).toHaveLength(40);
            expect(root().querySelector('app-auto-load [role=alert]')).toBeNull();
        });

        it('starts over with a notice when the next page belongs to another Deck revision', async () => {
            await open();
            await select(0);
            items.list.mockReturnValueOnce(of(page(20, 20, 50, { deckRevisionId: '77777777-7777-4777-8777-777777777777' })))
                .mockReturnValueOnce(of(page(0, 20, 49)));
            fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
            await settle();
            expect(root().querySelector('.note')?.textContent).toContain('Колода изменилась в другой вкладке');
            expect(rows()).toHaveLength(20);
            expect(bar()).toBeNull();
            expect(root().querySelector('h2')?.textContent).toContain('49');
        });

        it('treats a stale cursor (412) the same way', async () => {
            await open();
            items.list.mockReturnValueOnce(throwError(() => http(412, 'VERSION_CONFLICT'))).mockReturnValueOnce(of(page(0, 20, 50)));
            fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
            await settle();
            expect(root().querySelector('.note')?.textContent).toContain('Список обновлён');
            expect(items.list).toHaveBeenCalledTimes(3);
        });
    });

    describe('«Эталон»', () => {
        const ack = (overrides: Partial<ExemplarAcknowledgement> = {}): ExemplarAcknowledgement => ({
            commandId: '018f1d98-5c10-7abc-8abc-0123456789ad', deckId, memberKey: key(1), itemRevisionId: row(1).itemRevisionId,
            exemplar: true, changed: true, exemplarCount: 2, ...overrides
        });
        const star = (index: number) => root().querySelectorAll<HTMLButtonElement>('button.star')[index];

        it('sets the desired value against the item revision the user saw and updates the star and the budget', async () => {
            await open();
            api.setExemplar.mockReturnValueOnce(of(ack()));
            star(1).click();
            await settle();
            expect(api.setExemplar).toHaveBeenCalledWith(deckId, key(1), row(1).itemRevisionId, true, expect.stringMatching(/^[0-9a-f-]{36}$/));
            expect(star(1).getAttribute('aria-pressed')).toBe('true');
            expect(root().querySelector('.budget')?.textContent).toContain('Эталонов: 2 из 10');
            expect(root().querySelector('[role="status"]')?.textContent).toContain('Эталон отмечен: Материал 2.');
            api.setExemplar.mockReturnValueOnce(of(ack({ exemplar: false, exemplarCount: 1 })));
            star(1).click();
            await settle();
            expect(vi.mocked(api.setExemplar).mock.lastCall?.[3]).toBe(false);
            expect(star(1).getAttribute('aria-pressed')).toBe('false');
            expect(root().querySelector('[role="status"]')?.textContent).toContain('Эталон снят');
        });

        it('ignores a second click while the first request is in flight', async () => {
            await open();
            const pending = new Subject<ExemplarAcknowledgement>();
            api.setExemplar.mockReturnValueOnce(pending);
            star(1).click();
            fixture.detectChanges();
            star(1).click();
            expect(api.setExemplar).toHaveBeenCalledTimes(1);
            pending.next(ack());
            pending.complete();
            await settle();
            star(1).click();
            expect(api.setExemplar).toHaveBeenCalledTimes(2);
        });

        it('learns the limit from the server (422), explains it and blocks further stars', async () => {
            await open();
            api.setExemplar.mockReturnValueOnce(throwError(() => http(422, 'EXEMPLAR_LIMIT_REACHED')));
            star(1).click();
            await settle();
            expect(root().querySelector('p.note.error')?.textContent).toContain('не больше 10');
            expect(root().querySelector('.budget')?.textContent).toContain('Эталонов: 10 из 10');
            expect(star(1).getAttribute('aria-disabled')).toBe('true');
            expect(star(1).getAttribute('aria-pressed')).toBe('false');
        });

        it('reloads when the material changed in another tab, and reports other failures calmly', async () => {
            await open();
            api.setExemplar.mockReturnValueOnce(throwError(() => http(412, 'VERSION_CONFLICT')));
            items.list.mockReturnValueOnce(of(page(0, 20, 50)));
            star(1).click();
            await settle();
            expect(items.list).toHaveBeenCalledTimes(2);
            expect(root().querySelector('p.note.error')?.textContent).toContain('изменился в другой вкладке');
            api.setExemplar.mockReturnValueOnce(throwError(() => http(0)));
            star(2).click();
            await settle();
            expect(root().querySelector('p.note.error')?.textContent).toContain('нет связи');
            expect(star(2).getAttribute('aria-pressed')).toBe('false');
        });
    });

    describe('bulk deletion', () => {
        it('shows the bar only with a selection, announces the count politely and clears with Esc', async () => {
            await open();
            expect(bar()).toBeNull();
            await select(0, 1);
            expect(bar()).not.toBeNull();
            expect(bar()!.querySelector('section')?.getAttribute('aria-label')).toBe('Действия с выбранными');
            expect(bar()!.querySelector('.count')?.textContent?.trim()).toBe('Выбрано 2 материала');
            await settle(500);
            expect(root().querySelector('p.sr-only[role="status"]')?.textContent).toBe('Выбрано 2 материала.');
            checkbox(0).dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            await settle();
            expect(bar()).toBeNull();
            expect(checkbox(1).checked).toBe(false);
            await settle(500);
            expect(root().querySelector('p.sr-only[role="status"]')?.textContent).toBe('');
        });

        it('keeps the selection when Esc disarms the hold button instead', async () => {
            await open();
            await select(0);
            const hold = bar()!.querySelector<HTMLButtonElement>('app-hold-to-delete-button button')!;
            hold.click();
            fixture.detectChanges();
            hold.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
            await settle();
            expect(bar()).not.toBeNull();
            hold.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
            await settle();
            expect(bar()).toBeNull();
        });

        it('previews exactly the selection against the revision the list shows and spells out the consequences', async () => {
            await open();
            await select(0, 1);
            expect(api.previewDeletion).toHaveBeenCalledTimes(1);
            expect(api.previewDeletion).toHaveBeenCalledWith(deckId, revisionId, { itemIds: [key(0), key(1)] });
            const consequence = bar()!.querySelector('.consequence')!;
            expect(consequence.textContent).toBe(hub.bulkDelete.clientMessages.holdToDeleteConsequence.replace('7 материалов', '2 материала'));
            expect(bar()!.querySelector<HTMLButtonElement>('app-hold-to-delete-button button')!.disabled).toBe(false);
        });

        it('keeps deletion unavailable while the consequences are being counted, and debounces rapid selection changes', async () => {
            await open();
            checkbox(0).click();
            await settle(100);
            checkbox(1).click();
            await settle(100);
            expect(api.previewDeletion).not.toHaveBeenCalled();
            expect(bar()!.querySelector<HTMLButtonElement>('app-hold-to-delete-button button')!.disabled).toBe(true);
            expect(bar()!.querySelector('.hint')?.textContent).toContain('Считаем');
            await settle(300);
            expect(api.previewDeletion).toHaveBeenCalledTimes(1);
            expect(bar()!.querySelector('.hint')).toBeNull();
        });

        it('deletes through the bulk command with the Deck version, revision and a fresh command, then reloads and reports', async () => {
            const component = await open();
            let changed = 0;
            component.changed.subscribe(() => changed++);
            await select(0, 1);
            api.deleteMany.mockReturnValueOnce(of(receipt(result())));
            items.list.mockReturnValueOnce(of(page(0, 20, 48)));
            confirmDeletion();
            await settle();
            expect(api.deleteMany).toHaveBeenCalledWith(deckId, '7', revisionId, { itemIds: [key(0), key(1)] }, expect.stringMatching(/^[0-9a-f-]{36}$/));
            expect(changed).toBe(1);
            expect(bar()).toBeNull();
            expect(items.list).toHaveBeenCalledTimes(2);
            expect(root().querySelector('.note')?.textContent).toContain('Удалено 2 материала.');
            expect(root().querySelector('.note')?.getAttribute('role')).toBe('status');
            expect(root().querySelector('h2')?.textContent).toContain('48');
        });

        it('describes a partial run honestly and offers to select what is left', async () => {
            await open(of(page(0, 20, 102)));
            await select(0);
            api.deleteMany.mockReturnValueOnce(of(receipt(result({ status: 'PARTIAL', requested: 3, deleted: 2, notDeleted: [key(7)],
                stopReason: 'VERSION_CONFLICT' }))));
            items.list.mockReturnValueOnce(of(page(0, 20, 100)));
            confirmDeletion();
            await settle();
            const notice = root().querySelector('.note')!;
            expect(notice.getAttribute('role')).toBe('alert');
            expect(notice.textContent).toContain('Удалено 2 материала. 1 не тронут: колода изменилась в другой вкладке. Обновите список.');
            buttonWith('Выбрать оставшиеся (1)')!.click();
            await settle(300);
            expect(bar()!.querySelector('.count')?.textContent?.trim()).toBe('Выбрано 1 материал');
            expect(api.previewDeletion).toHaveBeenLastCalledWith(deckId, revisionId, { itemIds: [key(7)] });
        });

        it('reports a stale Deck (412) as «nothing deleted» and offers a refresh', async () => {
            await open();
            await select(0);
            api.deleteMany.mockReturnValueOnce(throwError(() => http(412, 'VERSION_CONFLICT')));
            confirmDeletion();
            await settle();
            expect(root().querySelector('.note')?.textContent).toContain(hub.bulkDelete.clientMessages.staleDeck);
            expect(bar()).not.toBeNull();
            items.list.mockReturnValueOnce(of(page(0, 20, 50)));
            buttonWith('Обновить список')!.click();
            await settle();
            expect(items.list).toHaveBeenCalledTimes(2);
            expect(root().querySelector('.note')).toBeNull();
            expect(bar()).toBeNull();
        });

        it('resends exactly the same command after an unconfirmed outcome, and a new one after a decision', async () => {
            await open();
            await select(0);
            api.deleteMany.mockReturnValueOnce(throwError(() => http(0))).mockReturnValueOnce(of(receipt(result({ requested: 1, deleted: 1 }))));
            items.list.mockReturnValueOnce(of(page(0, 20, 49)));
            confirmDeletion();
            await settle();
            expect(root().querySelector('.note')?.textContent).toContain('та же команда');
            confirmDeletion();
            await settle();
            const [first, second] = vi.mocked(api.deleteMany).mock.calls;
            expect(second).toEqual(first);
        });

        it('explains a server refusal for a too large selection and a generic failure', async () => {
            await open();
            await select(0);
            api.deleteMany.mockReturnValueOnce(throwError(() => http(422, 'BULK_SELECTION_TOO_LARGE')));
            confirmDeletion();
            await settle();
            expect(root().querySelector('.note')?.textContent).toContain('не больше 500');
            api.deleteMany.mockReturnValueOnce(throwError(() => http(400)));
            confirmDeletion();
            await settle();
            expect(root().querySelector('.note')?.textContent).toContain('Материалы не удалены');
            expect(vi.mocked(api.deleteMany).mock.calls[1][4]).not.toBe(vi.mocked(api.deleteMany).mock.calls[0][4]);
        });

        it('does not delete before a preview of exactly this selection is in', async () => {
            await open();
            checkbox(0).click();
            fixture.detectChanges();
            confirmDeletion();
            expect(api.deleteMany).not.toHaveBeenCalled();
        });

        it('turns a stale preview (412) and a failed preview into explanations instead of an enabled button', async () => {
            await open();
            api.previewDeletion.mockReturnValueOnce(throwError(() => http(412, 'VERSION_CONFLICT')));
            await select(0);
            expect(bar()!.querySelector('.hint')?.textContent).toContain('Ничего не удалено');
            expect(root().querySelector('.note')?.textContent).toContain('Обновите список');
            expect(bar()!.querySelector<HTMLButtonElement>('app-hold-to-delete-button button')!.disabled).toBe(true);
            api.previewDeletion.mockReturnValueOnce(throwError(() => http(500)));
            await select(1);
            expect(bar()!.querySelector('.hint')?.textContent).toContain('Не удалось посчитать последствия');
            api.previewDeletion.mockReturnValueOnce(throwError(() => http(422, 'BULK_SELECTION_TOO_LARGE')));
            await select(2);
            expect(bar()!.querySelector('.hint')?.textContent).toContain('не больше 500');
        });

        it('refuses to send what the server would refuse: more than 100 individual materials or more than 500 in the deck', async () => {
            await open(of(page(0, 20, 600)));
            // «Select all in deck» addresses 600 materials: above the 500 limit.
            checkbox(0).click();
            root().querySelector<HTMLInputElement>('.select-all input')!.click();
            fixture.detectChanges();
            buttonWith('Выбрать все 600 в колоде?')!.click();
            await settle(300);
            expect(api.previewDeletion).not.toHaveBeenCalled();
            expect(bar()!.querySelector('.hint')?.textContent).toContain('не больше 500');
            expect(bar()!.querySelector<HTMLButtonElement>('app-hold-to-delete-button button')!.disabled).toBe(true);
            expect(bar()!.querySelector('.count')?.textContent?.trim()).toBe('Выбрано 600 материалов');
        });

        it('sends «all in deck except …» when everything but a few is selected and the deck fits the limit', async () => {
            await open(of(page(0, 20, 30)));
            root().querySelector<HTMLInputElement>('.select-all input')!.click();
            fixture.detectChanges();
            buttonWith('Выбрать все 30 в колоде?')!.click();
            fixture.detectChanges();
            checkbox(3).click();
            await settle(300);
            expect(api.previewDeletion).toHaveBeenCalledWith(deckId, revisionId, { allInDeck: true, except: [key(3)] });
        });

        it('blocks more than 100 individually selected materials with an explanation instead of sending them', async () => {
            await open(of(page(0, 100, 150)));
            items.list.mockReturnValueOnce(of(page(100, 20, 150, { nextCursor: null })));
            fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
            await settle();
            root().querySelector<HTMLInputElement>('.select-all input')!.click();
            await settle(300);
            expect(api.previewDeletion).not.toHaveBeenCalled();
            expect(bar()!.querySelector('.hint')?.textContent).toContain('не больше 100');
            expect(bar()!.querySelector<HTMLButtonElement>('app-hold-to-delete-button button')!.disabled).toBe(true);
            // Dropping below the limit makes it deletable again.
            root().querySelector<HTMLInputElement>('.select-all input')!.click();
            await settle();
            for (let index = 0; index < 100; index++) checkbox(index).click();
            await settle(300);
            expect(api.previewDeletion).toHaveBeenCalledTimes(1);
        });
    });

    describe('AI generation', () => {
        it('has no «Упражнения с ИИ для выбранных» unless the server offers generation', async () => {
            await open();
            await select(0);
            expect(bar()!.textContent).not.toContain('ИИ');
            fixture.destroy();
            await open(of(page(0, 20, 50)), { generationAvailable: true });
            await select(0);
            expect(bar()!.textContent).toContain('Упражнения с ИИ для выбранных');
        });

        it('opens the exercise builder with the selected members: the address carries member keys, never revisions (AI-13)', async () => {
            const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
            await open(of(page(0, 20, 50)), { generationAvailable: true });
            await select(0, 2);
            buttonWith('Упражнения с ИИ для выбранных')!.click();
            expect(navigate).toHaveBeenCalledWith(['/decks', deckId, 'exercises', 'generate'], { queryParams: { members: `${key(0)},${key(2)}` } });
            expect(JSON.stringify(navigate.mock.calls)).not.toContain('5555');
        });

        it('opens it for «все материалы колоды» with the exceptions, and without a list of keys for none', async () => {
            const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
            const component = await open(of(page(0, 20, 50)), { generationAvailable: true });
            (component as unknown as { selection: { selectAllInDeck(): void; toggle(key: string, order: string[]): void } }).selection.selectAllInDeck();
            await settle(300);
            buttonWith('Упражнения с ИИ для выбранных')!.click();
            expect(navigate).toHaveBeenLastCalledWith(['/decks', deckId, 'exercises', 'generate'], { queryParams: { all: 1, except: null } });
            checkbox(1).click();
            await settle(300);
            buttonWith('Упражнения с ИИ для выбранных')!.click();
            expect(navigate).toHaveBeenLastCalledWith(['/decks', deckId, 'exercises', 'generate'], { queryParams: { all: 1, except: key(1) } });
        });

        it('says so instead of opening the builder when more than 100 materials are selected explicitly', async () => {
            const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
            const component = await open(of(page(0, 20, 150)), { generationAvailable: true });
            (component as unknown as { selection: { replace(keys: string[]): void } }).selection.replace(Array.from({ length: 101 }, (_, index) => key(index)));
            await settle(300);
            buttonWith('Упражнения с ИИ для выбранных')!.click();
            fixture.detectChanges();
            expect(navigate).not.toHaveBeenCalled();
            expect(root().querySelector('[role=alert]')?.textContent).toContain('не больше 100 материалов');
        });
    });

    describe('page integration', () => {
        it('reserves the bar height as scroll padding while the bar exists and releases it afterwards', async () => {
            await open();
            const property = () => document.documentElement.style.getPropertyValue('--mn-bulk-bar-height');
            expect(property()).toBe('');
            await select(0);
            expect(property()).toMatch(/^\d+px$/);
            checkbox(0).click();
            await settle();
            expect(property()).toBe('');
        });

        it('moves focus to the heading after the bar\'s «Снять выбор» removed the focused control', async () => {
            await open();
            document.body.append(root());
            await select(0);
            buttonWith('Снять выбор')!.click();
            await settle();
            expect(bar()).toBeNull();
            expect(document.activeElement).toBe(root().querySelector('h2'));
            root().remove();
        });

        it('reloads when the Deck id changes and ignores a slow answer for the previous one', async () => {
            const slow = new Subject<ItemPage>();
            await open(slow);
            items.list.mockReturnValueOnce(of(page(0, 3, 3)));
            fixture.componentRef.setInput('deckId', '99999999-9999-4999-8999-999999999999');
            fixture.detectChanges();
            await settle();
            slow.next(page(0, 20, 50));
            await settle();
            expect(rows()).toHaveLength(3);
        });
    });
});
