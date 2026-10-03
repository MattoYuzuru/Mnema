import { HttpErrorResponse } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import {
    ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, inject, signal
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, map, of, switchMap } from 'rxjs';

import {
    AuthoringBlock, ExerciseSpec, LIMITS, MECHANICS, Mechanic, ObjectiveCommand, PROMPT_SLOTS, REFERENCE_SLOTS, authoringSlots,
    isBlank, previewExerciseOf
} from '../../content/exercise/exercise-content.models';
import { MECHANIC_CATALOG, StepId, catalogEntry, stepTitle } from '../../content/exercise/mechanic-catalog';
import { MnemaSelectComponent, MnemaSelectOption } from '../../core/controls/mnema-select.component';
import { HoldToDeleteButtonComponent } from '../../shared/hold-to-delete-button.component';
import { NewBadgeComponent } from '../../shared/new-badge.component';
import { ToastService } from '../../core/notifications/toast.service';
import { ExerciseProposal, quoteContext, readProposal } from '../generation/exercise-proposal';
import { GenerationApiService } from '../generation/generation-api.service';
import { readProblem } from '../generation/generation-problem';
import { problemMessage } from '../generation/generation-view';
import { ArtifactDetail } from '../generation/generation.models';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { AuthoringProtocolError, ItemDetail, newCommandId } from './authoring.models';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from './capabilities-api.service';
import { ExerciseApiService } from './exercise-api.service';
import {
    CategorizeDraft, ChoiceDraft, ClozeDraft, DraftErrors, ExerciseDrafts, FreeResponseDraft, MatchDraft, OrderDraft, SlotContext, buildSpec, carryPrompt,
    draftsFromDetail, emptyDrafts, isPristine, learnerContent, materialText, mechanicSpecificData, validateDraft
} from './exercise-draft';
import { CategorizeItemsEditorComponent, CategoryGroupsEditorComponent } from './categorize-editors.component';
import { ExercisePreviewHostComponent } from './exercise-preview-host.component';
import { PreviewPresentation } from './exercise-preview.models';
import { ExerciseSlotEditorComponent } from './exercise-slot-editor.component';
import {
    ExerciseDetail, ExerciseObjective, ExercisePage, ExerciseSummary, ExerciseWriteResult, specOf, textProjections
} from './exercise.models';
import { ItemApiService } from './item-api.service';
import { ChoiceEditorComponent, ClozeEditorComponent, FreeResponseEditorComponent, MatchEditorComponent } from './mechanic-editors';
import { MechanicChoice, MechanicPickerComponent } from './mechanic-picker.component';
import { OrderEditorComponent } from './order-editor.component';

type Phase = 'loading' | 'ready' | 'saving' | 'saved' | 'conflict' | 'rejected' | 'error';
type ObjectiveMode = 'create' | 'reuse' | 'revise';

/**
 * An exercise Мнема proposed in a Workshop, opened for editing before it is saved (`?session=<id>&artifact=<id>`, AI-13): saving
 * approves the artifact with the edited exercise as its `replacement`, then returns to the Workshop.
 */
interface ProposalEdit {
    readonly sessionId: string;
    readonly artifact: ArtifactDetail;
    readonly proposal: ExerciseProposal;
}

interface PendingWrite {
    readonly commandId: string;
    readonly objective: ObjectiveCommand;
    readonly exercise: ExerciseSpec;
}

/** A tile pressed while the current draft holds mechanic-specific data: nothing changes until the author decides. */
interface PendingSwitch {
    readonly target: Mechanic;
    readonly lost: readonly string[];
}

const ID_PREFIX: Readonly<Record<Mechanic, string>> = {
    SELF_CHECK: 'self-check', FREE_RESPONSE: 'free-response', CLOZE: 'cloze', CHOICE: 'choice', MATCH: 'match', ORDER: 'order',
    CATEGORIZE: 'categorize'
};
const PROMPT_LABELS: Readonly<Record<StepId, string>> = {
    prompt: 'Что увидит ученик', context: 'Вводные слова (необязательно)', reference: 'Что увидит ученик после ответа',
    answers: '', passage: '', options: '', pairs: '', items: '', groups: '', finish: ''
};
const PROMPT_HINTS: Readonly<Partial<Record<Mechanic, string>>> = {
    MATCH: 'Необязательно: напишите общую инструкцию. Сами пары вы зададите на следующем шаге.',
    ORDER: 'Необязательно: например, «Восстановите порядок шагов». Сами элементы вы зададите на следующем шаге.',
    CATEGORIZE: 'Необязательно: например, «Распределите слова по частям речи». Группы и элементы вы зададите на следующих шагах.'
};
const STEPS_WITH_PROMPT: ReadonlySet<StepId> = new Set<StepId>(['prompt', 'context']);

/**
 * The messages of a set of field errors for a summary: each text once, and one line for all key points of a rubric (their messages
 * are the same sentence), so a screen reader is not read the same line three times.
 */
function distinctMessages(errors: DraftErrors): readonly string[] {
    const seen = new Set<string>();
    let criterion = false;
    for (const [key, text] of Object.entries(errors)) {
        if (text === undefined) continue;
        if (key.startsWith('rubric:criterion:')) {
            if (criterion) continue;
            criterion = true;
        }
        seen.add(text);
    }
    return [...seen];
}

