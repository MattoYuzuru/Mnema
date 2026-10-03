import { DatePipe } from '@angular/common';
import {
    ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, signal,
    untracked, viewChild
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subscription, catchError, forkJoin, map, of } from 'rxjs';

import { AuthService } from '../../auth.service';
import { ToggletipComponent } from '../../shared/toggletip.component';
import { SegmentedChoiceComponent } from '../../shared/segmented-choice.component';
import { AuthoringApiService } from '../authoring/authoring-api.service';
import { CaptureNote, newCommandId } from '../authoring/authoring.models';
import { CAPABILITIES_UNAVAILABLE, LearningCapabilities } from '../authoring/capabilities-api.service';
import { GenerationApiService } from './generation-api.service';
import { GenerationProblem, readProblem } from './generation-problem';
import { DEFAULT_SETTINGS, GenerationSettingsComponent, GenerationSettingsValue } from './generation-settings.component';
import {
    NOTES_MODE_OPTIONS, UsageExplanation, describeEstimate, describeUsageLimit, problemMessage
} from './generation-view';
import {
    GenerationEstimate, MAX_PROMPT_LENGTH, MaterialsSpec, NotesMode, SessionDetail, SessionSummary, SpecSource, serializeMaterialsSpec
} from './generation.models';
import { NoteOverridesComponent } from './note-overrides.component';
import {
    ComposerSource, NoteOverrideMap, customizedCount, overridesOf, refusalMessage, refusalOf, sourceKey
} from './note-sources';

/** How long the composer waits after the last change before it asks the server for the cost. */
export const ESTIMATE_DEBOUNCE_MS = 400;

type EstimateState =
    | { readonly phase: 'idle' }
    | { readonly phase: 'loading' }
    | { readonly phase: 'ready'; readonly estimate: GenerationEstimate }
    | { readonly phase: 'error' };

interface PendingCreation { readonly key: string; readonly commandId: string; }

/**
 * The spec the composer sends, from what is on the screen. Media is requested only where the capability exists. The spec is the
 * session defaults plus the sparse per-note `overrides` (only members the user changed; none at all unless there are two or more
 * `SOURCE` notes and each becomes its own material).
 */
