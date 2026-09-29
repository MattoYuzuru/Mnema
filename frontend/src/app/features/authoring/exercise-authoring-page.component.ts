import { HttpErrorResponse } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { NativeMediaSurfaceComponent } from '../../content/rendering/native-media-surface.component';
import { MnemaSelectComponent, MnemaSelectOption } from '../../core/controls/mnema-select.component';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { newCommandId } from './authoring.models';
import { ExerciseApiService } from './exercise-api.service';
import {
    AnswerContract, ExerciseDetail, ExerciseObjective, ExercisePage, ExerciseProjection, ExerciseType,
    ExerciseWriteResult, textProjections
} from './exercise.models';
import { ItemApiService } from './item-api.service';
import { ItemDetail } from './authoring.models';
import { NativeMediaUploadComponent } from './native-media-upload.component';
import { NativeMediaKind } from './native-media-upload.api';

type Phase = 'loading' | 'ready' | 'saving' | 'saved' | 'conflict' | 'rejected' | 'error';
type ObjectiveMode = 'create' | 'reuse' | 'revise';
type PromptMode = 'node' | 'custom';
type AnswerMode = 'node' | 'custom';

interface PendingWrite {
    readonly commandId: string;
    readonly objective: Record<string, unknown>;
    readonly exercise: Record<string, unknown>;
}

interface MatchRow {
    readonly cueId: string;
    readonly assetId: string;
    readonly title: string;
    readonly transcript: string;
    readonly optionNodeId: string;
}

