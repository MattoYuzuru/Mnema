import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

import { NORMALIZATION_RULES, NormalizationRule, MatchingMode } from '../../content/exercise/exercise-content.models';
import { SegmentedChoiceComponent, SegmentedOption } from '../../shared/segmented-choice.component';
import { ToggletipComponent } from '../../shared/toggletip.component';
import { AliasRow, TextAnswerDraft, newId } from './exercise-draft';

const RULE_LABELS: Readonly<Record<NormalizationRule, string>> = {
    UNICODE_NFC: 'Считать одинаковыми разные способы записи одной буквы (Unicode NFC)',
    TRIM: 'Не учитывать пробелы по краям',
    CASE_FOLD: 'Не различать заглавные и строчные буквы'
};

const MODE_OPTIONS: readonly SegmentedOption<MatchingMode>[] = [
    { value: 'STRICT', label: 'Строго', hint: 'После выбранных преобразований текст должен совпасть полностью.' },
    { value: 'SOFT', label: 'Мягко',
        hint: 'Дополнительно не учитываются знаки препинания, диакритика, дефисы и пробелы. Это не проверка смысла: другая формулировка засчитана не будет.' }
];

/**
 * Accepted answers with explicit comparison rules. One matching alternative is enough: the list is not a
 * set of answers the learner must all type. Soft matching is still deterministic text comparison, never a
 * semantic check.
 */
@Component({
    selector: 'app-text-answer-editor',
    imports: [SegmentedChoiceComponent, ToggletipComponent],
    templateUrl: './text-answer-editor.component.html',
    styleUrl: './exercise-fields.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class TextAnswerEditorComponent {
    readonly answer = model.required<TextAnswerDraft>();
    readonly legend = input('Допустимые ответы');
    readonly idPrefix = input.required<string>();
    readonly max = input(20);
    readonly error = input<string | null>(null);
    /** Maximum length of one alternative, shown next to the field. */
    readonly length = input(512);

    readonly rules = NORMALIZATION_RULES;
    readonly modeOptions = MODE_OPTIONS;

    ruleLabel(rule: NormalizationRule): string { return RULE_LABELS[rule]; }

    setValue(id: string, value: string): void {
        this.answer.update(current => ({ ...current, rows: current.rows.map(row => row.id === id ? { ...row, value } : row) }));
    }

    add(): void {
        if (this.answer().rows.length >= this.max()) return;
        const row: AliasRow = { id: newId(), value: '' };
        this.answer.update(current => ({ ...current, rows: [...current.rows, row] }));
    }

    remove(id: string): void {
        if (this.answer().rows.length <= 1) return;
        this.answer.update(current => ({ ...current, rows: current.rows.filter(row => row.id !== id) }));
    }

    toggleRule(rule: NormalizationRule, checked: boolean): void {
        this.answer.update(current => ({ ...current, normalization: NORMALIZATION_RULES
            .filter(candidate => candidate === rule ? checked : current.normalization.includes(candidate)) }));
    }

    onMode(value: MatchingMode | null): void {
        if (value !== null) this.answer.update(current => ({ ...current, matchingMode: value }));
    }
}
