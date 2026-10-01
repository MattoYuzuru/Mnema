import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { LearnerChoiceOption, SelectionMode } from './exercise-content.models';
import { LearnerBlocksComponent } from './learner-blocks.component';

/** Visible, neutral label of an option: its text, else the image description, else a generated name. */
export function optionLabel(option: LearnerChoiceOption, index: number): string {
    const text = option.blocks.find(block => block.kind === 'TEXT');
    if (text?.kind === 'TEXT') return text.text;
    const image = option.blocks.find(block => block.kind === 'IMAGE');
    if (image?.kind === 'IMAGE') return image.alt;
    const media = option.blocks.find(block => block.kind === 'AUDIO' || block.kind === 'VIDEO');
    return `${media?.kind === 'VIDEO' ? 'Видео' : 'Аудио'}, вариант ${index + 1}`;
}

/**
 * Single choice uses radio semantics, multiple choice uses checkbox semantics. Only the text of an option
 * sits inside its label; players and images are siblings, so using them never changes the selection.
 */
@Component({
    selector: 'app-choice-list',
    imports: [LearnerBlocksComponent],
    template: `
      <fieldset class="choice-set" [disabled]="disabled()">
        <legend>{{ selectionMode() === 'MULTIPLE' ? 'Выберите все подходящие варианты' : 'Выберите один вариант' }}</legend>
        <ul class="choice-list">
          @for (option of options(); track option.optionId; let index = $index) {
            <li class="choice-option" [class.is-selected]="selected().includes(option.optionId)"
                [class.is-correct]="correctIds()?.includes(option.optionId)">
              <div class="choice-row">
                <input [type]="selectionMode() === 'MULTIPLE' ? 'checkbox' : 'radio'" [name]="idPrefix()"
                  [id]="idPrefix() + '-' + option.optionId" [value]="option.optionId"
                  [attr.data-answer-control]="index === 0 ? '' : null"
                  [checked]="selected().includes(option.optionId)" (change)="selectedChange.emit(option.optionId)" />
                <label [for]="idPrefix() + '-' + option.optionId">{{ label(option, index) }}@if (correctIds()?.includes(option.optionId)) { (правильный ответ)}</label>
              </div>
              <app-learner-blocks [blocks]="option.blocks" [mediaOnly]="true" [nameSuffix]="', вариант ' + (index + 1)" />
            </li>
          }
        </ul>
      </fieldset>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .choice-set { min-inline-size: 0; margin: 0; border: 0; padding: 0; }
      legend { padding: 0 0 .5rem; font-weight: 700; color: var(--mn-ink); }
      .choice-list { display: grid; gap: .5rem; margin: 0; padding: 0; list-style: none; }
      .choice-option { display: grid; gap: .6rem; border: 1px solid var(--mn-field-border, var(--mn-rule)); padding: .6rem .8rem; min-inline-size: 0; }
      .choice-option.is-selected { border-color: var(--mn-ink); background: color-mix(in srgb, var(--mn-soft) 70%, transparent); }
      .choice-option.is-correct { border-inline-start: 4px solid var(--mn-ink); }
      .choice-row { display: flex; align-items: center; gap: .7rem; min-block-size: var(--mn-touch-min, 2.75rem); }
      .choice-row input { flex: 0 0 auto; inline-size: 1.25rem; block-size: 1.25rem; }
      .choice-row label { flex: 1 1 auto; min-inline-size: 0; align-self: stretch; display: flex; align-items: center; overflow-wrap: anywhere; white-space: pre-wrap; cursor: pointer; }
      input:focus-visible { outline: 3px solid var(--mn-focus, var(--mn-ink)); outline-offset: 3px; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ChoiceListComponent {
    readonly options = input.required<readonly LearnerChoiceOption[]>();
    readonly selectionMode = input.required<SelectionMode>();
    readonly selected = input<readonly string[]>([]);
    readonly disabled = input(false);
    readonly idPrefix = input('choice');
    /** Set only after the attempt was evaluated: marks the correct options. */
    readonly correctIds = input<readonly string[] | null>(null);
    readonly selectedChange = output<string>();

    label(option: LearnerChoiceOption, index: number): string { return optionLabel(option, index); }
}
