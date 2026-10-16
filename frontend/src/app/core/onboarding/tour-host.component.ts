import { DOCUMENT } from '@angular/common';
import { Router } from '@angular/router';
import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, computed, inject, signal, untracked, viewChild } from '@angular/core';

import { TourCardComponent } from '../../shared/tour-card.component';
import { TourPauseComponent } from '../../shared/tour-pause.component';
import { TourScrimComponent } from '../../shared/tour-scrim.component';
import { onboardingText } from './onboarding-texts';
import { currentUrl, isStudyUrl } from './study-route';
import { Box, CardPlacement, Point, Size, cutoutBox, isBottomSheet, makeRoomDelta, placeCard, revealDelta, sameBox } from './tour-geometry';
import { TourEngine } from './tour-engine.service';
import { inertOutside } from './tour-inert';
import { TourStep, isActionGate } from './tour.models';

/**
 * Draws the tour for the engine's state, once, in the app shell: the veil with the cut-out, the step card and the pause plaque, and a
 * `aria-live="polite"` region that announces «Шаг N из M» and the title on every step. It keeps the cut-out and the card in step with
 * the target (`ResizeObserver`, resize, scroll, coalesced into one `requestAnimationFrame`), scrolls an off-screen target into view
 * (above the bottom sheet on a phone), moves focus to the card heading on a step and to «Продолжить знакомство» on a pause, and makes
 * the rest of the page `inert` while a step waits for an action on its target. Escape pauses; a click on the veil does nothing.
 */
