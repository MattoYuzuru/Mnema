import {
    ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, signal, untracked, viewChild
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { EMPTY, Subscription, catchError, from, mergeMap } from 'rxjs';

import { Mechanic } from '../../content/exercise/exercise-content.models';
import { ExercisePreviewHostComponent } from '../authoring/exercise-preview-host.component';
import { PreviewPresentation } from '../authoring/exercise-preview.models';
import { ItemApiService } from '../authoring/item-api.service';
import { textProjections } from '../authoring/exercise.models';
import { mechanicName } from './exercise-builder';
import { ExerciseProposal, proposalPresentation, readProposal } from './exercise-proposal';
import { loadUnchecked, saveUnchecked } from './exercise-review-selection';
import { artifactStatus, exerciseFailureReason, failureNote } from './generation-view';
import { ArtifactDetail, ArtifactSummary, allows, isApprovable, isRetryable } from './generation.models';
import { DetailEntry, WorkshopSessionStore } from './workshop-session.store';

/** Details and material titles are fetched this many at a time: a batch can hold 60 exercises. */
const LOAD_CONCURRENCY = 4;
/** Published by the sticky bar so the page scrolls a focused control clear of it (WCAG 2.4.11), like the hub's bulk bar. */
const BAR_HEIGHT_PROPERTY = '--mn-bulk-bar-height';

/** One proposed (or pending, failed, saved) exercise of the batch, with what the card needs to show it. */
export interface ReviewCard {
    readonly artifact: ArtifactSummary;
    readonly entry: DetailEntry | null;
    readonly proposal: ExerciseProposal | null;
    readonly presentation: PreviewPresentation | null;
    readonly mechanic: Mechanic | null;
    readonly status: { readonly shape: string; readonly word: string };
}

/** A card in its place, with the material heading when it opens a run of that material's exercises. */
export interface ReviewEntry {
    readonly card: ReviewCard;
    readonly heading: string | null;
}

const WRITING: readonly string[] = ['QUEUED', 'GENERATING', 'REVISING'];

/**
 * Cards keep the order the server gave them (the targets in request order, then the exercises of each), so a card never moves while
 * its details load. A material heading appears above the first card whose proposal names that material; cards without a proposal yet
 * (written, failed, saved) stay where they are and need no heading of their own.
 */
export function withHeadings(cards: readonly ReviewCard[], titles: Readonly<Record<string, string>>): readonly ReviewEntry[] {
    let last: string | null = null;
    return cards.map(card => {
        const member = card.proposal?.exercise.subject.memberKey ?? null;
        if (member === null || member === last) return { card, heading: null };
        last = member;
        return { card, heading: titles[member] ?? 'Материал' };
    });
}

/**
 * The batch review of an `EXERCISES` Workshop (AI-13, #291): proposals in a stable order, each one playable in the author-preview
 * host (it never writes an attempt or a schedule), a «Оставить» choice per proposal (kept per session, so «Изменить» does not lose
 * it), and one sticky «Сохранить выбранные (N)». The store sends the commands. After a command focus stays on the next sensible
 * control of the same card, never on the page heading.
 */
@Component({
    selector: 'app-exercise-batch-review',
    imports: [RouterLink, ExercisePreviewHostComponent],
    templateUrl: './exercise-batch-review.component.html',
    styleUrl: './exercise-batch-review.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ExerciseBatchReviewComponent {
    readonly deckId = input.required<string>();

    protected readonly store = inject(WorkshopSessionStore);
    /** Proposals the user took the check off: new proposals start checked, so the choice is stored the other way round. */
    protected readonly unchecked = signal<ReadonlySet<string>>(new Set());
    protected readonly titles = signal<Readonly<Record<string, string>>>({});
    protected readonly saving = computed(() => this.store.busy().has('session'));
    protected readonly footer = viewChild<ElementRef<HTMLElement>>('footer');

    protected readonly cards = computed<readonly ReviewCard[]>(() => this.store.artifacts().map(artifact => {
        const entry = this.store.details()[artifact.artifactId] ?? null;
        const proposal = entry?.detail == null ? null : this.proposalOf(entry.detail);
        return { artifact, entry, proposal, presentation: proposal === null ? null : this.presentationOf(proposal),
            mechanic: proposal?.mechanic ?? null, status: artifactStatus(artifact) };
    }));
    protected readonly entries = computed(() => withHeadings(this.cards(), this.titles()));
    /** Proposals that «Сохранить выбранные» would save now: the ones shown as they are on the server and not unchecked. */
    protected readonly kept = computed(() => this.cards().filter(card => this.isKept(card)));
    protected readonly keptCount = computed(() => this.kept().length);
    /** Proposals the user left unchecked and that are still undecided. */
    protected readonly leftOut = computed(() => this.cards().filter(card => card.proposal !== null && card.artifact.state === 'PROPOSED'
        && this.unchecked().has(card.artifact.artifactId)));
    protected readonly savedCount = computed(() => this.store.artifacts().filter(artifact => artifact.state === 'PUBLISHED').length);
    protected readonly canSave = computed(() => {
        const session = this.store.session();
        return session !== null && allows(session.state, 'PROPOSED', 'approveArtifacts') && this.keptCount() > 0;
    });
    protected readonly empty = computed(() => this.store.artifacts().length === 0);

    private readonly items = inject(ItemApiService);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);
    private readonly titleLoads = new Subscription();
    /** Titles already asked for: a read in flight is never restarted because another one arrived. */
    private readonly requestedTitles = new Set<string>();
    private readonly proposals = new WeakMap<ArtifactDetail, ExerciseProposal | null>();
    private readonly presentations = new Map<string, PreviewPresentation>();
    private choiceSession: string | null = null;

    constructor() {
        // Details are fetched a few at a time, so a batch of 60 does not open 60 requests at once.
        effect(() => {
            const artifacts = this.store.artifacts();
            const details = this.store.details();
            untracked(() => {
                let inFlight = Object.values(details).filter(entry => entry.phase === 'loading').length;
                for (const artifact of artifacts) {
                    if (inFlight >= LOAD_CONCURRENCY) break;
                    if (this.store.needsDetail(artifact)) { this.store.loadDetail(artifact.artifactId); inFlight += 1; }
                }
            });
        });
        // The titles of the requested materials name the headings.
        effect(() => {
            const targets = this.store.session()?.spec.targets ?? [];
            const deckId = this.deckId();
            untracked(() => this.loadTitles(deckId, targets));
        });
        // The «Оставить» choice of this session, restored when the page opens again (for example after «Изменить»).
        effect(() => {
            const sessionId = this.store.session()?.sessionId ?? null;
            if (sessionId === null || sessionId === this.choiceSession) return;
            this.choiceSession = sessionId;
            untracked(() => this.unchecked.set(loadUnchecked(sessionId)));
        });
        // The sticky bar's height becomes scroll padding, so a focused card control is never hidden behind it.
        effect(onCleanup => {
            const element = this.footer()?.nativeElement;
            if (element === undefined) return;
            const root = element.ownerDocument.documentElement;
            const apply = (): void => root.style.setProperty(BAR_HEIGHT_PROPERTY, `${element.offsetHeight}px`);
            apply();
            const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(apply);
            observer?.observe(element);
            onCleanup(() => { observer?.disconnect(); root.style.removeProperty(BAR_HEIGHT_PROPERTY); });
        });
        this.destroyRef.onDestroy(() => this.titleLoads.unsubscribe());
    }

    /**
     * Kept means: a proposal that is approvable, not unchecked, and shown exactly as the server has it now (its loaded revision is
     * the artifact's current one and the load is not marked out of date), like a single approval requires.
     */
    protected isKept(card: ReviewCard): boolean {
        const proposal = card.proposal;
        return proposal !== null && isApprovable(card.artifact) && !this.unchecked().has(card.artifact.artifactId)
            && card.entry?.stale === false && proposal.revisionId === card.artifact.currentRevisionId;
    }

    protected toggleKept(artifactId: string, checked: boolean): void {
        const next = new Set(this.unchecked());
        if (checked) next.delete(artifactId); else next.add(artifactId);
        this.unchecked.set(next);
        const sessionId = this.store.session()?.sessionId;
        if (sessionId !== undefined) saveUnchecked(sessionId, next);
    }

    protected title(card: ReviewCard): string {
        const proposal = card.proposal;
        if (proposal === null) return card.artifact.title || 'Упражнение';
        return `${mechanicName(proposal.mechanic)} · ${proposal.objectiveTitle || card.artifact.title}`;
    }

    protected failureText(card: ReviewCard): string {
        const note = failureNote(card.artifact.errorCode);
        return exerciseFailureReason(card.artifact.errorCode) + (note === null ? '' : ` ${note}`);
    }

    protected canRetry(card: ReviewCard): boolean {
        const session = this.store.session();
        return session !== null && isRetryable(card.artifact) && allows(session.state, card.artifact.state, 'retryArtifact');
    }

    protected canReject(card: ReviewCard): boolean {
        const session = this.store.session();
        return session !== null && allows(session.state, card.artifact.state, 'rejectArtifact');
    }

    protected canUndo(card: ReviewCard): boolean {
        const session = this.store.session();
        return session !== null && allows(session.state, card.artifact.state, 'undoRejectArtifact');
    }

    protected busy(card: ReviewCard): boolean {
        return this.store.busy().has(card.artifact.artifactId);
    }

    protected writing(card: ReviewCard): boolean {
        return WRITING.includes(card.artifact.state);
    }

    protected materialLink(card: ReviewCard): readonly string[] | null {
        const ref = card.artifact.publishedRef;
        return ref?.kind === 'EXERCISE' ? ['/decks', this.deckId(), 'exercises', ref.exerciseId, 'edit'] : null;
    }

    protected editQuery(card: ReviewCard): Record<string, string> {
        return { session: this.store.session()?.sessionId ?? '', artifact: card.artifact.artifactId };
    }

    protected presentationId(card: ReviewCard): string {
        return `ex-${card.artifact.artifactId}`;
    }

    /** The id of the card's title: every control of the card is described by it. */
    protected titleId(card: ReviewCard): string {
        return `card-title-${card.artifact.artifactId}`;
    }

    protected reloadDetail(card: ReviewCard): void {
        this.store.loadDetail(card.artifact.artifactId);
    }

    protected async save(): Promise<void> {
        if (this.saving() || !this.canSave()) return;
        const saved = await this.store.approveSelected(this.kept().map(card => card.artifact.artifactId));
        // The button may be gone now: focus moves on to the next thing the user can do.
        this.focusAfter(saved > 0 ? '[data-after-save]' : '[data-save]');
    }

    protected async rejectOne(card: ReviewCard): Promise<void> {
        if (this.busy(card) || !this.canReject(card)) return;
        const id = card.artifact.artifactId;
        if (await this.store.reject(id)) this.focusAfter(`[data-card="${id}"] [data-undo]`);
    }

    protected async undoOne(card: ReviewCard): Promise<void> {
        if (this.busy(card) || !this.canUndo(card)) return;
        const id = card.artifact.artifactId;
        if (await this.store.undoReject(id)) this.focusAfter(`[data-card="${id}"] [data-reject]`, `[data-card="${id}"] h3`);
    }

    protected async retryOne(card: ReviewCard): Promise<void> {
        if (this.busy(card) || !this.canRetry(card)) return;
        const id = card.artifact.artifactId;
        if (await this.store.retry(id)) this.focusAfter(`[data-card="${id}"] h3`);
    }

    protected async rejectLeftOut(): Promise<void> {
        if (this.saving()) return;
        const ids = this.leftOut().map(card => card.artifact.artifactId);
        if (ids.length === 0) return;
        const rejected = await this.store.rejectMany(ids);
        if (rejected > 0) this.focusAfter('[data-after-save]');
    }

    /** The proposal of a loaded detail, read once per detail (a new load is a new object). */
    private proposalOf(detail: ArtifactDetail): ExerciseProposal | null {
        if (!this.proposals.has(detail)) this.proposals.set(detail, readProposal(detail));
        return this.proposals.get(detail) ?? null;
    }

    /** One presentation per (artifact, revision): the preview host restarts the trial when the key moves, never on a re-render. */
    private presentationOf(proposal: ExerciseProposal): PreviewPresentation {
        const key = `${proposal.artifactId}:${proposal.revisionId}`;
        let held = this.presentations.get(key);
        if (held === undefined) { held = proposalPresentation(proposal); this.presentations.set(key, held); }
        return held;
    }

    private loadTitles(deckId: string, targets: readonly { readonly memberKey: string; readonly itemRevisionId: string }[]): void {
        const wanted = targets.filter(target => !this.requestedTitles.has(deckId + target.memberKey));
        if (wanted.length === 0) return;
        for (const target of wanted) this.requestedTitles.add(deckId + target.memberKey);
        // The title is decoration: a material that cannot be read is called «Материал», and nothing else depends on it.
        this.titleLoads.add(from(wanted).pipe(mergeMap(target => this.items.read(deckId, target.memberKey, target.itemRevisionId)
            .pipe(catchError(() => EMPTY)), LOAD_CONCURRENCY)).subscribe(item => {
            const label = textProjections(item.document)[0]?.label ?? 'Материал';
            this.titles.update(held => ({ ...held, [item.memberKey]: label }));
        }));
    }

    /** Focuses the first selector that matches after the next render: the preferred control, then the fallbacks. */
    private focusAfter(...selectors: string[]): void {
        afterNextRender(() => {
            for (const selector of selectors) {
                const target = this.host.nativeElement.querySelector<HTMLElement>(selector);
                if (target !== null) { target.focus(); return; }
            }
        }, { injector: this.injector });
    }
}
