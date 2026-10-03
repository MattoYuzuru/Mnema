import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';

import { AuthService } from '../../auth.service';
import { ToggletipComponent } from '../../shared/toggletip.component';
import { newCommandId } from '../authoring/authoring.models';
import { CAPABILITIES_UNAVAILABLE, LearningCapabilities } from '../authoring/capabilities-api.service';
import { GenerationApiService } from './generation-api.service';
import { GenerationProblem, readProblem } from './generation-problem';
import { DEFAULT_SETTINGS, GenerationSettingsComponent, GenerationSettingsValue } from './generation-settings.component';
import {
    UsageExplanation, describeEstimate, describeUsageLimit, problemMessage
} from './generation-view';
import {
    GenerationEstimate, MAX_PROMPT_LENGTH, MaterialsSpec, SessionDetail, SessionSummary, SpecSource, serializeMaterialsSpec
} from './generation.models';

/** How long the composer waits after the last change before it asks the server for the cost. */
export const ESTIMATE_DEBOUNCE_MS = 400;

/** A pinned source shown as a chip (a note of «На потом» for AI-08 #290). `label` is the text the user recognises it by. */
export interface ComposerSource { readonly spec: SpecSource; readonly label: string; }

type EstimateState =
    | { readonly phase: 'idle' }
    | { readonly phase: 'loading' }
    | { readonly phase: 'ready'; readonly estimate: GenerationEstimate }
    | { readonly phase: 'error' };

interface PendingCreation { readonly key: string; readonly commandId: string; }

/** The spec the composer sends, from what is on the screen. Media is requested only where the capability exists. */
export function buildMaterialsSpec(prompt: string, settings: GenerationSettingsValue, sources: readonly SpecSource[],
                                   available: { readonly image: boolean; readonly audio: boolean }): MaterialsSpec {
    return {
        kind: 'MATERIALS', prompt: prompt.trim(), sources,
        settings: {
            effort: settings.effort, notesMode: settings.notesMode,
            media: { audio: { enabled: settings.audio && available.audio, lang: settings.audioLang,
                voice: settings.audioVoice === 'any' ? null : settings.audioVoice },
                imageSearch: settings.imageSearch && available.image },
            factCheck: false, similarToDeck: settings.similarToDeck, planFirst: false, budgetPercent: null
        }
    };
}

/** Whether a keydown in the prompt field sends the form: Enter alone, never Shift+Enter, never while an IME composes. */
export function isSendKey(event: Pick<KeyboardEvent, 'key' | 'shiftKey' | 'isComposing' | 'keyCode'>): boolean {
    return event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229;
}

let nextComposer = 0;

/**
 * One composer for every entry of AI generation: the greeting is the visible label of the request field, settings sit
 * below with progressive disclosure, and the preflight «≈ N % лимита» is the server's estimate, asked 400 ms after the last
 * change. A request that does not fit the budget is never blocked in advance: the button stays pressable and the press
 * explains the options. It creates the session and reports it through `created`; the page decides where to go.
 *
 * `sources` are the pinned sources of the request (note chips for AI-08, #290): with a `SOURCE` among them the prompt is
 * optional, as in the contract.
 */
