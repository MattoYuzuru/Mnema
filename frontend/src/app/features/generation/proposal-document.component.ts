import {
    ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, TemplateRef, afterNextRender, computed, effect, inject, input, signal,
    untracked, viewChild
} from '@angular/core';

import { NativeDocument } from '../../content/native-document';
import { BlockOverlay, BlockSlotContext } from '../../content/rendering/native-top-block.directive';
import { NativeMediaSurfaceComponent } from '../../content/rendering/native-media-surface.component';
import { ToggletipComponent } from '../../shared/toggletip.component';
import { CAPABILITIES_UNAVAILABLE, Capability, LearningCapabilities } from '../authoring/capabilities-api.service';
import { AiPromptAsk, AiPromptWindowComponent, AnchorRect } from './ai-prompt-window.component';
import { describeTurnAsk, describeTurnStatus, formatWorkshopStart, mediaActionReason, turnFailureReason } from './generation-view';
import { ArtifactDetail, ArtifactSummary, ArtifactTurn, EditAction, SessionState, allows } from './generation.models';
import { SelectionTarget, clearTarget, endRect, paintTarget, readSelection, runBetween } from './selection-targets';
import { EditMemo, WorkshopSessionStore } from './workshop-session.store';
import { DiffParagraph, blocksOf, diffLines, hasChanges, isMediaKind } from './word-diff';

/** The strip under a rewritten range, in either of its two shapes. */
interface Review {
    readonly kind: 'applied' | 'failed';
    readonly memo: EditMemo;
    readonly turn: ArtifactTurn;
    /** The blocks of the current revision the rewrite touched, in order. */
    readonly range: readonly string[];
    /** Whether «Вернуть» can go to the revision the rewrite started from. */
    readonly canUndo: boolean;
}

type DiffState =
    | { readonly phase: 'idle' }
    | { readonly phase: 'loading' }
    | { readonly phase: 'error' }
    | { readonly phase: 'ready'; readonly paragraphs: readonly DiffParagraph[]; readonly changed: boolean };

interface HistoryEntry {
    readonly key: string;
    readonly label: string;
    readonly status: string | null;
    readonly time: string | null;
    readonly datetime: string | null;
    readonly current: boolean;
    /** The revision «Вернуть к этой версии» goes to; `null` when it cannot (the current one, or a turn that made none). */
    readonly revertTo: string | null;
}