@Component({
    selector: 'app-exercise-authoring-page',
    imports: [RouterLink, NativeMediaSurfaceComponent, NativeMediaUploadComponent, MnemaSelectComponent],
    templateUrl: './exercise-authoring-page.component.html',
    styleUrl: './exercise-authoring-page.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ExerciseAuthoringPageComponent {
    readonly deck = signal<OwnDeck | null>(null);
    readonly item = signal<ItemDetail | null>(null);
    readonly exercise = signal<ExerciseDetail | null>(null);
    readonly page = signal<ExercisePage | null>(null);
    readonly phase = signal<Phase>('loading');
    readonly dirty = signal(false);
    readonly message = signal<string | null>(null);
    readonly fieldErrors = signal<Record<string, string>>({});

    readonly type = signal<ExerciseType>('TYPED');
    readonly enabled = signal(true);
    readonly promptMode = signal<PromptMode>('node');
    readonly answerMode = signal<AnswerMode>('node');
    readonly promptNodeId = signal<string | null>(null);
    readonly customPrompt = signal('');
    readonly answerNodeId = signal<string | null>(null);
    readonly aliasesText = signal('');
    readonly optionNodeIds = signal<readonly string[]>([]);
    readonly audioAssetId = signal('');
    readonly audioTitle = signal('');
    readonly audioInstruction = signal('');
    readonly audioTranscript = signal('');
    readonly matchRows = signal<readonly MatchRow[]>([newMatchRow(), newMatchRow()]);
    readonly audioTargetIndex = signal(0);
    readonly objectiveMode = signal<ObjectiveMode>('create');
    readonly selectedObjectiveId = signal<string | null>(null);

    readonly projections = computed(() => this.item() === null ? [] : textProjections(this.item()!.document));
    readonly projectionOptions = computed<readonly MnemaSelectOption[]>(() => [
        { value: '', label: 'Выберите фрагмент' },
        ...this.projections().map(projection => ({ value: projection.nodeId, label: projection.label }))
    ]);
    readonly objectives = computed(() => uniqueObjectives(this.page()?.exercises.map(value => value.objective) ?? []));
    readonly objectiveOptions = computed<readonly MnemaSelectOption[]>(() => [
        { value: '', label: 'Выберите цель' },
        ...this.objectives().map(objective => ({ value: objective.objectiveId, label: this.objectiveName(objective) }))
    ]);
    readonly selectedObjective = computed(() => this.objectives()
        .find(value => value.objectiveId === this.selectedObjectiveId()) ?? null);
    readonly previewPrompt = computed(() => this.isListening() ? this.audioInstruction().trim() || 'Прослушайте запись'
        : this.promptMode() === 'custom'
        ? this.customPrompt().trim() : this.projection(this.promptNodeId())?.text ?? 'Выберите фрагмент вопроса');
    readonly previewAnswer = computed(() => this.answerMode() === 'custom'
        ? this.aliases()[0] ?? 'Введите свой ответ'
        : this.projection(this.answerNodeId())?.text ?? 'Выберите проверяемый ответ');
    readonly staleProjection = computed(() => {
        const detail = this.exercise();
        const item = this.item();
        if (detail === null || item === null) return false;
        return detail.bindings.some(binding => binding.memberKey === item.memberKey
            && binding.itemRevisionId !== item.itemRevisionId);
    });

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly browserDocument = inject(DOCUMENT);
    private readonly decks = inject(OwnDecksApiService);
    private readonly items = inject(ItemApiService);
    private readonly exercises = inject(ExerciseApiService);
    private readonly destroyRef = inject(DestroyRef);
    private pending: PendingWrite | null = null;

    constructor() { this.load(); }

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

    setType(value: ExerciseType): void {
        if (value === 'AUDIO_TEXT_MATCH' && this.type() !== value) {
            this.objectiveMode.set('create');
            this.selectedObjectiveId.set(null);
        }
        if (value === 'SINGLE_CHOICE' || value === 'LISTEN_CHOICE' || value === 'AUDIO_TEXT_MATCH') {
            this.answerMode.set('node');
        }
        this.type.set(value); this.changed();
    }
    setEnabled(value: boolean): void { this.enabled.set(value); this.changed(); }
    setPromptMode(value: PromptMode, event?: MouseEvent): void {
        this.waveOrigin(event); this.promptMode.set(value); this.changed();
    }
    setAnswerMode(value: AnswerMode, event?: MouseEvent): void {
        this.waveOrigin(event);
        if (value !== this.answerMode()) {
            const answer = this.selectedObjective()?.answerContract;
            this.aliasesText.set(value === 'node' ? this.projection(this.answerNodeId())?.text ?? ''
                : this.objectiveMode() === 'reuse' && answer?.schemaVersion === 1
                ? answer.accepted.join('\n')
                : '');
            this.answerMode.set(value);
        }
        this.changed();
    }
    setPromptNode(value: string): void { this.promptNodeId.set(value); this.changed(); }
    setCustomPrompt(value: string): void { this.customPrompt.set(value); this.changed(); }
    setAnswerNode(value: string): void {
        const previous = this.answerNodeId();
        this.answerNodeId.set(value);
        if (this.type() === 'SINGLE_CHOICE' || this.type() === 'LISTEN_CHOICE') {
            this.optionNodeIds.update(options => [...new Set(options.filter(id => id !== previous).concat(value))]);
        } else if (this.type() === 'AUDIO_TEXT_MATCH' && this.matchRows()[0].optionNodeId === '') {
            this.matchRows.update(rows => rows.map((row, index) => index === 0
                ? { ...row, optionNodeId: value } : row));
        }
        const projection = this.projection(value);
        if (projection !== null && this.objectiveMode() !== 'reuse') this.aliasesText.set(projection.text);
        this.changed();
    }
    setAliases(value: string): void { this.aliasesText.set(value); this.changed(); }
    supportsCustomAnswer(): boolean {
        return this.type() === 'SELF_CHECK' || this.type() === 'TYPED'
            || this.type() === 'CLOZE_SINGLE' || this.type() === 'LISTEN_TYPE';
    }
    isListening(): boolean { return this.type() === 'LISTEN_CHOICE' || this.type() === 'LISTEN_TYPE'
        || this.type() === 'AUDIO_TEXT_MATCH'; }
    setAudio(field: 'assetId' | 'title' | 'instruction' | 'transcript', value: string): void {
        ({ assetId: this.audioAssetId, title: this.audioTitle, instruction: this.audioInstruction,
            transcript: this.audioTranscript })[field].set(value);
        this.changed();
    }
    setMatchRow(index: number, field: 'assetId' | 'title' | 'transcript' | 'optionNodeId', value: string): void {
        this.matchRows.update(rows => rows.map((row, position) => position === index ? { ...row, [field]: value } : row));
        this.changed();
    }
    selectAudioTarget(index: number): void { this.audioTargetIndex.set(index); }
    chooseUploadedAudio(selection: { kind: NativeMediaKind; assetId: string }): void {
        if (selection.kind !== 'audio') {
            this.message.set('Для аудирования выберите аудиофайл или запись с микрофона.');
            return;
        }
        if (this.type() === 'AUDIO_TEXT_MATCH') {
            const index = Math.min(this.audioTargetIndex(), this.matchRows().length - 1);
            this.setMatchRow(index, 'assetId', selection.assetId);
            if (!this.matchRows()[index].title.trim()) this.setMatchRow(index, 'title', `Запись ${index + 1}`);
        } else {
            this.setAudio('assetId', selection.assetId);
            if (!this.audioTitle().trim()) this.setAudio('title', 'Аудиозапись');
        }
        this.message.set('Аудиозапись выбрана. Сохраните упражнение, чтобы применить изменение.');
    }
    addMatchRow(): void {
        if (this.matchRows().length < 6) { this.matchRows.update(rows => [...rows, newMatchRow()]); this.changed(); }
    }
    removeMatchRow(index: number): void {
        if (this.matchRows().length > 2) { this.matchRows.update(rows => rows.filter((_, position) => position !== index));
            this.changed(); }
    }

    setObjectiveMode(value: ObjectiveMode): void {
        this.objectiveMode.set(value);
        if (value === 'reuse' || value === 'revise') {
            const selected = this.selectedObjective() ?? this.objectives()[0] ?? null;
            this.selectedObjectiveId.set(selected?.objectiveId ?? null);
            if (selected?.answerContract.schemaVersion === 1) this.aliasesText.set(selected.answerContract.accepted.join('\n'));
        }
        this.changed();
    }

    selectObjective(value: string): void {
        this.selectedObjectiveId.set(value);
        const selected = this.objectives().find(objective => objective.objectiveId === value);
        if (selected?.answerContract.schemaVersion === 1) this.aliasesText.set(selected.answerContract.accepted.join('\n'));
        this.changed();
    }

    toggleOption(nodeId: string, checked: boolean): void {
        this.optionNodeIds.update(values => checked
            ? [...new Set([...values, nodeId])] : values.filter(value => value !== nodeId));
        this.changed();
    }

    save(retry = false): void {
        const deck = this.deck();
        const item = this.item();
        const current = this.exercise();
        if (deck === null || item === null || this.phase() === 'saving') return;
        let pending = retry ? this.pending : null;
        if (pending === null) {
            const errors = this.validate();
            this.fieldErrors.set(errors);
            if (Object.keys(errors).length > 0) {
                this.phase.set('rejected');
                this.message.set('Исправьте отмеченные поля. Введённые данные сохранены в этой вкладке.');
                queueMicrotask(() => this.focusFirstError());
                return;
            }
            const exercise = this.exercisePayload(item);
            pending = { commandId: newCommandId(), objective: this.objectivePayload(exercise), exercise };
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

    canLeave(): boolean {
        return !this.dirty() || window.confirm('Уйти и потерять неподтверждённые настройки упражнения?');
    }

    exerciseName(type: ExerciseType): string {
        const names: Record<ExerciseType, string> = { SELF_CHECK: 'Вспомнить и сверить', TYPED: 'Ввести ответ', CLOZE_SINGLE: 'Заполнить пропуск',
            SINGLE_CHOICE: 'Выбрать один вариант', LISTEN_CHOICE: 'Аудирование · выбор',
            AUDIO_TEXT_MATCH: 'Аудирование · пары', LISTEN_TYPE: 'Аудирование · написать' };
        return names[type];
    }

    exerciseExample(type: ExerciseType): string {
        const examples: Record<ExerciseType, string> = {
            SELF_CHECK: 'Вспомните ответ, затем откройте его и оцените себя.',
            TYPED: 'Напишите ответ своими словами; Mnema сверит его с допустимыми вариантами.',
            CLOZE_SINGLE: 'Впишите пропущенное слово или короткую фразу.',
            SINGLE_CHOICE: 'Найдите правильный ответ среди нескольких вариантов.',
            LISTEN_CHOICE: 'Прослушайте запись и выберите подходящий текст.',
            AUDIO_TEXT_MATCH: 'Соедините несколько записей с соответствующими текстами.',
            LISTEN_TYPE: 'Прослушайте запись и напишите ответ.'
        };
        return examples[type];
    }

    objectiveName(objective: ExerciseObjective): string {
        return objective.answerContract.schemaVersion === 1 ? objective.answerContract.accepted[0] ?? 'Цель без подписи'
            : 'Соотнесение записей и текста';
    }

    projection(id: string | null): ExerciseProjection | null {
        return this.projections().find(value => value.nodeId === id) ?? null;
    }

    private openNew(deck: OwnDeck, item: ItemDetail, page: ExercisePage): void {
        this.deck.set(deck); this.item.set(item); this.page.set(page); this.exercise.set(null);
        this.type.set('TYPED'); this.enabled.set(true); this.promptMode.set('node');
        this.promptNodeId.set(null); this.customPrompt.set(''); this.answerMode.set('node'); this.answerNodeId.set(null);
        this.aliasesText.set(''); this.optionNodeIds.set([]); this.objectiveMode.set('create');
        this.audioAssetId.set(''); this.audioTitle.set(''); this.audioInstruction.set(''); this.audioTranscript.set('');
        this.matchRows.set([newMatchRow(), newMatchRow()]);
        this.selectedObjectiveId.set(null); this.pending = null; this.dirty.set(false); this.phase.set('ready');
    }

    private openExisting(deck: OwnDeck, item: ItemDetail, page: ExercisePage, detail: ExerciseDetail): void {
        this.deck.set(deck); this.item.set(item); this.page.set(page); this.exercise.set(detail);
        this.type.set(detail.type); this.enabled.set(detail.enabled);
        if (detail.prompt.kind === 'AUDIO_ASSET') {
            this.audioAssetId.set(detail.prompt.assetId); this.audioTitle.set(detail.prompt.title);
            this.audioInstruction.set(detail.prompt.instruction); this.audioTranscript.set(detail.prompt.transcript);
        } else if (detail.prompt.kind === 'AUDIO_MATCH') {
            this.audioInstruction.set(detail.prompt.instruction);
            const pairs = detail.objective.answerContract.schemaVersion === 2 ? detail.objective.answerContract.pairs : [];
            this.matchRows.set(detail.prompt.cues.map(cue => ({ ...cue, optionNodeId: detail.bindings
                .find(binding => binding.bindingId === pairs.find(pair => pair.cueId === cue.cueId)?.optionId)
                ?.nodeIds[0] ?? '' })));
        } else if (detail.prompt.kind === 'CUSTOM_TEXT') {
            this.promptMode.set('custom'); this.customPrompt.set(detail.prompt.text); this.promptNodeId.set(null);
        } else {
            this.promptMode.set('node'); this.promptNodeId.set(detail.prompt.nodeId); this.customPrompt.set('');
        }
        const assessed = detail.bindings.find(binding => binding.role === 'ASSESSED');
        this.answerMode.set(assessed?.display.kind === 'CUSTOM_TEXT' ? 'custom' : 'node');
        this.answerNodeId.set(assessed?.nodeIds[0] ?? null);
        this.optionNodeIds.set(detail.bindings.filter(binding => binding.role === 'OPTION')
            .flatMap(binding => binding.nodeIds));
        this.aliasesText.set(detail.objective.answerContract.schemaVersion === 1
            ? detail.objective.answerContract.accepted.join('\n') : '');
        this.objectiveMode.set(detail.type === 'AUDIO_TEXT_MATCH' ? 'revise' : 'reuse');
        this.selectedObjectiveId.set(detail.objective.objectiveId);
        this.pending = null; this.dirty.set(false); this.phase.set('ready');
        if (this.route.snapshot.queryParamMap.get('saved') === '1') {
            this.phase.set('saved'); this.message.set('Упражнение сохранено.');
        }
    }

    private changed(): void {
        this.dirty.set(true); this.pending = null; this.fieldErrors.set({}); this.message.set(null);
        if (this.phase() !== 'loading' && this.phase() !== 'saving') this.phase.set('ready');
    }

    private validate(): Record<string, string> {
        const errors: Record<string, string> = {};
        if (this.isListening()) {
            if (!this.audioInstruction().trim() || [...this.audioInstruction()].length > 80) {
                errors['prompt'] = 'Добавьте инструкцию до 80 символов.';
            }
            const cues = this.type() === 'AUDIO_TEXT_MATCH' ? this.matchRows() : [{ assetId: this.audioAssetId(),
                title: this.audioTitle(), transcript: this.audioTranscript() }];
            if (cues.some(cue => !validUuid(cue.assetId) || !cue.title.trim()
                || new TextEncoder().encode(cue.title).length > 1024
                || new TextEncoder().encode(cue.transcript).length > 16384)
                || new Set(cues.map(cue => cue.assetId)).size !== cues.length) {
                errors['audio'] = 'Укажите разные ID доступных записей, названия и допустимые транскрипты.';
            }
            if (this.type() === 'AUDIO_TEXT_MATCH') {
                const rows = this.matchRows();
                if (rows.length < 2 || rows.length > 6 || rows.some(row => this.projection(row.optionNodeId) === null)
                    || new Set(rows.map(row => row.optionNodeId)).size !== rows.length
                    || !rows.some(row => row.optionNodeId === this.answerNodeId())) {
                    errors['options'] = 'Соотнесите 2–6 разных записей с разными фрагментами, включая проверяемый.';
                }
                if (this.objectiveMode() === 'reuse') errors['objective'] = 'Для пар создайте или обновите цель.';
            }
        } else {
        const prompt = this.promptMode() === 'node' ? this.projection(this.promptNodeId()) : null;
        if (this.promptMode() === 'node' && prompt === null) errors['prompt'] = 'Выберите актуальный фрагмент вопроса.';
        else if (prompt !== null && [...prompt.text].length > 80) {
            errors['prompt'] = 'Фрагмент вопроса слишком длинный; выберите короткий узел или свой текст.';
        }
        if (this.promptMode() === 'custom') {
            const text = this.customPrompt().trim();
            if (text.length === 0) errors['prompt'] = 'Введите короткий вопрос.';
            else if ([...text].length > 80) errors['prompt'] = 'Сократите вопрос до 80 символов.';
        }
        }
        const answer = this.answerMode() === 'node' ? this.projection(this.answerNodeId()) : null;
        if (this.answerMode() === 'custom' && !this.supportsCustomAnswer()) {
            errors['answer'] = 'Для этого типа упражнения выберите фрагмент материала.';
        } else if (this.answerMode() === 'node' && answer === null) {
            errors['answer'] = 'Выберите актуальный проверяемый фрагмент.';
        } else if (answer !== null && [...answer.text].length > 80) {
            errors['answer'] = 'Ответ слишком длинный; выберите более короткий фрагмент.';
        }
        const aliases = this.aliases();
        if (this.type() !== 'AUDIO_TEXT_MATCH' && this.objectiveMode() !== 'reuse' && aliases.length === 0) errors['aliases'] = 'Добавьте хотя бы один допустимый ответ.';
        if (this.answerMode() === 'custom' && (aliases.length === 0 || [...aliases[0]].length > 80
            || new TextEncoder().encode(aliases[0]).length > 320)) {
            errors['aliases'] = 'Первый ответ должен быть коротким: до 80 символов.';
        }
        if (this.type() !== 'AUDIO_TEXT_MATCH' && aliases.length > 20) errors['aliases'] = 'Допустимо не более 20 вариантов ответа.';
        if (this.type() !== 'AUDIO_TEXT_MATCH' && aliases.some(value => new TextEncoder().encode(value).length > 512)) {
            errors['aliases'] = 'Один из ответов слишком длинный.';
        }
        if (this.type() !== 'AUDIO_TEXT_MATCH' && answer !== null && this.objectiveMode() !== 'reuse'
            && !aliases.some(value => normalized(value) === normalized(answer.text))) {
            errors['aliases'] = 'Допустимые ответы должны включать текст выбранного правильного фрагмента.';
        }
        if ((this.objectiveMode() === 'reuse' || this.objectiveMode() === 'revise') && this.selectedObjective() === null) {
            errors['objective'] = 'Выберите существующую цель.';
        }
        const existingAnswer = this.selectedObjective()?.answerContract;
        if (answer !== null && this.objectiveMode() === 'reuse' && existingAnswer !== undefined
            && (existingAnswer.schemaVersion !== 1
                || !existingAnswer.accepted.some(value => normalized(value) === normalized(answer.text)))) {
            errors['objective'] = 'Этот фрагмент не соответствует сохранённому ответу цели. Обновите цель или создайте отдельную.';
        }
        if (this.answerMode() === 'custom' && this.objectiveMode() === 'reuse' && existingAnswer !== undefined
            && (existingAnswer.schemaVersion !== 1 || !existingAnswer.accepted.includes(aliases[0]))) {
            errors['objective'] = 'Свой ответ должен совпадать с одним из ответов выбранного знания.';
        }
        if (this.type() === 'SINGLE_CHOICE' || this.type() === 'LISTEN_CHOICE') {
            const options = this.optionNodeIds().map(id => this.projection(id)).filter(value => value !== null);
            if (options.length < 2 || options.length > 6) errors['options'] = 'Выберите от 2 до 6 вариантов.';
            if (answer !== null && !options.some(value => value.nodeId === answer.nodeId)) {
                errors['options'] = 'Правильный ответ должен входить в список вариантов.';
            }
            const normalized = options.map(value => value.text.normalize('NFC').trim().toLowerCase());
            if (new Set(normalized).size !== normalized.length) errors['options'] = 'Варианты не должны повторяться.';
            if (options.some(value => [...value.text].length > 80)) {
                errors['options'] = 'Для варианта ответа выберите фрагмент не длиннее 80 символов.';
            }
        }
        return errors;
    }

    private aliases(): readonly string[] {
        return [...new Set(this.aliasesText().split(/\r?\n/u).map(value => value.trim()).filter(Boolean))];
    }

    private answerContract(exercise: Record<string, unknown>): AnswerContract {
        if (this.type() === 'AUDIO_TEXT_MATCH') {
            const bindings = exercise['bindings'] as Record<string, unknown>[];
            return { schemaVersion: 2, pairs: this.matchRows().map((row, index) => ({ cueId: row.cueId,
                optionId: bindings[index + 1]['bindingId'] as string })) };
        }
        return { schemaVersion: 1, normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], accepted: this.aliases() };
    }

    private objectivePayload(exercise: Record<string, unknown>): Record<string, unknown> {
        const selected = this.selectedObjective();
        if (this.objectiveMode() === 'reuse') return {
            operation: 'reuse', objectiveId: selected!.objectiveId, objectiveRevisionId: selected!.objectiveRevisionId
        };
        if (this.objectiveMode() === 'revise') return {
            operation: 'revise', objectiveId: selected!.objectiveId,
            expectedObjectiveRevisionId: selected!.objectiveRevisionId, answerContract: this.answerContract(exercise)
        };
        return { operation: 'create', answerContract: this.answerContract(exercise) };
    }

    private exercisePayload(item: ItemDetail): Record<string, unknown> {
        const prompt = this.type() === 'AUDIO_TEXT_MATCH'
            ? { kind: 'AUDIO_MATCH', instruction: this.audioInstruction().trim(), cues: this.matchRows().map(row => ({
                cueId: row.cueId, assetId: row.assetId.trim(), title: row.title.trim(), transcript: row.transcript
            })) }
            : this.isListening() ? { kind: 'AUDIO_ASSET', assetId: this.audioAssetId().trim(),
                title: this.audioTitle().trim(), instruction: this.audioInstruction().trim(),
                transcript: this.audioTranscript() }
            : this.promptMode() === 'custom'
            ? { kind: 'CUSTOM_TEXT', text: this.customPrompt().trim() }
            : { kind: 'NODE_TEXT', memberKey: item.memberKey, itemRevisionId: item.itemRevisionId,
                nodeId: this.promptNodeId()! };
        const bindings: Record<string, unknown>[] = [{ bindingId: crypto.randomUUID(), role: 'ASSESSED',
            memberKey: item.memberKey, itemRevisionId: item.itemRevisionId,
            nodeIds: this.answerMode() === 'custom' ? [] : [this.answerNodeId()!],
            display: this.answerMode() === 'custom' ? { kind: 'CUSTOM_TEXT', text: this.aliases()[0] }
                : { kind: 'NODE_TEXT' }, ordinal: 0 }];
        const optionNodes = this.type() === 'AUDIO_TEXT_MATCH' ? this.matchRows().map(row => row.optionNodeId)
            : this.optionNodeIds();
        if (this.type() === 'SINGLE_CHOICE' || this.type() === 'LISTEN_CHOICE'
            || this.type() === 'AUDIO_TEXT_MATCH') optionNodes.forEach((nodeId, index) => bindings.push({
            bindingId: crypto.randomUUID(), role: 'OPTION', memberKey: item.memberKey,
            itemRevisionId: item.itemRevisionId, nodeIds: [nodeId], display: { kind: 'NODE_TEXT' }, ordinal: index + 1
        }));
        const evaluator = this.type() === 'SELF_CHECK' ? 'self-check'
            : this.type() === 'SINGLE_CHOICE' || this.type() === 'LISTEN_CHOICE' ? 'deterministic-choice'
            : this.type() === 'AUDIO_TEXT_MATCH' ? 'deterministic-audio-match' : 'deterministic-text';
        return { type: this.type(), schemaVersion: 1, enabled: this.enabled(), prompt, bindings,
            evaluatorPolicy: { id: evaluator, version: '1' } };
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
        const response = error instanceof HttpErrorResponse ? error : null;
        if (response === null || response.status === 0 || response.status >= 500) {
            this.phase.set('error');
            this.message.set('Не удалось проверить сохранение. Повторите попытку.');
            return;
        }
        this.pending = null;
        if (response.status === 412) {
            this.phase.set('conflict');
            this.message.set('Колода или цель изменились в другой вкладке. Ваш ввод сохранён здесь; загрузите свежую основу.');
            return;
        }
        this.phase.set('rejected');
        this.message.set('Не удалось сохранить упражнение. Проверьте фрагменты материала и настройки ответа.');
    }

    private waveOrigin(event?: MouseEvent): void {
        const button = event?.currentTarget;
        if (!event || !(button instanceof HTMLElement)) return;
        const bounds = button.getBoundingClientRect();
        const x = event.detail === 0 ? bounds.width / 2 : event.clientX - bounds.left;
        const y = event.detail === 0 ? bounds.height / 2 : event.clientY - bounds.top;
        const radius = Math.hypot(Math.max(x, bounds.width - x), Math.max(y, bounds.height - y));
        button.style.setProperty('--wave-x', `${x}px`);
        button.style.setProperty('--wave-y', `${y}px`);
        button.style.setProperty('--wave-size', `${radius * 2}px`);
    }

    private focusFirstError(): void {
        this.browserDocument.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
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

function normalized(value: string): string {
    return value.normalize('NFC').trim().toLowerCase();
}

function newMatchRow(): MatchRow {
    return { cueId: crypto.randomUUID(), assetId: '', title: '', transcript: '', optionNodeId: '' };
}

function validUuid(value: string): boolean {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u.test(value.trim());
}
