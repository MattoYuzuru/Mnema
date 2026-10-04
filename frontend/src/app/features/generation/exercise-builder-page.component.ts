import {
    ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription, catchError, firstValueFrom, forkJoin, map, of } from 'rxjs';

import { ToastService } from '../../core/notifications/toast.service';
import { PageTransition } from '../../shared/page-transition.service';
import { ToggletipComponent } from '../../shared/toggletip.component';
import { newCommandId } from '../authoring/authoring.models';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from '../authoring/capabilities-api.service';
import { ItemApiService } from '../authoring/item-api.service';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import {
    BuilderTarget, BuilderValue, DEFAULT_BUILDER_VALUE, buildExercisesSpec, capSessions, describeExerciseLimit, describeExerciseUsage, materialsCount,
    readLimits, splitNotice, splitTargets, targetsSummary
} from './exercise-builder';
import { ExerciseSettingsFieldsComponent } from './exercise-settings-fields.component';
import { PlanFirstOptionComponent } from './plan-first-option.component';
import { ResolvedTargets, TargetRequest, parseTargetRequest, resolveTargets } from './exercise-targets';
import { GenerationApiService } from './generation-api.service';
import { blockImplicitSubmit } from './implicit-submit';
import { GenerationProblem, readProblem } from './generation-problem';
import { UsageExplanation, describeEstimate, describePlanCost, formatWorkshopStart, problemMessage } from './generation-view';
import { ExercisesSpec, GenerationEstimate, SessionDetail, SessionSummary, serializeExercisesSpec } from './generation.models';
import { scheduleEstimate } from './estimate-schedule';

type Phase = 'loading' | 'ready' | 'unavailable' | 'empty';

type EstimateState =
    | { readonly phase: 'idle' }
    | { readonly phase: 'loading' }
    | { readonly phase: 'ready'; readonly estimate: GenerationEstimate }
    | { readonly phase: 'limit'; readonly message: string }
    | { readonly phase: 'error' };

/**
 * `/decks/:deckId/exercises/generate?members=<key>,<key>` (or `?all=1&except=<key>,...`): the exercise builder (AI-13, #291),
 * the composer in exercise mode. There is no free text: the choices are the mechanics, what comes first, and how many; the
 * preflight «≈ N % лимита» is the server's estimate, asked 400 ms after the last change, like in the Materials composer. The page
 * reads the current revision of every material itself (the address names members, never revisions). A request over the server's
 * limits is explained in words with the numbers; a selection of more than 20 materials becomes several sessions, said before
 * anything starts. It creates the session and opens its Workshop.
 */
