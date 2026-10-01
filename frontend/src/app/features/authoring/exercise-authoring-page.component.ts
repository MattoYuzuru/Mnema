import { HttpErrorResponse } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import {
    ExerciseSpec, LIMITS, MECHANICS, Mechanic, ObjectiveCommand, authoringSlots, isBlank
} from '../../content/exercise/exercise-content.models';
import { NativeMediaSurfaceComponent } from '../../content/rendering/native-media-surface.component';
import { MnemaSelectComponent, MnemaSelectOption } from '../../core/controls/mnema-select.component';
import { HoldToDeleteButtonComponent } from '../../shared/hold-to-delete-button.component';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { AuthoringProtocolError, ItemDetail, newCommandId } from './authoring.models';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from './capabilities-api.service';
import { ExerciseApiService } from './exercise-api.service';
import {
    ChoiceDraft, ClozeDraft, DraftErrors, ExerciseDrafts, FreeResponseDraft, MatchDraft, SelfCheckDraft, SlotContext,
    buildSpec, draftsFromDetail, emptyDrafts, materialText, validateDraft
} from './exercise-draft';
import { ExercisePreviewComponent } from './exercise-preview.component';
import {
    ExerciseDetail, ExerciseObjective, ExercisePage, ExerciseWriteResult, textProjections
} from './exercise.models';
import { ItemApiService } from './item-api.service';
import {
    ChoiceEditorComponent, ClozeEditorComponent, FreeResponseEditorComponent, MatchEditorComponent, SelfCheckEditorComponent
} from './mechanic-editors';

type Phase = 'loading' | 'ready' | 'saving' | 'saved' | 'conflict' | 'rejected' | 'error';
type ObjectiveMode = 'create' | 'reuse' | 'revise';

interface PendingWrite {
    readonly commandId: string;
    readonly objective: ObjectiveCommand;
    readonly exercise: ExerciseSpec;
}

const MECHANIC_NAMES: Readonly<Record<Mechanic, { readonly name: string; readonly example: string }>> = {
    SELF_CHECK: { name: 'Вспомнить и сверить', example: 'Вспомните ответ, откройте эталон и честно оцените себя.' },
    FREE_RESPONSE: { name: 'Ввести ответ', example: 'Напишите ответ; подойдёт любая из допустимых формулировок.' },
    CLOZE: { name: 'Заполнить пропуски', example: 'Вставьте пропуски в текст или код.' },
    CHOICE: { name: 'Выбрать ответ', example: 'Один или несколько правильных вариантов.' },
    MATCH: { name: 'Соединить пары', example: 'Сопоставьте слова, записи, картинки или видео.' }
};

