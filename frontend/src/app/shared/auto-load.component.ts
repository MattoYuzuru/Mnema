import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, inject, input, output, signal, untracked, viewChild } from '@angular/core';

/**
 * Continues a cursor list when the viewport reaches roughly 75% of its loaded content. The observed tail has a
 * margin of one quarter of the list height (at least one viewport for short lists). A ResizeObserver recalculates
 * that margin after appends, reflow or zoom. `root` is the actual scrolling panel for an embedded list.
 *
 * Only the owner fetches/merges pages. Each armed continuation emits once; loading/error/disabled states disconnect the
 * observers, a failed page waits for explicit retry, and queued callbacks cannot survive teardown/context changes.
 * The retry button stays mounted while it retries; after a keyboard retry succeeds, focus moves to the first new row.
 * See angular.dev/api/core/afterRenderEffect and w3.org/TR/intersection-observer/.
 */
@Component({
    selector: 'app-auto-load',
    template: `
      <span #tail class="tail" aria-hidden="true"></span>
      <p class="hint status" role="status">{{ loading() ? loadingText() : '' }}</p>
      @if (error() ?? (retrying() ? retryState?.message : null); as message) {
        <div class="notice error" role="alert">
          <p>{{ message }}</p>
          <button class="button secondary" type="button" [attr.aria-disabled]="loading() || !enabled() ? 'true' : null"
            (click)="retry()">Повторить</button>
        </div>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; overflow-anchor: none; }
      .tail { display: block; block-size: 1px; pointer-events: none; }
      .status:empty { margin: 0; }
      button[aria-disabled='true'] { cursor: progress; opacity: .7; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AutoLoadComponent {
    readonly content = input.required<HTMLElement>();
    /** Changes when the owner replaces the list snapshot while keeping the same DOM/cursor. */
    readonly context = input<string | number>(0);
    readonly continuation = input<string | null>(null);
    readonly loading = input(false);
    readonly error = input<string | null>(null);
    readonly enabled = input(true);
    readonly root = input<HTMLElement | null>(null);
    readonly loadingText = input('Загружаем следующие записи…');
    readonly loadNext = output<void>();

    private readonly tail = viewChild<ElementRef<HTMLElement>>('tail');
    private requested: string | null = null;
    private previousContent: HTMLElement | null = null;
    private previousContext: string | number = 0;
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    /** Keeps the error and its focused button mounted while the owner clears the error and retries. */
    protected readonly retrying = signal(false);
    protected retryState: {
        readonly message: string; readonly cursor: string | null; readonly rows: number; readonly focused: boolean; started: boolean;
    } | null = null;

    constructor() {
        afterRenderEffect({ read: onCleanup => {
            const content = this.content();
            const context = this.context();
            const tail = this.tail()?.nativeElement;
            const cursor = this.continuation();
            const enabled = this.enabled();
            const root = this.root();
            const loading = this.loading();
            const blocked = loading || this.error() !== null;
            if (!enabled || cursor === null || content !== this.previousContent || context !== this.previousContext) this.requested = null;
            this.previousContent = content;
            this.previousContext = context;
            if (!tail || !enabled || cursor === null || blocked || typeof IntersectionObserver === 'undefined') return;

            let disposed = false;
            let generation = 0;
            let observer: IntersectionObserver | null = null;
            let margin = -1;
            let frame: number | null = null;
            const observe = (): void => {
                frame = null;
                if (disposed) return;
                const viewport = root?.clientHeight ?? window.innerHeight;
                const next = Math.ceil(Math.max(content.getBoundingClientRect().height / 4, viewport));
                if (next === margin) return;
                margin = next;
                observer?.disconnect();
                const current = ++generation;
                observer = new IntersectionObserver(entries => {
                    if (!disposed && current === generation && entries.some(entry => entry.isIntersecting)
                        && this.content() === content && this.continuation() === cursor) this.request(cursor);
                }, { root, rootMargin: `0px 0px ${margin}px 0px` });
                observer.observe(tail);
            };
            // Rows rendered lazily (content-visibility) resize while scrolling; recompute at most once per frame.
            const schedule = (): void => { if (frame === null && !disposed) frame = requestAnimationFrame(observe); };
            observe();
            const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
            resize?.observe(content);
            if (root) resize?.observe(root);
            window.addEventListener('resize', schedule, { passive: true });
            onCleanup(() => {
                disposed = true;
                if (frame !== null) cancelAnimationFrame(frame);
                observer?.disconnect();
                resize?.disconnect();
                window.removeEventListener('resize', schedule);
            });
        } });

        afterRenderEffect({ write: () => {
            const state = this.retryState;
            if (!this.retrying() || state === null) return;
            if (this.loading()) { state.started = true; return; }
            const failed = this.error() !== null;
            // An owner may finish within one turn, so a moved cursor also proves the retry completed.
            if (!failed && !state.started && this.continuation() === state.cursor) return;
            this.retryState = null;
            this.retrying.set(false);
            if (!failed && state.focused) this.focusFirstAppended(state.rows);
        } });
    }

    protected retry(): void {
        const message = this.error();
        if (this.loading() || !this.enabled() || message === null) return;
        const focused = this.host.nativeElement.contains(document.activeElement);
        this.retryState = { message, cursor: this.continuation(), rows: this.content().children.length, focused, started: false };
        this.retrying.set(true);
        this.requested = null;
        this.request(this.continuation(), true);
    }

    private focusFirstAppended(rows: number): void {
        const row = untracked(this.content).children.item(rows);
        if (!(row instanceof HTMLElement)) return;
        const target = row.querySelector<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]), [tabindex]') ?? row;
        if (target === row && !row.hasAttribute('tabindex')) row.tabIndex = -1;
        target.focus();
    }

    private request(cursor: string | null, retry = false): void {
        if (cursor === null || !this.enabled() || this.loading() || (!retry && this.error() !== null) || this.requested === cursor) return;
        this.requested = cursor;
        this.loadNext.emit();
    }
}
