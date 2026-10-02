import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';

import { SegmentedChoiceComponent, SegmentedOption } from '../../../shared/segmented-choice.component';
import { ExemplarBudget, ItemPage, ItemSort, ItemSummary, newCommandId } from '../../authoring/authoring.models';
import { ItemApiService } from '../../authoring/item-api.service';
import { BulkActionBarComponent } from './bulk-action-bar.component';
import { DeckHubApiService } from './deck-hub-api.service';
import {
    BULK_DELETE_EXPLICIT_MAX,
    BULK_DELETE_MAX,
    DeletionPreview,
    DeletionSelection,
    hubFailureOf,
    selectionKey
} from './deck-hub.models';
import {
    STALE_DECK_MESSAGE,
    TOO_LARGE_MESSAGE,
    TOO_MANY_EXPLICIT_MESSAGE,
    UNCERTAIN_DELETE_MESSAGE,
    consequenceText,
    deletionOutcomeText,
    hubFailureText,
    materialsText
} from './deck-hub.text';
import { MaterialSelection } from './material-selection';
import { SelectableMaterialListComponent } from './selectable-material-list.component';

type ListPhase = 'loading' | 'ready' | 'error';
/** What a page says about the list as a whole (the rows themselves accumulate in `items`). */
type ListMeta = Omit<ItemPage, 'items'>;

/** What the bulk bar may do with the current selection, learned from a server preview of exactly that selection. */
type PreviewState =
    | { readonly phase: 'idle' }
    | { readonly phase: 'loading' }
    | { readonly phase: 'ready'; readonly key: string; readonly preview: DeletionPreview }
    | { readonly phase: 'blocked'; readonly reason: string };

/** A notice that outlives one action, with the recovery it offers. */
interface HubNotice { readonly text: string; readonly refresh: boolean; readonly remainder: readonly string[]; readonly tone: 'info' | 'error'; }

const PREVIEW_DELAY_MS = 300;
const ANNOUNCE_DELAY_MS = 500;
const BAR_HEIGHT_PROPERTY = '--mn-bulk-bar-height';

const SORT_OPTIONS: readonly SegmentedOption<ItemSort>[] = [
    { value: 'ordinal', label: 'По порядку', hint: 'Материалы идут в порядке колоды.' },
    { value: 'exerciseCount', label: 'Без упражнений сначала', hint: 'Сначала материалы без упражнений: они не попадут в занятия.' }
];

/**
 * The material half of the Deck hub: sorted pages with exercise counts, selection, «Эталон» stars and bulk deletion.
 * The list is its own request, so it works when statistics fail. The selection is cleared whenever the list is
 * reloaded (another sort, another Deck revision, a finished deletion): a stale selection must never be deleted.
 * Deletion is gated on a server preview of exactly the selected materials (consequences for the hold text, the Deck
 * revision the selection was made against) and runs through the bulk command; a partial run is reported honestly.
 */
