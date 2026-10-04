import {
    ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, TemplateRef, afterNextRender, computed, effect, inject, input, signal,
    untracked, viewChild
} from '@angular/core';

import { NativeDocument } from '../../content/native-document';
import { BlockOverlay, BlockSlotContext } from '../../content/rendering/native-top-block.directive';
import { NativeMediaSurfaceComponent } from '../../content/rendering/native-media-surface.component';
import { ToggletipComponent } from '../../shared/toggletip.component';
import { CAPABILITIES_UNAVAILABLE, Capability, LearningCapabilities } from '../authoring/capabilities-api.service';
import { AiPromptAsk, AiPromptWindowComponent } from './ai-prompt-window.component';
import { EditHistoryComponent } from './edit-history.component';
import { IMAGE_SEARCH_RUNNING, attributionLine, mediaActionReason, slotFailureReason, turnFailureReason } from './generation-view';
import { AnchorRect, placeNear, viewport } from './place-near';
import { ArtifactDetail, ArtifactSummary, ArtifactTurn, EditAction, ImageCandidate, MediaSlot, SessionState, allows } from './generation.models';
import { ImageSearchPanelComponent } from './image-search-panel.component';
import { ImageVariantsComponent } from './image-variants.component';
import { SelectionTarget, clearTarget, endRect, paintTarget, readSelection, runBetween } from './selection-targets';
import { EditMemo, WorkshopSessionStore } from './workshop-session.store';
import { DiffParagraph, blocksOf, diffLines, hasChanges, isMediaKind } from './word-diff';

/** The blocks of the current revision a review (the strip under them) is about, in order. */
interface ReviewBase {
    readonly turn: ArtifactTurn;
    readonly range: readonly string[];
}

/** A rewrite made in this page that is now the shown revision. */
interface AppliedReview extends ReviewBase {
    readonly kind: 'applied';
    readonly memo: EditMemo;
    /** Whether «Вернуть» can go to the revision the rewrite started from. */
    readonly canUndo: boolean;
}

/** A rewrite that failed or was stopped; `memo` is `null` when the page learned of it by reading the turns (after a reload). */
interface FailedReview extends ReviewBase {
    readonly kind: 'failed';
    readonly memo: EditMemo | null;
    readonly canUndo: false;
}

type Review = AppliedReview | FailedReview;

/**
 * The inline panel of «Найти похожее» (AI-10, #296), open under one image. `origin` names the control focus returns to; `sent` is set
 * once the server accepted the search: the panel then stays, disabled, until the turn ends.
 */
interface SearchPanel {
    readonly nodeId: string;
    readonly query: string;
    readonly origin: string;
    readonly sent: boolean;
}

type DiffState =
    | { readonly phase: 'idle' }
    | { readonly phase: 'loading' }
    | { readonly phase: 'error' }
    | { readonly phase: 'ready'; readonly paragraphs: readonly DiffParagraph[]; readonly changed: boolean };

let nextDocument = 0;
const HOLD_MS = 800;
/** The floating group is small and its size is known well enough to place it (it grows by one line when it explains itself). */
const GROUP_SIZE = { width: 300, height: 64 } as const;

/**
 * The material of a proposal as the Workshop shows it, and everything that edits it in place (AI-11, #293): a selection of text
 * offers «Попросить Мнему…» (a small window under the selection, or a bar and a bottom sheet on a touch screen), the blocks being
 * rewritten say so, the rewritten range carries the strip «Переписано · Показать изменения · Оставить · Вернуть · Ещё раз» with an
 * inline word diff, image and audio blocks carry their own actions, and the history of the edits can be reopened.
 *
 * It draws through the renderer's block slots, so the markup of the material is the renderer's own; node ids are exposed here and
 * nowhere else. The selection is read from the DOM by node id (`readSelection`), the commands go to the page's store, and focus
 * returns to this component's host when the window or the strip goes away. Nothing here announces by itself: the end of an edit
 * reaches the summary line through the store.
 */
