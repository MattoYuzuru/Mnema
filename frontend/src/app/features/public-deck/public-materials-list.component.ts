import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, untracked, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { map } from 'rxjs';

import { AutoLoadComponent } from '../../shared/auto-load.component';
import { PublicDeckApiService } from './public-deck-api.service';
import { PublicDeckFailure, PublicMaterial } from './public-deck.models';
import { readFailureText, RESTART_NOTICE } from './public-deck.text';
import { PublicPager } from './public-pager';

/** Pages the list may read on its own to find the open material and the one after it; a deep link never walks the whole deck. */
const MAX_AUTO_PAGES = 20;

/**
 * The materials of a public deck in the manifest order, continued by `app-auto-load` (no page buttons). A row opens the
 * material in the same page (`?material=`). The page keeps this list mounted (hidden) while a material is open, so Back
 * returns to the same rows, cursor and place; while a material is open the list reads on, if need be, until it knows the
 * material before and after it ({@link neighbors}). The list reads the published revision only: when the deck is
 * republished while it is being read, the cursor answers 412 and the list starts again from the first page, says so in a
 * polite status and brings its head into view.
 */
@Component({
    selector: 'app-public-materials-list',
    imports: [RouterLink, AutoLoadComponent],
    template: `
      <section class="list" aria-labelledby="public-materials-heading">
        <h2 id="public-materials-heading" #heading>Материалы <span class="count">· {{ pager.total() }}</span></h2>
        <p class="status" role="status" [class.notice]="restartNotice() !== ''">{{ restartNotice() }}</p>
        @switch (pager.phase()) {
          @case ('loading') { <p class="hint" role="status">Загружаем материалы…</p> }
          @case ('error') {
            <div class="notice error" role="alert">
              <p>{{ failureText() }}</p>
              <button class="button" type="button" (click)="pager.start()">Повторить</button>
            </div>
          }
          @default {
            @if (pager.rows().length === 0) {
              <section class="empty-state"><h3>В этой колоде пока нет материалов</h3></section>
            }
            <ol #rows class="rows" role="list" [hidden]="pager.rows().length === 0">
              @for (item of pager.rows(); track item.memberKey) {
                <li class="item-row" [attr.data-member-key]="item.memberKey">
                  <span class="folio" aria-hidden="true">{{ item.ordinal + 1 }}</span>
                  <a class="row-link" [routerLink]="[]" [queryParams]="{ material: item.memberKey }">{{ titleOf(item) }}</a>
                </li>
              }
            </ol>
            <app-auto-load [content]="rows" [context]="pager.context()" [continuation]="pager.cursor()" [loading]="pager.loadingMore()"
              loadingText="Загружаем следующие материалы…"
              [error]="pager.moreFailed() ? 'Не удалось загрузить следующие материалы. Загруженные остались на месте.' : null"
              (loadNext)="pager.next()" />
          }
        }
      </section>
    `,
    styleUrl: './public-deck-lists.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PublicMaterialsListComponent {
    readonly code = input.required<string>();
    /** The material that is open in the page, or `null`: the list then reads on until it knows its neighbours. */
    readonly openKey = input<string | null>(null);
    /** The deck is gone (hidden or closed) while the list was being read: the page asks for the summary again. */
    readonly gone = output<PublicDeckFailure>();

    private readonly api = inject(PublicDeckApiService);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');
    protected readonly pager = new PublicPager<PublicMaterial>(
        cursor => this.api.materials(this.code(), cursor).pipe(map(page => ({ rows: page.items, total: page.total, nextCursor: page.nextCursor }))),
        row => row.memberKey, inject(DestroyRef), failure => this.gone.emit(failure));
    protected readonly restartNotice = computed(() => this.pager.restarted() && this.pager.phase() === 'ready' ? RESTART_NOTICE : '');

    /** The materials before and after the open one among the rows read so far; `null` where the list does not know one (yet). */
    readonly neighbors = computed(() => {
        const key = this.openKey();
        const rows = this.pager.rows();
        const index = key === null ? -1 : rows.findIndex(row => row.memberKey === key);
        return { previous: index > 0 ? rows[index - 1] : null, next: index >= 0 ? rows[index + 1] ?? null : null };
    });

    private autoPages = 0;
    private autoKey: string | null = null;

    constructor() {
        effect(() => {
            this.code();
            untracked(() => this.pager.start());
        });
        // An open material that is not among the rows, or is the last of them, needs one more page to know its neighbours.
        effect(() => {
            const key = this.openKey();
            const rows = this.pager.rows();
            if (key !== this.autoKey) { this.autoKey = key; this.autoPages = 0; }
            if (key === null || this.pager.phase() !== 'ready' || this.pager.cursor() === null || this.pager.loadingMore() || this.pager.moreFailed()) return;
            const index = rows.findIndex(row => row.memberKey === key);
            if ((index === -1 || index === rows.length - 1) && this.autoPages < MAX_AUTO_PAGES) {
                this.autoPages++;
                untracked(() => this.pager.next());
            }
        });
        effect(() => {
            if (this.pager.restarted() && this.pager.phase() === 'ready' && this.openKey() === null) {
                untracked(() => afterNextRender(() => this.heading()?.nativeElement.scrollIntoView({ block: 'start' }), { injector: this.injector }));
            }
        });
    }

    /** Puts the focus on the row of a material (and brings it into view). `false` when the list has no such row. */
    focusRow(memberKey: string): boolean {
        const link = [...this.host.nativeElement.querySelectorAll<HTMLElement>('li[data-member-key]')]
            .find(row => row.dataset['memberKey'] === memberKey)?.querySelector<HTMLElement>('.row-link');
        if (link === null || link === undefined) return false;
        link.focus();
        // `instant`: the page has `scroll-behavior: smooth`, which would race the router's own scrolling.
        link.scrollIntoView({ block: 'center', behavior: 'instant' });
        return true;
    }

    protected titleOf(item: PublicMaterial): string { return item.title.trim() || `Материал ${item.ordinal + 1}`; }

    protected failureText(): string { return readFailureText(this.pager.failure(), 'материалы'); }
}