@Component({
    selector: 'app-deck-materials',
    imports: [RouterLink, SegmentedChoiceComponent, SelectableMaterialListComponent, BulkActionBarComponent],
    host: { '(keydown.escape)': 'clearSelectionFromKeyboard()' },
    templateUrl: './deck-materials.component.html',
    styleUrl: './deck-materials.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class DeckMaterialsComponent {
    readonly deckId = input.required<string>();
    /** The server offers AI exercise generation (`aiGeneration` capability). Absent or failed reads mean false. */
    readonly generationAvailable = input(false);
    /** A deletion removed materials: statistics derived from the Deck are stale. */
    readonly changed = output<void>();

    readonly selection = new MaterialSelection();
    protected readonly sortOptions = SORT_OPTIONS;
    protected readonly sort = signal<ItemSort>('ordinal');
    protected readonly phase = signal<ListPhase>('loading');
    protected readonly meta = signal<ListMeta | null>(null);
    protected readonly items = signal<readonly ItemSummary[]>([]);
    protected readonly loadingMore = signal(false);
    protected readonly moreError = signal(false);
    protected readonly starPending = signal<ReadonlySet<string>>(new Set());
    protected readonly preview = signal<PreviewState>({ phase: 'idle' });
    protected readonly deleting = signal(false);
    protected readonly notice = signal<HubNotice | null>(null);
    protected readonly problem = signal<string | null>(null);
    /** Polite announcements: selection counts and star changes. The element exists before its text changes. */
    protected readonly announcement = signal('');
    protected readonly heading = viewChild<ElementRef<HTMLElement>>('heading');
    protected readonly bar = viewChild<BulkActionBarComponent, ElementRef<HTMLElement>>('bar', { read: ElementRef });

    protected readonly total = computed(() => this.meta()?.total ?? 0);
    protected readonly exemplars = computed<ExemplarBudget>(() => this.meta()?.exemplars ?? { count: 0, limit: 10 });
    protected readonly hasMore = computed(() => (this.meta()?.nextCursor ?? null) !== null);
    protected readonly selectionCount = this.selection.count;
    protected readonly consequence = computed(() => {
        const state = this.preview();
        return state.phase === 'ready' ? consequenceText(state.preview) : '';
    });
    protected readonly deleteHint = computed(() => {
        const state = this.preview();
        if (state.phase === 'loading') return 'Считаем, что исчезнет вместе с выбранными…';
        return state.phase === 'blocked' ? state.reason : '';
    });
    protected readonly deleteDisabled = computed(() => this.preview().phase !== 'ready' || this.deleting());

    private readonly items$ = inject(ItemApiService);
    private readonly hub = inject(DeckHubApiService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly revisionId = computed(() => this.meta()?.deckRevisionId ?? null);
    private listLoad: Subscription | null = null;
    private loadSequence = 0;
    private pendingDelete: { readonly key: string; readonly commandId: string } | null = null;

    constructor() {
        effect(() => {
            const deckId = this.deckId();
            untracked(() => { this.meta.set(null); this.items.set([]); this.reload(deckId, 'ordinal'); });
        });
        effect(() => this.selection.total.set(this.total()));
        effect(onCleanup => this.watchPreview(onCleanup));
        effect(onCleanup => {
            const count = this.selectionCount();
            const timer = setTimeout(() => this.announcement.set(count > 0 ? `Выбрано ${materialsText(count)}.` : ''), ANNOUNCE_DELAY_MS);
            onCleanup(() => clearTimeout(timer));
        });
        effect(onCleanup => this.reserveBarSpace(onCleanup));
        this.destroyRef.onDestroy(() => this.listLoad?.unsubscribe());
    }

    /** Lists materials without exercises first and moves the reader to the list; called by the statistics widgets. */
    showMissingFirst(): void {
        this.changeSort('exerciseCount');
        this.focusHeading();
    }

    protected changeSort(value: ItemSort | null): void {
        if (value === null || value === this.sort()) return;
        this.reload(this.deckId(), value);
    }

    protected refresh(): void {
        this.notice.set(null);
        this.reload(this.deckId(), this.sort());
    }

    protected loadMore(): void {
        const meta = this.meta();
        if (meta === null || meta.nextCursor === null || this.loadingMore()) return;
        this.loadingMore.set(true);
        this.moreError.set(false);
        this.items$.list(this.deckId(), { cursor: meta.nextCursor, sort: this.sort(), exerciseCount: true })
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: next => {
                    this.loadingMore.set(false);
                    if (next.deckRevisionId !== meta.deckRevisionId) { this.staleList(); return; }
                    const { items, ...rest } = next;
                    this.meta.set(rest);
                    this.items.update(current => [...current, ...items]);
                },
                error: (error: unknown) => {
                    this.loadingMore.set(false);
                    if (hubFailureOf(error)?.status === 412) this.staleList(); else this.moreError.set(true);
                }
            });
    }

    protected toggleExemplar(item: ItemSummary): void {
        const meta = this.meta();
        if (meta === null || this.starPending().has(item.memberKey)) return;
        const wanted = !item.exemplar;
        this.problem.set(null);
        this.starPending.update(set => new Set(set).add(item.memberKey));
        this.hub.setExemplar(this.deckId(), item.memberKey, item.itemRevisionId, wanted, newCommandId())
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: acknowledgement => {
                    this.settleStar(item.memberKey);
                    this.items.update(current => current.map(entry =>
                        entry.memberKey === item.memberKey ? { ...entry, exemplar: acknowledgement.exemplar } : entry));
                    this.meta.update(current => current && { ...current,
                        exemplars: { ...current.exemplars, count: acknowledgement.exemplarCount } });
                    this.announcement.set(`${acknowledgement.exemplar ? 'Эталон отмечен' : 'Эталон снят'}: ${item.title || 'материал без текста'}.`);
                },
                error: (error: unknown) => {
                    this.settleStar(item.memberKey);
                    const failure = hubFailureOf(error);
                    if (failure?.code === 'EXEMPLAR_LIMIT_REACHED') {
                        this.meta.update(current => current && { ...current, exemplars: { ...current.exemplars, count: current.exemplars.limit } });
                        this.problem.set(`Эталонов может быть не больше ${meta.exemplars.limit}. Снимите звёздочку с другого материала.`);
                    } else if (failure?.status === 412) {
                        this.reload(this.deckId(), this.sort());
                        this.problem.set('Материал изменился в другой вкладке. Список обновлён.');
                    } else {
                        this.problem.set(hubFailureText(failure, 'Эталон не изменён'));
                    }
                }
            });
    }

    protected deleteSelected(): void {
        const meta = this.meta();
        const selection = this.selection.selection();
        const state = this.preview();
        if (meta === null || selection === null || state.phase !== 'ready' || this.deleting()) return;
        const key = selectionKey(selection);
        if (state.key !== key) return;
        // The same selection after an unconfirmed attempt reuses its command: the server replays instead of repeating.
        const commandId = this.pendingDelete?.key === key ? this.pendingDelete.commandId : newCommandId();
        this.pendingDelete = { key, commandId };
        this.deleting.set(true);
        this.notice.set(null);
        this.hub.deleteMany(this.deckId(), meta.deckVersion, meta.deckRevisionId, selection, commandId)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: ({ result }) => {
                    this.pendingDelete = null;
                    this.deleting.set(false);
                    const partial = result.status === 'PARTIAL';
                    this.selection.clear();
                    this.reload(this.deckId(), this.sort());
                    this.notice.set({ text: deletionOutcomeText(result), refresh: false, remainder: partial ? result.notDeleted : [],
                        tone: partial ? 'error' : 'info' });
                    this.changed.emit();
                    this.focusHeading();
                },
                error: (error: unknown) => this.deletionFailed(error)
            });
    }

    protected selectRemainder(): void {
        const notice = this.notice();
        if (notice === null) return;
        this.selection.replace(notice.remainder);
        this.notice.set({ ...notice, remainder: [] });
    }

    protected dismissNotice(): void { this.notice.set(null); }

    protected clearSelection(): void {
        this.selection.clear();
        this.focusHeading();
    }

    /** Esc clears a selection; with nothing selected it does nothing, so it never fights a dialog or the hold button. */
    protected clearSelectionFromKeyboard(): void {
        if (this.selection.empty()) return;
        this.selection.clear();
    }

    private reload(deckId: string, sort: ItemSort): void {
        const sequence = ++this.loadSequence;
        this.listLoad?.unsubscribe();
        this.sort.set(sort);
        this.selection.clear();
        this.pendingDelete = null;
        this.problem.set(null);
        this.moreError.set(false);
        this.loadingMore.set(false);
        if (this.meta() === null || this.phase() === 'error') this.phase.set('loading');
        this.listLoad = this.items$.list(deckId, { sort, exerciseCount: true }).subscribe({
            next: page => {
                if (sequence !== this.loadSequence) return;
                const { items, ...rest } = page;
                this.meta.set(rest);
                this.items.set(items);
                this.phase.set('ready');
            },
            error: () => { if (sequence === this.loadSequence) this.phase.set('error'); }
        });
    }

    /** A later page belongs to another Deck revision: the loaded rows are stale, so start over and say so. */
    private staleList(): void {
        this.notice.set({ text: 'Колода изменилась в другой вкладке. Список обновлён.', refresh: false, remainder: [], tone: 'info' });
        this.reload(this.deckId(), this.sort());
    }

    private deletionFailed(error: unknown): void {
        this.deleting.set(false);
        const failure = hubFailureOf(error);
        const uncertain = failure === null || failure.status === 0 || failure.status >= 500;
        if (uncertain) {
            // pendingDelete stays: the next hold sends the very same command.
            this.notice.set({ text: UNCERTAIN_DELETE_MESSAGE, refresh: false, remainder: [], tone: 'error' });
            return;
        }
        this.pendingDelete = null;
        if (failure.status === 412) {
            this.notice.set({ text: STALE_DECK_MESSAGE, refresh: true, remainder: [], tone: 'error' });
        } else if (failure.code === 'BULK_SELECTION_TOO_LARGE') {
            this.notice.set({ text: TOO_LARGE_MESSAGE, refresh: false, remainder: [], tone: 'error' });
        } else {
            this.notice.set({ text: 'Материалы не удалены. Обновите страницу и повторите попытку.', refresh: true, remainder: [], tone: 'error' });
        }
    }

    private settleStar(memberKey: string): void {
        this.starPending.update(set => { const next = new Set(set); next.delete(memberKey); return next; });
    }

    /** Ask the server what the current selection would delete, shortly after the selection stops changing. */
    private watchPreview(onCleanup: (callback: () => void) => void): void {
        const selection = this.selection.selection();
        const revisionId = this.revisionId();
        if (selection === null || revisionId === null) { this.preview.set({ phase: 'idle' }); return; }
        const blocked = blockedReason(selection, this.selection.count());
        if (blocked !== null) { this.preview.set({ phase: 'blocked', reason: blocked }); return; }
        this.preview.set({ phase: 'loading' });
        let subscription: Subscription | null = null;
        const timer = setTimeout(() => {
            subscription = this.hub.previewDeletion(this.deckId(), revisionId, selection).subscribe({
                next: preview => this.preview.set({ phase: 'ready', key: selectionKey(selection), preview }),
                error: (error: unknown) => {
                    const failure = hubFailureOf(error);
                    if (failure?.status === 412) {
                        this.preview.set({ phase: 'blocked', reason: STALE_DECK_MESSAGE });
                        this.notice.set({ text: STALE_DECK_MESSAGE, refresh: true, remainder: [], tone: 'error' });
                    } else if (failure?.code === 'BULK_SELECTION_TOO_LARGE') {
                        this.preview.set({ phase: 'blocked', reason: TOO_LARGE_MESSAGE });
                    } else {
                        this.preview.set({ phase: 'blocked', reason: 'Не удалось посчитать последствия. Измените выбор или обновите список.' });
                    }
                }
            });
        }, PREVIEW_DELAY_MS);
        onCleanup(() => { clearTimeout(timer); subscription?.unsubscribe(); });
    }

    /** Keeps focused rows clear of the sticky bar: the page reserves the bar's height as scroll padding. */
    private reserveBarSpace(onCleanup: (callback: () => void) => void): void {
        const element = this.bar()?.nativeElement;
        const root = document.documentElement;
        if (element === undefined) return;
        const apply = (): void => root.style.setProperty(BAR_HEIGHT_PROPERTY, `${element.offsetHeight}px`);
        apply();
        const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(apply);
        observer?.observe(element);
        onCleanup(() => { observer?.disconnect(); root.style.removeProperty(BAR_HEIGHT_PROPERTY); });
    }

    private focusHeading(): void { queueMicrotask(() => this.heading()?.nativeElement.focus()); }
}

/** A selection the server would refuse is explained instead of sent. */
function blockedReason(selection: DeletionSelection, count: number): string | null {
    if ('allInDeck' in selection) return count > BULK_DELETE_MAX || selection.except.length > BULK_DELETE_MAX ? TOO_LARGE_MESSAGE : null;
    return selection.itemIds.length > BULK_DELETE_EXPLICIT_MAX ? TOO_MANY_EXPLICIT_MESSAGE : null;
}