@Component({
    selector: 'app-proposal-document',
    imports: [NativeMediaSurfaceComponent, AiPromptWindowComponent, ToggletipComponent, EditHistoryComponent, ImageSearchPanelComponent, ImageVariantsComponent],
    templateUrl: './proposal-document.component.html',
    styleUrls: ['../authoring/authoring-page.css', './proposal-document.component.css'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProposalDocumentComponent {
    readonly artifact = input.required<ArtifactSummary>();
    /** The revision on screen. */
    readonly document = input.required<NativeDocument>();
    readonly detail = input<ArtifactDetail | null>(null);
    readonly sessionState = input.required<SessionState>();
    readonly capabilities = input<LearningCapabilities>(CAPABILITIES_UNAVAILABLE);
    /** A command on this artifact is in flight. */
    readonly busy = input(false);
    /**
     * The strip under a rewritten range («Переписано · Оставить · Вернуть · Ещё раз»). A revision of a whole material (AI-16, #294) draws its own
     * result card above the document, where «Оставить» saves the new version, so the strip's own «Оставить» would mean something else.
     */
    readonly showStrip = input(true);

    private readonly store = inject(WorkshopSessionStore);
    private readonly injector = inject(Injector);
    private readonly host = viewChild.required<ElementRef<HTMLElement>>('host');
    private readonly slot = viewChild.required<TemplateRef<BlockSlotContext>>('slot');
    private readonly group = viewChild<ElementRef<HTMLElement>>('group');
    private readonly uid = `mn-document-${nextDocument++}`;
    private frame = 0;
    private costToken = 0;
    private diffToken = 0;
    private abort: AbortController | null = null;
    private painted = false;
    private holdUntil = 0;
    private menuKeyHandled = false;
    private destroyed = false;

    /** The image search panel, open under one image (AI-10). */
    protected readonly panel = signal<SearchPanel | null>(null);
    protected readonly panelError = signal<string | null>(null);
    protected readonly panelSending = signal(false);
    /** The candidate whose choice is being sent, and the refusal of the last choice (by node id). */
    protected readonly selecting = signal<string | null>(null);
    protected readonly selectionError = signal<{ readonly nodeId: string; readonly message: string } | null>(null);
    protected readonly searchRunning = IMAGE_SEARCH_RUNNING;
    /** What the end of a search says, in one polite region that lives as long as the document (the panel that was open is gone by then). */
    protected readonly announcement = signal('');
    /** A search started with «Повторить» (no panel): focus is restored when it ends. */
    private readonly retrying = signal<string | null>(null);
    private watchedTurn: string | null = null;
    protected readonly diffId = `${this.uid}-diff`;
    protected readonly phase = signal<'idle' | 'group' | 'window'>('idle');
    protected readonly target = signal<SelectionTarget | null>(null);
    /** The anchor the window is placed against; it follows the selection while the page scrolls. */
    protected readonly anchor = signal<AnchorRect | null>(null);
    protected readonly coarse = signal(false);
    protected readonly instruction = signal('');
    protected readonly sending = signal(false);
    protected readonly windowError = signal<string | null>(null);
    protected readonly cost = signal<string | null>(null);
    /** What the estimate said about the budget, once it has: the reason a rewrite cannot start, if it cannot. */
    protected readonly limit = signal<string | null>(null);
    protected readonly showDiff = signal(false);
    protected readonly diffState = signal<DiffState>({ phase: 'idle' });
    protected readonly marked = signal<ReadonlySet<string>>(new Set());

    protected readonly blocks = computed(() => blocksOf(this.document()));
    private readonly order = computed(() => this.blocks().map(block => block.id));
    private readonly kinds = computed(() => new Map(this.blocks().map(block => [block.id, block.kind] as const)));
    private readonly names = computed(() => new Map(this.document().root.content.map(node => [node.id, mediaName(node.attrs)] as const)));
    protected readonly isExercise = computed(() => this.artifact().targetKind === 'EXERCISE');
    /** The document on screen is the one the server holds as current: only then can an edit name its blocks. */
    protected readonly shownIsCurrent = computed(() => {
        const artifact = this.artifact();
        return artifact.currentRevisionId !== null && this.detail()?.currentRevisionId === artifact.currentRevisionId;
    });
    private readonly proposed = computed(() => !this.isExercise() && this.artifact().state === 'PROPOSED' && this.shownIsCurrent()
        && allows(this.sessionState(), 'PROPOSED', 'editArtifact'));
    /** The proposal can still be rewritten: it is proposed, in a session that can still run a model. */
    private readonly rewritable = computed(() => this.proposed() && this.sessionState() !== 'CANCELLED');
    /** A selection offers a rewrite: nothing else is running on the artifact. */
    protected readonly canRewrite = computed(() => this.rewritable() && !this.busy());
    /** A rewrite of this very material is running (here or in another tab): a selection is met with a disabled group that says so. */
    private readonly revising = computed(() => !this.isExercise() && this.artifact().state === 'REVISING' && this.shownIsCurrent()
        && allows(this.sessionState(), 'PROPOSED', 'editArtifact') && this.sessionState() !== 'CANCELLED');
    private readonly selectable = computed(() => this.rewritable() || this.revising());
    /** The group is drawn but cannot open the window: a rewrite is running or another command is in flight. */
    protected readonly groupDisabled = computed(() => !this.canRewrite());
    protected readonly groupReason = computed(() => this.revising() ? 'Мнема ещё переписывает этот материал'
        : this.busy() ? 'Подождите: предыдущее действие ещё выполняется' : null);
    protected readonly groupPlace = computed(() => placeNear(this.anchor(), GROUP_SIZE, viewport(), 6));
    protected readonly reasonId = `${this.uid}-reason`;
    protected readonly canMedia = computed(() => this.proposed() && !this.busy());
    /** A search runs a worker, so a stopped session takes none (only REMOVE_MEDIA is free); the buttons stay and say why. */
    protected readonly searchAllowed = computed(() => this.sessionState() !== 'CANCELLED' && this.capabilityOf('search').available);
    protected readonly searchReason = computed(() => this.sessionState() === 'CANCELLED'
        ? 'Работа остановлена: новый поиск изображений не запустится. Можно убрать блок.' : this.reasonOf('search'));
    /** The image slots of mode `search` of the shown revision by node id (a `generate` slot keeps the actions it had): their candidates, attribution and failure. */
    private readonly slots = computed(() => new Map((this.detail()?.mediaSlots ?? [])
        .filter(slot => slot.kind === 'IMAGE' && slot.mode === 'search' && slot.state !== 'REMOVED').map(slot => [slot.nodeId, slot] as const)));
    /** The node a search turn is queued or running on: the panel under it waits for the end. */
    protected readonly searchTurnRunning = computed(() => this.pending()?.action === 'IMAGE_SEARCH');
    protected readonly searchingNode = computed(() => {
        const turn = this.pending();
        return turn?.action === 'IMAGE_SEARCH' ? turn.targetNodeIds[0] ?? null : null;
    });
    /** The artifact can go back to another revision (its state and its session's allow it); a command in flight only makes the actions wait. */
    private readonly revertable = computed(() => this.shownIsCurrent() && !this.isExercise()
        && allows(this.sessionState(), this.artifact().state, 'revertArtifact'));
    protected readonly canRevert = computed(() => this.revertable() && !this.busy());
    protected readonly mode = computed<'popover' | 'sheet'>(() => this.coarse() ? 'sheet' : 'popover');

    private readonly memo = computed(() => this.store.edits()[this.artifact().artifactId] ?? null);
    private readonly pending = computed(() => this.detail()?.turns.find(turn => (turn.status === 'QUEUED' || turn.status === 'RUNNING')
        && turn.action !== 'REMOVE_MEDIA') ?? null);
    private readonly busyIds = computed<ReadonlySet<string>>(() => {
        const ids = new Set(this.pending()?.targetNodeIds ?? []);
        return new Set(this.order().filter(id => ids.has(id)));
    });
    protected readonly busyLast = computed(() => this.order().filter(id => this.busyIds().has(id)).at(-1) ?? null);
    /**
     * The strip under the last rewrite: «Переписано» with its four actions (only for a rewrite this page made, because only it knows the
     * revision the rewrite started from), or why a rewrite failed (also for one the page only read about, after a reload).
     * TODO(#293 follow-up): an applied rewrite read after a reload has no strip, only its entry in the history, because `turns[]` carry
     * no base revision. When the backend adds `baseRevisionId` to the turn, derive the applied strip, its diff and «Вернуть» from it too
     * and drop the memo; until then do not guess the base from the order of `revisions[]`: after a revert and a new edit it is wrong.
     */
    protected readonly review = computed<Review | null>(() => {
        const memo = this.memo();
        const detail = this.detail();
        if (!this.showStrip() || detail === null || this.artifact().state !== 'PROPOSED' || !this.shownIsCurrent()) return null;
        const order = this.order();
        if (memo === null) return this.readFailure(detail, order);
        if (memo.dismissed) return null;
        const turn = detail.turns.find(held => held.turnId === memo.turnId);
        if (turn === undefined) return null;
        const own = new Set(memo.ask.nodeIds);
        if (turn.status === 'APPLIED' && turn.resultRevisionId === this.artifact().currentRevisionId) {
            let range = runBetween(order, memo.ask.anchorBefore, memo.ask.anchorAfter);
            if (range.length === 0) range = order.filter(id => own.has(id));
            if (range.length === 0) return null;
            return { kind: 'applied', memo, turn, range, canUndo: this.revertable() && detail.revisions.some(revision => revision.revisionId === memo.baseRevisionId) };
        }
        if (turn.status === 'FAILED' || turn.status === 'CANCELLED') {
            const range = order.filter(id => own.has(id));
            return range.length === 0 ? null : { kind: 'failed', memo, turn, range, canUndo: false };
        }
        return null;
    });
    private readonly reviewKey = computed(() => {
        const review = this.review();
        return review === null || review.kind !== 'applied' ? null : `${review.turn.turnId}:${review.range.join()}`;
    });
    protected readonly reviewLast = computed(() => this.review()?.range.at(-1) ?? null);
    private readonly reviewText = computed(() => {
        const review = this.review();
        return review === null ? [] : (review.range.filter(id => !isMediaKind(this.kinds().get(id))));
    });
    /** While the diff is shown the rewritten text blocks give way to it; media blocks of the range stay where they are. */
    private readonly hiddenIds = computed<ReadonlySet<string>>(() => this.showDiff() && this.diffState().phase === 'ready'
        ? new Set(this.reviewText()) : new Set());
    protected readonly diffStart = computed(() => this.hiddenIds().size === 0 ? null : this.reviewText()[0] ?? null);
    protected readonly mediaIds = computed<ReadonlySet<string>>(() => !this.canMedia() ? new Set()
        : new Set(this.blocks().filter(block => block.kind === 'image' || block.kind === 'audio').map(block => block.id)));
    private readonly afterIds = computed<ReadonlySet<string>>(() => {
        const ids = new Set(this.mediaIds());
        const last = this.reviewLast();
        const busy = this.busyLast();
        if (last !== null) ids.add(last);
        if (busy !== null) ids.add(busy);
        // Under an image: its attribution, the panel of a search, the variants it found and why it failed.
        for (const [nodeId, slot] of this.slots()) {
            if (slot.attribution !== null || slot.state === 'FAILED' || this.variantsOf(slot).length > 1) ids.add(nodeId);
        }
        const panel = this.panel();
        if (panel !== null) ids.add(panel.nodeId);
        return ids;
    });
    private readonly beforeIds = computed<ReadonlySet<string>>(() => {
        const start = this.diffStart();
        return start === null ? new Set() : new Set([start]);
    });
    protected readonly overlay = computed<BlockOverlay>(() => ({ busy: this.busyIds(), marked: this.marked(), hidden: this.hiddenIds(),
        before: this.beforeIds(), after: this.afterIds(), template: this.slot() }));
    protected readonly failure = computed(() => {
        const review = this.review();
        if (review === null || review.kind !== 'failed') return null;
        if (review.turn.action === 'IMAGE_SEARCH') {
            return review.turn.status === 'CANCELLED' ? 'Поиск остановлен.' : imageSearchFailure(review.turn.errorCode);
        }
        return review.turn.status === 'CANCELLED' ? 'Правка остановлена.' : turnFailureReason(review.turn.errorCode);
    });
    /** What did not change when an edit failed: the strip says it in the words of what was asked. */
    protected readonly unchanged = computed(() => this.review()?.turn.action === 'IMAGE_SEARCH' ? 'Изображение не изменилось' : 'Текст не изменился');

    /** The last turn, when it failed or was stopped and its strip was not closed: its blocks are still where it asked for them. */
    private readFailure(detail: ArtifactDetail, order: readonly string[]): FailedReview | null {
        const turn = detail.turns.at(-1);
        if (turn === undefined || (turn.status !== 'FAILED' && turn.status !== 'CANCELLED') || (turn.action !== 'REWRITE' && turn.action !== 'FREE')
            || this.store.closedTurns().has(turn.turnId)) return null;
        const own = new Set(turn.targetNodeIds);
        const range = order.filter(id => own.has(id));
        return range.length === 0 ? null : { kind: 'failed', memo: null, turn, range, canUndo: false };
    }

    constructor() {
        const destroy = inject(DestroyRef);
        afterNextRender(() => this.listen(destroy));
        // Nothing can be asked once the proposal stops being editable (a rewrite started, the session ended): close what is open. A window
        // that is explaining a refusal stays until the user closes it: the refusal may be the very reason the proposal stopped being editable.
        effect(() => {
            if (this.rewritable()) return;
            const revising = this.revising();
            untracked(() => {
                if (this.phase() === 'window' && this.windowError() !== null) return;
                // The disabled group of a running rewrite stays; a window that was open for the old state goes.
                if (revising && this.phase() === 'group') return;
                this.reset(false);
            });
        });
        // The panel of a search goes when the search it started ends, or when the proposal can no longer be searched (it keeps focus's place).
        effect(() => {
            const panel = this.panel();
            if (panel === null) return;
            // The artifact stays REVISING until the search ends, even while a stale read of its detail does not list the turn yet.
            const searching = this.searchingNode() === panel.nodeId || this.artifact().state === 'REVISING';
            const open = this.proposed() || searching;
            untracked(() => {
                if (panel.sent && !searching) this.closePanel(true);
                else if (!panel.sent && !open) this.closePanel(false);
            });
        });
        // The end of a search is announced once, and a retry (no panel) gives focus back to what the search made.
        effect(() => {
            const turn = this.pending();
            const detail = this.detail();
            const retrying = this.retrying();
            const running = turn?.action === 'IMAGE_SEARCH' ? turn : null;
            const state = this.artifact().state;
            untracked(() => {
                if (running !== null) { this.watchedTurn = running.turnId; this.announcement.set(''); return; }
                if (retrying !== null && state !== 'REVISING') { this.retrying.set(null); this.focusAfterSearch(retrying, `retry:${retrying}`); }
                const id = this.watchedTurn;
                const done = id === null ? undefined : detail?.turns.find(held => held.turnId === id);
                if (done === undefined || done.status === 'QUEUED' || done.status === 'RUNNING') return;
                this.watchedTurn = null;
                this.announcement.set(this.endOfSearch(done));
            });
        });
        // The group is a popover in the top layer: it has to be shown when it exists.
        effect(() => {
            const element = this.group()?.nativeElement;
            if (element !== undefined && typeof element.showPopover === 'function') {
                try { element.showPopover(); } catch { /* already open */ }
            }
        });
        // A new rewrite starts with its diff closed.
        effect(() => {
            const turnId = this.memo()?.turnId ?? null;
            untracked(() => { void turnId; this.diffToken++; this.showDiff.set(false); this.diffState.set({ phase: 'idle' }); });
        });
        // The previous version is read once, when the diff is asked for (not again when the artifact is only read anew).
        effect(() => {
            const key = this.reviewKey();
            if (!this.showDiff() || key === null) return;
            untracked(() => { const review = this.review(); if (review !== null && review.kind === 'applied') void this.computeDiff(review); });
        });
        inject(DestroyRef).onDestroy(() => {
            this.destroyed = true;
            this.abort?.abort();
            clearTarget();
            if (this.frame !== 0) cancelAnimationFrame(this.frame);
        });
    }

    // --- Selection ---

    private listen(destroy: DestroyRef): void {
        const coarse = typeof window.matchMedia === 'function' ? window.matchMedia('(pointer: coarse)') : null;
        this.coarse.set(coarse?.matches === true);
        const onCoarse = (event: MediaQueryListEvent): void => this.coarse.set(event.matches);
        coarse?.addEventListener('change', onCoarse);
        const onSelection = (): void => this.schedule();
        const onReposition = (): void => this.reposition();
        const onPointer = (event: Event): void => this.outside(event);
        const onKey = (event: KeyboardEvent): void => this.keydown(event);
        const onKeyUp = (event: KeyboardEvent): void => this.keyup(event);
        const onContext = (event: MouseEvent): void => this.contextmenu(event);
        document.addEventListener('selectionchange', onSelection);
        document.addEventListener('pointerdown', onPointer, true);
        document.addEventListener('keydown', onKey, true);
        document.addEventListener('keyup', onKeyUp, true);
        document.addEventListener('contextmenu', onContext, true);
        window.addEventListener('scroll', onReposition, { capture: true, passive: true });
        window.addEventListener('resize', onReposition);
        destroy.onDestroy(() => {
            coarse?.removeEventListener('change', onCoarse);
            document.removeEventListener('selectionchange', onSelection);
            document.removeEventListener('pointerdown', onPointer, true);
            document.removeEventListener('keydown', onKey, true);
            document.removeEventListener('keyup', onKeyUp, true);
            document.removeEventListener('contextmenu', onContext, true);
            window.removeEventListener('scroll', onReposition, true);
            window.removeEventListener('resize', onReposition);
        });
    }

    private schedule(): void {
        if (this.frame !== 0 || this.phase() === 'window') return;
        this.frame = requestAnimationFrame(() => { this.frame = 0; this.refresh(); });
    }

    /** Reads the selection again: the group follows it, and goes when there is none (unless keyboard focus is on the group). */
    refresh(): void {
        if (this.destroyed || this.phase() === 'window') return;
        const next = this.selectable()
            ? readSelection(this.host().nativeElement, document.getSelection(), this.order(), this.kinds()) : null;
        if (next === null) {
            // A tap on the group can clear the selection before its click arrives: keep the group for a moment after a press on it.
            if (Date.now() < this.holdUntil) return;
            const active = document.activeElement;
            if (this.phase() === 'group' && active !== null && (this.group()?.nativeElement.contains(active) ?? false)) return;
            this.clearGroup();
            return;
        }
        const before = this.target();
        if (before === null || before.nodeIds.join() !== next.nodeIds.join()) this.instruction.set('');
        this.target.set(next);
        this.anchor.set(next.rect);
        this.phase.set('group');
    }

    /** A press on the group or the bar: the selection may go with it, the group stays for the click that follows. */
    protected hold(): void {
        this.holdUntil = Date.now() + HOLD_MS;
    }

    /** The group follows its selection while the page scrolls; the window, once open, stays where it was put (it would fight its own focus). */
    private reposition(): void {
        const target = this.target();
        if (target === null || this.phase() !== 'group') return;
        this.anchor.set(endRect(target.selected));
    }

    private clearGroup(): void {
        if (this.phase() === 'idle' && this.target() === null) return;
        this.phase.set('idle');
        this.target.set(null);
    }

    private outside(event: Event): void {
        if (this.phase() !== 'window' || this.mode() === 'sheet') return;
        const node = event.target;
        if (node instanceof Element && node.closest('app-ai-prompt-window, .selection-actions')) return;
        this.closeWindow(false);
        // The click moved focus to what was clicked; when that is nothing focusable, the document keeps it.
        setTimeout(() => { if (!this.destroyed && document.activeElement === document.body) this.focusHost(); });
    }

    /** The menu key, or Shift+F10: the keyboard's «context menu». */
    private menuKey(event: KeyboardEvent): boolean {
        return event.key === 'ContextMenu' || (event.key === 'F10' && event.shiftKey);
    }

    /** Whether the keyboard's menu may open the window now: the group is there and not disabled. */
    private opensWithMenu(): boolean {
        return this.phase() === 'group' && !this.groupDisabled();
    }

    private keydown(event: KeyboardEvent): void {
        if (!this.menuKey(event) || !this.opensWithMenu()) return;
        event.preventDefault();
        // The key's release must not bring up the browser's own menu either (some platforms show it on keyup).
        this.menuKeyHandled = true;
        this.openWindow();
    }

    /** Only the release of a key whose press opened the window is taken: in the window's field the menu key keeps its meaning. */
    private keyup(event: KeyboardEvent): void {
        if (!this.menuKeyHandled || !this.menuKey(event)) return;
        this.menuKeyHandled = false;
        event.preventDefault();
    }

    /** A context menu asked for with the keyboard (no pointer position) opens the window while the group is there; a mouse's stays native. */
    private contextmenu(event: MouseEvent): void {
        const pointer = event as MouseEvent & { pointerType?: string; pointerId?: number };
        const fromKeyboard = event.button === -1 || pointer.pointerType === '' || pointer.pointerId === -1;
        if (!fromKeyboard || !this.opensWithMenu()) return;
        event.preventDefault();
        this.openWindow();
    }

    // --- The window ---

    openWindow(): void {
        const target = this.target();
        if (target === null || this.phase() !== 'group' || this.groupDisabled()) return;
        this.windowError.set(null);
        this.limit.set(null);
        this.cost.set('Считаем…');
        this.phase.set('window');
        this.painted = paintTarget(target.range);
        if (!this.painted) this.marked.set(new Set(target.nodeIds));
        const token = ++this.costToken;
        const artifactId = this.artifact().artifactId;
        void this.store.editCost(artifactId, target.nodeIds.length).then(cost => {
            if (token !== this.costToken || this.destroyed) return;
            this.cost.set(cost === null ? null : !cost.blocked ? cost.text : `${cost.text} · не хватит лимита`);
            this.limit.set(cost?.blocked || null);
        });
    }

    protected async send(ask: AiPromptAsk): Promise<void> {
        const target = this.target();
        if (target === null || this.sending() || this.phase() !== 'window') return;
        // The budget does not fit this edit: pressing explains the options (as the composer does) instead of sending a request that will fail.
        const limit = this.limit();
        if (limit !== null) { this.windowError.set(limit); return; }
        this.sending.set(true);
        this.windowError.set(null);
        const abort = this.abort = new AbortController();
        const outcome = await this.store.edit(this.artifact().artifactId, {
            action: ask.preset === null ? 'FREE' : 'REWRITE', nodeIds: target.nodeIds, anchorBefore: target.anchorBefore,
            anchorAfter: target.anchorAfter, preset: ask.preset, instruction: ask.instruction
        }, abort.signal);
        if (this.destroyed) return;
        this.abort = null;
        this.sending.set(false);
        if (outcome.ok) {
            this.instruction.set('');
            // The text is being rewritten: the selection goes, after focus has moved (focusing may collapse a selection to a caret).
            this.reset(true, () => document.getSelection()?.removeAllRanges());
        } else if (!outcome.aborted) {
            this.windowError.set(outcome.message);
        }
    }

    protected cancelSend(): void {
        this.abort?.abort();
    }

    /** Esc, «×» or a click on the backdrop: the window goes, the selection comes back and focus returns to the document. */
    protected dismiss(restoreFocus: boolean): void {
        const selected = this.target()?.selected ?? null;
        this.closeWindow(restoreFocus);
        if (restoreFocus) {
            this.focusHost(() => {
                if (selected === null) return;
                const selection = document.getSelection();
                selection?.removeAllRanges();
                selection?.addRange(selected);
            });
        }
    }

    /** Takes the window away; the group of the selection stays (`keepGroup`) when the selection does. */
    private closeWindow(keepGroup: boolean): void {
        if (this.phase() !== 'window') return;
        this.abort?.abort();
        this.abort = null;
        this.sending.set(false);
        this.costToken++;
        this.unpaint();
        this.phase.set(keepGroup ? 'group' : 'idle');
        if (!keepGroup) this.target.set(null);
    }

    private reset(focus: boolean, afterFocus?: () => void): void {
        this.abort?.abort();
        this.abort = null;
        this.sending.set(false);
        this.costToken++;
        this.unpaint();
        this.phase.set('idle');
        this.target.set(null);
        this.windowError.set(null);
        if (focus) this.focusHost(afterFocus);
    }

    private unpaint(): void {
        if (this.painted) clearTarget();
        this.painted = false;
        this.marked.set(new Set());
    }

    /** Programmatic focus on the document: where focus goes when the control that had it is gone. */
    focusHost(afterFocus?: () => void): void {
        afterNextRender(() => {
            this.host().nativeElement.focus({ preventScroll: true });
            afterFocus?.();
        }, { injector: this.injector });
    }

    // --- The strip ---

    protected toggleDiff(): void {
        this.showDiff.update(shown => !shown);
        if (!this.showDiff()) this.diffState.set({ phase: 'idle' });
    }

    private async computeDiff(review: AppliedReview): Promise<void> {
        const token = ++this.diffToken;
        this.diffState.set({ phase: 'loading' });
        const before = await this.store.loadRevision(this.artifact().artifactId, review.memo.baseRevisionId);
        if (token !== this.diffToken || this.destroyed) return;
        if (before === null) { this.diffState.set({ phase: 'error' }); return; }
        const old = blocksOf(before);
        const ids = runBetween(old.map(block => block.id), review.memo.ask.anchorBefore, review.memo.ask.anchorAfter);
        const oldRange = ids.length > 0 ? ids : review.memo.ask.nodeIds;
        const lines = (list: readonly { id: string; kind: string; lines: readonly string[] }[], range: ReadonlySet<string>): string[] =>
            list.filter(block => range.has(block.id) && !isMediaKind(block.kind)).flatMap(block => block.lines);
        const language = this.document().root.attrs['lang'];
        const paragraphs = diffLines(lines(old, new Set(oldRange)), lines(this.blocks(), new Set(review.range)),
            typeof language === 'string' ? language : undefined);
        this.diffState.set({ phase: 'ready', paragraphs, changed: hasChanges(paragraphs) });
    }

    protected keep(review: Review): void {
        this.store.dismissEdit(this.artifact().artifactId, review.turn.turnId);
        this.focusHost();
    }

    protected async undo(review: AppliedReview): Promise<void> {
        if (this.busy() || !review.canUndo) return;
        const done = await this.store.revert(this.artifact().artifactId, review.memo.baseRevisionId);
        if (done) this.focusHost();
    }

    protected async again(review: Review): Promise<void> {
        if (this.busy()) return;
        // A search is asked again through its panel, with the previous query in the field: the owner may want to change it.
        if (review.turn.action === 'IMAGE_SEARCH') {
            const nodeId = review.range[0] ?? review.turn.targetNodeIds[0];
            if (nodeId !== undefined) this.openSearch(nodeId, `again:${nodeId}`, review.turn.instruction ?? '');
            return;
        }
        // The same request on the blocks that are there now (a rewrite is asked again of the rewritten text: it compounds), as a command of
        // its own named by this turn, so an answer that never arrived is repeated, not charged twice.
        const order = this.order();
        const first = order.indexOf(review.range[0]!);
        const last = order.indexOf(review.range[review.range.length - 1]!);
        const outcome = await this.store.edit(this.artifact().artifactId, { action: review.turn.action, nodeIds: review.range,
            anchorBefore: order[first - 1] ?? null, anchorAfter: order[last + 1] ?? null, preset: review.turn.preset,
            instruction: review.turn.instruction, againOf: review.turn.turnId });
        if (!outcome.ok && !outcome.aborted) this.store.notify(outcome.message);
        else if (outcome.ok) this.focusHost();
    }

    // --- Media and history ---

    protected mediaKind(id: string): 'image' | 'audio' | null {
        const kind = this.kinds().get(id);
        return kind === 'image' || kind === 'audio' ? kind : null;
    }

    protected mediaLabel(id: string): string {
        const name = this.names().get(id);
        const what = this.mediaKind(id) === 'audio' ? 'Действия с аудио' : 'Действия с изображением';
        return name ? `${what}: ${name}` : what;
    }

    protected capabilityOf(kind: 'search' | 'generate' | 'speech'): Capability {
        const all = this.capabilities();
        return kind === 'search' ? all.imageSearch : kind === 'generate' ? all.imageGeneration : all.textToSpeech;
    }

    protected reasonOf(kind: 'search' | 'generate' | 'speech'): string {
        return mediaActionReason(kind, this.capabilityOf(kind));
    }

    protected async mediaAction(id: string, action: EditAction): Promise<void> {
        if (this.busy()) return;
        const order = this.order();
        const at = order.indexOf(id);
        const outcome = await this.store.edit(this.artifact().artifactId, { action, nodeIds: [id], anchorBefore: order[at - 1] ?? null,
            anchorAfter: order[at + 1] ?? null });
        if (this.destroyed) return;
        if (!outcome.ok && !outcome.aborted) this.store.notify(outcome.message);
        else if (outcome.ok && action === 'REMOVE_MEDIA') this.focusHost();
    }

    // --- Image search (AI-10, #296) ---

    protected slotOf(id: string): MediaSlot | null {
        return this.slots().get(id) ?? null;
    }

    /** The slot of an image whose search failed: the frame says why and offers «Повторить», «Заменить» and «Убрать блок». */
    /** Only an image of a `search` slot offers «Найти похожее», its variants and the failed-search actions. */
    protected isSearch(id: string): boolean {
        return this.slots().has(id);
    }

    protected failedSlot(id: string): MediaSlot | null {
        const slot = this.slotOf(id);
        return slot !== null && slot.state === 'FAILED' ? slot : null;
    }

    protected failureOf(slot: MediaSlot): string {
        return slotFailureReason(slot.errorCode);
    }

    /** «Фото: Ann · Pixabay · Pixabay Content License» under a READY image that was found by a search. */
    protected attributionOf(id: string): string | null {
        const slot = this.slotOf(id);
        return slot !== null && slot.state === 'READY' && slot.attribution !== null ? attributionLine(slot.attribution) : null;
    }

    /** The candidates the owner can choose among: more than one READY. */
    protected variantsOf(slot: MediaSlot): readonly ImageCandidate[] {
        const ready = slot.candidates.filter(candidate => candidate.state === 'READY');
        return ready.length > 1 ? ready : [];
    }

    /** The variants open by themselves right after a search that found them. */
    protected variantsOpen(id: string): boolean {
        const review = this.review();
        return review !== null && review.kind === 'applied' && review.turn.action === 'IMAGE_SEARCH' && review.range.includes(id);
    }

    /** The slot whose variants are offered: the proposal can change (no search is running) and the slot found more than one image. */
    protected variantsShown(id: string): MediaSlot | null {
        const slot = this.slotOf(id);
        return slot !== null && this.proposed() && this.variantsOf(slot).length > 0 ? slot : null;
    }

    protected searchLabel(id: string): string {
        return this.names().get(id) ?? '';
    }

    protected openSearch(id: string, origin: string, query = ''): void {
        if (this.busy() || !this.searchAllowed() || !this.proposed()) return;
        this.panelError.set(null);
        this.panel.set({ nodeId: id, query, origin, sent: false });
    }

    protected toggleSearch(id: string): void {
        if (this.panel()?.nodeId === id && !this.panel()!.sent) { this.cancelSearch(); return; }
        this.openSearch(id, `search:${id}`);
    }

    protected cancelSearch(): void {
        this.closePanel(true);
    }

    /** Takes the panel away; focus goes to the control it was opened from, or, after a search, to what the search made. */
    private closePanel(focus: boolean): void {
        const panel = this.panel();
        if (panel === null) return;
        this.panel.set(null);
        this.panelError.set(null);
        this.panelSending.set(false);
        if (!focus) return;
        this.focusAfterSearch(panel.nodeId, panel.origin, panel.sent);
    }

    /**
     * Gives focus back after a search: the checked variant (when a search made some), «Найти похожее», the control it started from, the
     * document. Each candidate is tried and checked (`activeElement`): a closed `<details>` or a control that went away swallows focus.
     */
    private focusAfterSearch(nodeId: string, origin: string, variants = true): void {
        afterNextRender(() => {
            if (this.destroyed) return;
            const root = this.host().nativeElement;
            const wanted = variants ? [`[data-variants="${nodeId}"] input:checked`, `[data-variants="${nodeId}"] summary`] : [];
            // A panel closed without a search goes back to the control it was opened from; a search that ended goes to «Найти похожее».
            wanted.push(...(variants ? [`[data-focus-key="search:${nodeId}"]`, `[data-focus-key="${origin}"]`] : [`[data-focus-key="${origin}"]`, `[data-focus-key="search:${nodeId}"]`]));
            for (const selector of wanted) {
                const element = root.querySelector<HTMLElement>(selector);
                element?.focus();
                if (element !== null && element !== undefined && window.document.activeElement === element) return;
            }
            root.focus({ preventScroll: true });
        }, { injector: this.injector });
    }

    private endOfSearch(turn: ArtifactTurn): string {
        if (turn.status === 'APPLIED') {
            const slot = this.slotOf(turn.targetNodeIds[0] ?? '');
            const count = slot?.candidates.filter(candidate => candidate.state === 'READY').length ?? 0;
            return count > 1 ? `Нашла ${count} ${variantsWord(count)}, выбран первый.` : 'Нашла изображение.';
        }
        const why = turn.status === 'CANCELLED' ? 'Поиск остановлен.' : imageSearchFailure(turn.errorCode);
        return `${why} Изображение не изменилось, лимит не списан.`;
    }

    protected async submitSearch(id: string, query: string): Promise<void> {
        const panel = this.panel();
        if (panel === null || panel.nodeId !== id || panel.sent || this.panelSending() || this.busy()) return;
        this.panelSending.set(true);
        this.panelError.set(null);
        const outcome = await this.runSearch(id, query.length === 0 ? null : query);
        if (this.destroyed) return;
        this.panelSending.set(false);
        if (outcome.ok) this.panel.update(held => held === null ? null : { ...held, query, sent: true });
        else if (!outcome.aborted) this.panelError.set(outcome.message);
    }

    private runSearch(id: string, instruction: string | null): ReturnType<WorkshopSessionStore['edit']> {
        const order = this.order();
        const at = order.indexOf(id);
        return this.store.edit(this.artifact().artifactId, { action: 'IMAGE_SEARCH', nodeIds: [id], anchorBefore: order[at - 1] ?? null,
            anchorAfter: order[at + 1] ?? null, instruction });
    }

    /** «Повторить» on a failed slot: the same search once more, with the slot's own query. */
    protected async retrySearch(id: string): Promise<void> {
        if (this.busy() || !this.searchAllowed()) return;
        const outcome = await this.runSearch(id, null);
        if (this.destroyed) return;
        if (!outcome.ok && !outcome.aborted) this.store.notify(outcome.message);
        else if (outcome.ok) this.retrying.set(id);
    }

    /** «Убрать блок» on a failed slot. */
    protected removeBlock(id: string): Promise<void> {
        return this.mediaAction(id, 'REMOVE_MEDIA');
    }

    protected async choose(id: string, slot: MediaSlot, candidateId: string): Promise<void> {
        if (this.busy() || this.selecting() !== null) return;
        this.selecting.set(candidateId);
        this.selectionError.set(null);
        const outcome = await this.store.selectCandidate(this.artifact().artifactId, slot.slotKey, candidateId);
        if (this.destroyed) return;
        this.selecting.set(null);
        if (!outcome.ok) this.selectionError.set({ nodeId: id, message: outcome.message });
        // The document is drawn again from the answer (or from the re-read after a refusal): focus stays on the group.
        this.focusAfterSearch(id, `search:${id}`);
    }

    protected selectionMessage(id: string): string | null {
        const error = this.selectionError();
        return error !== null && error.nodeId === id ? error.message : null;
    }

    protected revertTo(revisionId: string): void {
        if (this.busy() || !this.canRevert()) return;
        void this.store.revert(this.artifact().artifactId, revisionId);
    }
}

/** Why an image search turn failed, in words; the picture did not change and nothing was charged. */
/** «1 вариант», «2 варианта», «5 вариантов». */
function variantsWord(count: number): string {
    const last = count % 10;
    return count % 100 >= 11 && count % 100 <= 14 ? 'вариантов' : last === 1 ? 'вариант' : last >= 2 && last <= 4 ? 'варианта' : 'вариантов';
}

function imageSearchFailure(code: ArtifactTurn['errorCode']): string {
    return code === 'NO_RESULT' || code === null ? 'Не нашлось подходящих изображений.'
        : slotFailureReason(code === 'PROVIDER_UNAVAILABLE' || code === 'DEADLINE_EXCEEDED' ? code : null);
}

/** A short name of a media block for the label of its actions: the image's description or the audio's title. */
function mediaName(attrs: Readonly<Record<string, unknown>>): string {
    const text = typeof attrs['alt'] === 'string' ? attrs['alt'] : typeof attrs['title'] === 'string' ? attrs['title'] : '';
    return text.length > 60 ? `${text.slice(0, 59).trimEnd()}…` : text;
}
