import { ChangeDetectionStrategy, Component, Directive, ElementRef, Injector, afterNextRender, computed, inject, input, model, signal } from '@angular/core';

import {
    AuthoringBlock, COMPACT_SLOT, LIMITS, REFERENCE_SLOTS, SelectionMode, isBlank
} from '../../content/exercise/exercise-content.models';
import { AiRubricEditorComponent } from './ai-rubric-editor.component';
import { AiRubricDraft, emptyRubric } from './ai-rubric-draft';
import { CAPABILITIES_UNAVAILABLE, LearningCapabilities } from './capabilities-api.service';
import {
    ChoiceDraft, ClozeBlankDraft, ClozeDraft, DraftErrors, FreeResponseDraft, MatchDraft, SelfCheckDraft, SlotContext, TextAnswerDraft,
    choiceSelectionProblem, newBlank, newOption, newPair
} from './exercise-draft';
import { ExerciseSlotEditorComponent } from './exercise-slot-editor.component';
import { TextAnswerEditorComponent } from './text-answer-editor.component';

/**
 * Inputs shared by every step editor. The question slot of each mechanic is edited by the page itself; these
 * components own the mechanic-specific step: answers, passage, options or pairs.
 */
@Directive()
export abstract class MechanicEditorBase {
    readonly context = input.required<SlotContext>();
    readonly errors = input<DraftErrors>({});
    readonly showProblems = input(false);
    readonly idPrefix = input('editor');
}

// ---------------------------------------------------------------------------------------------