@Component({
    selector: 'app-generation-composer',
    imports: [DatePipe, RouterLink, GenerationSettingsComponent, ToggletipComponent],
    templateUrl: './generation-composer.component.html',
    styleUrls: ['../authoring/authoring-page.css', './generation-composer.component.css'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class GenerationComposerComponent {
    readonly deckId = input.required<string>();
    readonly deckTitle = input<string | null>(null);
    readonly sources = input<readonly ComposerSource[]>([]);
    readonly capabilities = input<LearningCapabilities>(CAPABILITIES_UNAVAILABLE);
    readonly created = output<SessionDetail>();
    readonly sourceRemoved = output<ComposerSource>();

    readonly prompt = signal('');
    readonly settings = signal<GenerationSettingsValue>(DEFAULT_SETTINGS);
    readonly estimate = signal<EstimateState>({ phase: 'idle' });
    readonly creating = signal(false);
    readonly promptMissing = signal(false);
    readonly usageExplanation = signal<UsageExplanation | null>(null);
    readonly failure = signal<string | null>(null);
    /** The other workshops that hold the three places, when a new one is refused for that reason. */
    readonly activeWorkshops = signal<readonly SessionSummary[]>([]);
    readonly promptField = viewChild<ElementRef<HTMLTextAreaElement>>('promptField');

    protected readonly uid = `mn-composer-${nextComposer++}`;
    protected readonly maxLength = MAX_PROMPT_LENGTH;
    protected readonly imageAvailable = computed(() => this.capabilities().imageSearch.available);
    protected readonly audioAvailable = computed(() => this.capabilities().textToSpeech.available);

    private readonly auth = inject(AuthService);
    private readonly api = inject(GenerationApiService);
    private readonly destroyRef = inject(DestroyRef);
    private pending: PendingCreation | null = null;

    /** «Юзуру, что будем учить сегодня?»; without a name, «Что будем учить сегодня?». The name is never the e-mail address. */
    readonly greeting = computed(() => {
        const user = this.auth.user();
        const name = (user?.displayName ?? user?.profileUsername ?? '').trim().split(/\s+/u)[0] ?? '';
        return name.length > 0 ? `${name}, что будем учить сегодня?` : 'Что будем учить сегодня?';
    });

    /** A prompt is required unless a `SOURCE` is pinned. */
    readonly valid = computed(() => this.prompt().trim().length > 0 || this.sources().some(source => source.spec.role === 'SOURCE'));
    readonly spec = computed(() => buildMaterialsSpec(this.prompt(), this.settings(), this.sources().map(source => source.spec),
        { image: this.imageAvailable(), audio: this.audioAvailable() }));
    protected readonly overBudget = computed(() => {
        const state = this.estimate();
        return state.phase === 'ready' && !state.estimate.canStart;
    });
    /** The server found something that looks like personal data (it is redacted before the model sees it either way). */
    protected readonly personalData = computed(() => {
        const state = this.estimate();
        return state.phase === 'ready' && state.estimate.personalDataWarning;
    });
    protected readonly estimateText = computed(() => {
        const state = this.estimate();
        switch (state.phase) {
            case 'loading': return 'Считаем…';
            case 'ready': return state.estimate.canStart ? describeEstimate(state.estimate)
                : `${describeEstimate(state.estimate)}. Не хватит лимита: нажмите «Создать», чтобы увидеть варианты.`;
            case 'error': return 'Оценить не удалось, но запустить можно.';
            case 'idle': return '';
        }
    });

    constructor() {
        // One request per pause: every change cancels the timer and the request in flight.
        effect(onCleanup => {
            const deckId = this.deckId();
            const valid = this.valid();
            const spec = this.spec();
            if (!valid) {
                untracked(() => this.estimate.set({ phase: 'idle' }));
                return;
            }
            untracked(() => this.estimate.set({ phase: 'loading' }));
            let request: Subscription | null = null;
            const timer = setTimeout(() => {
                request = this.api.estimate(deckId, spec).subscribe({
                    next: estimate => this.estimate.set({ phase: 'ready', estimate }),
                    error: () => this.estimate.set({ phase: 'error' })
                });
            }, ESTIMATE_DEBOUNCE_MS);
            onCleanup(() => {
                clearTimeout(timer);
                request?.unsubscribe();
            });
        });
    }

    onInput(event: Event): void {
        this.prompt.set((event.target as HTMLTextAreaElement).value);
        this.promptMissing.set(false);
        this.resetOutcome();
    }

    onSettings(value: GenerationSettingsValue): void {
        this.settings.set(value);
        this.resetOutcome();
    }

    onKeydown(event: KeyboardEvent): void {
        if (!isSendKey(event)) return;
        event.preventDefault();
        this.submit();
    }

    onSubmit(event: Event): void {
        event.preventDefault();
        this.submit();
    }

    submit(): void {
        if (this.creating()) return;
        if (!this.valid()) {
            this.promptMissing.set(true);
            this.promptField()?.nativeElement.focus();
            return;
        }
        const state = this.estimate();
        if (state.phase === 'ready' && !state.estimate.canStart) {
            this.explainLimit(state.estimate.blockingBuckets[0]);
            return;
        }
        this.create();
    }

    removeSource(source: ComposerSource): void {
        this.sourceRemoved.emit(source);
    }

    private create(): void {
        const spec = this.spec();
        const key = JSON.stringify(serializeMaterialsSpec(spec));
        // An unknown outcome is retried with the same command; a changed request is a new command.
        if (this.pending?.key !== key) this.pending = { key, commandId: newCommandId() };
        const commandId = this.pending.commandId;
        this.creating.set(true);
        this.resetOutcome();
        this.api.createSession(this.deckId(), spec, commandId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => {
                this.pending = null;
                this.creating.set(false);
                this.created.emit(result.session);
            },
            error: (error: unknown) => {
                const problem = readProblem(error);
                if (!problem.uncertain) this.pending = null;
                this.creating.set(false);
                this.fail(problem);
            }
        });
    }

    private fail(problem: GenerationProblem): void {
        if (problem.code === 'USAGE_LIMIT_REACHED' && problem.usage !== null) {
            this.usageExplanation.set(describeUsageLimit(problem.usage, this.settings().effort));
            return;
        }
        this.failure.set(problemMessage(problem));
        if (problem.limit === 'ACTIVE_SESSIONS') {
            this.api.listActiveSessions().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: page => this.activeWorkshops.set(page.items), error: () => this.activeWorkshops.set([])
            });
        }
    }

    private explainLimit(bucket: GenerationEstimate['blockingBuckets'][number] | undefined): void {
        const effort = this.settings().effort;
        this.usageExplanation.set(bucket !== undefined ? describeUsageLimit(bucket, effort) : {
            headline: 'Не хватит лимита ИИ на этот запрос.',
            options: effort === 'SHORT' ? ['Сократите запрос.'] : ['Выберите «Кратко»: запрос обойдётся дешевле.'], plansLink: true
        });
    }

    private resetOutcome(): void {
        this.usageExplanation.set(null);
        this.failure.set(null);
        this.activeWorkshops.set([]);
    }
}