@Component({
    selector: 'app-tour-host',
    imports: [TourScrimComponent, TourCardComponent, TourPauseComponent],
    host: { '(document:keydown.escape)': 'onEscape($event)' },
    template: `
      @if (shown(); as step) {
        <app-tour-scrim [viewport]="viewport()" [hole]="hole()" [radius]="radius()" />
        <app-tour-card [position]="engine.index() + 1" [total]="engine.total()" [title]="step.title" [body]="step.body" [gate]="step.gate"
          [mode]="mode()" [canBack]="engine.index() > 0" [modal]="modal()" [sheet]="sheet()" [at]="at()"
          (back)="engine.back()" (next)="engine.next()" (finish)="engine.finish()" (pauseRequest)="engine.pause()" />
      }
      @if (engine.active() && engine.paused() && !studying()) {
        <app-tour-pause (resumed)="engine.resume()" />
      }
      <div class="visually-hidden" aria-live="polite" aria-atomic="true">{{ announcement() }}</div>
    `,
    styles: [`:host { display: contents; }`],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class TourHostComponent {
    protected readonly engine = inject(TourEngine);
    private readonly document = inject(DOCUMENT);
    private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly card = viewChild(TourCardComponent);
    private readonly pausePlaque = viewChild(TourPauseComponent);

    protected readonly viewport = signal<Size>({ width: 0, height: 0 });
    protected readonly hole = signal<Box | null>(null);
    protected readonly at = signal<Point | null>(null);
    protected readonly sheet = signal(false);
    protected readonly radius = signal(2);

    /** The current step while the tour is on the screen (not paused). */
    protected readonly shown = computed(() => {
        const step = this.engine.step();
        return this.engine.active() && !this.engine.paused() && step?.external !== true ? step : null;
    });
    /** A study session leaves the plaque out: it comes back with the page the learner returns to. */
    protected readonly studying = computed(() => isStudyUrl(this.url()));
    private readonly url = currentUrl(inject(Router));
    protected readonly mode = computed(() => this.engine.preparing() ? 'preparing' as const : this.engine.phase() === 'missing' ? 'missing' as const : 'normal' as const);
    /** An informational step, and any step whose target is not usable yet, is modal; only a step that waits for the target is not. */
    protected readonly modal = computed(() => {
        const step = this.shown();
        return step === null || this.mode() !== 'normal' || this.engine.phase() !== 'ready' || !isActionGate(step.gate);
    });
    protected readonly announcement = computed(() => {
        const step = this.shown();
        return step === null ? '' : `${onboardingText('tour.counter', { n: this.engine.index() + 1, m: this.engine.total() })}. ${step.title}`;
    });

    constructor() {
        // Keep the cut-out and the card with the target.
        afterRenderEffect(onCleanup => {
            const step = this.shown();
            const target = this.engine.target();
            const ready = this.engine.phase() === 'ready' && this.mode() === 'normal';
            const card = this.card();
            if (step === null || card === undefined) {
                untracked(() => { this.hole.set(null); this.at.set(null); });
                return;
            }
            const win = this.document.defaultView;
            if (win === null) return;
            // Only the signals above are dependencies; measuring and the listeners below must not add any.
            untracked(() => this.track(onCleanup, win, card, step.placement, ready ? target : null));
        });

        // A step that waits for an action on its target leaves the target and the tour's own surfaces live and the rest of the page inert.
        afterRenderEffect(onCleanup => {
            const step = this.shown();
            const target = this.engine.target();
            if (step === null || target === null || this.engine.phase() !== 'ready' || this.mode() !== 'normal' || !isActionGate(step.gate)) return;
            onCleanup(inertOutside(target, [this.element.nativeElement, this.document.querySelector('app-toast-region')]));
        });

        // A new step (or a resume) moves focus to the card heading; a pause moves it to the way back in.
        afterRenderEffect(() => {
            this.engine.focusRequest();
            if (this.shown() !== null) this.card()?.focusHeading();
        });
        afterRenderEffect(() => {
            if (this.engine.paused()) this.pausePlaque()?.focusResume();
        });
    }

    /**
     * Measures the target and the card, publishes the cut-out and the card position, and keeps them current until `onCleanup`.
     * Without a `tracked` target (not on the screen yet, or its text variant) the veil is whole and the card sits in the middle.
     */
    private track(onCleanup: (cleanup: () => void) => void, win: Window, card: TourCardComponent, placement: TourStep['placement'], tracked: HTMLElement | null): void {
        const root = this.document.documentElement;
        const body = this.document.body;
        const padding = body.style.paddingBlockEnd;
        const rem = (): number => parseFloat(win.getComputedStyle(root).fontSize) || 16;
        let frame = 0;
        const state: { placed: CardPlacement | null } = { placed: null };
        const update = (): void => {
            frame = 0;
            const size: Size = { width: root.clientWidth, height: win.innerHeight };
            const sheet = isBottomSheet(size.width, rem());
            const box = card.element.getBoundingClientRect();
            const cardSize: Size = { width: box.width, height: box.height };
            const radius = parseFloat(win.getComputedStyle(root).getPropertyValue('--mn-radius'));
            this.viewport.set(size);
            this.sheet.set(sheet);
            if (Number.isFinite(radius)) this.radius.set(radius);
            // On a phone the page gets room under the sheet, so the target can scroll above it.
            body.style.paddingBlockEnd = sheet ? `${cardSize.height}px` : padding;
            let hole: Box | null = null;
            let at: Point | null = null;
            if (tracked !== null) {
                const rect = tracked.getBoundingClientRect();
                hole = cutoutBox({ x: rect.left, y: rect.top, width: rect.width, height: rect.height });
                state.placed = sheet ? null : placeCard(hole, cardSize, size, placement);
                at = state.placed;
            } else if (!sheet) {
                state.placed = null;
                at = { left: Math.max(0, (size.width - cardSize.width) / 2), top: Math.max(0, (size.height - cardSize.height) / 2) };
            }
            if (!sameBox(this.hole(), hole)) this.hole.set(hole);
            const current = this.at();
            if (current?.left !== at?.left || current?.top !== at?.top) this.at.set(at);
        };
        const schedule = (): void => { if (frame === 0) frame = win.requestAnimationFrame(update); };
        // Brings the target into the free part of the window (above the sheet on a phone, clear of the card elsewhere), then measures.
        const settle = (): void => {
            if (tracked !== null) {
                tracked.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
                const rect = tracked.getBoundingClientRect();
                const sheetHeight = isBottomSheet(root.clientWidth, rem()) ? card.element.getBoundingClientRect().height : 0;
                const delta = revealDelta({ x: rect.left, y: rect.top, width: rect.width, height: rect.height }, win.innerHeight, sheetHeight);
                if (delta !== 0) win.scrollBy({ top: delta, behavior: 'instant' });
            }
            update();
            if (tracked !== null && state.placed?.side === 'overlay') {
                // The card would cover the target: move the page so that the two stand one above the other, if the window is tall enough.
                const rect = tracked.getBoundingClientRect();
                const room = makeRoomDelta({ x: rect.left, y: rect.top, width: rect.width, height: rect.height }, card.element.getBoundingClientRect(), win.innerHeight, placement);
                if (room !== 0) { win.scrollBy({ top: room, behavior: 'instant' }); update(); }
            }
        };
        // A resize (a phone turned, a window dragged) re-places the page's content, so the target is brought back into view; scrolling only follows it.
        const resized = (): void => { settle(); };
        settle();
        win.addEventListener('resize', resized);
        win.addEventListener('scroll', schedule, true);
        // A card that changes size (a slide, a phone's sheet) is placed again; a target that changes size only moves the cut-out.
        let settleFrame = 0;
        const cardChanged = (): void => { if (settleFrame === 0) settleFrame = win.requestAnimationFrame(() => { settleFrame = 0; settle(); }); };
        const cardObserver = typeof ResizeObserver === 'function' ? new ResizeObserver(cardChanged) : null;
        const targetObserver = typeof ResizeObserver === 'function' ? new ResizeObserver(schedule) : null;
        cardObserver?.observe(card.element);
        if (tracked !== null) targetObserver?.observe(tracked);
        onCleanup(() => {
            if (frame !== 0) win.cancelAnimationFrame(frame);
            if (settleFrame !== 0) win.cancelAnimationFrame(settleFrame);
            win.removeEventListener('resize', resized);
            win.removeEventListener('scroll', schedule, true);
            cardObserver?.disconnect();
            targetObserver?.disconnect();
            body.style.paddingBlockEnd = padding;
        });
    }

    protected onEscape(event: Event): void {
        if (!this.engine.active() || this.engine.paused() || event.defaultPrevented) return;
        event.preventDefault();
        this.engine.pause();
    }
}