@Component({
    selector: 'app-exercise-builder-page',
    imports: [RouterLink, ExerciseSettingsFieldsComponent, ToggletipComponent, PlanFirstOptionComponent],
    templateUrl: './exercise-builder-page.component.html',
    styleUrls: ['../authoring/authoring-page.css', './generation-composer.component.css', './exercise-builder-page.component.css'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ExerciseBuilderPageComponent {
    readonly deckId = signal('');
    readonly deckTitle = signal<string | null>(null);
    readonly phase = signal<Phase>('loading');
    readonly capabilities = signal<LearningCapabilities | null>(null);
    readonly targets = signal<readonly BuilderTarget[]>([]);
    /** What was asked for and could not be used: gone materials, or addresses that were not member keys. */
    readonly skipped = signal(0);
    readonly truncated = signal(false);
    readonly value = signal<BuilderValue>(DEFAULT_BUILDER_VALUE);
    readonly estimate = signal<EstimateState>({ phase: 'idle' });
    readonly creating = signal(false);
    readonly usageExplanation = signal<UsageExplanation | null>(null);
    readonly failure = signal<string | null>(null);
    /**
     * Workshops this request already opened (a split selection). Their materials are taken off the form at once, so pressing the
     * button again can never open a second session for them; the links stay until the page is left.
     */
    readonly startedSessions = signal<readonly SessionDetail[]>([]);
    readonly activeWorkshops = signal<readonly SessionSummary[]>([]);

    protected readonly uid = 'mn-exercise-builder';
    protected readonly materialsCount = materialsCount;
    protected readonly blockImplicitSubmit = blockImplicitSubmit;

    protected readonly aiAvailable = computed(() => this.capabilities()?.aiGeneration.available === true);
    protected readonly summary = computed(() => targetsSummary(this.targets().length));
    /** The sessions the selection becomes: at most 20 materials each, the ones without exercises first when asked. */
    private readonly allSessions = computed(() => splitTargets(this.targets(), this.value().priority));
    /** The sessions one press opens: never more than the account may have active at once. */
    protected readonly sessions = computed(() => capSessions(this.allSessions(), this.startedSessions().length));
    protected readonly deferred = computed(() => this.targets().length - this.sessions().reduce((sum, batch) => sum + batch.length, 0));
    protected readonly splitText = computed(() => splitNotice(this.targets().length, this.sessions().length, this.value().priority, this.deferred()));
    protected readonly showPriority = computed(() => this.targets().length >= 2);
    /** The request the estimate is for: the first session (the others are priced alike when they start). */
    protected readonly spec = computed<ExercisesSpec | null>(() => {
        const first = this.sessions()[0];
        return first === undefined ? null : buildExercisesSpec(first, this.value());
    });
    protected readonly backLink = computed<readonly string[]>(() => {
        const only = this.targets().length === 1 ? this.targets()[0] : undefined;
        return only === undefined ? ['/decks', this.deckId()] : ['/decks', this.deckId(), 'materials', only.memberKey];
    });
    protected readonly backLabel = computed(() => this.targets().length === 1 ? 'К материалу' : 'К колоде');
    protected readonly manualLink = computed<readonly string[]>(() => {
        const first = this.targets()[0];
        return first === undefined ? ['/decks', this.deckId()] : ['/decks', this.deckId(), 'materials', first.memberKey, 'exercises', 'new'];
    });
    /** The primary button: with «Сначала показать план» it makes the plan, not the exercises. */
    protected readonly ctaLabel = computed(() => this.value().planFirst ? 'Составить план' : 'Создать упражнения');
    /** «План: ≈ 1 % лимита» under the plan option, once the estimate knows it. */
    protected readonly planCost = computed(() => {
        const state = this.estimate();
        return state.phase === 'ready' ? describePlanCost(state.estimate) : null;
    });
    protected readonly overBudget = computed(() => {
        const state = this.estimate();
        return state.phase === 'limit' || (state.phase === 'ready' && !state.estimate.canStart);
    });
    protected readonly estimateText = computed(() => {
        const state = this.estimate();
        const sessions = this.sessions().length;
        switch (state.phase) {
            case 'loading': return 'Считаем…';
            case 'limit': return state.message;
            case 'ready': {
                const base = describeEstimate(state.estimate) + (sessions > 1 ? ' на первую мастерскую' : '');
                return state.estimate.canStart ? base : `${base}. Не хватит лимита: нажмите «${this.ctaLabel()}», чтобы увидеть варианты.`;
            }
            case 'error': return 'Оценить не удалось, но запустить можно.';
            case 'idle': return '';
        }
    });
    protected readonly personalData = computed(() => {
        const state = this.estimate();
        return state.phase === 'ready' && state.estimate.personalDataWarning;
    });
    protected readonly startedAt = (workshop: SessionSummary): string => formatWorkshopStart(workshop.createdAt);

    private readonly route = inject(ActivatedRoute);
    private readonly api = inject(GenerationApiService);
    private readonly items = inject(ItemApiService);
    private readonly decks = inject(OwnDecksApiService);
    private readonly capabilityApi = inject(CapabilitiesApiService);
    private readonly transition = inject(PageTransition);
    private readonly toast = inject(ToastService);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);
    /** The command of a request whose outcome is unknown, by request: a retry of the same request replays it. */
    private readonly pending = new Map<string, string>();
    private load: Subscription | null = null;

    constructor() {
        const deckId = this.route.snapshot.paramMap.get('deckId') ?? '';
        this.deckId.set(deckId.toLowerCase());
        const request = parseTargetRequest(this.route.snapshot.queryParamMap);
        this.skipped.set(request.rejected);
        if (!request.all && request.members.length === 0) {
            this.phase.set('empty');
        } else {
            this.start(deckId, request);
        }
        // One estimate per pause: every change cancels the timer and the request in flight.
        effect(onCleanup => {
            const spec = this.spec();
            const phase = this.phase();
            const deck = this.deckId();
            if (phase !== 'ready' || spec === null) return;
            untracked(() => this.estimate.set({ phase: 'loading' }));
            onCleanup(scheduleEstimate(next => this.api.estimate(deck, next), spec, {
                next: estimate => this.estimate.set({ phase: 'ready', estimate }),
                error: (error: unknown) => this.estimate.set(this.estimateFailure(error, spec))
            }));
        });
        // The shell focuses the heading on navigation; when the content replaces the loading text, focus stays where the user put it.
        effect(() => {
            if (this.phase() === 'loading') return;
            afterNextRender(() => {
                const document = this.host.nativeElement.ownerDocument;
                if (document.activeElement === null || document.activeElement === document.body) this.host.nativeElement.querySelector<HTMLElement>('h1')?.focus();
            }, { injector: this.injector });
        });
        this.destroyRef.onDestroy(() => this.load?.unsubscribe());
    }

    /** «Сначала показать план»: a change of the request like any other. */
    protected onPlanFirst(planFirst: boolean): void {
        this.onValue({ ...this.value(), planFirst });
    }

    /** The next choices of the mechanics, the order and the quantity; any change of the request clears what was said about the last one. */
    protected onValue(next: BuilderValue): void {
        this.value.set(next);
        this.resetOutcome();
    }

    protected onSubmit(event: Event): void {
        event.preventDefault();
        void this.submit();
    }

    /**
     * Creates the sessions one after another and opens the first. Each batch keeps its command until the request changes, a batch that
     * was created leaves the form at once, and a refusal stops the rest and is explained: pressing again continues with what is left.
     */
    async submit(): Promise<void> {
        if (this.creating() || this.phase() !== 'ready') return;
        const state = this.estimate();
        if (state.phase === 'limit') { this.failure.set(state.message); return; }
        if (state.phase === 'ready' && !state.estimate.canStart) {
            this.usageExplanation.set(describeExerciseUsage(state.estimate.blockingBuckets[0]));
            return;
        }
        this.creating.set(true);
        this.resetOutcome();
        let prepared: { readonly batch: readonly BuilderTarget[]; readonly spec: ExercisesSpec; readonly key: string }[];
        try {
            prepared = this.sessions().map(batch => {
                const spec = buildExercisesSpec(batch, this.value());
                return { batch, spec, key: JSON.stringify(serializeExercisesSpec(spec)) };
            });
        } catch {
            // The request does not satisfy the contract: nothing is sent, and this is not an unknown outcome.
            this.creating.set(false);
            this.failure.set('Запрос не удалось собрать: проверьте выбор материалов и настройки.');
            return;
        }
        const total = this.startedSessions().length + prepared.length;
        for (const { batch, spec, key } of prepared) {
            const commandId = this.pending.get(key) ?? newCommandId();
            this.pending.set(key, commandId);
            try {
                const result = await firstValueFrom(this.api.createSession(this.deckId(), spec, commandId));
                this.pending.delete(key);
                this.startedSessions.update(held => [...held, result.session]);
                const taken = new Set(batch.map(target => target.memberKey));
                this.targets.update(held => held.filter(target => !taken.has(target.memberKey)));
            } catch (error) {
                const problem = readProblem(error);
                if (!problem.uncertain) this.pending.delete(key);
                this.creating.set(false);
                this.fail(problem, batch.length, this.startedSessions().length, total);
                return;
            }
        }
        const started = this.startedSessions();
        if (started.length > 1) {
            this.toast.echo(`Мнема открыла мастерских: ${started.length}. Остальные — в списке мастерских колоды.`);
        }
        // The button stays busy until the Workshop is open: a second press can neither start another session nor lose the first.
        await this.transition.navigate(['/decks', this.deckId(), 'workshop', started[0]!.sessionId]);
        this.creating.set(false);
    }

    private start(deckId: string, request: TargetRequest): void {
        this.load = forkJoin({
            capabilities: this.capabilityApi.read().pipe(catchError(() => of(CAPABILITIES_UNAVAILABLE))),
            deck: this.decks.detail(deckId).pipe(catchError(() => of(null))),
            targets: resolveTargets(this.items, deckId, request).pipe(
                catchError(() => of<ResolvedTargets>({ targets: [], missing: request.members.length, truncated: false })))
        }).pipe(map(result => ({ ...result, title: result.deck?.metadata.title ?? null }))).subscribe(result => {
            this.deckTitle.set(result.title);
            this.capabilities.set(result.capabilities);
            this.targets.set(result.targets.targets);
            this.skipped.update(count => count + result.targets.missing);
            this.truncated.set(result.targets.truncated);
            this.phase.set(!result.capabilities.aiGeneration.available || result.targets.targets.length === 0 ? 'unavailable' : 'ready');
        });
    }

    private resetOutcome(): void {
        this.usageExplanation.set(null);
        this.failure.set(null);
        this.activeWorkshops.set([]);
    }

    private estimateFailure(error: unknown, spec: ExercisesSpec): EstimateState {
        const problem = readProblem(error);
        if (problem.status === 422 && problem.code === 'RESOURCE_LIMIT_EXCEEDED') {
            return { phase: 'limit', message: describeExerciseLimit(problem.limit, readLimits(problem.limits), spec.targets.length) };
        }
        return { phase: 'error' };
    }

    private fail(problem: GenerationProblem, inSession: number, done: number, total: number): void {
        if (problem.code === 'USAGE_LIMIT_REACHED' && problem.usage !== null) {
            this.usageExplanation.set(describeExerciseUsage(problem.usage));
        } else if (problem.status === 422 && problem.code === 'RESOURCE_LIMIT_EXCEEDED' && problem.limit !== 'ACTIVE_SESSIONS') {
            this.failure.set(describeExerciseLimit(problem.limit, readLimits(problem.limits), inSession));
        } else {
            this.failure.set(problemMessage(problem, 'EXERCISES'));
        }
        if (done > 0) {
            const note = `Открыто мастерских: ${done} из ${total}. Их материалы убраны из формы; остальные ждут: нажмите «Создать упражнения» ещё раз, когда освободится место.`;
            this.failure.update(message => message === null ? note : `${message} ${note}`);
        }
        if (problem.limit === 'ACTIVE_SESSIONS') {
            this.api.listActiveSessions().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: page => this.activeWorkshops.set(page.items), error: () => this.activeWorkshops.set([])
            });
        }
    }
}
