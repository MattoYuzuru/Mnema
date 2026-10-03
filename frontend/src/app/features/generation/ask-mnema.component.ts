import {
    ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, effect, inject, input, signal, untracked, viewChild
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { PageTransition } from '../../shared/page-transition.service';
import { newCommandId } from '../authoring/authoring.models';
import { BuilderValue, DEFAULT_BUILDER_VALUE, buildExercisesSpec, describeExerciseLimit, describeExerciseUsage, readLimits } from './exercise-builder';
import { ExerciseSettingsFieldsComponent } from './exercise-settings-fields.component';
import { scheduleEstimate } from './estimate-schedule';
import { GenerationApiService } from './generation-api.service';
import { IntentContext, IntentNote, IntentResult, MAX_INTENT_TEXT_LENGTH } from './generation-intent';
import { GenerationProblem, readProblem } from './generation-problem';
import {
    UsageExplanation, describeEditLimit, describeEstimate, intentProblemMessage, problemMessage, reviseStartMessage, voiceChipText, voiceName
} from './generation-view';
import {
    GenerationEstimate, GenerationSpec, MAX_INSTRUCTION_LENGTH, ReviseExerciseSpec, ReviseItemSpec, SPEECH_VOICES, SpeechVoice,
    serializeSpec
} from './generation.models';
import { blockImplicitSubmit, isSendKey } from './implicit-submit';

type EstimateState =
    | { readonly phase: 'idle' }
    | { readonly phase: 'loading' }
    | { readonly phase: 'ready'; readonly estimate: GenerationEstimate }
    | { readonly phase: 'limit'; readonly message: string }
    | { readonly phase: 'error' };

/** The voice chip has three states: leave the voice alone, or one of the two voices. */
type VoiceChoice = SpeechVoice | 'NONE';

let nextAsk = 0;

const MATERIAL_EXAMPLES = ['Сделай все типы упражнений по 3', 'Сделай объяснение проще'] as const;
const EXERCISE_EXAMPLES = ['Замени аудио на мужской голос', 'Сделай вопрос короче'] as const;

/**
 * «Попросить Мнему…» (AI-16, #294): a collapsed composer under the actions of a material and in the exercise editor. One sentence
 * becomes a spec through the free intent call, which the owner then edits as chips (the same mechanic and quantity choices as the
 * exercise builder, or the instruction and the voice of a revision) and only «Запустить» creates the session, which is the one step
 * that reserves anything. «Разобрать» reserves and debits nothing; a request Мнема cannot do says so and offers the builder or the editor.
 *
 * Enter sends the text and Shift+Enter is a new line, never while an IME composes. Focus follows the user: the field when the
 * composer opens, the chips heading after the sentence is read, the field again after «Изменить запрос»; the button that opened the
 * composer takes focus back when it closes. The live region says only what is happening and the estimate, never the chips again.
 */
@Component({
    selector: 'app-ask-mnema',
    imports: [RouterLink, ExerciseSettingsFieldsComponent],
    templateUrl: './ask-mnema.component.html',
    styleUrls: ['../authoring/authoring-page.css', './ask-mnema.component.css'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AskMnemaComponent {
    readonly deckId = input.required<string>();
    /** The material or the exercise the owner is looking at; the server pins it at its head. */
    readonly context = input.required<IntentContext>();
    /** «Открыть билдер упражнений» in the answer to a request Мнема cannot do (an address and its query), when there is one. */
    readonly builderLink = input<readonly string[] | null>(null);
    readonly builderQuery = input<Readonly<Record<string, string>> | null>(null);
    /** «Править самому»: the editor of the material, when this page is not already it. */
    readonly editLink = input<readonly string[] | null>(null);
    readonly editQuery = input<Readonly<Record<string, string>> | null>(null);
    /** The page holds changes that are not saved: Мнема works on the saved version, and the composer says so. */
    readonly unsaved = input(false);

    protected readonly uid = `mn-ask-${nextAsk++}`;
    protected readonly maxLength = MAX_INTENT_TEXT_LENGTH;
    protected readonly maxInstruction = MAX_INSTRUCTION_LENGTH;
    protected readonly voices = SPEECH_VOICES;
    protected readonly voiceName = voiceName;
    protected readonly voiceChipText = voiceChipText;
    protected readonly blockImplicitSubmit = blockImplicitSubmit;
    protected readonly examples = computed(() => this.context().kind === 'MATERIAL' ? MATERIAL_EXAMPLES : EXERCISE_EXAMPLES);

    readonly open = signal(false);
    readonly text = signal('');
    readonly parsing = signal(false);
    readonly intent = signal<IntentResult | null>(null);
    readonly starting = signal(false);
    /** Why the sentence could not be read (the free call), in words. */
    readonly intentError = signal<string | null>(null);
    /** Why the session could not start, in words; the usage explanation, when the budget is what stopped it, is separate. */
    readonly failure = signal<string | null>(null);
    readonly usageExplanation = signal<UsageExplanation | null>(null);
    readonly estimate = signal<EstimateState>({ phase: 'idle' });
    /** What the owner has made of the chips. */
    readonly value = signal<BuilderValue>(DEFAULT_BUILDER_VALUE);
    readonly instruction = signal('');
    readonly voice = signal<VoiceChoice>('NONE');
    /** A session was created but the Workshop did not open (a guard said no): where it is, and for which request. */
    readonly launched = signal<{ readonly key: string; readonly sessionId: string } | null>(null);
    /** The owner pressed «Запустить» with a revision that has nothing to ask: the field says so. */
    readonly blank = signal(false);
    /** What the quantity was when the answer came: the note about it goes once the owner changes it. */
    private readonly adoptedQuantity = signal<{ readonly mode: string; readonly perTarget: number } | null>(null);

    private readonly api = inject(GenerationApiService);
    private readonly transition = inject(PageTransition);
    private readonly injector = inject(Injector);
    private readonly trigger = viewChild<ElementRef<HTMLElement>>('trigger');
    private readonly field = viewChild<ElementRef<HTMLTextAreaElement>>('field');
    private readonly chipsHeading = viewChild<ElementRef<HTMLElement>>('chipsHeading');
    private readonly composing = signal(false);
    /** The command of a start whose outcome is unknown, by request: pressing again replays it instead of charging twice. */
    private readonly pending = new Map<string, string>();
    private parse = 0;

    protected readonly operation = computed(() => this.intent()?.operation ?? null);
    protected readonly base = computed<GenerationSpec | null>(() => this.intent()?.spec ?? null);
    protected readonly empty = computed(() => this.text().trim().length === 0);
    protected readonly notes = computed<readonly IntentNote[]>(() => {
        const adopted = this.adoptedQuantity();
        const value = this.value();
        const changed = adopted !== null && (adopted.mode !== value.quantityMode || adopted.perTarget !== value.perTarget);
        return (this.intent()?.notes ?? []).filter(note => !(changed && (note.code === 'PER_TARGET_CLAMPED')));
    });
    /** The request as it would be sent, as a string: what a pending command and a launched session are named by. */
    private readonly specKey = computed<string | null>(() => {
        const spec = this.spec();
        if (spec === null) return null;
        try { return JSON.stringify(serializeSpec(spec)); } catch { return null; }
    });
    /** The session of exactly this request exists already: «Запустить» waits for the owner to change something. */
    protected readonly alreadyLaunched = computed(() => this.launched() !== null && this.launched()!.key === this.specKey());
    protected readonly launchedWord = computed(() => this.isRevision() ? 'Правка запущена' : 'Мастерская запущена');
    /** The spec the owner would start: the server's, with the chips' changes. `null` when it is not complete (a revision without a request). */
    protected readonly spec = computed<GenerationSpec | null>(() => {
        const base = this.base();
        if (base === null) return null;
        switch (base.kind) {
            case 'EXERCISES': return buildExercisesSpec(base.targets, this.value(), base.outputLanguage);
            case 'REVISE_ITEM': {
                const instruction = this.instruction().trim();
                return instruction.length === 0 ? null : { ...base, instruction } satisfies ReviseItemSpec;
            }
            case 'REVISE_EXERCISE': {
                // Only what the owner can see is sent and priced: a request typed into the field before the voice hid it is not.
                const instruction = this.showInstruction() ? this.instruction().trim() : '';
                const voice = this.voice();
                const { instruction: _before, media: _media, ...rest } = base;
                if (instruction.length === 0 && voice === 'NONE') return null;
                return { ...rest, ...(instruction.length === 0 ? {} : { instruction }),
                    ...(voice === 'NONE' ? {} : { media: { action: 'AUDIO_REGENERATE', voice } }) } satisfies ReviseExerciseSpec;
            }
            default: return null;
        }
    });
    /** The instruction field is on screen: a revision of a material always has it; of an exercise, when it came with the answer or no voice is chosen. */
    protected readonly showInstruction = computed(() => this.base()?.kind === 'REVISE_ITEM'
        || (this.base()?.kind === 'REVISE_EXERCISE' && (this.intent()?.chips.some(chip => chip.kind === 'INSTRUCTION') === true || this.voice() === 'NONE')));
    protected readonly isExercises = computed(() => this.base()?.kind === 'EXERCISES');
    protected readonly isRevision = computed(() => this.base()?.kind === 'REVISE_ITEM' || this.base()?.kind === 'REVISE_EXERCISE');
    protected readonly hasVoiceChip = computed(() => this.base()?.kind === 'REVISE_EXERCISE' && this.intent()?.chips.some(chip => chip.kind === 'VOICE') === true);
    /** The instruction is optional only beside a voice change. */
    protected readonly instructionRequired = computed(() => this.base()?.kind === 'REVISE_ITEM' || this.voice() === 'NONE');
    protected readonly quantityModes = computed(() => {
        const base = this.base();
        return base?.kind === 'EXERCISES' && base.settings.quantity.mode === 'BUDGET_PERCENT'
            ? (['AUTO', 'EXACT', 'BUDGET_PERCENT'] as const) : (['AUTO', 'EXACT'] as const);
    });
    protected readonly estimateText = computed(() => {
        const state = this.estimate();
        switch (state.phase) {
            case 'loading': return 'Считаем…';
            case 'limit': return state.message;
            case 'ready': return state.estimate.canStart ? describeEstimate(state.estimate)
                : `${describeEstimate(state.estimate)}. Не хватит лимита: нажмите «Запустить», чтобы увидеть варианты.`;
            case 'error': return 'Оценить не удалось, но запустить можно.';
            case 'idle': return '';
        }
    });
    protected readonly overBudget = computed(() => {
        const state = this.estimate();
        return state.phase === 'limit' || (state.phase === 'ready' && !state.estimate.canStart);
    });
    protected readonly targetWord = computed(() => this.context().kind === 'MATERIAL' ? 'этого материала' : 'материала этого упражнения');

    constructor() {
        // The component outlives a reload of the page's material or exercise: what was asked about the old one is not about the new one.
        let seen: string | null = null;
        effect(() => {
            const context = this.context();
            const key = context.kind === 'MATERIAL' ? `M:${context.memberKey}` : `E:${context.exerciseId}`;
            untracked(() => {
                if (seen !== null && seen !== key) this.reset();
                seen = key;
            });
        });
        // One estimate per pause: every change cancels the timer and the request in flight. Nothing is reserved by it.
        effect(onCleanup => {
            const spec = this.spec();
            const deckId = this.deckId();
            if (spec === null || !this.open()) { untracked(() => this.estimate.set({ phase: 'idle' })); return; }
            untracked(() => this.estimate.set({ phase: 'loading' }));
            onCleanup(scheduleEstimate(next => this.api.estimate(deckId, next), spec, {
                next: estimate => this.estimate.set({ phase: 'ready', estimate }),
                error: (error: unknown) => this.estimate.set(this.estimateFailure(error, spec))
            }));
        });
    }

    /** Back to a composer that was never used: the sentence, the answer, the commands and any launched session are forgotten. */
    private reset(): void {
        this.parse += 1;
        this.parsing.set(false);
        this.text.set('');
        this.intent.set(null);
        this.intentError.set(null);
        this.failure.set(null);
        this.usageExplanation.set(null);
        this.launched.set(null);
        this.blank.set(false);
        this.adoptedQuantity.set(null);
        this.pending.clear();
        this.open.set(false);
    }

    // --- Opening and reading the sentence ---

    protected toggle(): void {
        if (this.open()) { this.close(); return; }
        this.open.set(true);
        this.focusAfter(() => this.field()?.nativeElement);
    }

    /** Esc or «Свернуть»: the composer goes, what was typed stays for the next time, and focus goes back to the button. */
    protected close(): void {
        this.parse += 1;
        this.parsing.set(false);
        this.open.set(false);
        this.focusAfter(() => this.trigger()?.nativeElement);
    }

    protected onEscape(event: KeyboardEvent): void {
        if (event.isComposing || event.keyCode === 229 || this.composing() || this.starting()) return;
        event.stopPropagation();
        this.close();
    }

    protected onInput(event: Event): void {
        this.text.set((event.target as HTMLTextAreaElement).value);
    }

    protected onKeydown(event: KeyboardEvent): void {
        if (!isSendKey(event)) return;
        event.preventDefault();
        void this.analyze();
    }

    protected onCompositionStart(): void { this.composing.set(true); }
    protected onCompositionEnd(): void { this.composing.set(false); }

    protected useExample(example: string): void {
        this.text.set(example);
        this.focusAfter(() => this.field()?.nativeElement);
    }

    /** «Разобрать»: the free call. Nothing is reserved or debited; the answer is the chips (or the reason it cannot be done). */
    protected async analyze(): Promise<void> {
        if (this.parsing() || this.starting() || this.empty()) return;
        const ticket = ++this.parse;
        this.parsing.set(true);
        this.intentError.set(null);
        this.failure.set(null);
        this.usageExplanation.set(null);
        try {
            const result = await firstValueFrom(this.api.createIntent(this.deckId(), this.context(), this.text()));
            if (ticket !== this.parse) return;
            this.adopt(result);
            this.focusAfter(() => this.chipsHeading()?.nativeElement);
        } catch (error) {
            if (ticket !== this.parse) return;
            this.intent.set(null);
            this.intentError.set(intentProblemMessage(readProblem(error)));
        } finally {
            if (ticket === this.parse) this.parsing.set(false);
        }
    }

    /** «Изменить запрос»: back to the sentence, which is still there. */
    protected editRequest(): void {
        this.parse += 1;
        this.intent.set(null);
        this.failure.set(null);
        this.usageExplanation.set(null);
        this.focusAfter(() => this.field()?.nativeElement);
    }

    // --- The chips ---

    protected onValue(next: BuilderValue): void {
        this.value.set(next);
        this.resetOutcome();
    }

    protected onInstruction(event: Event): void {
        this.instruction.set((event.target as HTMLTextAreaElement).value);
        this.blank.set(false);
        this.resetOutcome();
    }

    protected setVoice(voice: VoiceChoice): void {
        this.voice.set(voice);
        this.resetOutcome();
    }

    // --- Start: the only step that reserves ---

    /** «Запустить»: creates the session and opens its Workshop. The same request keeps its command until it changes, so an unknown outcome replays. */
    protected async start(): Promise<void> {
        const spec = this.spec();
        if (this.starting() || this.alreadyLaunched()) return;
        if (spec === null) {
            // A revision with nothing to ask: say so next to the field, never a silent press.
            this.blank.set(true);
            this.focusAfter(() => document.getElementById(`${this.uid}-instruction`) ?? undefined);
            return;
        }
        const state = this.estimate();
        if (state.phase === 'limit') { this.failure.set(state.message); return; }
        if (state.phase === 'ready' && !state.estimate.canStart) {
            this.usageExplanation.set(spec.kind === 'EXERCISES' ? describeExerciseUsage(state.estimate.blockingBuckets[0])
                : { headline: describeEditLimit(state.estimate.blockingBuckets[0]), options: [], plansLink: true });
            return;
        }
        let key: string;
        try {
            key = JSON.stringify(serializeSpec(spec));
        } catch {
            this.failure.set('Запрос не удалось собрать: проверьте текст и выбор.');
            return;
        }
        const commandId = this.pending.get(key) ?? newCommandId();
        this.pending.set(key, commandId);
        this.starting.set(true);
        this.failure.set(null);
        this.usageExplanation.set(null);
        try {
            const { session } = await firstValueFrom(this.api.createSession(this.deckId(), spec, commandId));
            // The session exists. The command stays: a repeat of the same request replays it and never makes a second session.
            this.launched.set({ key, sessionId: session.sessionId });
            // The button stays busy until the Workshop is open: a second press can neither start another session nor lose this one.
            const opened = await this.transition.navigate(['/decks', this.deckId(), 'workshop', session.sessionId]);
            // A page guard may refuse the move (unsaved changes): the session is still there, and the link below says so.
            if (opened) { this.pending.delete(key); this.launched.set(null); }
        } catch (error) {
            const problem = readProblem(error);
            if (!problem.uncertain) this.pending.delete(key);
            this.refuse(problem, spec);
        } finally {
            this.starting.set(false);
        }
    }

    private adopt(result: IntentResult): void {
        this.intent.set(result);
        this.estimate.set({ phase: 'idle' });
        this.launched.set(null);
        this.blank.set(false);
        this.adoptedQuantity.set(null);
        const spec = result.spec;
        if (spec === null) return;
        switch (spec.kind) {
            case 'EXERCISES': {
                const quantity = spec.settings.quantity;
                this.value.set({
                    mechanics: spec.settings.mechanics === 'AUTO' ? [] : spec.settings.mechanics, priority: spec.settings.priority,
                    quantityMode: quantity.mode, perTarget: quantity.mode === 'EXACT' ? quantity.perTarget : DEFAULT_BUILDER_VALUE.perTarget,
                    percent: quantity.mode === 'BUDGET_PERCENT' ? quantity.percent : DEFAULT_BUILDER_VALUE.percent
                });
                this.adoptedQuantity.set({ mode: this.value().quantityMode, perTarget: this.value().perTarget });
                break;
            }
            case 'REVISE_ITEM':
                this.instruction.set(spec.instruction);
                this.voice.set('NONE');
                break;
            case 'REVISE_EXERCISE':
                this.instruction.set(spec.instruction ?? '');
                this.voice.set(spec.media?.voice ?? 'NONE');
                break;
            default: break;
        }
    }

    private resetOutcome(): void {
        this.failure.set(null);
        this.usageExplanation.set(null);
    }

    private estimateFailure(error: unknown, spec: GenerationSpec): EstimateState {
        const problem = readProblem(error);
        if (spec.kind === 'EXERCISES' && problem.status === 422 && problem.code === 'RESOURCE_LIMIT_EXCEEDED') {
            return { phase: 'limit', message: describeExerciseLimit(problem.limit, readLimits(problem.limits), spec.targets.length) };
        }
        if ((spec.kind === 'REVISE_ITEM' || spec.kind === 'REVISE_EXERCISE') && !problem.uncertain && problem.status !== 429 && problem.status !== 412) {
            return { phase: 'limit', message: reviseStartMessage(problem, spec.kind) };
        }
        return { phase: 'error' };
    }

    private refuse(problem: GenerationProblem, spec: GenerationSpec): void {
        if (problem.code === 'USAGE_LIMIT_REACHED' && problem.usage !== null) {
            this.usageExplanation.set(spec.kind === 'EXERCISES' ? describeExerciseUsage(problem.usage)
                : { headline: describeEditLimit(problem.usage), options: [], plansLink: true });
        } else if (spec.kind === 'EXERCISES') {
            this.failure.set(problem.status === 422 && problem.code === 'RESOURCE_LIMIT_EXCEEDED' && problem.limit !== 'ACTIVE_SESSIONS'
                ? describeExerciseLimit(problem.limit, readLimits(problem.limits), spec.targets.length) : problemMessage(problem, 'EXERCISES'));
        } else if (spec.kind === 'MATERIALS') {
            this.failure.set(problemMessage(problem, 'MATERIALS'));
        } else {
            this.failure.set(reviseStartMessage(problem, spec.kind));
        }
    }

    private focusAfter(target: () => HTMLElement | undefined): void {
        afterNextRender(() => target()?.focus(), { injector: this.injector });
    }
}
