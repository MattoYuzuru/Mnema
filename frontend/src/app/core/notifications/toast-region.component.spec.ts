import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthService } from '../../auth.service';
import { NotificationsApiService } from './notifications-api.service';
import { ToastRegionComponent } from './toast-region.component';
import { TOAST_MS, ToastService } from './toast.service';

describe('ToastRegionComponent', () => {
    let fixture: ComponentFixture<ToastRegionComponent>;
    let toasts: ToastService;
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const region = (): HTMLElement => root().querySelector<HTMLElement>('.toast-region')!;
    const items = (): HTMLElement[] => Array.from(root().querySelectorAll<HTMLElement>('.toast'));
    const render = async (): Promise<void> => { fixture.detectChanges(); await fixture.whenStable(); };
    const pointer = (type: string, init: PointerEventInit & { timeStamp?: number } = {}): PointerEvent =>
        new PointerEvent(type, { bubbles: true, cancelable: true, pointerId: 1, isPrimary: true, pointerType: 'touch', ...init });

    beforeEach(async () => {
        localStorage.clear();
        vi.useFakeTimers();
        TestBed.configureTestingModule({ providers: [
            provideRouter([]),
            { provide: AuthService, useValue: { status: signal('anonymous') } },
            { provide: NotificationsApiService, useValue: {} }
        ] });
        toasts = TestBed.inject(ToastService);
        fixture = TestBed.createComponent(ToastRegionComponent);
        await render();
    });
    afterEach(() => { vi.useRealTimers(); localStorage.clear(); });

    it('is a labelled top-layer popover section and keeps a persistent status region outside it', () => {
        expect(region().tagName).toBe('SECTION');
        expect(region().getAttribute('aria-label')).toBe('Уведомления');
        expect(region().getAttribute('popover')).toBe('manual');
        expect(region().hasAttribute('aria-live')).toBe(false);
        expect(region().querySelector('[role=status], [aria-live]')).toBeNull();
        const status = root().querySelector('[role=status]')!;
        expect(region().contains(status)).toBe(false);
    });

    it('shows and hides the popover with the stack and announces new toasts in the status region', async () => {
        const show = vi.fn();
        const hide = vi.fn();
        const element = region();
        element.showPopover = show;
        element.hidePopover = hide;
        toasts.notify('a', 'Готово: 5 материалов ждут проверки', 'INFO', { label: 'Открыть колоду', commands: ['/decks', 'x'] });
        await render();
        expect(show).toHaveBeenCalled();
        expect(items()).toHaveLength(1);
        expect(items()[0].textContent).toContain('Готово: 5 материалов ждут проверки');
        expect(root().querySelector('[role=status]')?.textContent).toBe('Готово: 5 материалов ждут проверки');
        expect(items()[0].querySelector('a')?.textContent).toBe('Открыть колоду');
    });

    it('closes with the × button, which is the single-pointer alternative to the swipe', async () => {
        toasts.notify('a', 'Ошибка', 'ERROR', null);
        await render();
        const close = items()[0].querySelector<HTMLButtonElement>('.close')!;
        expect(close.getAttribute('aria-label')).toBe('Закрыть уведомление');
        close.click();
        await render();
        expect(items()).toHaveLength(0);
    });

    it('closes the focused toast with Esc and moves focus to a neighbour', async () => {
        toasts.notify('a', 'Первое', 'ERROR', null);
        toasts.notify('b', 'Второе', 'ERROR', null);
        await render();
        const [newest, older] = items();
        expect(newest.dataset['toastId']).toBe('b');
        const close = newest.querySelector<HTMLButtonElement>('.close')!;
        close.focus();
        close.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
        await render();
        expect(items().map(item => item.dataset['toastId'])).toEqual(['a']);
        expect(document.activeElement).toBe(older.querySelector('.close'));
    });

    it('hands focus back to where it came from when the last toast is closed, or to main as a fallback', async () => {
        const before = document.createElement('a');
        before.href = '#before';
        const main = document.createElement('main');
        main.id = 'main-content';
        main.tabIndex = -1;
        document.body.append(before, main);
        toasts.notify('a', 'Первое', 'ERROR', null);
        await render();
        const close = items()[0].querySelector<HTMLButtonElement>('.close')!;
        before.focus();
        close.focus();
        region().dispatchEvent(new FocusEvent('focusin', { bubbles: true, relatedTarget: before }));
        close.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
        await render();
        expect(items()).toHaveLength(0);
        expect(document.activeElement).toBe(before);

        // Without a known origin (or one that is gone) focus lands on main, not on <body>.
        toasts.notify('b', 'Второе', 'ERROR', null);
        await render();
        before.remove();
        const next = items()[0].querySelector<HTMLButtonElement>('.close')!;
        next.focus();
        next.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
        await render();
        expect(document.activeElement).toBe(main);
        main.remove();
    });

    it('does not react to Esc from outside the region', async () => {
        toasts.notify('a', 'Первое', 'ERROR', null);
        await render();
        region().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
        await render();
        expect(items()).toHaveLength(1);
    });

    it('pauses the timers on hover and on focus inside the region', async () => {
        toasts.notify('a', 'Готово', 'INFO', null);
        await render();
        region().dispatchEvent(new MouseEvent('pointerenter'));
        vi.advanceTimersByTime(TOAST_MS * 3);
        expect(toasts.visible()).toHaveLength(1);
        region().dispatchEvent(new MouseEvent('pointerleave'));
        region().dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
        vi.advanceTimersByTime(TOAST_MS * 3);
        expect(toasts.visible()).toHaveLength(1);
        // Focus moving between elements of the region keeps the pause; leaving the region ends it.
        const inside = items()[0].querySelector('.close')!;
        region().dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: inside }));
        vi.advanceTimersByTime(TOAST_MS * 3);
        expect(toasts.visible()).toHaveLength(1);
        region().dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: null }));
        vi.advanceTimersByTime(TOAST_MS);
        expect(toasts.visible()).toHaveLength(0);
    });

    it('closes a toast with a long horizontal swipe but not with a short drag or a mouse', async () => {
        toasts.notify('a', 'Первое', 'ERROR', null);
        toasts.notify('b', 'Второе', 'ERROR', null);
        toasts.notify('c', 'Третье', 'ERROR', null);
        await render();
        const swipe = (target: HTMLElement, from: number, to: number, init: PointerEventInit = {}): void => {
            target.dispatchEvent(pointer('pointerdown', { clientX: from, ...init }));
            target.dispatchEvent(pointer('pointermove', { clientX: to, ...init }));
            target.dispatchEvent(pointer('pointerup', { clientX: to, ...init }));
        };

        swipe(items()[0], 100, 110);
        expect(toasts.visible()).toHaveLength(3);
        expect(items()[0].style.transform).toBe('');

        swipe(items()[0], 100, 400, { pointerType: 'mouse' });
        expect(toasts.visible()).toHaveLength(3);

        swipe(items()[0].querySelector<HTMLElement>('.close')!, 100, 400);
        expect(toasts.visible()).toHaveLength(3);

        swipe(items()[0], 100, 400);
        await render();
        expect(toasts.visible().map(toast => toast.id)).toEqual(['b', 'a']);

        // A cancelled gesture leaves the toast where it was.
        items()[0].dispatchEvent(pointer('pointerdown', { clientX: 100 }));
        items()[0].dispatchEvent(pointer('pointermove', { clientX: 300 }));
        items()[0].dispatchEvent(pointer('pointercancel', { clientX: 300 }));
        expect(items()[0].style.transform).toBe('');
        expect(toasts.visible()).toHaveLength(2);
    });

    it('says how many toasts wait', async () => {
        for (const id of ['a', 'b', 'c', 'd', 'e']) toasts.notify(id, `Текст ${id}`, 'ERROR', null);
        await render();
        expect(items()).toHaveLength(3);
        expect(root().querySelector('.more')?.textContent).toBe('Ещё 2 — во «Входящих»');
    });
});
