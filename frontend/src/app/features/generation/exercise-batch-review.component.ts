import {
    ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, signal, untracked
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { EMPTY, Subscription, catchError, from, mergeMap } from 'rxjs';

import { Mechanic } from '../../content/exercise/exercise-content.models';
import { NewBadgeComponent } from '../../shared/new-badge.component';
import { ExercisePreviewHostComponent } from '../authoring/exercise-preview-host.component';
import { PreviewPresentation } from '../authoring/exercise-preview.models';
import { ItemApiService } from '../authoring/item-api.service';
import { textProjections } from '../authoring/exercise.models';
import { mechanicName } from './exercise-builder';
import { ExerciseProposal, proposalPresentation, readProposal } from './exercise-proposal';
import { artifactStatus, exerciseFailureReason, failureNote } from './generation-view';
import { ArtifactSummary, allows, isApprovable, isRetryable } from './generation.models';
import { DetailEntry, WorkshopSessionStore } from './workshop-session.store';

/** Details and material titles are fetched this many at a time: a batch can hold 60 exercises. */
const LOAD_CONCURRENCY = 4;

/** One proposed (or pending, failed, saved) exercise of the batch, with what the card needs to show it. */
export interface ReviewCard {
    readonly artifact: ArtifactSummary;
    readonly entry: DetailEntry | null;
    readonly proposal: ExerciseProposal | null;
    readonly presentation: PreviewPresentation | null;
    readonly mechanic: Mechanic | null;
    readonly status: { readonly shape: string; readonly word: string };
}

export interface ReviewGroup {
    readonly key: string;
    readonly title: string;
    /** The material the cards are about; `null` for the groups that hold what has no proposal to read yet. */
    readonly memberKey: string | null;
    readonly cards: readonly ReviewCard[];
}

const WRITING: readonly string[] = ['QUEUED', 'GENERATING', 'REVISING'];

/**
 * Groups the cards of a batch: one group per material (in the order the materials were requested) for every exercise whose proposal
 * is known, then what has no proposal to read yet: the ones still being written, the failed ones, and the ones already saved
 * (a reopened Workshop does not load published artifacts again).
 */
export function groupCards(cards: readonly ReviewCard[], order: readonly string[], titles: Readonly<Record<string, string>>): readonly ReviewGroup[] {
    const byMaterial = new Map<string, ReviewCard[]>();
    const writing: ReviewCard[] = [];
    const failed: ReviewCard[] = [];
    const saved: ReviewCard[] = [];
    for (const card of cards) {
        const member = card.proposal?.exercise.subject.memberKey ?? null;
        if (member !== null) { byMaterial.set(member, [...(byMaterial.get(member) ?? []), card]); continue; }
        if (card.artifact.state === 'FAILED') failed.push(card);
        else if (card.artifact.state === 'PUBLISHED' || card.artifact.state === 'HANDED_OFF') saved.push(card);
        else writing.push(card);
    }
    const known = [...order.filter(member => byMaterial.has(member)), ...[...byMaterial.keys()].filter(member => !order.includes(member))];
    const groups: ReviewGroup[] = known.map(member => ({ key: member, memberKey: member, title: titles[member] ?? 'Материал', cards: byMaterial.get(member)! }));
    if (writing.length > 0) groups.push({ key: 'writing', memberKey: null, title: 'Пишутся', cards: writing });
    if (failed.length > 0) groups.push({ key: 'failed', memberKey: null, title: 'Не удались', cards: failed });
    if (saved.length > 0) groups.push({ key: 'saved', memberKey: null, title: 'Уже в колоде', cards: saved });
    return groups;
}

/**
 * The batch review of an `EXERCISES` Workshop (AI-13, #291): proposals grouped by material, each one playable in the author-preview
 * host (it never writes an attempt or a schedule), a «Оставить» choice per proposal, and one sticky «Сохранить выбранные (N)».
 * Everything the user decides is kept here (which proposals are unchecked); the store sends the commands. After a command focus
 * stays on the next sensible control of the same card, never on the page heading.
 */
@Component({
    selector: 'app-exercise-batch-review',
    imports: [RouterLink, ExercisePreviewHostComponent, NewBadgeComponent],
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

    protected readonly cards = computed<readonly ReviewCard[]>(() => this.store.artifacts().map(artifact => {
        const entry = this.store.details()[artifact.artifactId] ?? null;
        const proposal = entry?.detail == null ? null : readProposal(entry.detail);
        return { artifact, entry, proposal, presentation: proposal === null ? null : proposalPresentation(proposal),
            mechanic: proposal?.mechanic ?? null, status: artifactStatus(artifact) };
    }));
    protected readonly groups = computed(() => groupCards(this.cards(), this.store.session()?.spec.targets.map(target => target.memberKey) ?? [],
        this.titles()));
    /** Proposals that «Сохранить выбранные» would save now. */
    protected readonly kept = computed(() => this.cards().filter(card => card.proposal !== null && this.isKept(card)));
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
    private titleLoad: Subscription | null = null;
    private requestedTitles = '';

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
        // The titles of the requested materials name the groups.
        effect(() => {
            const targets = this.store.session()?.spec.targets ?? [];
            const deckId = this.deckId();
            untracked(() => this.loadTitles(deckId, targets));
        });
        this.destroyRef.onDestroy(() => this.titleLoad?.unsubscribe());
    }

    protected isKept(card: ReviewCard): boolean {
        return isApprovable(card.artifact) && !this.unchecked().has(card.artifact.artifactId);
    }

    protected toggleKept(artifactId: string, checked: boolean): void {
        this.unchecked.update(held => {
            const next = new Set(held);
            if (checked) next.delete(artifactId); else next.add(artifactId);
            return next;
        });
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

    private loadTitles(deckId: string, targets: readonly { readonly memberKey: string; readonly itemRevisionId: string }[]): void {
        const wanted = targets.filter(target => this.titles()[target.memberKey] === undefined);
        const key = deckId + wanted.map(target => target.memberKey).join(',');
        if (wanted.length === 0 || key === this.requestedTitles) return;
        this.requestedTitles = key;
        this.titleLoad?.unsubscribe();
        // The title is decoration: a material that cannot be read is called «Материал», and nothing else depends on it.
        this.titleLoad = from(wanted).pipe(mergeMap(target => this.items.read(deckId, target.memberKey, target.itemRevisionId)
            .pipe(catchError(() => EMPTY)), LOAD_CONCURRENCY))
            .subscribe({
                next: item => {
                    const label = textProjections(item.document)[0]?.label ?? 'Материал';
                    this.titles.update(held => ({ ...held, [item.memberKey]: label }));
                },
                error: () => { /* names stay «Материал» */ }
            });
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
