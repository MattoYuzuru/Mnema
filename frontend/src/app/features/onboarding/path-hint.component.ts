import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, afterRenderEffect, computed, inject, signal, untracked, viewChild } from '@angular/core';

import { PathHint } from '../../core/onboarding/path-hint.service';
import { Box, CardSide, Point, Size, cutoutBox, makeRoomDelta, placeCard, revealDelta } from '../../core/onboarding/tour-geometry';
import { otherDialogOpen, otherPopoverOpen } from '../../core/onboarding/tour-inert';
import { PathHintCardComponent } from '../../shared/path-hint-card.component';

/** Room around the anchor between it and the card, in px (the card is not drawn over what it points at). */
const ANCHOR_PADDING = 4;
const FOCUSABLE = 'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])';

/**
 * Draws the in-place hint of «Путь» in the app shell, at the top of the main content: next to its anchor (fixed, kept with the anchor by
 * `ResizeObserver`, resize and scroll in one `requestAnimationFrame`, the page scrolled so the anchor is in view) or, when the anchor did
 * not appear in four seconds, in the flow at the top of the page with the same sentence. The card takes the focus when it opens. «Понятно»
 * and Escape close it and return the focus to the anchor; so does nothing else but the action itself: a click on the anchor closes the hint
 * and leaves the focus to what the click does. Never modal, and no veil. State is `PathHint`'s.
 */