let nextDocument = 0;
const HOLD_MS = 800;

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
    imports: [NativeMediaSurfaceComponent, AiPromptWindowComponent, ToggletipComponent],
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
    private destroyed = false;

    protected readonly imageActions = [
        { key: 'search', edit: 'IMAGE_SEARCH', label: 'Найти похожее' },
        { key: 'generate', edit: 'IMAGE_GENERATE', label: 'Создать' }
    ] as const satisfies readonly { key: 'search' | 'generate'; edit: EditAction; label: string }[];
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
    protected readonly canMedia = computed(() => this.proposed() && !this.busy());
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
    /** The strip of the last rewrite made on this page: «Переписано» with its four actions, or why it failed. */
    protected readonly review = computed<Review | null>(() => {
        const memo = this.memo();
        const detail = this.detail();
        if (memo === null || memo.dismissed || detail === null || this.artifact().state !== 'PROPOSED' || !this.shownIsCurrent()) return null;
        const turn = detail.turns.find(held => held.turnId === memo.turnId);
        if (turn === undefined) return null;
        const order = this.order();
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
        return ids;
    });
    private readonly beforeIds = computed<ReadonlySet<string>>(() => {
        const start = this.diffStart();
        return start === null ? new Set() : new Set([start]);
    });
    protected readonly overlay = computed<BlockOverlay>(() => ({ busy: this.busyIds(), marked: this.marked(), hidden: this.hiddenIds(),
        before: this.beforeIds(), after: this.afterIds(), template: this.slot() }));
    protected readonly history = computed<readonly HistoryEntry[]>(() => {
        const detail = this.detail();
        if (detail === null || detail.turns.length === 0) return [];
        const current = this.artifact().currentRevisionId;
        const listed = new Set(detail.revisions.map(revision => revision.revisionId));
        const target = (revisionId: string | null): string | null => revisionId !== null && revisionId !== current && listed.has(revisionId) ? revisionId : null;
        const first = detail.revisions[0];
        const entries: HistoryEntry[] = [];
        if (first !== undefined) {
            entries.push({ key: first.revisionId, label: 'Исходная версия', status: null, time: formatWorkshopStart(first.createdAt),
                datetime: first.createdAt, current: first.revisionId === current, revertTo: target(first.revisionId) });
        }
        for (const turn of detail.turns) {
            entries.push({ key: turn.turnId, label: describeTurnAsk(turn), status: describeTurnStatus(turn), time: formatWorkshopStart(turn.createdAt),
                datetime: turn.createdAt, current: turn.status === 'APPLIED' && turn.resultRevisionId === current,
                revertTo: turn.status === 'APPLIED' ? target(turn.resultRevisionId) : null });
        }
        return entries;
    });
    protected readonly failure = computed(() => {
        const review = this.review();
        if (review === null || review.kind !== 'failed') return null;
        return review.turn.status === 'CANCELLED' ? 'Правка остановлена.' : turnFailureReason(review.turn.errorCode);
    });

    constructor() {
        const destroy = inject(DestroyRef);
        afterNextRender(() => this.listen(destroy));
        // Nothing can be asked once the proposal stops being editable (a rewrite started, the session ended): close what is open. A window
        // that is explaining a refusal stays until the user closes it: the refusal may be the very reason the proposal stopped being editable.
        effect(() => {
            if (this.rewritable()) return;
            untracked(() => { if (this.phase() !== 'window' || this.windowError() === null) this.reset(false); });
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
        const onKeyUp = (event: KeyboardEvent): void => { if (this.menuKey(event) && this.phase() !== 'idle') event.preventDefault(); };
        document.addEventListener('selectionchange', onSelection);
        document.addEventListener('pointerdown', onPointer, true);
        document.addEventListener('keydown', onKey, true);
        document.addEventListener('keyup', onKeyUp, true);
        window.addEventListener('scroll', onReposition, { capture: true, passive: true });
        window.addEventListener('resize', onReposition);
        destroy.onDestroy(() => {
            coarse?.removeEventListener('change', onCoarse);
            document.removeEventListener('selectionchange', onSelection);
            document.removeEventListener('pointerdown', onPointer, true);
            document.removeEventListener('keydown', onKey, true);
            document.removeEventListener('keyup', onKeyUp, true);
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
        const next = this.canRewrite()
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
        setTimeout(() => { if (document.activeElement === document.body) this.focusHost(); });
    }

    /** The menu key, or Shift+F10: the keyboard's «context menu». */
    private menuKey(event: KeyboardEvent): boolean {
        return event.key === 'ContextMenu' || (event.key === 'F10' && event.shiftKey);
    }

    private keydown(event: KeyboardEvent): void {
        if (!this.menuKey(event) || this.phase() !== 'group') return;
        event.preventDefault();
        this.openWindow();
    }

    // --- The window ---

    openWindow(): void {
        const target = this.target();
        if (target === null || this.phase() !== 'group') return;
        this.windowError.set(null);
        this.cost.set('Считаем…');
        this.phase.set('window');
        this.painted = paintTarget(target.range);
        if (!this.painted) this.marked.set(new Set(target.nodeIds));
        const token = ++this.costToken;
        const artifactId = this.artifact().artifactId;
        void this.store.editCost(artifactId, target.nodeIds.length).then(cost => {
            if (token === this.costToken && !this.destroyed) this.cost.set(cost?.text ?? null);
        });
    }

    protected async send(ask: AiPromptAsk): Promise<void> {
        const target = this.target();
        if (target === null || this.sending() || this.phase() !== 'window') return;
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

    private async computeDiff(review: Review): Promise<void> {
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
        const paragraphs = diffLines(lines(old, new Set(oldRange)), lines(this.blocks(), new Set(review.range)));
        this.diffState.set({ phase: 'ready', paragraphs, changed: hasChanges(paragraphs) });
    }

    protected keep(): void {
        this.store.dismissEdit(this.artifact().artifactId);
        this.focusHost();
    }

    protected async undo(review: Review): Promise<void> {
        if (this.busy() || !review.canUndo) return;
        const done = await this.store.revert(this.artifact().artifactId, review.memo.baseRevisionId);
        if (done) this.focusHost();
    }

    protected async again(review: Review): Promise<void> {
        if (this.busy()) return;
        const { nodeIds: _nodeIds, ...ask } = review.memo.ask;
        const outcome = await this.store.edit(this.artifact().artifactId, { ...ask, nodeIds: review.range, again: true });
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

    protected revertTo(entry: HistoryEntry): void {
        if (entry.revertTo === null || this.busy() || !this.canRevert()) return;
        void this.store.revert(this.artifact().artifactId, entry.revertTo);
    }
}

/** A short name of a media block for the label of its actions: the image's description or the audio's title. */
function mediaName(attrs: Readonly<Record<string, unknown>>): string {
    const text = typeof attrs['alt'] === 'string' ? attrs['alt'] : typeof attrs['title'] === 'string' ? attrs['title'] : '';
    return text.length > 60 ? `${text.slice(0, 59).trimEnd()}…` : text;
}
