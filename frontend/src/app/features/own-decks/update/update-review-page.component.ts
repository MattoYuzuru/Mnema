import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';

import { ToastService } from '../../../core/notifications/toast.service';
import { AutoLoadComponent } from '../../../shared/auto-load.component';
import { COPY_TEXT, UPDATE_TEXT, UpdateChoice } from '../../../shared/copy-text';
import { CountTab, CountTabsComponent } from '../../../shared/count-tabs.component';
import { UnavailableNoteComponent } from '../../../shared/unavailable-note.component';
import { UpdateSummaryComponent } from '../../../shared/update-summary.component';
import { UpdateUnitRowComponent } from '../../../shared/update-unit-row.component';
import { FORK_POLL_LIMIT, forkPollDelay } from '../fork/fork-poll';
import { isCanonicalEntityId } from '../own-deck.models';
import { OwnDecksApiService } from '../own-decks-api.service';
import { ApplyMode, Picks, dependencyTitle, effectiveChoice, explicitChoices, lockedDependencies, picksForSelected, skippedDependency } from './update-choices';
import { UpdatePlanApiService } from './update-plan-api.service';
import {
    ApplyCommand,
    PLAN_PAGE_LIMIT,
    PlanState,
    PlanSummary,
    ReviewUnit,
    UPDATE_TABS,
    UnitSides,
    UpdatePlanPage,
    UpdateTab,
    choiceToWire,
    classRank,
    invalidChoiceOf,
    lastRankOf,
    newUpdateCommandId,
    summaryCounts,
    tabCounts,
    tabOf,
    toReviewUnit,
    unitView,
    updateFailureOf
} from './update-plan.models';
import { UpdateUnitSidesComponent } from './update-unit-sides.component';

/** What the screen keeps of the first page of the plan: the pins and the numbers of the whole plan. */
interface PlanHead {
    readonly state: PlanState;
    readonly planVersion: string | null;
    readonly releaseNote: string | null;
    readonly summary: PlanSummary | null;
    readonly lastApplyFailed: boolean;
}

type SideState =
    | { readonly phase: 'loading' }
    | { readonly phase: 'ready'; readonly sides: UnitSides }
    | { readonly phase: 'error'; readonly busy: boolean };

/** One apply intent: the command stays the same, commandId included, until its outcome is known. */
interface ApplyIntent {
    readonly mode: ApplyMode;
    readonly command: ApplyCommand;
}

interface LoadOptions {
    /** Keep the reader's picks (a stale plan was read again). */
    readonly keepPicks?: boolean;
    /** Move the focus to the summary once the new plan is on the screen. */
    readonly focus?: boolean;
    /** The plan was read again after a background apply ended: say that it was applied. */
    readonly afterBackgroundApply?: boolean;
}

/**
 * The update review screen of a copy (`/decks/:deckId/update`, Updates/5): what the author changed since the copy's base, class by class, and
 * the reader's decisions. It is built only from the shared update elements (summary, tabs, unit rows, comparison columns).
 *
 * States from the plan: loading and error with «Повторить»; `PREPARING`, `DETACHED`, `SOURCE_UNAVAILABLE`, `UP_TO_DATE` and `APPLYING` each
 * say so in words; `READY` shows the summary, the two apply buttons and the tabs with the units.
 *
 * Paging and filter: the API returns ONE ordered list (class NEW, SAFE, CONFLICT, DELETED, ASK_AGAIN, then kind), so the tabs filter the
 * loaded units on the client. A class is one contiguous run of the list, which gives two rules: a tab is complete as soon as the units
 * loaded for it reach its count in the summary, or a unit of a later class has arrived; and while the active tab shows fewer rows than a
 * page and its run is not complete, the next page is fetched by itself (bounded by the cursor), because a tab like «Удалено автором» sits
 * behind the pages of the earlier classes. Past that, rows are loaded by `app-auto-load` while the reader scrolls, with no page buttons.
 * A cursor answered with 409 `UPDATE_PLAN_STALE` rereads the plan from the first page.
 *
 * Decisions: `picks` holds only what the reader picked; defaults of the classes (a conflict stands on «Оставить моё», a deletion on
 * «Оставить у себя») are shown by the row and sent only when picked. A unit the reader accepts locks the units it needs to «Принять».
 *
 * Apply: «Принять всё безопасное» sends `acceptSafe: true` with the picks that differ from the defaults, «Применить выбранное» sends
 * `acceptSafe: false` with every pick. One `commandId` per intent: an unknown outcome is retried with the very same command.
 */