@Component({
    selector: 'app-free-response-editor',
    imports: [ExerciseSlotEditorComponent, TextAnswerEditorComponent, AiRubricEditorComponent],
    template: `
      <div class="ai-switch">
        <label class="check-line" [attr.for]="idPrefix() + '-ai'">
          <input type="checkbox" role="switch" [id]="idPrefix() + '-ai'" [disabled]="!aiAvailable() && !aiOn()" [checked]="aiOn()"
                 [attr.aria-describedby]="idPrefix() + '-ai-hint'" (change)="setAi($any($event.target).checked)" />
          <span>Проверять смысл ответа с ИИ</span> <span class="stamp">ИИ</span>
        </label>
        <p class="hint" [id]="idPrefix() + '-ai-hint'">{{ aiHint() }}</p>
      </div>
      @if (draft().aiRubric; as rubric) {
        <app-ai-rubric-editor [rubric]="rubric" (rubricChange)="setRubric($event)" [errors]="errors()" [idPrefix]="idPrefix() + '-rubric'" />
      } @else {
        <app-text-answer-editor [answer]="draft().answer" (answerChange)="setAnswer($event)" [idPrefix]="idPrefix() + '-answer'"
          [error]="errors()['accepted'] ?? null" [max]="20" [length]="512" />
      }
      <details class="optional" [open]="referenceOpen()">
        <summary>Эталон после ответа (необязательно)</summary>
        <app-exercise-slot-editor label="Эталон" [spec]="reference" [blocks]="draft().reference"
          (blocksChange)="draft.set({ ...draft(), reference: $event })" [context]="context()" [idPrefix]="idPrefix() + '-reference'"
          [error]="errors()['reference'] ?? null" [showProblems]="showProblems()"
          [hint]="draft().aiRubric ? 'Богатый эталон показывается после ответа вместе с текстом эталонного ответа. Его не читает проверка.' : 'Богатый эталон показывается после ответа. Проверка использует только список допустимых ответов выше.'" />
      </details>
    `,
    styleUrl: './exercise-fields.css',
    styles: [':host { display: grid; gap: 1.5rem; min-inline-size: 0; } .optional summary { min-block-size: var(--mn-touch-min, 2.75rem); display: flex; align-items: center; color: var(--mn-ink); font-weight: 650; cursor: pointer; }'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class FreeResponseEditorComponent extends MechanicEditorBase {
    readonly draft = model.required<FreeResponseDraft>();
    readonly capabilities = input<LearningCapabilities>(CAPABILITIES_UNAVAILABLE);
    readonly reference = REFERENCE_SLOTS.FREE_RESPONSE;
    readonly aiAvailable = computed(() => this.capabilities().aiAssessment.available);
    readonly aiOn = computed(() => this.draft().aiRubric !== null);
    readonly aiHint = computed(() => {
        const capability = this.capabilities().aiAssessment;
        if (capability.available) {
            return 'Мнема сравнит ответ с эталоном и покажет ученику, что в нём есть и чего не хватает. '
                + 'Если проверка недоступна или не уверена, ученик оценит себя сам.';
        }
        return 'Проверка объяснений и формулировок по эталону. Пока недоступна. '
            + (capability.reason === 'PROVIDER_NOT_CONFIGURED' ? 'Сервер включил функцию, но поставщик проверки не подключён.'
                : capability.reason === 'TEMPORARILY_UNAVAILABLE' ? 'Сервис временно недоступен.' : 'Функция отключена на сервере.');
    });
    /** Opens by itself for an existing reference or a reference problem, so neither stays hidden. */
    readonly referenceOpen = computed(() => this.errors()['reference'] !== undefined
        || this.draft().reference.some(block => block.kind !== 'TEXT' || !isBlank(block.text)));
    /** Switching AI checking off keeps what was typed, so switching it back on restores the rubric. */
    private stash: AiRubricDraft | null = null;

    setAnswer(answer: TextAnswerDraft): void { this.draft.set({ ...this.draft(), answer }); }
    setRubric(aiRubric: AiRubricDraft): void { this.draft.set({ ...this.draft(), aiRubric }); }

    setAi(on: boolean): void {
        const current = this.draft().aiRubric;
        if (on && current === null) {
            if (!this.aiAvailable()) return;
            this.draft.set({ ...this.draft(), aiRubric: this.stash ?? emptyRubric() });
        } else if (!on && current !== null) {
            this.stash = current;
            this.draft.set({ ...this.draft(), aiRubric: null });
        }
    }
}

// ---------------------------------------------------------------------------------------------

interface Selection { readonly index: number; readonly start: number; readonly end: number; }

@Component({
    selector: 'app-cloze-editor',
    imports: [TextAnswerEditorComponent],
    template: `
      <fieldset class="passage" [attr.aria-describedby]="errors()['passage'] ? idPrefix() + '-passage-error' : null">
        <legend class="visually-hidden">Текст с пропусками</legend>
        <p class="hint">Напишите текст или код. Выделите слово или фрагмент и нажмите «Сделать пропуском»: выделенное станет первым правильным ответом.
          Каждый пропуск получает свой номер и свои ответы, поэтому повторяющиеся слова не мешают друг другу. Переносы строк и отступы сохраняются.
          Пропусков: {{ draft().blanks.length }} из {{ limits.max }}.</p>
        <div class="add-row">
          <button type="button" class="button" data-make-blank [disabled]="draft().blanks.length >= limits.max"
                  (click)="makeBlank()">Сделать пропуском</button>
          <span class="hint" role="status">{{ selectionHint() }}</span>
        </div>
        @for (text of draft().texts; track $index; let index = $index) {
          <label [for]="idPrefix() + '-text-' + index" class="visually-hidden">{{ textLabel(index) }}</label>
          <textarea [id]="idPrefix() + '-text-' + index" class="code-area" rows="4" spellcheck="false" [value]="text"
                    (input)="setText(index, $any($event.target).value)"
                    (select)="remember(index, $any($event.target))" (keyup)="remember(index, $any($event.target))"
                    (mouseup)="remember(index, $any($event.target))" (focus)="remember(index, $any($event.target))"></textarea>
          @if (draft().blanks[index]; as blank) {
            <section class="card" [attr.data-blank]="blank.blankId" [attr.aria-label]="'Пропуск ' + (index + 1)">
              <div class="card-head">
                <h3>Пропуск {{ index + 1 }}</h3>
                <button type="button" class="button" (click)="removeBlank(index)">Убрать пропуск и вернуть текст</button>
              </div>
              <app-text-answer-editor [answer]="blank.answer" (answerChange)="setBlankAnswer(index, $event)"
                [idPrefix]="idPrefix() + '-blank-' + blank.blankId" legend="Допустимые ответы" [max]="10" [length]="200"
                [error]="errors()['blank:' + blank.blankId] ?? null" />
              <fieldset class="row-stack">
                <legend class="setting-title">Ширина поля для ученика</legend>
                <label class="radio-line"><input type="radio" [name]="idPrefix() + '-size-' + blank.blankId"
                  [checked]="blank.size.mode === 'FIXED'" (change)="setSize(index, 'FIXED')" /> Заданная ширина</label>
                @if (blank.size.mode === 'FIXED') {
                  <label [for]="idPrefix() + '-length-' + blank.blankId">Знаков в поле (5–20)</label>
                  <input type="number" min="5" max="20" step="1" [id]="idPrefix() + '-length-' + blank.blankId" [value]="blank.size.length"
                         (input)="setLength(index, $any($event.target).valueAsNumber)" />
                }
                <label class="radio-line"><input type="radio" [name]="idPrefix() + '-size-' + blank.blankId"
                  [checked]="blank.size.mode === 'ANSWER_LENGTH'" (change)="setSize(index, 'ANSWER_LENGTH')" /> По длине ответа</label>
                <p class="hint">«По длине ответа» подсказывает длину правильного ответа, поэтому все допустимые ответы должны быть одной длины.</p>
              </fieldset>
              <label class="check-line"><input type="checkbox" [checked]="blank.firstLetterHint"
                (change)="setHint(index, $any($event.target).checked)" /> Разрешить кнопку «Первая буква»</label>
              <p class="hint">Подсказка запишется в доказательство знания и снизит его вес для этого пропуска.</p>
            </section>
          }
        }
        @if (errors()['passage']; as message) { <p class="field-error" role="alert" [id]="idPrefix() + '-passage-error'">{{ message }}</p> }
      </fieldset>
    `,
    styleUrl: './exercise-fields.css',
    styles: [`
      :host { display: grid; gap: 1.5rem; min-inline-size: 0; }
      .passage { gap: .9rem; }
      .code-area { font-family: var(--mn-font-mono, ui-monospace, monospace); tab-size: 4; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ClozeEditorComponent extends MechanicEditorBase {
    readonly draft = model.required<ClozeDraft>();
    readonly limits = LIMITS.clozeBlanks;
    readonly selection = signal<Selection | null>(null);
    readonly selectionHint = computed(() => {
        const selection = this.selection();
        if (selection === null) return 'Поставьте курсор в тексте или выделите фрагмент.';
        return selection.start === selection.end ? 'Пропуск появится в позиции курсора.'
            : `Будет пропуском: «${this.draft().texts[selection.index]?.slice(selection.start, selection.end)}».`;
    });

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);

    textLabel(index: number): string {
        const blanks = this.draft().blanks.length;
        return blanks === 0 ? 'Текст' : index === 0 ? 'Текст до первого пропуска'
            : index === blanks ? 'Текст после последнего пропуска' : `Текст между пропусками ${index} и ${index + 1}`;
    }

    remember(index: number, area: HTMLTextAreaElement): void {
        this.selection.set({ index, start: area.selectionStart, end: area.selectionEnd });
    }

    setText(index: number, value: string): void {
        this.draft.update(draft => ({ ...draft, texts: draft.texts.map((text, position) => position === index ? value : text) }));
        this.selection.set(null);
    }

    /** Splits the remembered text range into text, blank, text. The selected text becomes the first answer. */
    makeBlank(): void {
        const draft = this.draft();
        if (draft.blanks.length >= this.limits.max) return;
        const selection = this.selection() ?? { index: draft.texts.length - 1, start: draft.texts.at(-1)!.length, end: draft.texts.at(-1)!.length };
        const text = draft.texts[selection.index];
        if (text === undefined) return;
        const start = Math.min(selection.start, text.length);
        const end = Math.min(Math.max(selection.end, start), text.length);
        const blank = newBlank(text.slice(start, end));
        const texts = [...draft.texts.slice(0, selection.index), text.slice(0, start), text.slice(end), ...draft.texts.slice(selection.index + 1)];
        const blanks = [...draft.blanks.slice(0, selection.index), blank, ...draft.blanks.slice(selection.index)];
        this.draft.set({ ...draft, texts, blanks });
        this.selection.set(null);
        this.focusAfterRender(`[data-blank="${blank.blankId}"] input[type="text"]`);
    }

    /** Deleting a blank restores its first accepted answer into the text, so no authored text is lost. */
    removeBlank(index: number): void {
        const draft = this.draft();
        const blank = draft.blanks[index];
        if (blank === undefined) return;
        const restored = draft.texts[index] + (blank.answer.rows[0]?.value ?? '') + draft.texts[index + 1];
        this.draft.set({ ...draft, texts: [...draft.texts.slice(0, index), restored, ...draft.texts.slice(index + 2)],
            blanks: draft.blanks.filter((_, position) => position !== index) });
        this.selection.set(null);
        this.focusAfterRender(`#${CSS.escape(this.idPrefix() + '-text-' + index)}`);
    }

    private patchBlank(index: number, change: (blank: ClozeBlankDraft) => ClozeBlankDraft): void {
        this.draft.update(draft => ({ ...draft, blanks: draft.blanks.map((blank, position) => position === index ? change(blank) : blank) }));
    }

    setBlankAnswer(index: number, answer: TextAnswerDraft): void { this.patchBlank(index, blank => ({ ...blank, answer })); }
    setHint(index: number, firstLetterHint: boolean): void { this.patchBlank(index, blank => ({ ...blank, firstLetterHint })); }
    setSize(index: number, mode: 'FIXED' | 'ANSWER_LENGTH'): void {
        this.patchBlank(index, blank => ({ ...blank, size: mode === 'FIXED'
            ? { mode, length: blank.size.mode === 'FIXED' ? blank.size.length : 12 } : { mode } }));
    }
    setLength(index: number, length: number): void {
        this.patchBlank(index, blank => ({ ...blank, size: { mode: 'FIXED', length } }));
    }

    private focusAfterRender(selector: string): void {
        afterNextRender({ write: () => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus() }, { injector: this.injector });
    }
}

// ---------------------------------------------------------------------------------------------

@Component({
    selector: 'app-choice-editor',
    imports: [ExerciseSlotEditorComponent],
    template: `
      <fieldset class="options" [attr.aria-describedby]="idPrefix() + '-selection-error'">
        <legend class="visually-hidden">Варианты ответа</legend>
        <div class="row-stack" role="radiogroup" aria-label="Сколько ответов выбирает ученик">
          <label class="radio-line"><input type="radio" [name]="idPrefix() + '-mode'" [checked]="draft().selectionMode === 'SINGLE'"
            (change)="setMode('SINGLE')" /> Один ответ</label>
          <label class="radio-line"><input type="radio" [name]="idPrefix() + '-mode'" [checked]="draft().selectionMode === 'MULTIPLE'"
            (change)="setMode('MULTIPLE')" /> Несколько ответов</label>
          <p class="hint">{{ draft().selectionMode === 'SINGLE' ? 'Ученик увидит переключатели и выберет один вариант.'
            : 'Ученик увидит флажки; засчитывается только полностью совпавший набор.' }}</p>
        </div>
        <p class="hint">Вариантов: {{ limits.min }}–{{ limits.max }}. В каждом — текст и/или одно изображение, аудио или видео.</p>
        @for (option of draft().options; track option.optionId; let index = $index; let first = $first; let last = $last) {
          <section class="card" [attr.data-option]="option.optionId" [attr.aria-label]="'Вариант ' + (index + 1)">
            <div class="card-head">
              <h3>Вариант {{ index + 1 }}</h3>
              <span class="row-actions">
                <button type="button" class="button small" [disabled]="first" (click)="move(index, -1)" [attr.aria-label]="'Поднять вариант ' + (index + 1)">↑</button>
                <button type="button" class="button small" [disabled]="last" (click)="move(index, 1)" [attr.aria-label]="'Опустить вариант ' + (index + 1)">↓</button>
                <button type="button" class="button" [disabled]="draft().options.length <= limits.min" (click)="remove(index)"
                        [attr.aria-label]="'Удалить вариант ' + (index + 1)">Удалить</button>
              </span>
            </div>
            <label class="check-line"><input type="checkbox" [checked]="isCorrect(option.optionId)"
              (change)="mark(option.optionId, $any($event.target).checked)" /> Правильный ответ</label>
            <app-exercise-slot-editor [label]="'Содержимое варианта ' + (index + 1)" [spec]="compact" [blocks]="option.blocks"
              (blocksChange)="setBlocks(option.optionId, $event)" [context]="context()"
              [idPrefix]="idPrefix() + '-option-' + option.optionId" [error]="errors()['option:' + option.optionId] ?? null"
              [showProblems]="showProblems()" />
          </section>
        }
        <button type="button" class="button" data-add-option [disabled]="draft().options.length >= limits.max" (click)="add()">+ Добавить вариант</button>
        @if (selectionProblem(); as message) { <p class="field-error" role="alert" [id]="idPrefix() + '-selection-error'">{{ message }}</p> }
        @if (errors()['options']; as message) { <p class="field-error" role="alert">{{ message }}</p> }
      </fieldset>
    `,
    styleUrl: './exercise-fields.css',
    styles: [':host { display: grid; gap: 1.5rem; min-inline-size: 0; } .row-actions { display: flex; flex-wrap: wrap; gap: .35rem; }'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ChoiceEditorComponent extends MechanicEditorBase {
    readonly draft = model.required<ChoiceDraft>();
    readonly limits = LIMITS.choiceOptions;
    readonly compact = COMPACT_SLOT;
    /** Live, so a mode switch that no longer matches the marks is reported immediately and nothing is dropped. */
    readonly selectionProblem = computed(() => choiceSelectionProblem(this.draft()));

    isCorrect(optionId: string): boolean { return this.draft().correctIds.includes(optionId); }

    setMode(selectionMode: SelectionMode): void { this.draft.set({ ...this.draft(), selectionMode }); }

    mark(optionId: string, checked: boolean): void {
        const draft = this.draft();
        const base = draft.correctIds.filter(id => id !== optionId);
        // Choosing a radio-like single answer replaces the previous mark; unchecking only removes this one.
        const correctIds = !checked ? base : draft.selectionMode === 'SINGLE' ? [optionId] : [...base, optionId];
        this.draft.set({ ...draft, correctIds });
    }

    setBlocks(optionId: string, blocks: readonly AuthoringBlock[]): void {
        this.draft.update(draft => ({ ...draft, options: draft.options.map(option => option.optionId === optionId ? { ...option, blocks } : option) }));
    }

    add(): void {
        if (this.draft().options.length >= this.limits.max) return;
        this.draft.update(draft => ({ ...draft, options: [...draft.options, newOption()] }));
    }

    remove(index: number): void {
        if (this.draft().options.length <= this.limits.min) return;
        const removed = this.draft().options[index]?.optionId;
        this.draft.update(draft => ({ ...draft, options: draft.options.filter((_, position) => position !== index),
            correctIds: draft.correctIds.filter(id => id !== removed) }));
    }

    move(index: number, delta: -1 | 1): void {
        const target = index + delta;
        this.draft.update(draft => {
            if (target < 0 || target >= draft.options.length) return draft;
            const options = [...draft.options];
            [options[index], options[target]] = [options[target], options[index]];
            return { ...draft, options };
        });
    }
}

// ---------------------------------------------------------------------------------------------

@Component({
    selector: 'app-match-editor',
    imports: [ExerciseSlotEditorComponent],
    template: `
      <fieldset class="pairs">
        <legend class="visually-hidden">Пары</legend>
        <p class="hint">Пар: {{ limits.min }}–{{ limits.max }}. Каждый элемент участвует ровно в одной паре; ученик увидит стороны перемешанными независимо.
          В каждом элементе — текст и/или одно изображение, аудио или видео; аудио можно записать с любой стороны.</p>
        @for (pair of draft().pairs; track pair.pairId; let index = $index; let first = $first; let last = $last) {
          <section class="card" [attr.data-pair]="pair.pairId" [attr.aria-label]="'Пара ' + (index + 1)">
            <div class="card-head">
              <h3>Пара {{ index + 1 }}</h3>
              <span class="row-actions">
                <button type="button" class="button small" [disabled]="first" (click)="move(index, -1)" [attr.aria-label]="'Поднять пару ' + (index + 1)">↑</button>
                <button type="button" class="button small" [disabled]="last" (click)="move(index, 1)" [attr.aria-label]="'Опустить пару ' + (index + 1)">↓</button>
                <button type="button" class="button" [disabled]="draft().pairs.length <= limits.min" (click)="remove(index)"
                        [attr.aria-label]="'Удалить пару ' + (index + 1)">Удалить</button>
              </span>
            </div>
            <app-exercise-slot-editor [label]="'Пара ' + (index + 1) + ': левая сторона'" [spec]="compact" [blocks]="pair.left.blocks"
              (blocksChange)="setSide(pair.pairId, 'left', $event)" [context]="context()"
              [idPrefix]="idPrefix() + '-left-' + pair.left.itemId" [error]="errors()['left:' + pair.left.itemId] ?? null"
              [showProblems]="showProblems()" />
            <app-exercise-slot-editor [label]="'Пара ' + (index + 1) + ': правая сторона'" [spec]="compact" [blocks]="pair.right.blocks"
              (blocksChange)="setSide(pair.pairId, 'right', $event)" [context]="context()"
              [idPrefix]="idPrefix() + '-right-' + pair.right.itemId" [error]="errors()['right:' + pair.right.itemId] ?? null"
              [showProblems]="showProblems()" />
          </section>
        }
        <button type="button" class="button" data-add-pair [disabled]="draft().pairs.length >= limits.max" (click)="add()">+ Добавить пару</button>
        @if (errors()['pairs']; as message) { <p class="field-error" role="alert">{{ message }}</p> }
      </fieldset>
    `,
    styleUrl: './exercise-fields.css',
    styles: [':host { display: grid; gap: 1.5rem; min-inline-size: 0; } .pairs { gap: .9rem; } .row-actions { display: flex; flex-wrap: wrap; gap: .35rem; }'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class MatchEditorComponent extends MechanicEditorBase {
    readonly draft = model.required<MatchDraft>();
    readonly limits = LIMITS.matchPairs;
    readonly compact = COMPACT_SLOT;

    setSide(pairId: string, side: 'left' | 'right', blocks: readonly AuthoringBlock[]): void {
        this.draft.update(draft => ({ ...draft, pairs: draft.pairs.map(pair => pair.pairId === pairId
            ? { ...pair, [side]: { ...pair[side], blocks } } : pair) }));
    }

    add(): void {
        if (this.draft().pairs.length >= this.limits.max) return;
        this.draft.update(draft => ({ ...draft, pairs: [...draft.pairs, newPair()] }));
    }

    remove(index: number): void {
        if (this.draft().pairs.length <= this.limits.min) return;
        this.draft.update(draft => ({ ...draft, pairs: draft.pairs.filter((_, position) => position !== index) }));
    }

    move(index: number, delta: -1 | 1): void {
        const target = index + delta;
        this.draft.update(draft => {
            if (target < 0 || target >= draft.pairs.length) return draft;
            const pairs = [...draft.pairs];
            [pairs[index], pairs[target]] = [pairs[target], pairs[index]];
            return { ...draft, pairs };
        });
    }
}
