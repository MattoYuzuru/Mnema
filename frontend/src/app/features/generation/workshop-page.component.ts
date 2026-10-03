import {
    ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked, viewChild
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { ToastService } from '../../core/notifications/toast.service';
import { HoldToDeleteButtonComponent } from '../../shared/hold-to-delete-button.component';
import { PageTransition } from '../../shared/page-transition.service';
import { BatchPagerComponent } from './batch-pager.component';
import { ExerciseBatchReviewComponent } from './exercise-batch-review.component';
import { targetsSummary } from './exercise-builder';
import { NBSP, describeNoteArchive, formatDay, positionLabel, promptExcerpt, summarize } from './generation-view';
import { ArtifactState, ArtifactSummary, sessionAllows } from './generation.models';
import { ProposalViewComponent } from './proposal-view.component';
import { WorkshopSessionStore } from './workshop-session.store';

/** The summary is a live region, so it changes at most once per this many milliseconds; extra changes are merged. */
export const ANNOUNCE_GAP_MS = 2_000;

/** States that still wait for the user (or for Мнема): «Одобрить и далее» moves on to the next one of these. */
const UNREVIEWED: readonly ArtifactState[] = ['QUEUED', 'GENERATING', 'PROPOSED', 'REVISING', 'FAILED', 'STALE'];

/**
 * The Workshop of one session: a pager over its materials, the material on show, and the batch commands. Opening it
 * again after leaving loses nothing: everything lives on the server. The position is the 1-based `?n=` of the URL, so
 * a link (from a notification) opens the right material. Nothing scrolls or takes focus by itself; focus moves only
 * after the user approves a material (to the title of the next one) or opens a confirmation.
 */
@Component({
    selector: 'app-workshop-page',
    imports: [RouterLink, BatchPagerComponent, ProposalViewComponent, HoldToDeleteButtonComponent, ExerciseBatchReviewComponent],
    providers: [WorkshopSessionStore],
    templateUrl: './workshop-page.component.html',
    styleUrls: ['../authoring/authoring-page.css', './workshop-page.component.css'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class WorkshopPageComponent {
    readonly store = inject(WorkshopSessionStore);
    readonly deckId = signal('');
    readonly selectedId = signal<string | null>(null);
    /** The «Одобрить все готовые» confirmation is open. */
    readonly confirmingAll = signal(false);
    /** The summary as announced: throttled to one change per {@link ANNOUNCE_GAP_MS}. */
    readonly statusText = signal('');
    readonly proposal = viewChild(ProposalViewComponent);
    private readonly archiveResult = viewChild<ElementRef<HTMLElement>>('archiveResult');
    private readonly confirmButton = viewChild<ElementRef<HTMLElement>>('confirmApprove');
    private readonly approveAllTrigger = viewChild<ElementRef<HTMLElement>>('approveAllTrigger');

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly transition = inject(PageTransition);
    private readonly toast = inject(ToastService);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);
    private readonly requestedPosition = signal<number | null>(null);
    private initialised = false;
    private lastAnnounced = 0;
    private pendingStatus: string | null = null;
    private statusTimer: ReturnType<typeof setTimeout> | null = null;

    protected readonly nbsp = NBSP;
    protected readonly positionLabel = positionLabel;
    protected readonly session = this.store.session;
    protected readonly artifacts = this.store.artifacts;
    protected readonly current = computed<ArtifactSummary | null>(() =>
        this.artifacts().find(artifact => artifact.artifactId === this.selectedId()) ?? null);
    protected readonly currentIndex = computed(() => this.artifacts().findIndex(artifact => artifact.artifactId === this.selectedId()));
    protected readonly entry = computed(() => {
        const current = this.current();
        return current === null ? null : this.store.details()[current.artifactId] ?? null;
    });
    protected readonly draft = computed(() => {
        const current = this.current();
        return current === null ? null : this.store.drafts()[current.artifactId] ?? null;
    });
    /** An `EXERCISES` session has its own review: proposals grouped by material, playable, kept or not. */
    protected readonly exercises = computed(() => this.session()?.kind === 'EXERCISES');
    protected readonly heading = computed(() => {
        switch (this.store.phase()) {
            case 'missing': return 'Мастерская недоступна';
            case 'error': return 'Не удалось открыть мастерскую';
            default: return this.exercises() ? 'Мастерская упражнений' : 'Мастерская';
        }
    });
    protected readonly excerpt = computed(() => promptExcerpt(this.session()?.spec.prompt ?? null));
    /** «Для 7 материалов»: what the exercises of this Workshop are for, in place of the prompt a Materials Workshop quotes. */
    protected readonly targetsLine = computed(() => {
        const count = this.session()?.spec.targets.length ?? 0;
        return count > 0 ? `${targetsSummary(count)}: проверьте упражнения и оставьте нужные.` : null;
    });
    /** «Стоп» is there for as long as anything is being written (WCAG 2.2.2). */
    protected readonly canStop = computed(() => {
        const session = this.session();
        if (session === null || !sessionAllows(session.state, 'cancelSession')) return false;
        // Not in REVIEW unless something is being rewritten: stopping a review session only takes away the retries.
        return session.state === 'PLANNING' || session.state === 'PLAN_READY' || session.state === 'RUNNING' || this.artifacts().some(artifact => artifact.state === 'REVISING');
    });
    protected readonly approvableCount = computed(() => this.store.approvable().length);
    protected readonly canApproveAll = computed(() => {
        const session = this.session();
        return session !== null && sessionAllows(session.state, 'approveArtifacts') && this.approvableCount() > 0;
    });
    protected readonly archivableNotes = this.store.archivableNotes;
    protected readonly notesBusy = computed(() => this.store.busy().has('notes'));
    protected readonly archiveSummary = computed(() => {
        const result = this.store.noteArchive();
        return result === null ? null : describeNoteArchive(result);
    });
    protected readonly sessionBusy = computed(() => this.store.busy().has('session'));
    protected readonly artifactBusy = computed(() => {
        const current = this.current();
        return current !== null && this.store.busy().has(current.artifactId);
    });
    protected readonly connectionNote = computed(() => {
        switch (this.store.connection()) {
            case 'offline': return 'Нет сети: продолжим, когда она появится. Готовое можно читать.';
            case 'degraded': return 'Не удалось обновить данные. Пробуем снова.';
            case 'recovered': return 'Связь восстановлена.';
            case 'online': return null;
        }
    });
    protected readonly deferredUntil = computed(() => formatDay(this.store.usage()?.deferredUntil ?? null));
    protected readonly endNote = computed(() => {
        const session = this.session();
        const noun = session?.kind === 'EXERCISES' ? 'упражнения' : 'материалы';
        switch (session?.state) {
            case 'CANCELLED': return session.kind === 'EXERCISES'
                ? 'Вы остановили мастерскую. Готовые упражнения можно сохранить; новые писаться не будут.'
                : 'Вы остановили мастерскую. Готовые материалы можно одобрить; новые писаться не будут.';
            case 'CLOSED': return `Все ${noun} разобраны.`;
            case 'EXPIRED': return 'Срок мастерской вышел. Её можно только удалить: одобренное уже в колоде.';
            case 'PLANNING':
            case 'PLAN_READY': return 'Мнема составляет план. Планы пока не поддерживаются: остановите мастерскую и создайте материал заново.';
            default: return null;
        }
    });

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
            const deckId = params.get('deckId');
            const sessionId = params.get('sessionId');
            if (deckId === null || sessionId === null) return;
            this.deckId.set(deckId.toLowerCase());
            this.initialised = false;
            this.selectedId.set(null);
            this.confirmingAll.set(false);
            this.store.open(deckId, sessionId);
        });
        this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe(params => {
            const value = params.get('n');
            this.requestedPosition.set(value !== null && /^[1-9][0-9]{0,3}$/u.test(value) ? Number(value) : null);
        });
        // The URL asks for a position (a link, or Back): move there once the batch is known.
        effect(() => {
            const position = this.requestedPosition();
            untracked(() => {
                const artifacts = this.artifacts();
                if (!this.initialised || position === null || artifacts.length === 0) return;
                const target = artifacts[Math.min(position, artifacts.length) - 1]!;
                if (target.artifactId !== this.selectedId()) this.selectedId.set(target.artifactId);
            });
        });
        // Choose the first material to show, and keep one chosen when the batch changes under it.
        effect(() => {
            const artifacts = this.artifacts();
            untracked(() => this.ensureSelection(artifacts));
        });
        // The material on show needs its stored revision (and again when that moves on).
        effect(() => {
            const current = this.current();
            if (current === null) return;
            const needed = this.store.needsDetail(current);
            if (needed) untracked(() => this.store.loadDetail(current.artifactId));
        });
        effect(() => {
            // Nothing to say before the batch is known: «Пока ничего» would be a false first announcement.
            if (this.session() === null) return;
            const text = summarize(this.artifacts());
            untracked(() => this.announce(text));
        });
        this.destroyRef.onDestroy(() => { if (this.statusTimer !== null) clearTimeout(this.statusTimer); });
    }

    select(artifactId: string): void {
        if (artifactId === this.selectedId()) return;
        this.selectedId.set(artifactId);
        this.confirmingAll.set(false);
        const position = this.artifacts().findIndex(artifact => artifact.artifactId === artifactId) + 1;
        void this.router.navigate([], { relativeTo: this.route, queryParams: { n: position }, queryParamsHandling: 'merge', replaceUrl: true });
    }

    async approve(): Promise<void> {
        const current = this.current();
        if (current === null) return;
        const approved = await this.store.approve(current.artifactId);
        if (approved) this.advanceFrom(current.artifactId);
    }

    async reject(): Promise<void> {
        const current = this.current();
        if (current !== null) await this.store.reject(current.artifactId);
    }

    async undo(): Promise<void> {
        const current = this.current();
        if (current !== null) await this.store.undoReject(current.artifactId);
    }

    async retry(): Promise<void> {
        const current = this.current();
        if (current !== null) await this.store.retry(current.artifactId);
    }

    reloadDetail(): void {
        const current = this.current();
        if (current !== null) this.store.loadDetail(current.artifactId);
    }

    /** Opens the shown revision in the editor, on the draft the hand-off created. */
    async handoff(): Promise<void> {
        const current = this.current();
        if (current === null) return;
        const result = await this.store.handoff(current.artifactId);
        if (result !== null) {
            await this.transition.navigate(['/decks', this.deckId(), 'materials', 'new'],
                { queryParams: { write: 1, draft: result.draft.draftId } });
        }
    }

    /** The button goes away once nothing is left to archive: focus moves to the result, which says what happened. */
    async archiveNotes(): Promise<void> {
        if (await this.store.archiveNotes()) {
            afterNextRender(() => this.archiveResult()?.nativeElement.focus(), { injector: this.injector });
        }
    }

    async stop(): Promise<void> {
        await this.store.cancel();
    }

    askApproveAll(): void {
        this.confirmingAll.set(true);
        afterNextRender(() => this.confirmButton()?.nativeElement.focus(), { injector: this.injector });
    }

    /** Taking the confirmation back returns focus to the button that opened it. */
    dismissApproveAll(): void {
        this.confirmingAll.set(false);
        afterNextRender(() => this.approveAllTrigger()?.nativeElement.focus(), { injector: this.injector });
    }

    /** The confirming button is gone afterwards: focus goes to the title of the material on show, as after «Одобрить и далее». */
    async approveAll(): Promise<void> {
        this.confirmingAll.set(false);
        await this.store.approveAll();
        afterNextRender(() => this.proposal()?.focusHeading(), { injector: this.injector });
    }

    async deleteSession(): Promise<void> {
        if (await this.store.deleteSession()) {
            this.toast.echo('Мастерская удалена');
            await this.transition.navigate(['/decks', this.deckId()]);
        }
    }

    private ensureSelection(artifacts: readonly ArtifactSummary[]): void {
        if (artifacts.length === 0) return;
        const selected = this.selectedId();
        if (!this.initialised) {
            this.initialised = true;
            const position = untracked(() => this.requestedPosition());
            const requested = position === null ? undefined : artifacts[Math.min(position, artifacts.length) - 1];
            this.selectedId.set((requested ?? this.firstToReview(artifacts)).artifactId);
            return;
        }
        if (selected === null || !artifacts.some(artifact => artifact.artifactId === selected)) {
            this.selectedId.set(this.firstToReview(artifacts).artifactId);
        }
    }

    private firstToReview(artifacts: readonly ArtifactSummary[]): ArtifactSummary {
        return artifacts.find(artifact => artifact.state === 'PROPOSED') ?? artifacts.find(artifact => UNREVIEWED.includes(artifact.state))
            ?? artifacts[0]!;
    }

    private advanceFrom(artifactId: string): void {
        const artifacts = this.artifacts();
        const start = artifacts.findIndex(artifact => artifact.artifactId === artifactId);
        const next = [...artifacts.slice(start + 1), ...artifacts.slice(0, start)].find(artifact => UNREVIEWED.includes(artifact.state));
        if (next !== undefined) this.select(next.artifactId);
        // The previous material is gone from the actions: put focus on the title that replaced it.
        afterNextRender(() => this.proposal()?.focusHeading(), { injector: this.injector });
    }

    private announce(text: string): void {
        const wait = this.lastAnnounced + ANNOUNCE_GAP_MS - Date.now();
        if (wait <= 0) {
            this.statusText.set(text);
            this.lastAnnounced = Date.now();
            this.pendingStatus = null;
            if (this.statusTimer !== null) { clearTimeout(this.statusTimer); this.statusTimer = null; }
            return;
        }
        this.pendingStatus = text;
        this.statusTimer ??= setTimeout(() => {
            this.statusTimer = null;
            if (this.pendingStatus !== null) this.statusText.set(this.pendingStatus);
            this.pendingStatus = null;
            this.lastAnnounced = Date.now();
        }, wait);
    }
}