@Component({
    selector: 'app-path-hint',
    imports: [PathHintCardComponent],
    host: { '(document:keydown.escape)': 'onEscape($event)' },
    template: `
      @if (shown(); as hint) {
        @if (hint.phase === 'ready') {
          <app-path-hint-card [title]="hint.request.title" [text]="hint.request.text" [floating]="true" [at]="at()" [side]="side()" (acknowledged)="close(true)" />
        } @else {
          <!-- The anchor never appeared: the same sentence, in the flow at the top of the page, with the margins of a page. -->
          <div class="top">
            <app-path-hint-card [title]="hint.request.title" [text]="hint.request.text" (acknowledged)="close(true)" />
          </div>
        }
      }
    `,
    styles: [`
      :host { display: contents; }
      .top { box-sizing: border-box; inline-size: min(100%, var(--mn-workspace-width)); margin-inline: auto; padding: 1rem clamp(1.125rem, 5vw, 3rem) 0; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PathHintComponent {
    protected readonly hint = inject(PathHint);
    private readonly document = inject(DOCUMENT);
    private readonly card = viewChild(PathHintCardComponent);

    protected readonly at = signal<Point | null>(null);
    protected readonly side = signal<CardSide | 'overlay'>('bottom');
    /** What is drawn: the request and where it stands; `null` while the anchor is being looked for. */
    protected readonly shown = computed(() => {
        const request = this.hint.request();
        const phase = this.hint.phase();
        return request !== null && (phase === 'ready' || phase === 'missing') ? { request, phase } : null;
    });

    constructor() {
        // Keep the card with its anchor.
        afterRenderEffect(onCleanup => {
            const shown = this.shown();
            const target = this.hint.target();
            const card = this.card();
            if (shown === null || card === undefined || shown.phase !== 'ready' || target === null) {
                untracked(() => this.at.set(null));
                return;
            }
            const win = this.document.defaultView;
            if (win === null) return;
            // Only the signals above are dependencies; measuring and the listeners below must not add any.
            untracked(() => this.track(onCleanup, win, card, target));
        });

        // The action on the anchor is what the hint asked for: it closes the card and does not take the focus from what the action does.
        afterRenderEffect(onCleanup => {
            const target = this.hint.phase() === 'ready' ? this.hint.target() : null;
            if (target === null) return;
            const acted = (): void => this.close(false);
            target.addEventListener('click', acted, true);
            onCleanup(() => target.removeEventListener('click', acted, true));
        });

        // A new hint takes the focus once: the heading, so that a screen reader reads the title and the sentence. A card that is drawn again
        // (the anchor came late and the card moved from the top of the page to it) takes it again.
        let focused = '';
        afterRenderEffect(() => {
            const key = `${this.hint.id()}-${this.hint.phase()}`;
            const card = this.card();
            if (this.hint.id() === 0 || card === undefined || key === focused) return;
            focused = key;
            card.focusHeading();
            this.hint.focused();
            // The fallback card stands at the top of the page: bring it into view.
            if (this.hint.phase() === 'missing') card.element.scrollIntoView({ block: 'nearest', behavior: 'instant' });
        });
    }

    /** «Понятно», Escape, or the action: ends the hint; the first two return the focus to the anchor. */
    protected close(returnFocus: boolean): void {
        const target = this.hint.target();
        this.hint.dismiss();
        if (returnFocus) this.focusAnchor(target);
    }

    /** Escape closes the hint when it is the hint's: focus is in the card, on the anchor or on the page itself, and nothing else has handled the key. */
    protected onEscape(event: Event): void {
        if (this.shown() === null || event.defaultPrevented) return;
        const origin = event.target;
        const body = this.document.body;
        const target = this.hint.target();
        const inside = origin === body || origin === this.document.documentElement || (origin instanceof Node
            && (this.card()?.element.contains(origin) === true || target?.contains(origin) === true));
        if (!inside || otherDialogOpen(this.document) || otherPopoverOpen(this.document)) return;
        event.preventDefault();
        this.close(true);
    }

    /** The anchor, or the first control inside it (a form, a list); with none the page heading, as after a route change. */
    private focusAnchor(target: HTMLElement | null): void {
        const focusable = target === null ? null : target.matches(FOCUSABLE) ? target : target.querySelector<HTMLElement>(FOCUSABLE);
        const next = focusable ?? this.document.querySelector<HTMLElement>('#main-content h1');
        next?.focus({ preventScroll: false });
    }

    /** Scrolls the anchor into view, then keeps the card next to it until `onCleanup`. */
    private track(onCleanup: (cleanup: () => void) => void, win: Window, card: PathHintCardComponent, target: HTMLElement): void {
        const root = this.document.documentElement;
        let frame = 0;
        const update = (): void => {
            frame = 0;
            const size: Size = { width: root.clientWidth, height: win.innerHeight };
            const box = card.element.getBoundingClientRect();
            const rect = target.getBoundingClientRect();
            const hole: Box = cutoutBox({ x: rect.left, y: rect.top, width: rect.width, height: rect.height }, ANCHOR_PADDING);
            const placed = placeCard(hole, { width: box.width, height: box.height }, size, 'bottom', 12);
            const current = this.at();
            if (current?.left !== placed.left || current?.top !== placed.top) this.at.set({ left: placed.left, top: placed.top });
            if (this.side() !== placed.side) this.side.set(placed.side);
        };
        const schedule = (): void => { if (frame === 0) frame = win.requestAnimationFrame(update); };
        // Brings the anchor into the window, clear of the card when the window is tall enough, then places the card.
        const settle = (): void => {
            target.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
            const rect = target.getBoundingClientRect();
            const delta = revealDelta({ x: rect.left, y: rect.top, width: rect.width, height: rect.height }, win.innerHeight, 0);
            if (delta !== 0) win.scrollBy({ top: delta, behavior: 'instant' });
            update();
            if (this.side() === 'overlay') {
                const moved = target.getBoundingClientRect();
                const room = makeRoomDelta({ x: moved.left, y: moved.top, width: moved.width, height: moved.height }, card.element.getBoundingClientRect(), win.innerHeight, 'bottom', 12, ANCHOR_PADDING);
                if (room !== 0) { win.scrollBy({ top: room, behavior: 'instant' }); update(); }
            }
        };
        settle();
        const resized = (): void => settle();
        win.addEventListener('resize', resized);
        win.addEventListener('scroll', schedule, true);
        const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(schedule) : null;
        observer?.observe(card.element);
        observer?.observe(target);
        onCleanup(() => {
            if (frame !== 0) win.cancelAnimationFrame(frame);
            win.removeEventListener('resize', resized);
            win.removeEventListener('scroll', schedule, true);
            observer?.disconnect();
        });
    }
}