/** Whether a validation key belongs to the given step, so «Продолжить» only checks what the step just asked for. */
function stepOwns(step: StepId, key: string): boolean {
    switch (step) {
        case 'prompt':
        case 'context': return key === 'prompt';
        case 'reference': return key === 'reference';
        case 'answers': return key === 'accepted' || key === 'reference' || key.startsWith('rubric:');
        case 'passage': return key === 'passage' || key.startsWith('blank:');
        case 'options': return key === 'options' || key === 'selection' || key.startsWith('option:');
        case 'pairs': return key === 'pairs' || key.startsWith('left:') || key.startsWith('right:');
        case 'groups': return key === 'categories' || key.startsWith('category:');
        case 'items': return key === 'items' || key.startsWith('item:') || key.startsWith('assignment:');
        case 'finish': return false;
    }
}

@Component({
    selector: 'app-exercise-authoring-page',
    imports: [RouterLink, MnemaSelectComponent, HoldToDeleteButtonComponent, NewBadgeComponent, MechanicPickerComponent, ExercisePreviewHostComponent,
        ExerciseSlotEditorComponent, FreeResponseEditorComponent, ClozeEditorComponent, ChoiceEditorComponent, MatchEditorComponent,
        OrderEditorComponent, CategoryGroupsEditorComponent, CategorizeItemsEditorComponent],
    templateUrl: './exercise-authoring-page.component.html',
    styleUrl: './exercise-authoring-page.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ExerciseAuthoringPageComponent {
    /** The catalog drives the tiles, the steps and the demo; a new mechanic is added there. */
    readonly catalog = MECHANIC_CATALOG;
    readonly deck = signal<OwnDeck | null>(null);
    readonly item = signal<ItemDetail | null>(null);
    readonly exercise = signal<ExerciseDetail | null>(null);
    readonly page = signal<ExercisePage | null>(null);
    readonly phase = signal<Phase>('loading');
    readonly dirty = signal(false);
    readonly message = signal<string | null>(null);
    readonly fieldErrors = signal<DraftErrors>({});
    /** Problems of the step whose «Продолжить» was pressed; they disappear with the next edit. */
    readonly stepErrors = signal<DraftErrors>({});
    /** Set after the first failed save so untouched new blocks are not flagged while the author is still typing. */
    readonly showProblems = signal(false);
    readonly capabilities = signal<LearningCapabilities>(CAPABILITIES_UNAVAILABLE);
    /** Set when the page edits a Workshop proposal instead of a new or saved exercise. */
    readonly proposalEdit = signal<ProposalEdit | null>(null);

    /** Nothing is selected on a new exercise: the page starts with the type choice alone. */
    readonly mechanic = signal<Mechanic | null>(null);
    /** How many steps are open per mechanic. 0 means the type was never chosen; 1 is the preview and the first step. */
    readonly opened = signal<Readonly<Record<Mechanic, number>>>(closedSteps());
    readonly pendingSwitch = signal<PendingSwitch | null>(null);
    readonly enabled = signal(true);
    readonly drafts = signal<ExerciseDrafts>(emptyDrafts());
    readonly objectiveMode = signal<ObjectiveMode>('create');
    readonly selectedObjectiveId = signal<string | null>(null);
    readonly objectiveTitle = signal('');
    /** Existing-exercise row whose enable toggle or delete is in flight. */
    readonly listBusy = signal<string | null>(null);
    readonly listMessage = signal<string | null>(null);
    private readonly titleEdited = signal(false);

    readonly entry = computed(() => { const value = this.mechanic(); return value === null ? null : catalogEntry(value); });
    readonly steps = computed(() => this.entry()?.steps ?? []);
    readonly openCount = computed(() => { const value = this.mechanic(); return value === null ? 0 : this.opened()[value]; });
    readonly visibleSteps = computed(() => this.steps().slice(0, this.openCount()));
    readonly idPrefix = computed(() => { const value = this.mechanic(); return value === null ? 'exercise' : ID_PREFIX[value]; });

    /**
     * The text the exercise can quote. A proposal may quote an earlier revision of the material than the one now current: its
     * quotes (sent with the artifact) fill in what the current document no longer has.
     */
    readonly projections = computed(() => {
        const item = this.item();
        if (item === null) return [];
        const own = textProjections(item.document);
        const quotes = this.proposalEdit()?.proposal.quotes;
        if (quotes === undefined) return own;
        const known = new Set(own.map(projection => projection.nodeId));
        return [...own, ...quoteContext(quotes).projections.filter(projection => !known.has(projection.nodeId))];
    });
    readonly context = computed<SlotContext | null>(() => {
        const item = this.item();
        return item === null ? null : { document: item.document, memberKey: item.memberKey,
            itemRevisionId: item.itemRevisionId, projections: this.projections() };
    });
    readonly objectives = computed(() => uniqueObjectives(this.page()?.exercises.map(value => value.objective) ?? []));
    readonly objectiveOptions = computed<readonly MnemaSelectOption[]>(() => [
        { value: '', label: 'Выберите цель' },
        ...this.objectives().map(objective => ({ value: objective.objectiveId, label: objective.title }))
    ]);
    readonly selectedObjective = computed(() => this.objectives()
        .find(value => value.objectiveId === this.selectedObjectiveId()) ?? null);
    /** First line of the question: a sensible default name for a new learning objective. */
    readonly suggestedTitle = computed(() => suggestTitle(this.mechanic(), this.drafts(), this.context()));
    readonly effectiveTitle = computed(() => {
        if (this.titleEdited()) return this.objectiveTitle();
        return this.objectiveMode() === 'create' ? this.suggestedTitle() : this.selectedObjective()?.title ?? '';
    });
    readonly errorSummary = computed(() => distinctMessages(this.fieldErrors()));
    readonly stepProblems = computed(() => distinctMessages(this.stepErrors()));
    readonly visibleErrors = computed<DraftErrors>(() => ({ ...this.stepErrors(), ...this.fieldErrors() }));
    readonly staleProjection = computed(() => {
        const detail = this.exercise();
        const item = this.item();
        const mechanic = this.mechanic();
        if (item === null || mechanic === null) return false;
        const stale = (revision: string) => revision !== item.itemRevisionId;
        return (detail !== null && stale(detail.subject.itemRevisionId))
            || authoringSlots(buildSpec(mechanic, this.drafts(), { memberKey: item.memberKey, itemRevisionId: item.itemRevisionId }, true))
                .flat().some(block => block.kind === 'MATERIAL' && block.memberKey === item.memberKey && stale(block.itemRevisionId));
    });
    /**
     * What the preview shows. A pristine draft of a new exercise shows the catalog demo; the first authored value
     * switches to the author's own content, and a complete draft becomes playable. An existing exercise never
     * shows a demo.
     */
    readonly presentation = computed<PreviewPresentation | null>(() => {
        const mechanic = this.mechanic();
        const context = this.context();
        if (mechanic === null || context === null || this.openCount() < 1) return null;
        const drafts = this.drafts();
        if (this.exercise() === null && isPristine(mechanic, drafts)) {
            const demo = catalogEntry(mechanic).demo.exercise;
            return { mode: 'DEMO', key: `demo:${mechanic}`, exercise: demo, blockedReason: null,
                learner: revealed => learnerContent(demo, { context: null, revealed, placeholders: false }) };
        }
        const spec = buildSpec(mechanic, drafts, { memberKey: context.memberKey, itemRevisionId: context.itemRevisionId }, true);
        const exercise = previewExerciseOf(spec);
        const problems = Object.values(validateDraft(mechanic, drafts, context));
        if (problems.length === 0) {
            return { mode: 'AUTHOR_READY', key: `ready:${JSON.stringify(exercise)}`, exercise, blockedReason: null,
                learner: revealed => learnerContent(exercise, { context, revealed, placeholders: false }) };
        }
        const learner = (revealed: boolean) => learnerContent(exercise, { context, revealed, placeholders: true });
        return { mode: 'AUTHOR_DRAFT', key: `draft:${mechanic}:${JSON.stringify(learner(false))}`, exercise: null,
            blockedReason: `Проверить ответ пока нельзя: ${problems[0]}`, learner };
    });

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly browserDocument = inject(DOCUMENT);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly decks = inject(OwnDecksApiService);
    private readonly items = inject(ItemApiService);
    private readonly exercises = inject(ExerciseApiService);
    private readonly capabilityApi = inject(CapabilitiesApiService);
    private readonly generation = inject(GenerationApiService);
    private readonly toast = inject(ToastService);
    private readonly destroyRef = inject(DestroyRef);
    private pending: PendingWrite | null = null;

    constructor() {
        this.load();
        // Fail closed: until the server answers, AI and speech are unavailable.
        this.capabilityApi.read().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: value => this.capabilities.set(value),
            error: () => this.capabilities.set(CAPABILITIES_UNAVAILABLE)
        });
    }

    /** The Workshop a proposal came from (`?session=`), for the way back when the proposal cannot be opened. */
    workshopLink(): readonly string[] | null {
        const deckId = this.route.snapshot.paramMap.get('deckId');
        const sessionId = this.route.snapshot.queryParamMap.get('session');
        return deckId === null || sessionId === null ? null : ['/decks', deckId, 'workshop', sessionId];
    }

    load(): void {
        const deckId = this.route.snapshot.paramMap.get('deckId');
        const exerciseId = this.route.snapshot.paramMap.get('exerciseId');
        const routeMember = this.route.snapshot.paramMap.get('memberKey');
        if (deckId === null || (exerciseId === null && routeMember === null)) {
            this.fail('Некорректный адрес редактора упражнения.');
            return;
        }
        this.phase.set('loading');
        this.message.set(null);
        this.fieldErrors.set({});
        if (exerciseId === null) {
            const sessionId = this.route.snapshot.queryParamMap.get('session');
            const artifactId = this.route.snapshot.queryParamMap.get('artifact');
            const proposed = sessionId !== null && artifactId !== null;
            forkJoin({ deck: this.decks.detail(deckId), item: this.items.read(deckId, routeMember!),
                page: this.exercises.list(deckId, routeMember!),
                artifact: proposed ? this.generation.getArtifact(deckId, sessionId, artifactId).pipe(map(value => ({ sessionId, value }))) : of(null) })
                .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                    next: result => result.artifact === null
                        ? this.openNew(result.deck, result.item, result.page)
                        : this.openProposal(result.deck, result.item, result.page, result.artifact.sessionId, result.artifact.value),
                    error: () => this.fail(proposed ? 'Не удалось загрузить упражнение из мастерской.' : 'Не удалось загрузить материал и упражнения.')
                });
            return;
        }
        forkJoin({ deck: this.decks.detail(deckId), detail: this.exercises.read(deckId, exerciseId) })
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: result => {
                    const member = result.detail.objective.memberKey;
                    forkJoin({ item: this.items.read(deckId, member), page: this.exercises.list(deckId, member) })
                        .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                            next: content => {
                                this.openExisting(result.deck, content.item, content.page, result.detail);
                                // Opening the exercise is what clears «Новое»; the mark is decoration, so a failure is ignored.
                                this.exercises.clearNewMark(result.deck.deckId, result.detail.exerciseId).subscribe({ error: () => undefined });
                            },
                            error: () => this.fail('Не удалось сверить упражнение с актуальным материалом.')
                        });
                },
                error: () => this.fail('Не удалось загрузить упражнение.')
            });
    }

    loadMore(): void {
        const deck = this.deck();
        const item = this.item();
        const page = this.page();
        if (deck === null || item === null || page === null || page.nextCursor === null || this.phase() === 'loading') return;
        this.exercises.list(deck.deckId, item.memberKey, page.nextCursor)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: next => {
                    if (next.deckRevisionId !== page.deckRevisionId) { this.load(); return; }
                    this.page.set({ ...next, exercises: [...page.exercises, ...next.exercises] });
                },
                error: () => this.message.set('Не удалось загрузить следующую страницу упражнений.')
            });
    }

    // -----------------------------------------------------------------------------------------
    // Step 1: the type
    // -----------------------------------------------------------------------------------------

    /** A tile was activated. Arrow keys inside the group select but never scroll. */
    choose(choice: MechanicChoice): void {
        const target = choice.mechanic;
        if (target === this.mechanic() && this.pendingSwitch() === null) {
            if (choice.deliberate) this.scrollTo('#exercise-preview-anchor', false, true);
            return;
        }
        if (this.pendingSwitch()?.target === target) return;
        const current = this.mechanic();
        if (current !== null) {
            const lost = mechanicSpecificData(current, this.drafts());
            if (lost.length > 0) { this.pendingSwitch.set({ target, lost }); return; }
        }
        this.applySwitch(target, choice.deliberate);
    }

    confirmSwitch(): void {
        const request = this.pendingSwitch();
        if (request !== null) this.applySwitch(request.target, true);
    }

    /** «Отмена»: the previous type and the whole draft stay as they were. */
    cancelSwitch(): void {
        this.pendingSwitch.set(null);
        afterNextRender({ write: () => this.host.nativeElement
            .querySelector<HTMLElement>('input[name="mechanic"]:checked')?.focus({ preventScroll: true }) }, { injector: this.injector });
    }

    // -----------------------------------------------------------------------------------------
    // Steps 2..N
    // -----------------------------------------------------------------------------------------

    /** «Продолжить»: validates the step that was just filled, then opens the next one and brings it into view. */
    continueFrom(step: StepId): void {
        const mechanic = this.mechanic();
        const context = this.context();
        if (mechanic === null || context === null) return;
        const problems = Object.entries(validateDraft(mechanic, this.drafts(), context)).filter(([key]) => stepOwns(step, key));
        if (problems.length > 0) {
            this.showProblems.set(true);
            this.stepErrors.set(Object.fromEntries(problems));
            return;
        }
        this.stepErrors.set({});
        const index = this.steps().indexOf(step);
        const next = this.steps()[index + 1];
        if (next === undefined) return;
        this.opened.update(current => ({ ...current, [mechanic]: Math.max(current[mechanic], index + 2) }));
        this.scrollTo(`#step-${next}`, true, false);
    }

    stepNumber(index: number): number { return index + 2; }
    titleOf(step: StepId): string {
        const mechanic = this.mechanic()!;
        return mechanic === 'FREE_RESPONSE' && step === 'answers' && this.drafts().FREE_RESPONSE.aiRubric !== null
            ? 'Эталон и пункты проверки' : stepTitle(mechanic, step);
    }
    typeTitle(type: Mechanic): string { return catalogEntry(type).title; }
    promptLabel(step: StepId): string { return PROMPT_LABELS[step]; }
    promptHint(): string | null { return PROMPT_HINTS[this.mechanic()!] ?? null; }
    isPromptStep(step: StepId): boolean { return STEPS_WITH_PROMPT.has(step); }
    promptSpec() { return PROMPT_SLOTS[this.mechanic()!]; }
    readonly referenceSpec = REFERENCE_SLOTS.SELF_CHECK;
    /** Whether the last open step is not the final one, so «Продолжить» belongs under it. */
    canContinueFrom(index: number): boolean { return index === this.openCount() - 1 && index < this.steps().length - 1; }

    setPrompt(blocks: readonly AuthoringBlock[]): void {
        const mechanic = this.mechanic();
        if (mechanic === null) return;
        this.drafts.update(current => ({ ...current, [mechanic]: { ...current[mechanic], prompt: blocks } }));
        this.changed();
    }

    setReference(blocks: readonly AuthoringBlock[]): void {
        this.drafts.update(current => ({ ...current, SELF_CHECK: { ...current.SELF_CHECK, reference: blocks } }));
        this.changed();
    }

    setEnabled(value: boolean): void { this.enabled.set(value); this.changed(); }

    setFreeResponse(draft: FreeResponseDraft): void { this.setDraft('FREE_RESPONSE', draft); }
    setCloze(draft: ClozeDraft): void { this.setDraft('CLOZE', draft); }
    setChoice(draft: ChoiceDraft): void { this.setDraft('CHOICE', draft); }
    setMatch(draft: MatchDraft): void { this.setDraft('MATCH', draft); }
    setOrder(draft: OrderDraft): void { this.setDraft('ORDER', draft); }
    setCategorize(draft: CategorizeDraft): void { this.setDraft('CATEGORIZE', draft); }

    setObjectiveMode(value: ObjectiveMode): void {
        this.objectiveMode.set(value);
        if (value !== 'create') {
            const selected = this.selectedObjective() ?? this.objectives()[0] ?? null;
            this.selectedObjectiveId.set(selected?.objectiveId ?? null);
        }
        this.changed();
    }

    selectObjective(value: string): void {
        this.selectedObjectiveId.set(value === '' ? null : value);
        this.titleEdited.set(false);
        this.changed();
    }

    setObjectiveTitle(value: string): void {
        this.objectiveTitle.set(value);
        this.titleEdited.set(true);
        this.changed();
    }

    /** The finish step only exists once the last content step was completed; Enter in a field cannot skip ahead. */
    submit(): void {
        if (this.openCount() >= this.steps().length) this.save();
    }

    save(retry = false): void {
        const deck = this.deck();
        const item = this.item();
        const context = this.context();
        const mechanic = this.mechanic();
        const current = this.exercise();
        if (deck === null || item === null || context === null || mechanic === null || this.phase() === 'saving') return;
        let pending = retry ? this.pending : null;
        if (pending === null) {
            const errors = this.validate(mechanic, context);
            this.fieldErrors.set(errors);
            this.showProblems.set(true);
            if (Object.keys(errors).length > 0) {
                this.phase.set('rejected');
                this.message.set('Исправьте отмеченные поля. Введённые данные сохранены в этой вкладке.');
                queueMicrotask(() => this.focusErrors());
                return;
            }
            const exercise = buildSpec(mechanic, this.drafts(), { memberKey: item.memberKey, itemRevisionId: item.itemRevisionId },
                this.enabled());
            pending = { commandId: newCommandId(), objective: this.objectiveCommand(), exercise };
            this.pending = pending;
        }
        this.phase.set('saving');
        this.message.set(null);
        const edit = this.proposalEdit();
        if (edit !== null) {
            this.approveProposal(edit, deck, pending);
            return;
        }
        const request = current === null
            ? this.exercises.create(deck.deckId, deck.rowVersion, deck.revisionId,
                pending.objective, pending.exercise, pending.commandId)
            : this.exercises.update(deck.deckId, current.exerciseId, deck.rowVersion, deck.revisionId,
                current.exerciseRevisionId, pending.objective, pending.exercise, pending.commandId);
        request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => this.saved(result),
            error: error => this.writeFailed(error)
        });
    }

    retry(): void {
        if (this.pending === null) this.load();
        else this.save(true);
    }

    resetChanges(): void {
        const deck = this.deck(); const item = this.item(); const page = this.page(); const detail = this.exercise();
        if (!this.dirty() || this.phase() === 'saving' || !deck || !item || !page || !detail) return;
        this.openExisting(deck, item, page, detail);
        this.phase.set('ready'); this.message.set('Изменения отменены.'); this.fieldErrors.set({});
    }

    deleteExercise(): void {
        const deck = this.deck(); const detail = this.exercise();
        if (!deck || !detail || this.phase() === 'saving') return;
        this.phase.set('saving'); this.message.set(null);
        this.exercises.delete(deck.deckId, detail.exerciseId, deck.rowVersion)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: () => { this.dirty.set(false); void this.router.navigate(['/decks', deck.deckId, 'materials', detail.objective.memberKey]); },
                error: error => {
                    const response = error instanceof HttpErrorResponse ? error : null;
                    this.phase.set(response?.status === 409 || response?.status === 412 ? 'conflict' : 'error');
                    this.message.set(response?.status === 409 || response?.status === 412
                        ? 'Упражнение изменилось. Загрузите его снова перед удалением.'
                        : 'Не удалось удалить упражнение. Попробуйте ещё раз.');
                }
            });
    }

    canLeave(): boolean {
        return !this.dirty() || window.confirm('Уйти и потерять неподтверждённые настройки упражнения?');
    }

    // -----------------------------------------------------------------------------------------
    // Existing exercises of this material (bottom of the page)
    // -----------------------------------------------------------------------------------------

    /** The small anchor at the top: moves to the list without opening another page. */
    jumpToList(event: Event): void {
        event.preventDefault();
        this.scrollTo('#existing-exercises', true, true);
    }

    /** Flips the enabled flag of another exercise through a normal revision of that exercise. */
    toggleListed(entry: ExerciseSummary): void {
        const deck = this.deck();
        if (deck === null || this.listBusy() !== null || entry.exerciseId === this.exercise()?.exerciseId) return;
        this.listBusy.set(entry.exerciseId); this.listMessage.set(null);
        this.exercises.read(deck.deckId, entry.exerciseId).pipe(
            switchMap(detail => this.exercises.update(deck.deckId, entry.exerciseId, deck.rowVersion, deck.revisionId,
                detail.exerciseRevisionId, { operation: 'reuse', objectiveId: detail.objective.objectiveId,
                    objectiveRevisionId: detail.objective.objectiveRevisionId },
                { ...specOf(detail), enabled: !detail.enabled }, newCommandId())),
            takeUntilDestroyed(this.destroyRef)
        ).subscribe({
            next: () => this.refreshList('Настройка упражнения обновлена.'),
            error: error => this.listFailed(error, 'Не удалось изменить упражнение. Попробуйте ещё раз.')
        });
    }

    deleteListed(entry: ExerciseSummary): void {
        const deck = this.deck();
        if (deck === null || this.listBusy() !== null || entry.exerciseId === this.exercise()?.exerciseId) return;
        this.listBusy.set(entry.exerciseId); this.listMessage.set(null);
        this.exercises.delete(deck.deckId, entry.exerciseId, deck.rowVersion).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: () => this.refreshList('Упражнение удалено.'),
            error: error => this.listFailed(error, 'Не удалось удалить упражнение. Попробуйте ещё раз.')
        });
    }

    // -----------------------------------------------------------------------------------------

    private applySwitch(target: Mechanic, scroll: boolean): void {
        const current = this.mechanic();
        const carried = current === null ? this.drafts() : carryPrompt(this.drafts(), current, target);
        const changedDraft = carried !== this.drafts();
        this.drafts.set(carried);
        this.mechanic.set(target);
        this.pendingSwitch.set(null);
        this.stepErrors.set({});
        this.opened.update(open => ({ ...open, [target]: Math.max(open[target], 1) }));
        // Exploring the examples of a new exercise is not an edit; carrying a question or changing a saved exercise is.
        if (changedDraft || this.exercise() !== null) this.changed();
        if (scroll) this.scrollTo('#exercise-preview-anchor', false, false);
    }

    private setDraft<T extends Mechanic>(type: T, draft: ExerciseDrafts[T]): void {
        this.drafts.update(current => ({ ...current, [type]: draft }));
        this.changed();
    }

    private openNew(deck: OwnDeck, item: ItemDetail, page: ExercisePage): void {
        this.deck.set(deck); this.item.set(item); this.page.set(page); this.exercise.set(null);
        this.mechanic.set(null); this.pendingSwitch.set(null); this.enabled.set(true); this.drafts.set(emptyDrafts());
        this.opened.set(closedSteps());
        this.objectiveMode.set('create'); this.selectedObjectiveId.set(null);
        this.objectiveTitle.set(''); this.titleEdited.set(false);
        this.stepErrors.set({});
        this.pending = null; this.dirty.set(false); this.showProblems.set(false); this.phase.set('ready');
    }

    private openExisting(deck: OwnDeck, item: ItemDetail, page: ExercisePage, detail: ExerciseDetail): void {
        this.deck.set(deck); this.item.set(item); this.page.set(page); this.exercise.set(detail);
        this.mechanic.set(detail.type); this.pendingSwitch.set(null); this.enabled.set(detail.enabled);
        this.drafts.set(draftsFromDetail(detail));
        // An existing exercise opens with every relevant step filled in and open: no onboarding, no demo.
        this.opened.set({ ...closedSteps(), [detail.type]: catalogEntry(detail.type).steps.length });
        this.objectiveMode.set('reuse'); this.selectedObjectiveId.set(detail.objective.objectiveId);
        this.objectiveTitle.set(detail.objective.title); this.titleEdited.set(false);
        this.stepErrors.set({});
        this.pending = null; this.dirty.set(false); this.showProblems.set(false); this.phase.set('ready');
        if (this.route.snapshot.queryParamMap.get('saved') === '1') {
            this.phase.set('saved'); this.message.set('Упражнение сохранено.');
        }
    }

    /**
     * Opens a Workshop proposal in the editor. Only a proposed exercise of this material is editable; anything else (already
     * decided, another material, not an exercise) says so and offers the way back instead of a form that cannot be saved.
     */
    private openProposal(deck: OwnDeck, item: ItemDetail, page: ExercisePage, sessionId: string, artifact: ArtifactDetail): void {
        const proposal = readProposal(artifact);
        if (proposal === null || artifact.state !== 'PROPOSED' || proposal.exercise.subject.memberKey !== item.memberKey) {
            this.proposalEdit.set(null);
            this.fail('Это упражнение уже нельзя править: его решили, или материал другой. Вернитесь в мастерскую.');
            return;
        }
        this.proposalEdit.set({ sessionId, artifact, proposal });
        this.openNew(deck, item, page);
        const { exercise, objective } = proposal;
        this.mechanic.set(exercise.type);
        this.enabled.set(exercise.enabled);
        this.drafts.set(draftsFromDetail(exercise));
        this.opened.set({ ...closedSteps(), [exercise.type]: catalogEntry(exercise.type).steps.length });
        const known = objective.operation === 'reuse' ? this.objectives().find(value => value.objectiveId === objective.objectiveId) : undefined;
        if (known !== undefined) {
            this.objectiveMode.set('reuse'); this.selectedObjectiveId.set(known.objectiveId);
        } else {
            // A new objective, or a reused one this page has not listed: the title is the objective's name, and the server reuses
            // an objective of the same material and title when there is one.
            this.objectiveMode.set('create'); this.selectedObjectiveId.set(null);
            this.objectiveTitle.set(proposal.objectiveTitle); this.titleEdited.set(true);
        }
    }

    /** Saves the edited proposal: the artifact is approved with the exercise as its `replacement`; then back to the Workshop. */
    private approveProposal(edit: ProposalEdit, deck: OwnDeck, pending: PendingWrite, retried = false): void {
        const artifact = edit.artifact;
        this.generation.approveArtifact(deck.deckId, edit.sessionId, { artifactId: artifact.artifactId,
            expectedArtifactVersion: artifact.rowVersion, expectedRevisionId: artifact.currentRevisionId! },
            { rowVersion: deck.rowVersion, revisionId: deck.revisionId }, pending.commandId,
            { objective: pending.objective, exercise: pending.exercise })
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: () => {
                    this.pending = null; this.dirty.set(false); this.phase.set('saved');
                    this.toast.echo('Упражнение сохранено в колоду');
                    void this.router.navigate(['/decks', deck.deckId, 'workshop', edit.sessionId]);
                },
                error: (error: unknown) => this.proposalFailed(error, edit, deck, pending, retried)
            });
    }

    /**
     * A `412` (the deck moved, or the proposal's version did) never costs the user their edits: only the pins are read again (the
     * deck and the artifact) and the same edited exercise is sent once more as a new command. If the proposal is no longer open for
     * a decision the edits stay in the form and the way back to the Workshop is offered.
     */
    private proposalFailed(error: unknown, edit: ProposalEdit, deck: OwnDeck, pending: PendingWrite, retried: boolean): void {
        const problem = readProblem(error);
        if (problem.uncertain) {
            this.phase.set('error');
            this.message.set('Не удалось проверить сохранение. Повторите попытку: будет отправлена та же команда.');
            return;
        }
        if (problem.status === 412 && !retried) {
            forkJoin({ deck: this.decks.detail(deck.deckId), artifact: this.generation.getArtifact(deck.deckId, edit.sessionId, edit.artifact.artifactId) })
                .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                    next: fresh => {
                        const proposal = readProposal(fresh.artifact);
                        if (proposal === null || fresh.artifact.state !== 'PROPOSED') {
                            this.pending = null;
                            this.phase.set('conflict');
                            this.message.set('Предложение уже решено или изменилось. Ваши правки остались в форме, но сохранить их из него нельзя. Вернитесь в мастерскую.');
                            return;
                        }
                        const next: ProposalEdit = { sessionId: edit.sessionId, artifact: fresh.artifact, proposal };
                        this.deck.set(fresh.deck);
                        this.proposalEdit.set(next);
                        this.pending = { ...pending, commandId: newCommandId() };
                        this.approveProposal(next, fresh.deck, this.pending, true);
                    },
                    error: () => {
                        this.phase.set('error');
                        this.message.set('Не удалось обновить данные колоды. Ваши правки остались в форме: повторите попытку.');
                    }
                });
            return;
        }
        this.pending = null;
        if (problem.status === 409 && problem.reason === 'SOURCE_STALE') {
            this.phase.set('conflict');
            this.message.set('Материал изменился — предложение устарело. Ваши правки остались в форме, но сохранить их из этого предложения уже нельзя. Вернитесь в мастерскую: там упражнение можно пересоздать.');
            return;
        }
        if (problem.status === 412 || problem.status === 409) {
            this.phase.set('conflict');
            this.message.set(`${problemMessage(problem, 'EXERCISES')} Ваши правки остались в форме; можно вернуться в мастерскую.`);
            return;
        }
        this.phase.set('rejected');
        this.message.set(problem.status === 400 || problem.status === 422
            ? 'Упражнение не принято. Проверьте фрагменты материала и настройки ответа.' : problemMessage(problem, 'EXERCISES'));
    }

    private changed(): void {
        this.dirty.set(true); this.pending = null; this.fieldErrors.set({}); this.stepErrors.set({}); this.message.set(null);
        if (this.phase() !== 'loading' && this.phase() !== 'saving') this.phase.set('ready');
    }

    private validate(mechanic: Mechanic, context: SlotContext): DraftErrors {
        const errors: Record<string, string | undefined> = { ...validateDraft(mechanic, this.drafts(), context) };
        const mode = this.objectiveMode();
        if (mode !== 'create' && this.selectedObjective() === null) errors['objective'] = 'Выберите существующую цель.';
        const title = this.effectiveTitle();
        if (mode !== 'reuse' && (isBlank(title) || title.length > LIMITS.objectiveTitle)) {
            errors['objective-title'] = `Назовите цель: от 1 до ${LIMITS.objectiveTitle} знаков.`;
        }
        // Never send AI checking or voice input while the server reports them unavailable.
        const spec = buildSpec(mechanic, this.drafts(), { memberKey: context.memberKey, itemRevisionId: context.itemRevisionId }, true);
        if (spec.type === 'FREE_RESPONSE') {
            const capabilities = this.capabilities();
            if (spec.evaluatorPolicy.id === 'ai-semantic' && !capabilities.aiAssessment.available) {
                errors['capability'] = 'Проверка смысла с ИИ сейчас недоступна, поэтому такое упражнение сохранить нельзя.';
            }
            if (spec.content.responseInput === 'TEXT_OR_SPEECH' && !capabilities.speechToText.available) {
                errors['capability'] = 'Голосовой ответ сейчас недоступен, поэтому такое упражнение сохранить нельзя.';
            }
        }
        return errors;
    }

    private objectiveCommand(): ObjectiveCommand {
        const selected = this.selectedObjective();
        switch (this.objectiveMode()) {
            case 'reuse': return { operation: 'reuse', objectiveId: selected!.objectiveId, objectiveRevisionId: selected!.objectiveRevisionId };
            case 'revise': return { operation: 'revise', objectiveId: selected!.objectiveId,
                expectedObjectiveRevisionId: selected!.objectiveRevisionId, title: this.effectiveTitle() };
            case 'create': return { operation: 'create', title: this.effectiveTitle() };
        }
    }

    private saved(result: ExerciseWriteResult): void {
        this.pending = null; this.dirty.set(false); this.phase.set('saved');
        this.message.set('Упражнение сохранено.');
        const deck = this.deck();
        if (deck === null) return;
        void this.router.navigate(['/decks', deck.deckId, 'exercises', result.acknowledgement.exerciseId, 'edit'],
            { queryParams: { saved: 1 } });
    }

    private writeFailed(error: unknown): void {
        if (error instanceof AuthoringProtocolError) {
            this.pending = null;
            this.phase.set('rejected');
            this.message.set('Упражнение не прошло проверку формата. Проверьте все поля; введённые данные сохранены в этой вкладке.');
            return;
        }
        const response = error instanceof HttpErrorResponse ? error : null;
        if (response === null || response.status === 0 || response.status >= 500) {
            this.phase.set('error');
            this.message.set('Не удалось проверить сохранение. Повторите попытку.');
            return;
        }
        this.pending = null;
        const code = typeof response.error === 'object' && response.error !== null
            ? (response.error as Record<string, unknown>)['code'] : null;
        if (code === 'CAPABILITY_UNAVAILABLE') {
            this.phase.set('rejected');
            this.message.set('Эта возможность сейчас недоступна на сервере. Уберите проверку с ИИ или голосовой ввод и сохраните снова.');
            return;
        }
        if (response.status === 412 || response.status === 409) {
            this.phase.set('conflict');
            this.message.set('Колода или цель изменились в другой вкладке. Ваш ввод сохранён здесь; загрузите свежую основу.');
            return;
        }
        this.phase.set('rejected');
        this.message.set('Не удалось сохранить упражнение. Проверьте фрагменты материала и настройки ответа.');
    }

    /** Re-reads the deck pins and the list after another exercise changed, without touching the open draft. */
    private refreshList(done: string): void {
        const deck = this.deck();
        const item = this.item();
        if (deck === null || item === null) return;
        forkJoin({ deck: this.decks.detail(deck.deckId), page: this.exercises.list(deck.deckId, item.memberKey) })
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: result => {
                    this.deck.set(result.deck); this.page.set(result.page);
                    this.listBusy.set(null); this.listMessage.set(done);
                },
                error: () => { this.listBusy.set(null); this.listMessage.set('Не удалось обновить список. Обновите страницу.'); }
            });
    }

    private listFailed(error: unknown, fallback: string): void {
        const status = error instanceof HttpErrorResponse ? error.status : 0;
        this.listBusy.set(null);
        this.listMessage.set(status === 409 || status === 412
            ? 'Колода изменилась в другой вкладке. Обновите страницу и повторите действие.' : fallback);
    }

    /**
     * Brings a part of the page into view. Only called from an explicit action (a tile, «Продолжить», an anchor),
     * never while typing. Reduced motion jumps instead of animating; a step heading may take focus so keyboard and
     * screen-reader users keep their place.
     */
    private scrollTo(selector: string, focusHeading: boolean, immediate: boolean): void {
        const run = (): void => {
            const target = this.host.nativeElement.querySelector<HTMLElement>(selector);
            if (target === null) return;
            const reduced = this.browserDocument.defaultView?.matchMedia('(prefers-reduced-motion: reduce)').matches === true;
            target.scrollIntoView({ block: 'start', behavior: reduced ? 'auto' : 'smooth' });
            if (focusHeading) target.querySelector<HTMLElement>('h2')?.focus({ preventScroll: true });
        };
        // Right after a state change the target may not exist yet, so wait for the next render; an anchor can go now.
        if (immediate) run();
        else afterNextRender({ write: run }, { injector: this.injector });
    }

    private focusErrors(): void {
        this.browserDocument.querySelector<HTMLElement>('#exercise-errors')?.focus();
    }

    private fail(message: string): void { this.phase.set('error'); this.message.set(message); }
}

