import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, effect, inject, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';

import { NotificationGlyphComponent } from './notification-glyph.component';
import { ToastService } from './toast.service';

/** A swipe closes a toast past this share of its width, or faster than this many px per ms. */
const SWIPE_SHARE = 0.4;
const SWIPE_SPEED = 0.5;
const SWIPE_MIN_PX = 48;

interface Drag { readonly id: string; readonly element: HTMLElement; readonly pointerId: number; readonly startX: number; readonly startTime: number; dx: number }

/**
 * The toast stack. The `section` is a labelled landmark in the top layer (`popover="manual"`, shown only while a toast
 * is visible) so it never sits under page content. The toasts are deliberately not a live region: the persistent
 * `role="status"` element beside them exists before any text is inserted and receives the queued announcements.
 * Hover and focus inside the region pause the timers, Esc closes the toast that has focus, and a horizontal swipe closes
 * one on touch; the «×» button is the single-pointer alternative to the swipe.
 */
@Component({
    selector: 'app-toast-region',
    imports: [RouterLink, NotificationGlyphComponent],
    templateUrl: './toast-region.component.html',
    styleUrl: './toast-region.component.css',
    host: { '[style.display]': '"contents"' },
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ToastRegionComponent {
    protected readonly toasts = inject(ToastService);
    private readonly injector = inject(Injector);
    private readonly region = viewChild<ElementRef<HTMLElement>>('region');
    private drag: Drag | null = null;
    private returnTo: HTMLElement | null = null;

    constructor() {
        effect(() => {
            const element = this.region()?.nativeElement;
            const shown = this.toasts.visible().length > 0;
            if (element !== undefined) setPopover(element, shown);
        });
    }

    protected pointerEntered(): void { this.toasts.setHovered(true); }
    protected pointerLeft(): void { this.toasts.setHovered(false); }
    protected focusEntered(event: FocusEvent): void {
        const region = this.region()?.nativeElement;
        // Where focus came from, so closing the toast that holds it can hand focus back instead of dropping it on the page.
        if (event.relatedTarget instanceof HTMLElement && region !== undefined && !region.contains(event.relatedTarget)) {
            this.returnTo = event.relatedTarget;
        }
        this.toasts.setFocused(true);
    }

    protected focusLeft(event: FocusEvent): void {
        const region = this.region()?.nativeElement;
        if (!(event.relatedTarget instanceof Node) || region === undefined || !region.contains(event.relatedTarget)) {
            this.toasts.setFocused(false);
        }
    }

    protected escape(event: Event): void {
        const toast = (event.target as Element).closest<HTMLElement>('[data-toast-id]');
        const id = toast?.dataset['toastId'];
        if (id === undefined) return;
        event.preventDefault();
        this.closeKeepingFocus(id);
    }

    protected close(id: string): void { this.closeKeepingFocus(id); }

    /** Closing the toast that holds focus would drop focus to the page; hand it to a neighbour or back to where it came from. */
    private closeKeepingFocus(id: string): void {
        const region = this.region()?.nativeElement;
        const toast = region?.querySelector<HTMLElement>(`[data-toast-id="${CSS.escape(id)}"]`);
        const hadFocus = toast?.contains(document.activeElement) === true;
        const neighbour = toast?.nextElementSibling ?? toast?.previousElementSibling ?? null;
        this.toasts.close(id);
        if (!hadFocus) return;
        if (neighbour instanceof HTMLElement) {
            afterNextRender(() => neighbour.querySelector<HTMLElement>('button, a')?.focus(), { injector: this.injector });
        } else {
            // Last toast: back to where the learner was, or to the page's main landmark; never to <body>.
            const target = this.returnTo?.isConnected === true ? this.returnTo : document.querySelector<HTMLElement>('#main-content');
            target?.focus();
        }
    }

    protected swipeStart(event: PointerEvent, id: string): void {
        if (event.pointerType === 'mouse' || !event.isPrimary || (event.target as Element).closest('a, button') !== null) return;
        const element = event.currentTarget as HTMLElement;
        this.drag = { id, element, pointerId: event.pointerId, startX: event.clientX, startTime: event.timeStamp, dx: 0 };
        element.setPointerCapture?.(event.pointerId);
    }

    protected swipeMove(event: PointerEvent): void {
        const drag = this.drag;
        if (drag === null || drag.pointerId !== event.pointerId) return;
        drag.dx = event.clientX - drag.startX;
        drag.element.style.transform = `translateX(${drag.dx}px)`;
        drag.element.style.opacity = String(Math.max(0.2, 1 - Math.abs(drag.dx) / Math.max(1, drag.element.offsetWidth)));
    }

    protected swipeEnd(event: PointerEvent): void {
        const drag = this.drag;
        if (drag === null || drag.pointerId !== event.pointerId) return;
        this.drag = null;
        const distance = Math.abs(drag.dx);
        const speed = distance / Math.max(1, event.timeStamp - drag.startTime);
        if (distance > Math.max(SWIPE_MIN_PX, drag.element.offsetWidth * SWIPE_SHARE) || (distance > SWIPE_MIN_PX / 2 && speed > SWIPE_SPEED)) {
            this.closeKeepingFocus(drag.id);
        } else {
            drag.element.style.transform = '';
            drag.element.style.opacity = '';
        }
    }

    protected swipeCancel(event: PointerEvent): void {
        const drag = this.drag;
        if (drag === null || drag.pointerId !== event.pointerId) return;
        this.drag = null;
        drag.element.style.transform = '';
        drag.element.style.opacity = '';
    }
}

/** Shows or hides the top-layer popover; browsers without the Popover API simply keep the element in flow. */
function setPopover(element: HTMLElement, shown: boolean): void {
    if (typeof element.showPopover !== 'function' || typeof element.hidePopover !== 'function') return;
    let open: boolean;
    try { open = element.matches(':popover-open'); } catch { open = false; }
    try {
        if (shown && !open) element.showPopover();
        else if (!shown && open) element.hidePopover();
    } catch { /* state changed meanwhile */ }
}
