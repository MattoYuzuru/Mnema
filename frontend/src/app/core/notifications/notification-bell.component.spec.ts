import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthService } from '../../auth.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { NotificationBellComponent } from './notification-bell.component';
import { NotificationCenter } from './notification-center';
import { AppNotification } from './notification.models';
import { NotificationsApiService } from './notifications-api.service';

const DECK = '11111111-1111-4111-8111-111111111111';
const note = (seq: number, overrides: Partial<AppNotification> = {}): AppNotification => ({
    notificationId: `0a000000-0000-4000-8000-${String(seq).padStart(12, '0')}`, seq: String(seq), kind: 'GENERATION_READY',
    severity: 'INFO', params: { deckId: DECK, sessionId: DECK, sessionKind: 'MATERIALS', artifactCount: 3, approvableCount: 3 },
    route: 'DECK', createdAt: '2026-10-02T09:00:00Z', expiresAt: '2026-11-01T09:00:00Z', ...overrides
});

@Component({ template: '' })
class DeckPageStub {}

describe('NotificationBellComponent', () => {
    let fixture: ComponentFixture<NotificationBellComponent>;
    let center: NotificationCenter;
    let api: SpyObj<NotificationsApiService>;
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const button = (): HTMLButtonElement => root().querySelector<HTMLButtonElement>('.bell')!;
    const panel = (): HTMLElement => root().querySelector<HTMLElement>('[popover]')!;
    const render = async (): Promise<void> => { fixture.detectChanges(); await fixture.whenStable(); };

    beforeEach(() => {
        api = spyObj<NotificationsApiService>({
            list: vi.fn().mockName('list'), setReadCursor: vi.fn().mockName('setReadCursor'), dismiss: vi.fn().mockName('dismiss')
        });
        TestBed.configureTestingModule({ providers: [
            provideRouter([{ path: 'decks/:deckId', component: DeckPageStub }]),
            { provide: NotificationsApiService, useValue: api },
            // Signed out: the center never polls, the spec drives its state directly.
            { provide: AuthService, useValue: { status: signal('anonymous') } }
        ] });
        center = TestBed.inject(NotificationCenter);
        TestBed.tick(); // the signed-out reset runs once, before the spec sets its own state
        fixture = TestBed.createComponent(NotificationBellComponent);
    });

    it('names the exact unread count and shows 99+ only visually', async () => {
        await render();
        expect(button().getAttribute('aria-label')).toBe('Уведомления');
        expect(root().querySelector('.badge')).toBeNull();

        center.unreadCount.set(1);
        await render();
        expect(button().getAttribute('aria-label')).toBe('Уведомления, 1 непрочитанное');
        expect(root().querySelector('.badge')?.textContent?.trim()).toBe('1');

        center.unreadCount.set(150);
        await render();
        expect(button().getAttribute('aria-label')).toBe('Уведомления, 150 непрочитанных');
        const badge = root().querySelector('.badge')!;
        expect(badge.textContent?.trim()).toBe('99+');
        expect(badge.getAttribute('aria-hidden')).toBe('true');
    });

    it('wires the button to a popover panel and follows its open state', async () => {
        await render();
        const open = vi.spyOn(center, 'open').mockImplementation(() => center.panelOpen.set(true));
        const close = vi.spyOn(center, 'close').mockImplementation(() => center.panelOpen.set(false));
        expect(button().getAttribute('aria-controls')).toBe(panel().id);
        expect(button().getAttribute('popovertarget')).toBe(panel().id);
        expect(panel().getAttribute('popover')).toBe('auto');
        expect(button().getAttribute('aria-expanded')).toBe('false');

        panel().dispatchEvent(Object.assign(new Event('toggle'), { newState: 'open' }));
        await render();
        expect(open).toHaveBeenCalledOnce();
        expect(button().getAttribute('aria-expanded')).toBe('true');

        panel().dispatchEvent(Object.assign(new Event('toggle'), { newState: 'closed' }));
        await render();
        expect(close).toHaveBeenCalledOnce();
        expect(button().getAttribute('aria-expanded')).toBe('false');
    });

    it('shows the empty state when there is nothing to list', async () => {
        await render();
        expect(root().querySelector('h2')?.textContent).toBe('Входящие');
        expect(root().querySelector('ul')).toBeNull();
        expect(root().querySelector('.empty')?.textContent).toContain('Пока тихо');
    });

    it('lists links, plain sentences and no unknown kinds, and marks the new ones in words', async () => {
        center.items.set([
            note(4, { kind: 'FROM_THE_FUTURE', params: {} }),
            note(3, { kind: 'USAGE_EXHAUSTED', route: 'PLANS', severity: 'WARNING', params: { bucket: 'CREDITS', window: 'WEEK', renewsAt: null, plan: 'FREE' } }),
            note(2),
            note(1, { route: 'NONE' })
        ]);
        center.freshAfter.set('2');
        await render();
        const entries = Array.from(root().querySelectorAll('li'));
        expect(entries).toHaveLength(3);
        expect(entries[0].textContent).toContain('Лимит «Кредиты ИИ» закончился');
        expect(entries[0].querySelector<HTMLAnchorElement>('a')?.getAttribute('href')).toBe('/profile#ai-budget');
        expect(entries[0].querySelector('.fresh')?.textContent).toBe('Новое');
        const link = entries[1].querySelector<HTMLAnchorElement>('a')!;
        expect(link.getAttribute('href')).toBe(`/decks/${DECK}`);
        expect(link.textContent).toContain('Готово: 3 материала ждут проверки');
        expect(entries[1].querySelector('.fresh')).toBeNull();
        expect(entries[2].querySelector('a')).toBeNull();
        expect(root().querySelector('[aria-hidden=true] > svg, app-notification-glyph svg')?.getAttribute('aria-hidden')).toBe('true');
    });

    it('dismisses one item with its own button and closes the panel when a link is followed', async () => {
        center.items.set([note(2), note(1, { route: 'NONE' })]);
        await render();
        const dismiss = vi.spyOn(center, 'dismiss').mockResolvedValue();
        const buttons = root().querySelectorAll<HTMLButtonElement>('.dismiss');
        expect(buttons[0].getAttribute('aria-label')).toBe('Убрать уведомление');
        expect(buttons[0].getAttribute('aria-describedby')).toBe(root().querySelector('li a')!.id);
        buttons[1].click();
        expect(dismiss).toHaveBeenCalledWith(note(1).notificationId);

        const hide = vi.fn();
        panel().hidePopover = hide;
        root().querySelector<HTMLAnchorElement>('li a')!.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        expect(hide).toHaveBeenCalledOnce();
        await fixture.whenStable();
    });

    it('offers older notifications and any error in an alert', async () => {
        center.items.set([note(1)]);
        center.olderCursor.set('RDE');
        center.panelError.set('Не удалось убрать уведомление. Попробуйте ещё раз.');
        await render();
        expect(root().querySelector('[role=alert]')?.textContent).toContain('Не удалось убрать');
        const loadMore = vi.spyOn(center, 'loadMore').mockResolvedValue();
        root().querySelector<HTMLButtonElement>('.more')!.click();
        expect(loadMore).toHaveBeenCalledOnce();
        expect(api.list).not.toHaveBeenCalled();
    });
});
