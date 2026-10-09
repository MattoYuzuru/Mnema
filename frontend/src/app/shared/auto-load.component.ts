import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, input, output, viewChild } from '@angular/core';

/**
 * Continues a cursor list when the viewport reaches roughly 75% of its loaded content. The observed tail has a
 * margin of one quarter of the list height (at least one viewport for short lists). A ResizeObserver recalculates
 * that margin after appends, reflow or zoom. `root` is the actual scrolling panel for an embedded list.
 *
 * Only the owner fetches/merges pages. Each armed continuation emits once; loading/error/disabled states disconnect the
 * observers, a failed page waits for explicit retry, and queued callbacks cannot survive teardown/context changes.
 * See angular.dev/api/core/afterRenderEffect and w3.org/TR/intersection-observer/.
 */
@Component({
    selector: 'app-auto-load',
    template: `
      <span #tail class="tail" aria-hidden="true"></span>
      <p class="hint status" role="status">{{ loading() ? loadingText() : '' }}</p>
      @if (error()) {
        <div class="notice error" role="alert">
          <p>{{ error() }}</p>
          <button class="button secondary" type="button" [disabled]="loading() || !enabled()" (click)="retry()">Повторить</button>
        </div>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; overflow-anchor: none; }
      .tail { display: block; block-size: 1px; pointer-events: none; }
      .status:empty { margin: 0; }
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
            const observe = (): void => {
                if (disposed) return;
                observer?.disconnect();
                const current = ++generation;
                const viewport = root?.clientHeight ?? window.innerHeight;
                const margin = Math.ceil(Math.max(content.getBoundingClientRect().height / 4, viewport));
                observer = new IntersectionObserver(entries => {
                    if (!disposed && current === generation && entries.some(entry => entry.isIntersecting)
                        && this.content() === content && this.continuation() === cursor) this.request(cursor);
                }, { root, rootMargin: `0px 0px ${margin}px 0px` });
                observer.observe(tail);
            };
            observe();
            const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(observe);
            resize?.observe(content);
            if (root) resize?.observe(root);
            window.addEventListener('resize', observe, { passive: true });
            onCleanup(() => {
                disposed = true;
                observer?.disconnect();
                resize?.disconnect();
                window.removeEventListener('resize', observe);
            });
        } });
    }

    protected retry(): void {
        this.requested = null;
        this.request(this.continuation(), true);
    }

    private request(cursor: string | null, retry = false): void {
        if (cursor === null || !this.enabled() || this.loading() || (!retry && this.error() !== null) || this.requested === cursor) return;
        this.requested = cursor;
        this.loadNext.emit();
    }
}
