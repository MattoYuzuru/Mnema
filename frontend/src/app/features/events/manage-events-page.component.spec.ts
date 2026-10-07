import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ManageEventsPageComponent } from './manage-events-page.component';
import { TEST_EVENT } from './events-test-data';

describe('owner event editor', () => {
    let http: HttpTestingController;
    let fixture: ComponentFixture<ManageEventsPageComponent>;
    let root: HTMLElement;
    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
        http = TestBed.inject(HttpTestingController);
        fixture = TestBed.createComponent(ManageEventsPageComponent); root = fixture.nativeElement; fixture.detectChanges();
    });
    afterEach(() => http.verify());
    async function settle(): Promise<void> {
        // The editor deliberately awaits authorization before its list. Flush that Promise chain before rendering.
        await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
        await fixture.whenStable(); fixture.detectChanges();
    }
    async function admit(): Promise<void> {
        http.expectOne('/api/admin/events/access').flush({ allowed: true }); await settle();
        http.expectOne('/api/admin/events').flush({ items: [TEST_EVENT], nextCursor: null }); await settle();
    }
    function click(text: string): void {
        const button = Array.from(root.querySelectorAll('button')).find(button => button.textContent?.includes(text));
        expect(button, text).toBeDefined(); button!.click(); fixture.detectChanges();
    }
    function fill(): void {
        const values = { title: 'Обновление', eventDate: '2026-10-07', bodyMarkdown: '**Текст** события' };
        for (const [name, value] of Object.entries(values)) {
            const field = root.querySelector(`[formControlName=${name}]`) as HTMLInputElement;
            field.value = value; field.dispatchEvent(new Event('input')); field.dispatchEvent(new Event('change'));
        }
        fixture.detectChanges();
    }

    it('does not load entries or create editor fields without server authorization', async () => {
        http.expectOne('/api/admin/events/access').flush({}, { status: 403, statusText: 'Forbidden' }); await settle();
        expect(root.textContent).toContain('Доступ закрыт'); expect(root.querySelector('form')).toBeNull();
        http.expectNone('/api/admin/events');
    });

    it('can retry access checks and validates an empty draft before sending', async () => {
        http.expectOne('/api/admin/events/access').flush({}, { status: 503, statusText: 'Unavailable' }); await settle();
        click('Попробовать снова'); await admit();
        click('Сохранить черновик'); await settle();
        expect(root.querySelector('[role=alert]')?.textContent).toContain('Заполните'); http.expectNone('/api/admin/events');
        fill(); click('Предпросмотр текста'); expect(root.querySelector('.preview strong')?.textContent).toBe('Текст');
    });

    it('saves a draft with an explicit publication choice and does not confuse a list outage with an unknown save', async () => {
        await admit(); fill(); click('Сохранить черновик');
        const request = http.expectOne('/api/admin/events'); expect(request.request.method).toBe('POST'); expect(request.request.body.published).toBe(false);
        request.flush({ event: { ...TEST_EVENT, title: 'Обновление', published: false } }); await settle();
        http.expectOne('/api/admin/events').flush({}, { status: 503, statusText: 'Unavailable' }); await settle();
        expect(root.textContent).toContain('Черновик сохранён'); expect(root.textContent).toContain('Изменение сохранено');
        expect(root.textContent).not.toContain('Повторить тот же запрос');
    });

    it('freezes an uncertain command and retries the same body without creating a second event', async () => {
        await admit(); fill(); click('Сохранить черновик');
        const request = http.expectOne('/api/admin/events'); const command = request.request.body;
        request.error(new ProgressEvent('network-error')); await settle();
        expect((root.querySelector('input') as HTMLInputElement).disabled).toBe(true);
        click('Повторить тот же запрос');
        const retry = http.expectOne('/api/admin/events'); expect(retry.request.body).toEqual(command);
        retry.flush({ event: TEST_EVENT }); await settle();
        http.expectOne('/api/admin/events').flush({ items: [TEST_EVENT], nextCursor: null }); await settle();
        expect(root.textContent).toContain('Событие опубликовано');
    });

    it('requires delete confirmation and carries version plus command on a confirmed deletion', async () => {
        await admit(); click('Новый редактор'); click('Удалить событие');
        http.expectNone(request => request.method === 'DELETE');
        click('Отмена'); expect(root.textContent).not.toContain('Удалить запись «');
        click('Удалить событие'); click('Удалить запись');
        const request = http.expectOne(request => request.method === 'DELETE'); expect(request.request.headers.get('If-Match')).toBe('"1"');
        expect(request.request.params.get('commandId')).toMatch(/^[a-f0-9-]{36}$/u); request.flush(null); await settle();
        http.expectOne('/api/admin/events').flush({ items: [], nextCursor: null }); await settle();
        expect(root.textContent).toContain('Событие удалено');
    });

    it('preserves unsaved text on selecting another event and requires explicit discard', async () => {
        await admit(); fill(); click('Новый редактор');
        expect((root.querySelector('input') as HTMLInputElement).value).toBe('Обновление');
        expect(root.textContent).toContain('несохранённые изменения');
        click('Продолжить редактирование');
        const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
        expect(fixture.componentInstance.canLeave()).toBe(false); expect(confirm).toHaveBeenCalledOnce();
        click('Новый редактор'); click('Отбросить изменения');
        expect((root.querySelector('input') as HTMLInputElement).value).toBe(TEST_EVENT.title);
        expect(fixture.componentInstance.canLeave()).toBe(true);
    });

    it('blocks a pending selection while a save is in flight or uncertain', async () => {
        await admit(); fill(); click('Новый редактор'); click('Сохранить черновик');
        const request = http.expectOne('/api/admin/events');
        const discard = Array.from(root.querySelectorAll('button')).find(button => button.textContent?.includes('Отбросить изменения'))!;
        expect(discard.disabled).toBe(true); expect(fixture.componentInstance.canLeave()).toBe(false);
        request.error(new ProgressEvent('network-error')); await settle();
        expect(discard.disabled).toBe(true); expect(fixture.componentInstance.canLeave()).toBe(false);
        expect((root.querySelector('input') as HTMLInputElement).value).toBe('Обновление');
    });

    it('keeps edits on a version conflict and clears private data when access is revoked', async () => {
        await admit(); click('Новый редактор'); click('Опубликовать');
        http.expectOne(`/api/admin/events/${TEST_EVENT.eventId}`).flush({}, { status: 409, statusText: 'Conflict' }); await settle();
        expect(root.textContent).toContain('Запись уже изменилась'); expect((root.querySelector('input') as HTMLInputElement).value).toBe(TEST_EVENT.title);
        click('Опубликовать');
        http.expectOne(`/api/admin/events/${TEST_EVENT.eventId}`).flush({}, { status: 403, statusText: 'Forbidden' }); await settle();
        expect(root.textContent).toContain('Доступ закрыт'); expect(root.textContent).not.toContain(TEST_EVENT.title); expect(root.querySelector('form')).toBeNull();
    });
});