@Component({
    selector: 'app-exercise-authoring-page',
    imports: [RouterLink, NativeMediaSurfaceComponent, MnemaSelectComponent, HoldToDeleteButtonComponent, ExercisePreviewComponent,
        SelfCheckEditorComponent, FreeResponseEditorComponent, ClozeEditorComponent, ChoiceEditorComponent, MatchEditorComponent],
    templateUrl: './exercise-authoring-page.component.html',
    styleUrl: './exercise-authoring-page.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ExerciseAuthoringPageComponent {
    readonly mechanics = MECHANICS;
    readonly deck = signal<OwnDeck | null>(null);
    readonly item = signal<ItemDetail | null>(null);
    readonly exercise = signal<ExerciseDetail | null>(null);
    readonly page = signal<ExercisePage | null>(null);
    readonly phase = signal<Phase>('loading');
    readonly dirty = signal(false);
    readonly message = signal<string | null>(null);
    readonly fieldErrors = signal<DraftErrors>({});
    /** Set after the first failed save so untouched new blocks are not flagged while the author is still typing. */
    readonly showProblems = signal(false);
    readonly capabilities = signal<LearningCapabilities>(CAPABILITIES_UNAVAILABLE);

    readonly type = signal<Mechanic>('FREE_RESPONSE');
    readonly enabled = signal(true);
    readonly drafts = signal<ExerciseDrafts>(emptyDrafts());
    readonly objectiveMode = signal<ObjectiveMode>('create');
    readonly selectedObjectiveId = signal<string | null>(null);
    readonly objectiveTitle = signal('');
    private readonly titleEdited = signal(false);

    readonly projections = computed(() => this.item() === null ? [] : textProjections(this.item()!.document));
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
    readonly suggestedTitle = computed(() => suggestTitle(this.type(), this.drafts(), this.context()));
    readonly effectiveTitle = computed(() => {
        if (this.titleEdited()) return this.objectiveTitle();
        return this.objectiveMode() === 'create' ? this.suggestedTitle() : this.selectedObjective()?.title ?? '';
    });
    readonly errorSummary = computed(() => Object.values(this.fieldErrors()).filter((text): text is string => text !== undefined));
    readonly staleProjection = computed(() => {
        const detail = this.exercise();
        const item = this.item();
        if (item === null) return false;
        const stale = (revision: string) => revision !== item.itemRevisionId;
        return (detail !== null && stale(detail.subject.itemRevisionId))
            || authoringSlots(buildSpec(this.type(), this.drafts(), { memberKey: item.memberKey, itemRevisionId: item.itemRevisionId }, true))
                .flat().some(block => block.kind === 'MATERIAL' && block.memberKey === item.memberKey && stale(block.itemRevisionId));
    });

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly browserDocument = inject(DOCUMENT);
    private readonly decks = inject(OwnDecksApiService);
    private readonly items = inject(ItemApiService);
    private readonly exercises = inject(ExerciseApiService);
    private readonly capabilityApi = inject(CapabilitiesApiService);
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
            forkJoin({ deck: this.decks.detail(deckId), item: this.items.read(deckId, routeMember!),
                page: this.exercises.list(deckId, routeMember!) })
                .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                    next: result => this.openNew(result.deck, result.item, result.page),
                    error: () => this.fail('Не удалось загрузить материал и упражнения.')
                });
            return;
        }
        forkJoin({ deck: this.decks.detail(deckId), detail: this.exercises.read(deckId, exerciseId) })
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: result => {
                    const member = result.detail.objective.memberKey;
                    forkJoin({ item: this.items.read(deckId, member), page: this.exercises.list(deckId, member) })
                        .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                            next: content => this.openExisting(result.deck, content.item, content.page, result.detail),
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

    /** Switching only changes which draft is shown: everything typed for the other mechanics is kept. */
    setType(value: Mechanic): void { this.type.set(value); this.changed(); }
    setEnabled(value: boolean): void { this.enabled.set(value); this.changed(); }

    setSelfCheck(draft: SelfCheckDraft): void { this.setDraft('SELF_CHECK', draft); }
    setFreeResponse(draft: FreeResponseDraft): void { this.setDraft('FREE_RESPONSE', draft); }
    setCloze(draft: ClozeDraft): void { this.setDraft('CLOZE', draft); }
    setChoice(draft: ChoiceDraft): void { this.setDraft('CHOICE', draft); }
    setMatch(draft: MatchDraft): void { this.setDraft('MATCH', draft); }

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

    save(retry = false): void {
        const deck = this.deck();
        const item = this.item();
        const context = this.context();
        const current = this.exercise();
        if (deck === null || item === null || context === null || this.phase() === 'saving') return;
        let pending = retry ? this.pending : null;
        if (pending === null) {
            const errors = this.validate(context);
            this.fieldErrors.set(errors);
            this.showProblems.set(true);
            if (Object.keys(errors).length > 0) {
                this.phase.set('rejected');
                this.message.set('Исправьте отмеченные поля. Введённые данные сохранены в этой вкладке.');
                queueMicrotask(() => this.focusErrors());
                return;
            }
            const exercise = buildSpec(this.type(), this.drafts(), { memberKey: item.memberKey, itemRevisionId: item.itemRevisionId },
                this.enabled());
            pending = { commandId: newCommandId(), objective: this.objectiveCommand(), exercise };
            this.pending = pending;
        }
        this.phase.set('saving');
        this.message.set(null);
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

    mechanicName(type: Mechanic): string { return MECHANIC_NAMES[type].name; }
    mechanicExample(type: Mechanic): string { return MECHANIC_NAMES[type].example; }

    private setDraft<T extends Mechanic>(type: T, draft: ExerciseDrafts[T]): void {
        this.drafts.update(current => ({ ...current, [type]: draft }));
        this.changed();
    }

    private openNew(deck: OwnDeck, item: ItemDetail, page: ExercisePage): void {
        this.deck.set(deck); this.item.set(item); this.page.set(page); this.exercise.set(null);
        this.type.set('FREE_RESPONSE'); this.enabled.set(true); this.drafts.set(emptyDrafts());
        this.objectiveMode.set('create'); this.selectedObjectiveId.set(null);
        this.objectiveTitle.set(''); this.titleEdited.set(false);
        this.pending = null; this.dirty.set(false); this.showProblems.set(false); this.phase.set('ready');
    }

    private openExisting(deck: OwnDeck, item: ItemDetail, page: ExercisePage, detail: ExerciseDetail): void {
        this.deck.set(deck); this.item.set(item); this.page.set(page); this.exercise.set(detail);
        this.type.set(detail.type); this.enabled.set(detail.enabled); this.drafts.set(draftsFromDetail(detail));
        this.objectiveMode.set('reuse'); this.selectedObjectiveId.set(detail.objective.objectiveId);
        this.objectiveTitle.set(detail.objective.title); this.titleEdited.set(false);
        this.pending = null; this.dirty.set(false); this.showProblems.set(false); this.phase.set('ready');
        if (this.route.snapshot.queryParamMap.get('saved') === '1') {
            this.phase.set('saved'); this.message.set('Упражнение сохранено.');
        }
    }

    private changed(): void {
        this.dirty.set(true); this.pending = null; this.fieldErrors.set({}); this.message.set(null);
        if (this.phase() !== 'loading' && this.phase() !== 'saving') this.phase.set('ready');
    }

    private validate(context: SlotContext): DraftErrors {
        const errors: Record<string, string | undefined> = { ...validateDraft(this.type(), this.drafts(), context) };
        const mode = this.objectiveMode();
        if (mode !== 'create' && this.selectedObjective() === null) errors['objective'] = 'Выберите существующую цель.';
        const title = this.effectiveTitle();
        if (mode !== 'reuse' && (isBlank(title) || title.length > LIMITS.objectiveTitle)) {
            errors['objective-title'] = `Назовите цель: от 1 до ${LIMITS.objectiveTitle} знаков.`;
        }
        // Never send AI checking or voice input while the server reports them unavailable.
        const spec = buildSpec(this.type(), this.drafts(), { memberKey: context.memberKey, itemRevisionId: context.itemRevisionId }, true);
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

    private focusErrors(): void {
        this.browserDocument.querySelector<HTMLElement>('#exercise-errors')?.focus();
    }

    private fail(message: string): void { this.phase.set('error'); this.message.set(message); }
}

export function canLeaveExerciseAuthoring(component: ExerciseAuthoringPageComponent): boolean {
    return component.canLeave();
}

function uniqueObjectives(values: readonly ExerciseObjective[]): readonly ExerciseObjective[] {
    const result = new Map<string, ExerciseObjective>();
    values.forEach(value => result.set(value.objectiveId, value));
    return [...result.values()];
}

/** First readable line of the question, at most 160 UTF-16 units and never ending inside a surrogate pair. */
function suggestTitle(type: Mechanic, drafts: ExerciseDrafts, context: SlotContext | null): string {
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