export function canLeaveExerciseAuthoring(component: ExerciseAuthoringPageComponent): boolean {
    return component.canLeave();
}

/** Every mechanic starts with no step open; 0 means its type was never chosen. */
function closedSteps(): Record<Mechanic, number> {
    return Object.fromEntries(MECHANICS.map(mechanic => [mechanic, 0])) as Record<Mechanic, number>;
}

function uniqueObjectives(values: readonly ExerciseObjective[]): readonly ExerciseObjective[] {
    const result = new Map<string, ExerciseObjective>();
    values.forEach(value => result.set(value.objectiveId, value));
    return [...result.values()];
}

/** First readable line of the question, at most 160 UTF-16 units and never ending inside a surrogate pair. */
function suggestTitle(type: Mechanic | null, drafts: ExerciseDrafts, context: SlotContext | null): string {
    if (type === null) return '';
    const prompt = drafts[type].prompt;
    let source = '';
    for (const block of prompt) {
        const text = block.kind === 'TEXT' ? block.text : context === null ? null : materialText(block, context);
        if (text !== null && !isBlank(text)) { source = text; break; }
    }
    if (source === '' && type === 'CLOZE') source = drafts.CLOZE.texts.find(text => !isBlank(text)) ?? '';
    const line = source.replace(/\s+/gu, ' ').trim();
    if (line.length <= LIMITS.objectiveTitle) return line;
    const cut = line.slice(0, LIMITS.objectiveTitle - 1);
    const safe = /[\uD800-\uDBFF]$/u.test(cut) ? cut.slice(0, -1) : cut;
    return `${safe}…`;
}
