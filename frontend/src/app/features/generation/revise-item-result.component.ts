import {
    ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, effect, inject, input, signal, untracked
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { CAPABILITIES_UNAVAILABLE, LearningCapabilities } from '../authoring/capabilities-api.service';
import { NativeDocument } from '../../content/native-document';
import { ProposalDocumentComponent } from './proposal-document.component';
import { artifactStatus, failureNote, failureReason, turnFailureReason } from './generation-view';
import { ArtifactSummary, MAX_EDIT_TARGETS, SessionState, allows, isApprovable } from './generation.models';
import { DiffParagraph, blocksOf, diffLines, hasChanges, isMediaKind } from './word-diff';
import { DetailEntry, WorkshopSessionStore } from './workshop-session.store';

type DiffState =
    | { readonly phase: 'idle' }
    | { readonly phase: 'loading' }
    | { readonly phase: 'error' }
    | { readonly phase: 'ready'; readonly paragraphs: readonly DiffParagraph[]; readonly changed: boolean };

/**
 * The result of a `REVISE_ITEM` session (AI-16, #294): the material as Мнема rewrote it, with the card that decides what happens to it.
 * «Оставить» saves it as the next revision of the material (an ordinary revise: the old text stays in the history), «Вернуть» goes back to
 * the text the revision started from, «Ещё раз» asks the same of the text on screen again. What changed is shown as a word diff against the
 * original; the document below is the #293 proposal document, so a fragment can still be rewritten or reverted from its history. The
 * strip of a fragment edit is off here: this card is the one place that says «Оставить».
 */
@Component({
    selector: 'app-revise-item-result',
    imports: [RouterLink, ProposalDocumentComponent],
    templateUrl: './revise-item-result.component.html',
    styleUrls: ['../authoring/authoring-page.css', './revise-result.css'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ReviseItemResultComponent {
    readonly artifact = input.required<ArtifactSummary>();
    readonly deckId = input.required<string>();
    readonly sessionState = input.required<SessionState>();
    readonly entry = input<DetailEntry | null>(null);
    /** A command on this artifact is in flight. */
    readonly busy = input(false);
    readonly capabilities = input<LearningCapabilities>(CAPABILITIES_UNAVAILABLE);

    protected readonly store = inject(WorkshopSessionStore);
    private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private diffToken = 0;

    protected readonly headingId = 'revision-heading';
    protected readonly status = computed(() => artifactStatus(this.artifact()));
    protected readonly detail = computed(() => this.entry()?.detail ?? null);
    protected readonly document = computed<NativeDocument | null>(() => {
        const payload = this.detail()?.revision?.payload;
        return payload?.kind === 'NATIVE_DOCUMENT' ? payload.document : null;
    });
    /** What the user asked for, as the session echoes it. */
    protected readonly request = computed(() => this.store.session()?.spec.instruction ?? null);
    /** The text the revision started from: the first revision of the draft is the copy of the material, made without a model. */
    protected readonly originalId = computed(() => this.detail()?.revisions[0]?.revisionId ?? null);
    protected readonly shownIsCurrent = computed(() => {
        const artifact = this.artifact();
        return artifact.currentRevisionId !== null && this.detail()?.currentRevisionId === artifact.currentRevisionId;
    });
    /** The text now differs from the original: there is something to keep, and something to give back. */
    protected readonly changedFromOriginal = computed(() => this.originalId() !== null && this.artifact().currentRevisionId !== this.originalId());
    protected readonly writing = computed(() => ['QUEUED', 'GENERATING', 'REVISING'].includes(this.artifact().state));
    /** The last turn, when it failed or was stopped: the text is where it was and nothing was charged. */
    protected readonly lastTurn = computed(() => this.detail()?.turns.at(-1) ?? null);
    protected readonly failure = computed(() => {
        const turn = this.lastTurn();
        if (turn === null || (turn.status !== 'FAILED' && turn.status !== 'CANCELLED') || this.artifact().state !== 'PROPOSED') return null;
        return turn.status === 'CANCELLED' ? 'Правка остановлена.' : turnFailureReason(turn.errorCode);
    });
    protected readonly canApprove = computed(() => allows(this.sessionState(), this.artifact().state, 'approveArtifact') && isApprovable(this.artifact()));
    protected readonly canReject = computed(() => allows(this.sessionState(), this.artifact().state, 'rejectArtifact'));
    protected readonly canUndoReject = computed(() => allows(this.sessionState(), this.artifact().state, 'undoRejectArtifact'));
    protected readonly canRevert = computed(() => this.shownIsCurrent() && allows(this.sessionState(), this.artifact().state, 'revertArtifact'));
    protected readonly canAskAgain = computed(() => this.shownIsCurrent() && this.sessionState() !== 'CANCELLED'
        && allows(this.sessionState(), this.artifact().state, 'editArtifact') && this.request() !== null && this.blockIds().length > 0
        && this.blockIds().length <= MAX_EDIT_TARGETS);
    private readonly blockIds = computed(() => {
        const document = this.document();
        return document === null ? [] : blocksOf(document).filter(block => !isMediaKind(block.kind)).map(block => block.id);
    });
    protected readonly published = computed(() => {
        const ref = this.artifact().publishedRef;
        return ref?.kind === 'ITEM' ? ref : null;
    });
    protected readonly failureText = computed(() => failureReason(this.artifact().errorCode));
    protected readonly failureNote = computed(() => failureNote(this.artifact().errorCode));
    protected readonly diff = signal<DiffState>({ phase: 'idle' });
    protected readonly diffKey = computed(() => {
        const original = this.originalId();
        const current = this.artifact().currentRevisionId;
        return !this.shownIsCurrent() || original === null || current === null || current === original ? null : `${original}:${current}`;
    });
    protected readonly approveWait = computed(() => {
        const artifact = this.artifact();
        if (artifact.state !== 'PROPOSED') return null;
        return this.shownIsCurrent() ? null : 'Материал обновился. Загружаем новую версию…';
    });

    constructor() {
        // The word diff of the original and the text on screen, read once per pair of revisions.
        effect(() => {
            const key = this.diffKey();
            untracked(() => {
                if (key === null) { this.diffToken++; this.diff.set({ phase: 'idle' }); return; }
                void this.computeDiff();
            });
        });
    }

    protected async keep(): Promise<void> {
        if (this.busy() || !this.canApprove() || !this.shownIsCurrent()) return;
        if (await this.store.approve(this.artifact().artifactId)) this.focusHeading();
    }

    protected async giveBack(): Promise<void> {
        const original = this.originalId();
        if (this.busy() || !this.canRevert() || original === null || !this.changedFromOriginal()) return;
        if (await this.store.revert(this.artifact().artifactId, original)) this.focusHeading();
    }

    /**
     * The same request, asked again of the text that is on screen (it compounds, like «Ещё раз» of a fragment): a command of its own named
     * by the last turn and the revision shown, so an answer that never arrived is repeated, not charged twice.
     */
    protected async again(): Promise<void> {
        const request = this.request();
        const turn = this.lastTurn();
        if (this.busy() || !this.canAskAgain() || request === null) return;
        const outcome = await this.store.edit(this.artifact().artifactId, { action: 'FREE', nodeIds: this.blockIds(), anchorBefore: null, anchorAfter: null,
            instruction: request, againOf: turn?.turnId ?? 'first' });
        if (!outcome.ok && !outcome.aborted) this.store.notify(outcome.message);
        else if (outcome.ok) this.focusHeading();
    }

    protected async reject(): Promise<void> {
        if (this.busy() || !this.canReject()) return;
        if (await this.store.reject(this.artifact().artifactId)) this.focusHeading();
    }

    protected async undoReject(): Promise<void> {
        if (this.busy() || !this.canUndoReject()) return;
        if (await this.store.undoReject(this.artifact().artifactId)) this.focusHeading();
    }

    protected reload(): void {
        this.store.loadDetail(this.artifact().artifactId);
    }

    focusHeading(): void {
        afterNextRender(() => this.element.nativeElement.querySelector<HTMLElement>('h2')?.focus(), { injector: this.injector });
    }

    private async computeDiff(): Promise<void> {
        const token = ++this.diffToken;
        const original = this.originalId();
        const current = this.document();
        if (original === null || current === null) return;
        this.diff.set({ phase: 'loading' });
        const before = await this.store.loadRevision(this.artifact().artifactId, original);
        if (token !== this.diffToken) return;
        if (before === null) { this.diff.set({ phase: 'error' }); return; }
        const lines = (document: NativeDocument): string[] => blocksOf(document).filter(block => !isMediaKind(block.kind)).flatMap(block => block.lines);
        const language = current.root.attrs['lang'];
        const paragraphs = diffLines(lines(before), lines(current), typeof language === 'string' ? language : undefined);
        this.diff.set({ phase: 'ready', paragraphs, changed: hasChanges(paragraphs) });
    }
}
