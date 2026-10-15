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

    it('applies a non-public choice immediately with no apply or cancel step', async () => {
        const state = link();
        api.save.mockImplementation((_deck, _version, command) => of(written(invite(), command.commandId)));
        await create(state);
        await pick('По приглашению');
        expect(lastCommand()).toMatchObject({ visibility: 'INVITE', publish: null, metadata: state.metadata });
        expect(saved[0].visibility).toBe('INVITE');
        expect(button('Применить')).toBeUndefined();
        expect(button('Отмена')).toBeUndefined();
        expect(toasts.echo).toHaveBeenCalledWith('Доступ изменён: По приглашению');
    });

    it('publishes the exact current head when leaving private, with the loaded version', async () => {
        const state = neverPublished();
        api.save.mockImplementation((_deck, _version, command) => of(written(link(), command.commandId)));
        await create(state);
        await pick('По ссылке');
        expect(lastCall(api.save).slice(0, 2)).toEqual([state.deckId, state.rowVersion]);
        expect(lastCommand()).toMatchObject({ visibility: 'LINK', publish: { expectedHeadRevisionId: state.headRevisionId, releaseNote: null } });
    });

    it('keeps real state selected after a definite rejection and refreshes a stale version', async () => {
        api.save.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 412, error: { code: 'VERSION_CONFLICT' } })));
        await create(link());
        await pick('Приватная');
        expect(checked()).toBe('По ссылке');
        expect(refreshed).toBe(1);
        expect(root.querySelector('[role=alert]')?.textContent).toContain('Колода изменилась');
    });

    it('follows the real visibility on a host refresh', async () => {
        await create(link());
        fixture.componentRef.setInput('state', invite());
        await settle();
        expect(checked()).toBe('По приглашению');
    });

    it('opens the mandatory public checklist on selection without publishing', async () => {
        await create(link());
        await pick('Публичная');
        expect(opened).toBe(1);
        expect(api.save).not.toHaveBeenCalled();
        fixture.componentInstance.cancelPublicChoice();
        await settle();
        expect(checked()).toBe('По ссылке');
        expect(document.activeElement).toBe(root.querySelector('input[type=radio]:checked'));
    });

    it('closes the public checklist when another level is chosen', async () => {
        api.save.mockImplementation((_deck, _version, command) => of(written(invite(), command.commandId)));
        await create(link(), { checklistOpen: true });
        await pick('По приглашению');
        expect(closed).toBe(1);
    });

    it('keeps controls focusable while a change is in flight and ignores concurrent selections', async () => {
        const pending = new Subject<PublicationWriteResult>();
        api.save.mockReturnValue(pending);
        await create(link());
        await pick('Приватная');
        await pick('По приглашению');
        expect(api.save).toHaveBeenCalledTimes(1);
        expect(root.querySelector('fieldset')!.disabled).toBe(false);
        expect(root.querySelector('input[type=radio]')?.getAttribute('aria-disabled')).toBe('true');
        pending.next(written(neverPublished(), lastCommand().commandId)); pending.complete();
        await settle();
        expect(checked()).toBe('Приватная');
    });

    it('locks an unknown outcome and retries the exact command before permitting another choice', async () => {
        api.save.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
        await create(link());
        await pick('Приватная');
        const first = lastCommand().commandId;
        await pick('По приглашению');
        expect(api.save).toHaveBeenCalledTimes(1);
        api.save.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
        button('Повторить').click(); await settle();
        expect(lastCommand().commandId).toBe(first);
        api.save.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 412, error: { code: 'VERSION_CONFLICT' } })));
        button('Повторить').click(); await settle();
        expect(lastCommand().commandId).toBe(first);
        expect(refreshed).toBe(1);
        expect(button('Повторить')).toBeUndefined();
        api.save.mockImplementation((_deck, _version, command) => of(written(invite(), command.commandId)));
        await pick('По приглашению');
        expect(lastCommand().commandId).not.toBe(first);
    });

    it('re-reads a replayed acknowledgement instead of applying the stale state', async () => {
        api.save.mockImplementation((_deck, _version, command) => of(written(link(), command.commandId, true)));
        await create(link());
        await pick('Приватная');
        expect(replayed).toBe(1);
        expect(saved).toEqual([]);
        expect(checked()).toBe('По ссылке');
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
