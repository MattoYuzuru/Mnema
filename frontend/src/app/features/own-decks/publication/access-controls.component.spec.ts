import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import { lastCall, spyObj, type SpyObj } from '../../../../testing/mocks';
import { ToastService } from '../../../core/notifications/toast.service';
import { AccessControlsComponent } from './access-controls.component';
import { PublicationApiService } from './publication-api.service';
import { PublicationCommand, PublicationState, PublicationWriteResult } from './publication.models';
import { neverPublished, sharedState, written } from './publication-test-data';

describe('AccessControlsComponent', () => {
    let fixture: ComponentFixture<AccessControlsComponent>;
    let root: HTMLElement;
    let api: SpyObj<PublicationApiService>;
    let toasts: ToastService;
    let saved: PublicationState[];
    let replayed: number;
    let refreshed: number;
    let opened: number;
    let closed: number;

    const invite = (): PublicationState => sharedState({ visibility: 'INVITE', link: '/d/Kq7xT3mNpR', unpublishedChanges: 0 });
    const link = (): PublicationState => sharedState({ visibility: 'LINK', link: '/d/Kq7xT3mNpR', unpublishedChanges: 0 });
    const settle = async (): Promise<void> => { await fixture.whenStable(); fixture.detectChanges(); };
    const button = (label: string): HTMLButtonElement =>
        [...root.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent!.trim() === label)!;
    const checked = (): string => root.querySelector<HTMLInputElement>('input[type=radio]:checked')!.closest('label')!.textContent!.trim();
    const pick = async (label: string): Promise<void> => {
        [...root.querySelectorAll<HTMLInputElement>('input[type=radio]')].find(radio => radio.closest('label')!.textContent!.trim() === label)!.click();
        await settle();
    };
    const lastCommand = (): PublicationCommand => lastCall(api.save)[2] as PublicationCommand;

    async function create(state: PublicationState, inputs: { checklistOpen?: boolean } = {}): Promise<void> {
        fixture = TestBed.createComponent(AccessControlsComponent);
        fixture.componentRef.setInput('state', state);
        fixture.componentRef.setInput('checklistOpen', inputs.checklistOpen ?? false);
        fixture.componentRef.setInput('checklistId', 'checklist');
        fixture.componentInstance.saved.subscribe(next => saved.push(next));
        fixture.componentInstance.replayed.subscribe(() => replayed++);
        fixture.componentInstance.refresh.subscribe(() => refreshed++);
        fixture.componentInstance.openChecklist.subscribe(() => opened++);
        fixture.componentInstance.closeChecklist.subscribe(() => closed++);
        root = fixture.nativeElement as HTMLElement;
        document.body.append(root);
        fixture.detectChanges();
        await settle();
    }

    beforeEach(async () => {
        saved = [];
        replayed = refreshed = opened = closed = 0;
        api = spyObj<PublicationApiService>({ save: vi.fn().mockName('PublicationApiService.save') });
        await TestBed.configureTestingModule({ providers: [{ provide: PublicationApiService, useValue: api }] }).compileComponents();
        toasts = TestBed.inject(ToastService);
        vi.spyOn(toasts, 'echo');
    });

    afterEach(() => root?.remove());

    it('shows the four levels in the order of the product table, the real one selected, and says who sees the deck', async () => {
        await create(link());
        expect([...root.querySelectorAll('input[type=radio]')].map(radio => radio.closest('label')!.textContent!.trim()))
            .toEqual(['Приватная', 'По ссылке', 'По приглашению', 'Публичная']);
        expect(checked()).toBe('По ссылке');
        expect(root.querySelector('legend')!.textContent).toBe('Кто видит колоду');
        expect(root.querySelector('.hint')!.textContent).toContain('Любой, у кого есть ссылка');
        expect(root.querySelector('.change')).toBeNull();
        expect(root.querySelector('[role=switch]')).toBeNull();
    });

    it('only stages a pick: a panel says what will happen and nothing is sent until «Применить»', async () => {
        await create(sharedState({ visibility: 'PUBLIC' }));
        await pick('По ссылке');
        expect(api.save).not.toHaveBeenCalled();
        const panel = root.querySelector('.change')!;
        expect(panel.getAttribute('role')).toBe('group');
        expect(document.getElementById(panel.getAttribute('aria-labelledby')!)!.textContent).toBe('Изменить доступ: По ссылке');
        expect(panel.textContent).toContain('пропадёт из каталога');
        expect(panel.textContent).toContain('старые ссылки перестанут работать');
        const apply = button('Применить');
        expect(document.getElementById(apply.getAttribute('aria-describedby')!)!.textContent).toContain('Ссылка изменится');
    });

    it('cancels back to the real level and puts the focus on the picker', async () => {
        await create(link());
        await pick('Приватная');
        button('Отмена').click();
        await settle();
        expect(checked()).toBe('По ссылке');
        expect(root.querySelector('.change')).toBeNull();
        expect(document.activeElement).toBe(root.querySelector('input[type=radio]:checked'));
        expect(api.save).not.toHaveBeenCalled();
    });

    it('leaving «Приватная» publishes the current head, with the If-Match of the loaded version', async () => {
        const state = neverPublished();
        api.save.mockImplementation((_deck, _version, command) => of(written(sharedState({ visibility: 'LINK', link: '/d/Kq7xT3mNpR' }), command.commandId)));
        await create(state);
        await pick('По ссылке');
        expect(root.querySelector('.change')!.textContent).toContain('будет опубликована сейчас');
        button('Применить').click();
        await settle();
        const [deck, version, command] = lastCall(api.save) as [string, string, PublicationCommand];
        expect(deck).toBe(state.deckId);
        expect(version).toBe(state.rowVersion);
        expect(command).toMatchObject({
            visibility: 'LINK', metadata: state.metadata, requestsEnabled: state.requestsEnabled,
            publish: { expectedHeadRevisionId: state.headRevisionId, releaseNote: null }
        });
        expect(toasts.echo).toHaveBeenCalledWith('Доступ изменён: По ссылке');
        expect(saved).toHaveLength(1);
        expect(saved[0].visibility).toBe('LINK');
    });

    it('moves between shared levels without publishing, and keeps the focus on the picker afterwards', async () => {
        api.save.mockImplementation((_deck, _version, command) => of(written(sharedState({ visibility: 'INVITE', link: '/d/Kq7xT3mNpR' }), command.commandId)));
        await create(link());
        await pick('По приглашению');
        button('Применить').click();
        await settle();
        expect(lastCommand()).toMatchObject({ visibility: 'INVITE', publish: null });
        fixture.componentRef.setInput('state', saved[0]);
        await settle();
        expect(document.activeElement).toBe(root.querySelector('input[type=radio]:checked'));
    });

    it('follows the stored level when the host re-reads it', async () => {
        await create(link());
        fixture.componentRef.setInput('state', invite());
        await settle();
        expect(checked()).toBe('По приглашению');
        // A re-read of the same level (after a conflict, say) keeps the pick; a new level replaces it.
        await pick('Приватная');
        fixture.componentRef.setInput('state', { ...invite(), rowVersion: '9' });
        await settle();
        expect(checked()).toBe('Приватная');
        fixture.componentRef.setInput('state', link());
        await settle();
        expect(checked()).toBe('По ссылке');
    });

    it('says what a downgrade to «Приватная» keeps', async () => {
        await create(invite());
        await pick('Приватная');
        const text = root.querySelector('.change')!.textContent!;
        expect(text).toContain('Колоду увидите только вы');
        expect(text).toContain('Выданные доступы сохранятся');
        expect(text).toContain('Пригласительные ссылки перестанут работать');
        api.save.mockImplementation((_deck, _version, command) => of(written(neverPublished({ rowVersion: '4' }), command.commandId)));
        button('Применить').click();
        await settle();
        expect(lastCommand()).toMatchObject({ visibility: 'PRIVATE', publish: null });
    });

    describe('«Публичная»', () => {
        it('is not applied here: its button opens the checklist and reports aria-expanded', async () => {
            await create(link());
            await pick('Публичная');
            const open = button('Сделать публичной…');
            expect(open.getAttribute('aria-expanded')).toBe('false');
            expect(open.getAttribute('aria-controls')).toBeNull();
            open.click();
            expect(opened).toBe(1);
            expect(api.save).not.toHaveBeenCalled();
            fixture.componentRef.setInput('checklistOpen', true);
            await settle();
            expect(button('Сделать публичной…').getAttribute('aria-expanded')).toBe('true');
            expect(button('Сделать публичной…').getAttribute('aria-controls')).toBe('checklist');
            button('Сделать публичной…').click();
            expect(closed).toBe(1);
        });

        it('closes an open checklist when another level is picked or the pick is cancelled', async () => {
            await create(link(), { checklistOpen: true });
            await pick('Публичная');
            await pick('По приглашению');
            expect(closed).toBe(1);
            await pick('Публичная');
            button('Отмена').click();
            await settle();
            expect(closed).toBe(2);
            expect(checked()).toBe('По ссылке');
        });

        it('hands the focus back to its button when asked', async () => {
            await create(link());
            await pick('Публичная');
            fixture.componentInstance.focusPublicButton();
            await settle();
            expect(document.activeElement).toBe(button('Сделать публичной…'));
        });
    });

    describe('while applying', () => {
        it('marks the button busy, ignores a second press and a pick, and unlocks after the answer', async () => {
            const pending = new Subject<PublicationWriteResult>();
            api.save.mockReturnValue(pending);
            await create(link());
            await pick('Приватная');
            const apply = button('Применить');
            apply.click();
            apply.click();
            await settle();
            expect(api.save).toHaveBeenCalledTimes(1);
            expect(button('Применяем…').getAttribute('aria-busy')).toBe('true');
            expect(button('Применяем…').getAttribute('aria-disabled')).toBe('true');
            expect(root.querySelector('fieldset')!.disabled).toBe(true);
            pending.next(written(neverPublished(), lastCommand().commandId));
            pending.complete();
            await settle();
            expect(root.querySelector('fieldset')!.disabled).toBe(false);
        });

        it('retries a lost answer with the same command id and drops it after a definite answer', async () => {
            api.save.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
            await create(link());
            await pick('Приватная');
            button('Применить').click();
            await settle();
            expect(root.querySelector('[role=alert]')!.textContent).toContain('Не удалось связаться с сервером');
            const first = lastCommand().commandId;
            api.save.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
            button('Применить').click();
            await settle();
            expect(lastCommand().commandId).toBe(first);
            api.save.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 412, error: { code: 'VERSION_CONFLICT' } })));
            button('Применить').click();
            await settle();
            expect(lastCommand().commandId).toBe(first);
            expect(refreshed).toBe(1);
            expect(root.querySelector('[role=alert]')!.textContent).toContain('Колода изменилась');
            api.save.mockReturnValueOnce(of(written(neverPublished(), 'x')));
            button('Применить').click();
            await settle();
            expect(lastCommand().commandId).not.toBe(first);
        });

        it('asks the host to re-read after a replayed acknowledgement', async () => {
            api.save.mockImplementation((_deck, _version, command) => of(written(link(), command.commandId, true)));
            await create(link());
            await pick('Приватная');
            button('Применить').click();
            await settle();
            expect(replayed).toBe(1);
            expect(saved).toEqual([]);
        });
    });

    describe('the requests switch', () => {
        const switchInput = (): HTMLInputElement => root.querySelector<HTMLInputElement>('input[role=switch]')!;

        it('exists only at «По приглашению», names what it does and describes itself', async () => {
            await create(invite());
            expect(switchInput().checked).toBe(true);
            expect(switchInput().closest('label')!.textContent).toContain('Принимать запросы доступа');
            expect(document.getElementById(switchInput().getAttribute('aria-describedby')!)!.textContent).toContain('Запросить доступ');
            fixture.componentRef.setInput('state', { ...invite(), requestsEnabled: false });
            await settle();
            expect(switchInput().checked).toBe(false);
            expect(document.getElementById(switchInput().getAttribute('aria-describedby')!)!.textContent).toContain('Запросы не принимаются');
        });

        it('sends the whole state with only the switch changed and no publish', async () => {
            api.save.mockImplementation((_deck, _version, command) => of(written({ ...invite(), requestsEnabled: command.requestsEnabled, rowVersion: '4' }, command.commandId)));
            await create(invite());
            switchInput().checked = false;
            switchInput().dispatchEvent(new Event('change'));
            await settle();
            expect(lastCommand()).toMatchObject({ visibility: 'INVITE', requestsEnabled: false, publish: null, metadata: invite().metadata });
            expect(toasts.echo).toHaveBeenCalledWith('Настройка запросов сохранена');
            expect(saved[0].requestsEnabled).toBe(false);
        });

        it('puts the switch back when the save fails, and holds the click while busy', async () => {
            api.save.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 403 })));
            await create(invite());
            switchInput().checked = false;
            switchInput().dispatchEvent(new Event('change'));
            await settle();
            expect(switchInput().checked).toBe(true);
            expect(root.querySelector('[role=alert]')!.textContent).toContain('Не удалось опубликовать');
            const pending = new Subject<PublicationWriteResult>();
            api.save.mockReturnValue(pending);
            switchInput().checked = false;
            switchInput().dispatchEvent(new Event('change'));
            await settle();
            expect(switchInput().getAttribute('aria-disabled')).toBe('true');
            const held = new MouseEvent('click', { cancelable: true });
            switchInput().dispatchEvent(held);
            expect(held.defaultPrevented).toBe(true);
            switchInput().checked = true;
            switchInput().dispatchEvent(new Event('change'));
            expect(api.save).toHaveBeenCalledTimes(2);
            expect(switchInput().checked).toBe(true);
        });
    });
});
