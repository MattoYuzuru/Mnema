import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { ToastService } from '../../../core/notifications/toast.service';
import { lastCall, spyObj, type SpyObj } from '../../../../testing/mocks';
import { ItemApiService } from '../../authoring/item-api.service';
import { PublicationApiService } from './publication-api.service';
import { PublicationBlockComponent } from './publication-block.component';
import { PublicationCommand, PublicationState, PublicationWriteResult } from './publication.models';
import { neverPublished, sharedState, topicDirectory, written } from './publication-test-data';

describe('PublicationBlockComponent', () => {
    let fixture: ComponentFixture<PublicationBlockComponent>;
    let root: HTMLElement;
    let api: SpyObj<PublicationApiService>;
    let toasts: ToastService;
    let levels: string[];
    const deckId = '11111111-1111-4111-8111-111111111111';

    const text = (): string => root.textContent!.replace(/\s+/g, ' ');
    const button = (label: string): HTMLButtonElement =>
        [...root.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent!.trim() === label)!;
    const settle = async (): Promise<void> => { await fixture.whenStable(); fixture.detectChanges(); };

    async function create(state: PublicationState | Subject<PublicationState> = neverPublished()): Promise<void> {
        api.read.mockReturnValue(state instanceof Subject ? state : of(state));
        fixture = TestBed.createComponent(PublicationBlockComponent);
        fixture.componentRef.setInput('deckId', deckId);
        fixture.componentInstance.levelChange.subscribe(level => levels.push(level));
        fixture.detectChanges();
        root = fixture.nativeElement as HTMLElement;
        await settle();
    }

    beforeEach(async () => {
        levels = [];
        api = spyObj<PublicationApiService>({
            read: vi.fn().mockName('PublicationApiService.read'),
            save: vi.fn().mockName('PublicationApiService.save'),
            topics: vi.fn().mockName('PublicationApiService.topics')
        });
        api.topics.mockReturnValue(of(topicDirectory()));
        const items = spyObj<ItemApiService>({ list: vi.fn().mockName('ItemApiService.list') });
        await TestBed.configureTestingModule({
            providers: [provideRouter([]), { provide: PublicationApiService, useValue: api }, { provide: ItemApiService, useValue: items }]
        }).compileComponents();
        toasts = TestBed.inject(ToastService);
        vi.spyOn(toasts, 'echo');
    });

    describe('loading', () => {
        it('says it is loading, then shows the private line', async () => {
            const pending = new Subject<PublicationState>();
            await create(pending);
            expect(text()).toContain('Загружаем сведения о доступе…');
            pending.next(neverPublished());
            await settle();
            expect(text()).toContain('Колода видна только вам');
            expect(levels).toEqual(['private']);
            expect(api.read).toHaveBeenCalledWith(deckId);
        });

        it('shows an inline retry when the state cannot be read, and recovers', async () => {
            api.read.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
            fixture = TestBed.createComponent(PublicationBlockComponent);
            fixture.componentRef.setInput('deckId', deckId);
            fixture.componentInstance.levelChange.subscribe(level => levels.push(level));
            fixture.detectChanges();
            root = fixture.nativeElement as HTMLElement;
            await settle();
            expect(text()).toContain('Не удалось загрузить сведения о доступе.');
            expect(root.querySelector('h2')).not.toBeNull();
            api.read.mockReturnValue(of(sharedState()));
            button('Повторить').click();
            await settle();
            expect(text()).toContain('Опубликовано');
            expect(levels).toEqual(['public']);
        });

        it('reads again when the host bumps the reload token', async () => {
            await create();
            fixture.componentRef.setInput('reloadToken', 1);
            await settle();
            expect(api.read).toHaveBeenCalledTimes(2);
        });
    });

    describe('a private deck', () => {
        it('is quiet: one line and one action, no link, no publish controls', async () => {
            await create();
            expect(text()).toContain('Колода видна только вам');
            expect(button('Сделать публичной…')).toBeDefined();
            expect(root.querySelector('app-access-level')).toBeNull();
            expect(root.querySelector('.publication-link')).toBeNull();
            expect(text()).not.toContain('Опубликовать обновление');
        });

        it('opens the checklist in place and closes it again, keeping the button state in aria-expanded', async () => {
            await create();
            const open = button('Сделать публичной…');
            expect(open.getAttribute('aria-expanded')).toBe('false');
            open.click();
            await settle();
            expect(open.getAttribute('aria-expanded')).toBe('true');
            expect(root.querySelector('app-publication-checklist')).not.toBeNull();
            expect(open.getAttribute('aria-controls')).toBe(root.querySelector('app-publication-checklist')!.id);
            button('Отмена').click();
            await settle();
            expect(root.querySelector('app-publication-checklist')).toBeNull();
            expect(open.getAttribute('aria-expanded')).toBe('false');
        });

        it('becomes public in place when the checklist publishes', async () => {
            await create();
            button('Сделать публичной…').click();
            await settle();
            const checklist = fixture.debugElement.query(el => el.name === 'app-publication-checklist');
            checklist.componentInstance.published.emit(sharedState({ unpublishedChanges: 0, publishedRevisionId: '44444444-4444-4444-8444-444444444444' }));
            await settle();
            expect(root.querySelector('app-publication-checklist')).toBeNull();
            expect(root.querySelector('app-access-level')!.textContent).toContain('Публичная');
            expect(levels).toEqual(['private', 'public']);
        });

        it('passes «Добавить описание» on to the hub', async () => {
            await create(neverPublished({ checklist: { ...neverPublished().checklist, description: false } }));
            const edit = vi.fn();
            fixture.componentInstance.editDescription.subscribe(edit);
            button('Сделать публичной…').click();
            await settle();
            button('Добавить описание').click();
            expect(edit).toHaveBeenCalledOnce();
        });
    });

    describe('a replayed first publication', () => {
        it('closes the checklist and focuses the heading once the re-read state is public', async () => {
            await create();
            button('Сделать публичной…').click();
            await settle();
            const checklist = fixture.debugElement.query(el => el.name === 'app-publication-checklist').componentInstance;
            api.read.mockReturnValue(of(sharedState({ unpublishedChanges: 0 })));
            checklist.replayed.emit();
            checklist.refresh.emit();
            await settle();
            expect(root.querySelector('app-publication-checklist')).toBeNull();
            expect(root.querySelector('app-access-level')!.textContent).toContain('Публичная');
        });

        it('keeps the checklist open when the re-read state is not public', async () => {
            await create();
            button('Сделать публичной…').click();
            await settle();
            const checklist = fixture.debugElement.query(el => el.name === 'app-publication-checklist').componentInstance;
            checklist.replayed.emit();
            checklist.refresh.emit();
            await settle();
            expect(root.querySelector('app-publication-checklist')).not.toBeNull();
        });
    });

    describe('a shared deck', () => {
        it('shows the level, the link, the time of publication and the unpublished changes', async () => {
            await create(sharedState());
            expect(root.querySelector('app-access-level')!.textContent).toContain('Публичная');
            const link = root.querySelector<HTMLAnchorElement>('.publication-link a')!;
            expect(link.href).toBe(`${window.location.origin}/d/Kq7xT3mNpR/yaponskiy-zametki`);
            expect(link.textContent).toContain('откроется в новой вкладке');
            expect(text()).toContain('Опубликовано');
            expect(root.querySelector('time')!.getAttribute('datetime')).toBe('2026-10-11T09:30:00.123456Z');
            expect(text()).toContain('Изменения для учеников не опубликованы (4)');
            const update = button('Опубликовать обновление');
            expect(update.classList.contains('primary')).toBe(true);
            expect(update.getAttribute('aria-expanded')).toBe('false');
            expect(text()).not.toContain('Сделать публичной');
        });

        it('says all is published when nothing changed after the publication', async () => {
            await create(sharedState({ unpublishedChanges: 0, headRevisionId: sharedState().publishedRevisionId! }));
            expect(text()).toContain('Все изменения опубликованы');
            expect(text()).not.toContain('Опубликовать обновление');
        });

        it('offers the update when only the title or the description changed', async () => {
            await create(sharedState({ unpublishedChanges: 0 }));
            expect(text()).toContain('Название или описание изменились, но ученики пока видят прежние');
            expect(button('Опубликовать обновление')).toBeDefined();
        });

        it('names each other level and offers to make a link deck public', async () => {
            await create(sharedState({ visibility: 'LINK', link: '/d/Kq7xT3mNpR', unpublishedChanges: 0, headRevisionId: sharedState().publishedRevisionId! }));
            expect(root.querySelector('app-access-level')!.textContent).toContain('По ссылке');
            expect(button('Сделать публичной…')).toBeDefined();
            expect(root.querySelector<HTMLAnchorElement>('.publication-link a')!.href).toBe(`${window.location.origin}/d/Kq7xT3mNpR`);
            expect(levels).toEqual(['link']);
        });

        it('copies the link and confirms it', async () => {
            const writeText = vi.fn().mockResolvedValue(undefined);
            vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
            await create(sharedState());
            button('Скопировать ссылку').click();
            await settle();
            expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/d/Kq7xT3mNpR/yaponskiy-zametki`);
            expect(toasts.echo).toHaveBeenCalledWith('Ссылка скопирована');
        });

        it('shows the link as selectable text when the browser refuses to copy', async () => {
            vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
            await create(sharedState());
            button('Скопировать ссылку').click();
            await settle();
            const field = root.querySelector<HTMLInputElement>('input[type=url]')!;
            expect(field.readOnly).toBe(true);
            expect(field.value).toContain('/d/Kq7xT3mNpR');
            expect(root.querySelector('label[for]')!.textContent).toContain('Ссылка на колоду');
            expect(text()).toContain('Не удалось скопировать');
        });
    });

    describe('publishing an update', () => {
        const openForm = async (): Promise<HTMLTextAreaElement> => {
            button('Опубликовать обновление').click();
            await settle();
            return root.querySelector<HTMLTextAreaElement>('textarea')!;
        };
        const typeNote = async (field: HTMLTextAreaElement, value: string): Promise<void> => {
            field.value = value;
            field.dispatchEvent(new Event('input'));
            await settle();
        };
        const submit = (): void => { root.querySelector('form')!.dispatchEvent(new Event('submit')); };
        const saved = (note: string | null): PublicationState => sharedState({
            unpublishedChanges: 0, headRevisionId: sharedState().headRevisionId, publishedRevisionId: sharedState().headRevisionId,
            releaseNote: note, rowVersion: '4'
        });

        it('opens a labelled optional field with a live counter and no changes until it is sent', async () => {
            await create(sharedState());
            const field = await openForm();
            const update = button('Опубликовать обновление');
            expect(update.getAttribute('aria-expanded')).toBe('true');
            expect(root.querySelector('label')!.textContent).toBe('Что нового');
            expect(field.labels![0].textContent).toBe('Что нового');
            expect(text()).toContain('0/500');
            await typeNote(field, 'Добавлены слова');
            expect(text()).toContain('15/500');
            expect(api.save).not.toHaveBeenCalled();
        });

        it('sends the whole state with the head it saw and the note, with the If-Match of the loaded version', async () => {
            await create(sharedState());
            const field = await openForm();
            await typeNote(field, '  Добавлены 20 слов  ');
            api.save.mockImplementation((_deck, _version, command) => of(written(saved('Добавлены 20 слов'), command.commandId)));
            submit();
            await settle();
            const [deck, version, command] = lastCall(api.save) as [string, string, PublicationCommand];
            expect(deck).toBe(deckId);
            expect(version).toBe('3');
            expect(command).toMatchObject({
                visibility: 'PUBLIC', requestsEnabled: true,
                metadata: sharedState().metadata,
                publish: { expectedHeadRevisionId: sharedState().headRevisionId, releaseNote: 'Добавлены 20 слов' }
            });
            expect(toasts.echo).toHaveBeenCalledWith('Обновление опубликовано');
            expect(root.querySelector('form')).toBeNull();
            expect(text()).toContain('Все изменения опубликованы');
            expect(root.querySelector('.publication-status button')).toBeNull();
        });

        it('sends a blank note as null', async () => {
            await create(sharedState());
            const field = await openForm();
            await typeNote(field, '   ');
            api.save.mockImplementation((_deck, _version, command) => of(written(saved(null), command.commandId)));
            submit();
            await settle();
            expect((lastCall(api.save)[2] as PublicationCommand).publish!.releaseNote).toBeNull();
        });

        it('shows only one primary button while the form is open', async () => {
            await create(sharedState());
            expect(root.querySelectorAll('.button.primary')).toHaveLength(1);
            await openForm();
            const primary = [...root.querySelectorAll('.button.primary')];
            expect(primary.map(item => item.textContent!.trim())).toEqual(['Опубликовать']);
            expect(button('Опубликовать обновление').classList.contains('primary')).toBe(false);
        });

        it('speaks the crossing of the limit once, as a polite status, and shows a static alert', async () => {
            await create(sharedState());
            const field = await openForm();
            const status = (): string => root.querySelector('form [role=status]')!.textContent!;
            expect(root.querySelector('form [role=status]')!.getAttribute('aria-live')).toBe('polite');
            await typeNote(field, 'я'.repeat(500));
            expect(status()).toBe('');
            await typeNote(field, 'я'.repeat(503));
            expect(status()).toBe('Лишних символов: 3');
            await typeNote(field, 'я'.repeat(510));
            expect(status()).toBe('Лишних символов: 3');
            expect(root.querySelector('.field-error')!.getAttribute('role')).toBe('alert');
            await typeNote(field, 'я'.repeat(500));
            expect(status()).toBe('');
            expect(root.querySelector('.field-error')).toBeNull();
            await typeNote(field, 'я'.repeat(501));
            expect(status()).toBe('Лишних символов: 1');
        });

        it('refuses a note over 500 characters and says how to fix it', async () => {
            await create(sharedState());
            const field = await openForm();
            await typeNote(field, '😀'.repeat(501));
            expect(text()).toContain('501/500');
            expect(root.querySelector('.field-error')!.textContent).toContain('Сократите текст до 500 символов');
            expect(field.getAttribute('aria-invalid')).toBe('true');
            expect(button('Опубликовать').getAttribute('aria-disabled')).toBe('true');
            submit();
            expect(api.save).not.toHaveBeenCalled();
            await typeNote(field, '😀'.repeat(500));
            expect(field.hasAttribute('aria-invalid')).toBe(false);
        });

        it('shows the publishing state and ignores a second press while it runs', async () => {
            await create(sharedState());
            await openForm();
            const response = new Subject<PublicationWriteResult>();
            api.save.mockReturnValue(response);
            submit();
            submit();
            await settle();
            expect(api.save).toHaveBeenCalledOnce();
            const busy = button('Публикуем…');
            expect(busy.getAttribute('aria-busy')).toBe('true');
            expect(busy.getAttribute('aria-disabled')).toBe('true');
            response.next(written(saved(null), (api.save.mock.lastCall![2] as PublicationCommand).commandId));
            response.complete();
            await settle();
            expect(root.querySelector('form')).toBeNull();
        });

        it('reloads the state on a stale version, says so, and keeps the form and the note', async () => {
            await create(sharedState());
            const field = await openForm();
            await typeNote(field, 'Моя заметка');
            api.save.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 412, error: { code: 'VERSION_CONFLICT' } })));
            api.read.mockReturnValue(of(sharedState({ rowVersion: '4', unpublishedChanges: 6 })));
            submit();
            await settle();
            expect(root.querySelector('[role=alert]')!.textContent).toContain('Колода изменилась, пока вы публиковали');
            expect(api.read).toHaveBeenCalledTimes(2);
            expect(text()).toContain('Изменения для учеников не опубликованы (6)');
            expect(root.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('Моя заметка');
        });

        it('names what a public deck lacks when the server refuses the update', async () => {
            await create(sharedState());
            await openForm();
            api.save.mockReturnValue(throwError(() => new HttpErrorResponse({
                status: 409, error: { code: 'PUBLICATION_REQUIREMENTS', failed: ['description', 'blockedMedia'] }
            })));
            submit();
            await settle();
            expect(root.querySelector('[role=alert]')!.textContent).toContain('не хватает: описание, картинки');
        });

        it('retries a lost request with the very same command', async () => {
            await create(sharedState());
            await openForm();
            api.save.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
            submit();
            await settle();
            expect(root.querySelector('[role=alert]')!.textContent).toContain('Проверьте соединение');
            const first = (api.save.mock.lastCall![2] as PublicationCommand).commandId;
            api.save.mockImplementation((_deck, _version, command) => of(written(saved(null), command.commandId)));
            submit();
            await settle();
            expect((api.save.mock.lastCall![2] as PublicationCommand).commandId).toBe(first);
            expect(root.querySelector('form')).toBeNull();
        });

        it('uses a new command when the note changed after an unknown outcome', async () => {
            await create(sharedState());
            const field = await openForm();
            api.save.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
            submit();
            await settle();
            const first = (api.save.mock.lastCall![2] as PublicationCommand).commandId;
            await typeNote(field, 'Другой текст');
            submit();
            await settle();
            expect((api.save.mock.lastCall![2] as PublicationCommand).commandId).not.toBe(first);
        });

        it('re-reads the state after a replayed answer, whose embedded state is historical', async () => {
            await create(sharedState());
            await openForm();
            api.save.mockImplementation((_deck, _version, command) => of(written(saved(null), command.commandId, true)));
            api.read.mockReturnValue(of(saved(null)));
            submit();
            await settle();
            expect(api.read).toHaveBeenCalledTimes(2);
            expect(text()).toContain('Все изменения опубликованы');
        });

        it('closes with Cancel, with Escape and by pressing the toggle again, returning focus to the button', async () => {
            await create(sharedState());
            await openForm();
            button('Отмена').click();
            await settle();
            expect(root.querySelector('form')).toBeNull();
            await openForm();
            root.querySelector('form')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            await settle();
            expect(root.querySelector('form')).toBeNull();
            await openForm();
            button('Опубликовать обновление').click();
            await settle();
            expect(root.querySelector('form')).toBeNull();
        });
    });
});