export function buildMaterialsSpec(prompt: string, settings: GenerationSettingsValue, sources: readonly SpecSource[],
                                   available: { readonly image: boolean; readonly audio: boolean },
                                   overrides: NoteOverrideMap = {}): MaterialsSpec {
    const notes = sources.filter(source => source.type === 'NOTE' && source.role === 'SOURCE');
    const perNote = settings.notesMode === 'ONE_PER_NOTE' && notes.length > 1;
    return {
        kind: 'MATERIALS', prompt: prompt.trim(),
        sources: sources.map(source => {
            if (!perNote || source.type !== 'NOTE' || source.role !== 'SOURCE') return source;
            const own = overridesOf(overrides[source.noteId], settings, available);
            return own === undefined ? source : { ...source, overrides: own };
        }),
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
 * optional, as in the contract. With two or more notes the user picks the grouping («Материал на заметку» by default, or
 * «Объединить в один») and can tune each note separately. Before the session is created every note is read again, so the
 * pin is the version that is current at that moment; a note that is gone, archived or from another deck is reported and
 * taken off the list, never sent.
 */
@Component({
    selector: 'app-generation-composer',
    imports: [DatePipe, RouterLink, GenerationSettingsComponent, ToggletipComponent, SegmentedChoiceComponent, NoteOverridesComponent],
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
    /** Per-note settings by note id, edited in «Настроить для каждой заметки отдельно». */
    readonly overrides = signal<NoteOverrideMap>({});
    /** The overrides were dropped because the user switched to «Объединить в один»: said once, in words. */
    readonly overridesCleared = signal(false);

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
    private readonly chips = viewChild<ElementRef<HTMLElement>>('chips');

    protected readonly uid = `mn-composer-${nextComposer++}`;
    protected readonly maxLength = MAX_PROMPT_LENGTH;
    protected readonly imageAvailable = computed(() => this.capabilities().imageSearch.available);
    protected readonly audioAvailable = computed(() => this.capabilities().textToSpeech.available);

    protected readonly groupingOptions = NOTES_MODE_OPTIONS;
    protected readonly sourceKey = sourceKey;
    private readonly auth = inject(AuthService);
    private readonly authoring = inject(AuthoringApiService);
    private readonly injector = inject(Injector);
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
    /** The notes that are the material (`SOURCE`), in order. */
    protected readonly noteSources = computed(() => this.sources().filter(source => source.spec.type === 'NOTE' && source.spec.role === 'SOURCE'));
    protected readonly hasNotes = computed(() => this.noteSources().length > 0);
    /** Grouping and per-note settings mean something only for two or more notes. */
    protected readonly groupable = computed(() => this.noteSources().length > 1);
    /** Row versions read again at submit; they replace the ones the chips were made with. */
    private readonly freshPins = signal<ReadonlyMap<string, string>>(new Map());
    readonly spec = computed(() => buildMaterialsSpec(this.prompt(), this.settings(), this.sources().map(source => {
        const pin = source.spec.type === 'NOTE' ? this.freshPins().get(source.spec.noteId) : undefined;
        return pin === undefined || source.spec.type !== 'NOTE' ? source.spec : { ...source.spec, noteRowVersion: pin };
    }), { image: this.imageAvailable(), audio: this.audioAvailable() }, this.overrides()));
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

    onOverrides(value: NoteOverrideMap): void {
        this.overrides.set(value);
        this.resetOutcome();
    }

    /** «Материал на заметку» / «Объединить в один». Merging leaves nothing to tune per note, so those settings are dropped, visibly. */
    setNotesMode(mode: NotesMode): void {
        const customized = customizedCount(this.overrides(), this.noteSources().map(sourceKey));
        if (mode === 'MERGE_INTO_ONE' && customized > 0) {
            this.overrides.set({});
            this.overridesCleared.set(true);
        } else if (mode === 'ONE_PER_NOTE') {
            this.overridesCleared.set(false);
        }
        this.onSettings({ ...this.settings(), notesMode: mode });
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
        // After an unknown outcome the very same request is retried with the pins it was first sent with: reading the notes
        // again could change a pin, and then the server would see a new command instead of replaying the stored answer.
        // The trade-off: a retry of an unchanged request does not notice a note edited meanwhile; the approval stays safe, because
        // the server compares the pin with the note and the Workshop marks it «заметка изменилась».
        if (!this.hasNotes() || this.pending?.key === JSON.stringify(serializeMaterialsSpec(this.spec()))) { this.create(); return; }
        this.createFromCurrentNotes();
    }

    removeSource(source: ComposerSource): void {
        const index = this.sources().indexOf(source);
        this.sourceRemoved.emit(source);
        // The chip that held focus is gone: focus goes to the chip now at its place (the last one when it was last), or to the
        // request field when none is left.
        afterNextRender(() => {
            const buttons = this.chipRemoveButtons();
            const next = buttons[Math.min(Math.max(index, 0), buttons.length - 1)];
            if (next !== undefined) next.focus(); else this.promptField()?.nativeElement.focus();
        }, { injector: this.injector });
    }

    private chipRemoveButtons(): HTMLElement[] {
        return Array.from(this.chips()?.nativeElement.querySelectorAll<HTMLElement>('.chip-remove') ?? []);
    }

    /**
     * Reads every pinned note again, so the session pins what the notes say now. Refused notes (archived, from another deck,
     * deleted) are named and taken off the list; the user presses «Создать» again for the rest. A failed read creates nothing.
     */
    private createFromCurrentNotes(): void {
        const deckId = this.deckId().toLowerCase();
        const wanted = this.noteSources().flatMap(source => source.spec.type === 'NOTE' ? [{ source, noteId: source.spec.noteId }] : []);
        this.creating.set(true);
        this.resetOutcome();
        forkJoin(wanted.map(entry => this.authoring.readCapture(entry.noteId).pipe(
            map((note): { readonly note: CaptureNote } | { readonly gone: true } | { readonly failed: true } => ({ note })),
            catchError((error: unknown) => of(isNotFound(error) ? { gone: true as const } : { failed: true as const }))
        ))).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(answers => {
            if (answers.some(answer => 'failed' in answer)) {
                this.creating.set(false);
                this.failure.set('Не удалось проверить заметки. Ничего не создано: попробуйте ещё раз.');
                return;
            }
            const refused: ComposerSource[] = [];
            let archived = 0;
            let elsewhere = 0;
            let missing = 0;
            const pins = new Map<string, string>();
            answers.forEach((answer, position) => {
                const source = wanted[position]!.source;
                if ('note' in answer) {
                    const refusal = refusalOf(answer.note, deckId);
                    if (refusal === null) { pins.set(answer.note.noteId, answer.note.rowVersion); return; }
                    if (refusal === 'ARCHIVED') archived += 1; else elsewhere += 1;
                } else {
                    missing += 1;
                }
                refused.push(source);
            });
            if (refused.length > 0) {
                this.creating.set(false);
                this.failure.set(refusalMessage(archived, elsewhere, missing));
                refused.forEach(source => this.sourceRemoved.emit(source));
                return;
            }
            this.freshPins.set(pins);
            this.creating.set(false);
            this.create();
        });
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

function isNotFound(error: unknown): boolean {
    return typeof error === 'object' && error !== null && 'status' in error && (error as { status: unknown }).status === 404;
}