@Component({
    selector: 'app-update-review-page',
    imports: [RouterLink, AutoLoadComponent, CountTabsComponent, UnavailableNoteComponent, UpdateSummaryComponent, UpdateUnitRowComponent, UpdateUnitSidesComponent],
    templateUrl: './update-review-page.component.html',
    styleUrls: ['../own-decks-page.css', './update-review-page.component.css'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class UpdateReviewPageComponent {
    protected readonly text = UPDATE_TEXT;
    protected readonly copyText = COPY_TEXT;

    readonly deckId = signal('');
    readonly deckTitle = signal<string | null>(null);
    readonly sourceTitle = signal<string | null>(null);
    /** The public code of the source the copy was made by (`fork.upstreamCode`): the author's side of a comparison reads its media by it. */
    readonly upstreamCode = signal<string | null>(null);

    readonly phase = signal<'loading' | 'error' | 'ready'>('loading');
    readonly loadFailure = signal<'not-found' | 'busy' | 'other'>('other');
    readonly head = signal<PlanHead | null>(null);
    readonly units = signal<readonly ReviewUnit[]>([]);
    readonly nextCursor = signal<string | null>(null);
    readonly moreLoading = signal(false);
    readonly moreError = signal<string | null>(null);
    /** Changes whenever the list is replaced, so the auto-load component forgets a cursor it already used. */
    readonly generation = signal(0);
    readonly tab = signal<UpdateTab>('new');
    readonly picks = signal<Picks>(new Map());
    readonly opened = signal<ReadonlySet<string>>(new Set());
    readonly sides = signal<ReadonlyMap<string, SideState>>(new Map());

    readonly applying = signal<ApplyMode | null>(null);
    /** The job runs on the server (202, or the plan said so): the screen shows the status and asks again. */
    readonly backgroundApply = signal(false);
    readonly pollStalled = signal(false);
    readonly applyProblem = signal<string | null>(null);
    /** The last request ended without a known outcome: «Повторить» sends the same command again. */
    readonly canRetryApply = signal(false);
    /** The notice that the plan was read again under the reader, in words (it also says when open comparisons were closed). */
    readonly staleNotice = signal<string | null>(null);
    /** What the last apply did, in plain words: it stays on the page after the toast has gone. */
    readonly appliedLine = signal<string | null>(null);
    readonly dependencyProblem = signal<string | null>(null);
    /** The one polite live region of the page. */
    readonly liveMessage = signal('');

    readonly view = computed<'loading' | 'error' | 'preparing' | 'detached' | 'source-unavailable' | 'up-to-date' | 'applying' | 'ready'>(() => {
        const phase = this.phase();
        if (phase !== 'ready') return phase;
        if (this.backgroundApply()) return 'applying';
        switch (this.head()?.state) {
            case 'PREPARING': return 'preparing';
            case 'DETACHED': return 'detached';
            case 'SOURCE_UNAVAILABLE': return 'source-unavailable';
            case 'UP_TO_DATE': return 'up-to-date';
            case 'APPLYING': return 'applying';
            default: return 'ready';
        }
    });
    protected readonly counts = computed(() => {
        const summary = this.head()?.summary;
        return summary ? summaryCounts(summary) : { materials: 0, exercises: 0, fixes: 0, conflicts: 0, deleted: 0 };
    });
    protected readonly tabList = computed<readonly CountTab<UpdateTab>[]>(() => {
        const summary = this.head()?.summary;
        const counts = summary ? tabCounts(summary) : null;
        return UPDATE_TABS.map(value => ({ value, label: UPDATE_TEXT.tabs[value], count: counts?.[value] ?? 0 }));
    });
    private readonly byId = computed(() => new Map(this.units().map(unit => [unit.id, unit] as const)));
    protected readonly locks = computed(() => lockedDependencies(this.units(), this.picks()));
    private readonly visible = computed(() => this.units().filter(unit => tabOf(unit) === this.tab()));
    protected readonly rows = computed(() => this.visible().map(unit => ({ unit, view: unitView(unit, dependencyTitle(unit, this.byId())) })));
    /** The active tab cannot get more rows from later pages. */
    protected readonly tabComplete = computed(() => {
        if (this.nextCursor() === null) return true;
        const summary = this.head()?.summary;
        if (summary && this.visible().length >= tabCounts(summary)[this.tab()]) return true;
        const last = lastRankOf(this.tab());
        return this.units().some(unit => classRank(unit.wireClass) > last);
    });
    protected readonly continuation = computed(() => this.tabComplete() ? null : this.nextCursor());
    /** Picks on conflicts, units changed again and deletions: «Принять всё безопасное» does not send them, «Применить выбранное» does. */
    protected readonly pendingPicks = computed(() => picksForSelected(this.units(), this.picks(), this.locks()));
    protected readonly pickCount = computed(() => explicitChoices(this.units(), this.picks(), this.locks(), 'selected').length);
    private readonly needsFill = computed(() => this.view() === 'ready' && !this.moreLoading() && this.moreError() === null
        && this.continuation() !== null && this.visible().length < PLAN_PAGE_LIMIT);

    private readonly route = inject(ActivatedRoute);
    private readonly api = inject(UpdatePlanApiService);
    private readonly decks = inject(OwnDecksApiService);
    private readonly toasts = inject(ToastService);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);
    private readonly element: ElementRef<HTMLElement> = inject(ElementRef);

    private loadSequence = 0;
    private planLoad: Subscription | null = null;
    private moreLoad: Subscription | null = null;
    private readonly sideLoads = new Map<string, Subscription>();
    private applyRequest: Subscription | null = null;
    private pendingIntent: ApplyIntent | null = null;
    private pollTimer: ReturnType<typeof setTimeout> | null = null;
    private pollAttempt = 0;
    private pollLoad: Subscription | null = null;

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
            const deckId = params.get('deckId');
            if (deckId !== null) this.start(deckId.toLowerCase());
        });
        effect(() => {
            if (this.needsFill()) untracked(() => this.loadMore());
        });
        this.destroyRef.onDestroy(() => {
            this.stopPolling();
            this.planLoad?.unsubscribe();
            this.moreLoad?.unsubscribe();
            this.pollLoad?.unsubscribe();
            this.applyRequest?.unsubscribe();
            this.cancelSides();
        });
    }

    // -- loading ------------------------------------------------------------------------------

    private start(deckId: string): void {
        this.deckId.set(deckId);
        this.deckTitle.set(null);
        this.sourceTitle.set(null);
        this.upstreamCode.set(null);
        this.phase.set('loading');
        this.head.set(null);
        this.units.set([]);
        this.nextCursor.set(null);
        this.picks.set(new Map());
        this.pendingIntent = null;
        this.applying.set(null);
        this.applyProblem.set(null);
        this.canRetryApply.set(false);
        this.staleNotice.set(null);
        this.appliedLine.set(null);
        this.backgroundApply.set(false);
        this.liveMessage.set('');
        this.stopPolling();
        if (!isCanonicalEntityId(deckId)) {
            this.loadFailure.set('not-found');
            this.phase.set('error');
            return;
        }
        this.decks.detail(deckId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: deck => { this.deckTitle.set(deck.metadata.title); this.sourceTitle.set(deck.fork?.upstreamTitle ?? null); this.upstreamCode.set(deck.fork?.upstreamCode ?? null); },
            error: () => undefined
        });
        this.loadFirst();
    }

    /** Reads the plan from its first page. The content on the screen stays until the new plan arrives. */
    reload(options: LoadOptions = {}): void {
        this.loadFirst(options);
    }

    private loadFirst(options: LoadOptions = {}): void {
        this.planLoad?.unsubscribe();
        this.moreLoad?.unsubscribe();
        this.cancelSides();
        this.moreLoading.set(false);
        const sequence = ++this.loadSequence;
        if (this.head() === null || this.phase() === 'error') this.phase.set('loading');
        this.planLoad = this.api.plan(this.deckId()).subscribe({
            next: page => {
                if (sequence !== this.loadSequence) return;
                this.show(page, options);
            },
            error: error => {
                if (sequence !== this.loadSequence) return;
                const kind = updateFailureOf(error);
                this.loadFailure.set(kind === 'not-found' ? 'not-found' : kind === 'busy' ? 'busy' : 'other');
                this.phase.set('error');
            }
        });
    }

    private show(page: UpdatePlanPage, options: LoadOptions): void {
        const first = this.head() === null;
        const units = page.units.map(toReviewUnit);
        this.head.set({
            state: page.state, planVersion: page.planVersion, releaseNote: page.releaseNote, summary: page.summary, lastApplyFailed: page.lastApplyFailed !== null
        });
        this.units.set(units);
        this.nextCursor.set(page.nextCursor);
        this.moreError.set(null);
        this.opened.set(new Set());
        this.sides.set(new Map());
        // A pick that does not fit the unit's class any more (the plan changed under it) is dropped.
        this.picks.update(current => options.keepPicks ? new Map([...current].filter(([id, choice]) => {
            const unit = units.find(candidate => candidate.id === id);
            return unit === undefined || choiceToWire(unit.class, choice) !== null;
        })) : new Map());
        this.generation.update(value => value + 1);
        this.phase.set('ready');
        const counts = page.summary ? tabCounts(page.summary) : null;
        if (counts !== null && counts[this.tab()] === 0) this.tab.set(UPDATE_TABS.find(tab => counts[tab] > 0) ?? 'new');
        if (page.state === 'APPLYING') {
            this.enterApplying();
        } else {
            this.backgroundApply.set(false);
            this.stopPolling();
        }
        if (options.afterBackgroundApply) {
            if (page.lastApplyFailed !== null) {
                this.liveMessage.set(UPDATE_TEXT.applyJobFailed);
            } else {
                this.toasts.echo(UPDATE_TEXT.applied);
                this.appliedLine.set(UPDATE_TEXT.applied);
            }
        }
        if (options.focus || options.afterBackgroundApply) this.focusResult(); else if (first) this.focusHeadingIfLost();
    }

    /** Called by the auto-load component and by the fill rule. */
    loadMore(): void {
        const cursor = this.nextCursor();
        const planVersion = this.head()?.planVersion ?? null;
        if (cursor === null || planVersion === null || this.moreLoading() || this.view() !== 'ready') return;
        this.moreLoading.set(true);
        this.moreError.set(null);
        const sequence = this.loadSequence;
        this.moreLoad = this.api.plan(this.deckId(), cursor).subscribe({
            next: page => {
                if (sequence !== this.loadSequence) return;
                this.moreLoading.set(false);
                if (page.state !== 'READY' || page.planVersion !== planVersion) { this.refreshStale(); return; }
                const known = new Set(this.units().map(unit => unit.id));
                this.units.update(current => [...current, ...page.units.filter(unit => !known.has(unit.unitId)).map(toReviewUnit)]);
                // A cursor that comes back unchanged would loop; the list ends there.
                this.nextCursor.set(page.nextCursor === cursor ? null : page.nextCursor);
            },
            error: error => {
                if (sequence !== this.loadSequence) return;
                this.moreLoading.set(false);
                const kind = updateFailureOf(error);
                if (kind === 'stale') this.refreshStale(); else this.moreError.set(kind === 'busy' ? UPDATE_TEXT.busy : UPDATE_TEXT.loadFailed);
            }
        });
    }

    /** The copy or the source changed after the plan was read: say so once and read the plan again. */
    private refreshStale(): void {
        // The open comparisons go away with the old list: say so, so a reader who was inside one is not left wondering.
        const message = this.opened().size > 0 ? UPDATE_TEXT.staleClosed : UPDATE_TEXT.stale;
        this.staleNotice.set(message);
        this.liveMessage.set(message);
        this.loadFirst({ keepPicks: true });
    }

    // -- tabs, rows, sides ---------------------------------------------------------------------

    protected choiceOf(unit: ReviewUnit): UpdateChoice | null {
        return effectiveChoice(unit, this.picks(), this.locks());
    }

    protected lockNote(unit: ReviewUnit): string | null {
        const by = this.locks().get(unit.id);
        return by === undefined ? null : UPDATE_TEXT.lockedFor(by.title);
    }

    protected pick(unit: ReviewUnit, choice: UpdateChoice | null): void {
        if (choice === null || this.locks().has(unit.id)) return;
        this.dependencyProblem.set(null);
        this.picks.update(current => new Map(current).set(unit.id, choice));
    }

    protected isOpen(unitId: string): boolean {
        return this.opened().has(unitId);
    }

    protected setOpen(unit: ReviewUnit, open: boolean): void {
        this.opened.update(current => {
            const next = new Set(current);
            if (open) next.add(unit.id); else next.delete(unit.id);
            return next;
        });
        if (open) this.fetchSides(unit);
    }

    protected sideState(unitId: string): SideState | undefined {
        return this.sides().get(unitId);
    }

    /** The sides of a unit are fetched once, when its row is first opened (and again only after a failure). */
    protected fetchSides(unit: ReviewUnit): void {
        const planVersion = this.head()?.planVersion ?? null;
        const current = this.sides().get(unit.id);
        if (planVersion === null || (current !== undefined && current.phase !== 'error')) return;
        this.setSide(unit.id, { phase: 'loading' });
        const sequence = this.loadSequence;
        this.sideLoads.get(unit.id)?.unsubscribe();
        this.sideLoads.set(unit.id, this.api.unit(this.deckId(), unit.wireKind, unit.key, planVersion).subscribe({
            next: sides => { if (sequence === this.loadSequence) this.setSide(unit.id, { phase: 'ready', sides }); },
            error: error => {
                if (sequence !== this.loadSequence) return;
                const kind = updateFailureOf(error);
                if (kind === 'stale') this.refreshStale(); else this.setSide(unit.id, { phase: 'error', busy: kind === 'busy' });
            }
        }));
    }

    private setSide(unitId: string, state: SideState): void {
        this.sides.update(current => new Map(current).set(unitId, state));
    }

    private cancelSides(): void {
        this.sideLoads.forEach(subscription => subscription.unsubscribe());
        this.sideLoads.clear();
    }

    // -- apply --------------------------------------------------------------------------------

    /** «Принять всё безопасное» (`safe`) and «Применить выбранное» (`selected`). */
    apply(mode: ApplyMode): void {
        const planVersion = this.head()?.planVersion ?? null;
        if (this.applying() !== null || planVersion === null || this.view() !== 'ready') return;
        this.applyProblem.set(null);
        this.canRetryApply.set(false);
        const blocked = skippedDependency(this.units(), this.picks(), mode);
        if (blocked !== null) {
            this.dependencyProblem.set(UPDATE_TEXT.dependencyBlocked(blocked.needed.title, blocked.by.title));
            return;
        }
        this.dependencyProblem.set(null);
        const choices = explicitChoices(this.units(), this.picks(), this.locks(), mode);
        if (mode === 'selected' && choices.length === 0) return;
        const body = { acceptSafe: mode === 'safe', choices };
        const previous = this.pendingIntent;
        // The same body with the same plan after an unknown outcome is the same command: the server answers it once.
        const same = previous !== null && previous.command.planVersion === planVersion && previous.mode === mode
            && previous.command.acceptSafe === body.acceptSafe && JSON.stringify(previous.command.choices) === JSON.stringify(choices);
        this.send(same ? previous : { mode, command: { commandId: newUpdateCommandId(), planVersion, ...body } });
    }

    /** «Повторить» after an apply whose outcome is unknown: the same command, the same `commandId`. */
    retryApply(): void {
        if (this.applying() !== null || this.pendingIntent === null) return;
        this.applyProblem.set(null);
        this.canRetryApply.set(false);
        this.send(this.pendingIntent);
    }

    private send(intent: ApplyIntent): void {
        this.pendingIntent = intent;
        this.applying.set(intent.mode);
        this.appliedLine.set(null);
        this.liveMessage.set(UPDATE_TEXT.applying);
        this.applyRequest?.unsubscribe();
        this.applyRequest = this.api.apply(this.deckId(), intent.command).subscribe({
            next: answer => {
                this.pendingIntent = null;
                this.applying.set(null);
                if (answer.status === 'applied') {
                    const counts = UPDATE_TEXT.appliedCounts(answer.applied, answer.declined, answer.pending, answer.alsoAccepted);
                    this.toasts.echo(counts);
                    this.appliedLine.set(counts);
                    this.liveMessage.set('');
                    this.staleNotice.set(null);
                    this.loadFirst({ focus: true });
                } else {
                    this.enterApplying();
                    this.focusResult();
                }
            },
            error: error => this.applyFailed(error)
        });
    }

    private applyFailed(error: unknown): void {
        this.applying.set(null);
        this.liveMessage.set('');
        const kind = updateFailureOf(error);
        switch (kind) {
            case 'stale':
                this.pendingIntent = null;
                this.refreshStale();
                this.focusResult();
                break;
            case 'preparing':
            case 'detached':
            case 'source-unavailable':
                // The copy changed state: the screen of that state explains it.
                this.pendingIntent = null;
                this.loadFirst({ focus: true });
                break;
            case 'invalid':
                this.pendingIntent = null;
                this.invalidApply(error);
                break;
            case 'not-found':
            case 'other':
                this.pendingIntent = null;
                this.applyProblem.set(kind === 'not-found' ? UPDATE_TEXT.notFound : UPDATE_TEXT.applyInvalid);
                break;
            case 'limit':
                this.pendingIntent = null;
                this.applyProblem.set(UPDATE_TEXT.applyTooMany);
                break;
            case 'busy':
                this.applyProblem.set(UPDATE_TEXT.busy);
                this.canRetryApply.set(true);
                break;
            case 'rate-limited':
                this.applyProblem.set(UPDATE_TEXT.applyRateLimited);
                this.canRetryApply.set(true);
                break;
            default:
                this.applyProblem.set(UPDATE_TEXT.applyUnknown);
                this.canRetryApply.set(true);
        }
    }

    /** A 400 that names the unit and the reason (`reason` of the contract): the message names the units by their titles. */
    private invalidApply(error: unknown): void {
        const invalid = invalidChoiceOf(error);
        const title = (unitId: string | null): string | null => unitId === null ? null : this.byId().get(unitId)?.title ?? null;
        switch (invalid?.reason) {
            case 'unknown_unit':
                // The plan moved on under the screen: read it again, the picks that still fit stay.
                this.refreshStale();
                break;
            case 'choice_not_allowed':
                this.applyProblem.set(UPDATE_TEXT.applyChoiceNotAllowed(title(invalid.unitId)));
                break;
            case 'exercise_needs_material':
                this.applyProblem.set(UPDATE_TEXT.applyNeedsMaterial(title(invalid.unitId)));
                break;
            case 'dependency_declined':
                {
                    const needed = title(invalid.dependency);
                    const by = title(invalid.unitId);
                    this.dependencyProblem.set(needed !== null && by !== null ? UPDATE_TEXT.dependencyBlocked(needed, by) : UPDATE_TEXT.dependencyDeclined);
                }
                break;
            default:
                this.applyProblem.set(UPDATE_TEXT.applyInvalid);
        }
    }

    // -- the background apply ------------------------------------------------------------------

    private enterApplying(): void {
        this.backgroundApply.set(true);
        this.pollStalled.set(false);
        this.pollAttempt = 0;
        this.liveMessage.set(UPDATE_TEXT.applying);
        this.schedulePoll();
    }

    /** «Проверить снова» after the page stopped asking by itself. */
    checkApplyAgain(): void {
        this.pollStalled.set(false);
        this.pollAttempt = 0;
        this.liveMessage.set(UPDATE_TEXT.applying);
        // The pressed button leaves with the stalled sentence: the heading of the state takes the focus.
        this.focusResult();
        this.poll();
    }

    private stopPolling(): void {
        if (this.pollTimer !== null) clearTimeout(this.pollTimer);
        this.pollTimer = null;
        this.pollLoad?.unsubscribe();
        this.pollLoad = null;
    }

    private schedulePoll(): void {
        if (this.pollTimer !== null || this.pollStalled() || this.destroyRef.destroyed) return;
        this.pollTimer = setTimeout(() => this.poll(), forkPollDelay(this.pollAttempt));
    }

    private poll(): void {
        this.pollTimer = null;
        if (this.destroyRef.destroyed || !this.backgroundApply()) return;
        this.pollAttempt += 1;
        if (this.pollAttempt > FORK_POLL_LIMIT) { this.pollStalled.set(true); this.liveMessage.set(UPDATE_TEXT.applyingStalled); return; }
        // One unit is enough to see the state; the full plan is read when the job has ended.
        this.pollLoad = this.api.plan(this.deckId(), null, 1).subscribe({
            next: page => {
                if (page.state === 'APPLYING') { this.schedulePoll(); return; }
                this.backgroundApply.set(false);
                this.loadFirst({ afterBackgroundApply: true });
            },
            error: () => this.schedulePoll()
        });
    }

    // -- focus --------------------------------------------------------------------------------

    /** After an apply or a reload the focus goes to the heading of the new state (the summary when there is one). */
    private focusResult(): void {
        afterNextRender(() => {
            const host = this.element.nativeElement;
            const target = host.querySelector<HTMLElement>('app-update-summary h2') ?? host.querySelector<HTMLElement>('[data-focus-target]')
                ?? host.querySelector<HTMLElement>('app-unavailable-note .title') ?? host.querySelector<HTMLElement>('h1');
            if (target === null) return;
            if (!target.hasAttribute('tabindex')) target.tabIndex = -1;
            target.focus();
        }, { injector: this.injector });
    }

    private focusHeadingIfLost(): void {
        afterNextRender(() => {
            const document = this.element.nativeElement.ownerDocument;
            if (document.activeElement === null || document.activeElement === document.body || !document.activeElement.isConnected) {
                this.element.nativeElement.querySelector<HTMLElement>('h1')?.focus();
            }
        }, { injector: this.injector });
    }
}
