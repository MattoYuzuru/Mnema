import {
    ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, inject, input, model, output
} from '@angular/core';

import {
    CRITERION_TIERS, CRITERION_WEIGHTS, CriterionTier, CriterionWeight, LIMITS
} from '../../content/exercise/exercise-content.models';
import {
    AiRubricDraft, RubricTextRow, TIER_HINTS, TIER_LABELS, newCriterion, newTextRow, nextTier, tierCount, tierRange
} from './ai-rubric-draft';
import { DraftErrors } from './exercise-draft';

const WEIGHT_LABELS: Readonly<Record<CriterionWeight, string>> = { 1: '1 · обычный', 2: '2 · важный', 3: '3 · главный' };

/** One editable list of short strings (typical mistakes, accepted terms) with add and remove that keep keyboard focus. */
@Component({
    selector: 'app-rubric-text-list',
    template: `
      <fieldset class="list" [attr.aria-describedby]="error() ? idPrefix() + '-error' : null">
        <legend class="setting-title">{{ legend() }}</legend>
        <p class="hint">{{ hint() }}</p>
        @for (row of rows(); track row.id; let index = $index) {
          <div class="alias-row">
            <label [for]="idPrefix() + '-' + row.id" class="visually-hidden">{{ rowLabel() }} {{ index + 1 }}</label>
            <div class="alias-controls">
              <input type="text" [id]="idPrefix() + '-' + row.id" [value]="row.value" autocomplete="off"
                     [attr.aria-invalid]="error() ? 'true' : null" (input)="setValue(row.id, $any($event.target).value)" />
              <button type="button" class="button small" [attr.aria-label]="'Убрать: ' + rowLabel().toLowerCase() + ' ' + (index + 1)"
                      (click)="remove(row.id, index)">−</button>
            </div>
          </div>
        }
        <button type="button" class="button" data-add [disabled]="rows().length >= max()" (click)="add()">{{ addLabel() }}</button>
        <p class="counter" [class.over]="rows().length >= max()">{{ rows().length }} из {{ max() }}</p>
        @if (error(); as message) { <p class="field-error" role="alert" [id]="idPrefix() + '-error'">{{ message }}</p> }
      </fieldset>
    `,
    styleUrl: './exercise-fields.css',
    styles: [':host { display: block; min-inline-size: 0; } .list { gap: .6rem; }'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class RubricTextListComponent {
    readonly rows = input.required<readonly RubricTextRow[]>();
    readonly legend = input.required<string>();
    readonly hint = input.required<string>();
    readonly rowLabel = input.required<string>();
    readonly addLabel = input.required<string>();
    readonly idPrefix = input.required<string>();
    readonly max = input.required<number>();
    readonly length = input.required<number>();
    readonly error = input<string | null>(null);
    readonly rowsChange = output<readonly RubricTextRow[]>();

    private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
    private readonly injector = inject(Injector);

    setValue(id: string, value: string): void {
        this.rowsChange.emit(this.rows().map(row => row.id === id ? { ...row, value } : row));
    }

    add(): void {
        if (this.rows().length >= this.max()) return;
        const row = newTextRow();
        this.rowsChange.emit([...this.rows(), row]);
        this.focusAfterRender(`#${CSS.escape(this.idPrefix() + '-' + row.id)}`);
    }

    remove(id: string, index: number): void {
        const next = this.rows().filter(row => row.id !== id);
        this.rowsChange.emit(next);
        const target = next[Math.min(index, next.length - 1)];
        this.focusAfterRender(target === undefined ? '[data-add]' : `#${CSS.escape(this.idPrefix() + '-' + target.id)}`);
    }

    private focusAfterRender(selector: string): void {
        afterNextRender({ write: () => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus() },
            { injector: this.injector });
    }
}

/**
 * Rubric v1 of an AI-checked free response: the reference answer, 3-9 key points with a tier and a weight, and
 * (collapsed under «Тонкая настройка») typical mistakes and accepted terms. Counters and the 2–3 / 1–4 / 0–2 rule
 * are visible while editing; the host decides when problems are shown.
 */
@Component({
    selector: 'app-ai-rubric-editor',
    imports: [RubricTextListComponent],
    template: `
      <div class="field">
        <label [for]="idPrefix() + '-reference'">Эталонный ответ</label>
        <p class="hint" [id]="idPrefix() + '-reference-hint'">Так ответил бы знающий человек. Ученик увидит его только после своего ответа.</p>
        <textarea [id]="idPrefix() + '-reference'" rows="5" [value]="rubric().referenceAnswer"
                  [attr.aria-describedby]="idPrefix() + '-reference-hint' + (errors()['rubric:reference'] ? ' ' + idPrefix() + '-reference-error' : '')"
                  [attr.aria-invalid]="errors()['rubric:reference'] ? 'true' : null"
                  (input)="setReference($any($event.target).value)"></textarea>
        <p class="counter" [class.over]="rubric().referenceAnswer.length > limits.referenceAnswer">{{ rubric().referenceAnswer.length }} / {{ limits.referenceAnswer }}</p>
        @if (errors()['rubric:reference']; as message) {
          <p class="field-error" role="alert" [id]="idPrefix() + '-reference-error'">{{ message }}</p>
        }
      </div>

      <fieldset class="points" [attr.aria-describedby]="idPrefix() + '-points-hint ' + idPrefix() + '-points-counts'">
        <legend>Ключевые пункты</legend>
        <p class="hint" [id]="idPrefix() + '-points-hint'">Что должно быть в хорошем ответе, своими словами. «Суть» — без этого ответ не засчитают;
          «Детали» делают ответ полным; «Термины» — точные слова и обозначения.</p>
        <p class="tier-counts" role="status" [id]="idPrefix() + '-points-counts'">
          @for (tier of tiers; track tier) {
            <span class="tier-count" [class.off]="tierOff(tier)">{{ tierLabels[tier] }}: {{ count(tier) }} (нужно {{ range(tier) }})@if (tierOff(tier)) { ·
              {{ count(tier) < min(tier) ? 'добавьте' : 'уберите' }}}</span>
          }
        </p>
        <ol class="point-list">
          @for (criterion of rubric().criteria; track criterion.criterionId; let index = $index) {
            <li class="point" [attr.data-criterion]="criterion.criterionId">
              <div class="point-head">
                <h3 class="point-title">Пункт {{ index + 1 }}</h3>
                <button type="button" class="button small" [attr.aria-label]="'Убрать пункт ' + (index + 1)"
                        (click)="removeCriterion(criterion.criterionId, index)">Убрать</button>
              </div>
              <label [for]="pointId(criterion.criterionId, 'description')">Что должно прозвучать<span class="visually-hidden"> (пункт {{ index + 1 }})</span></label>
              <textarea [id]="pointId(criterion.criterionId, 'description')" rows="2" [value]="criterion.description"
                        [attr.aria-invalid]="errors()['rubric:criterion:' + criterion.criterionId] ? 'true' : null"
                        [attr.aria-describedby]="errors()['rubric:criterion:' + criterion.criterionId] ? pointId(criterion.criterionId, 'error') : null"
                        (input)="setDescription(criterion.criterionId, $any($event.target).value)"></textarea>
              <div class="point-selects">
                <div class="select-field">
                  <label [for]="pointId(criterion.criterionId, 'tier')">Вид<span class="visually-hidden"> пункта {{ index + 1 }}</span></label>
                  <select [id]="pointId(criterion.criterionId, 'tier')" (change)="setTier(criterion.criterionId, $any($event.target).value)">
                    @for (tier of tiers; track tier) {
                      <option [value]="tier" [selected]="criterion.tier === tier">{{ tierLabels[tier] }} — {{ tierHints[tier] }}</option>
                    }
                  </select>
                </div>
                <div class="select-field narrow">
                  <label [for]="pointId(criterion.criterionId, 'weight')">Вес<span class="visually-hidden"> пункта {{ index + 1 }}</span></label>
                  <select [id]="pointId(criterion.criterionId, 'weight')" (change)="setWeight(criterion.criterionId, $any($event.target).value)">
                    @for (weight of weights; track weight) {
                      <option [value]="weight" [selected]="criterion.weight === weight">{{ weightLabels[weight] }}</option>
                    }
                  </select>
                </div>
              </div>
              @if (errors()['rubric:criterion:' + criterion.criterionId]; as message) {
                <p class="field-error" role="alert" [id]="pointId(criterion.criterionId, 'error')">{{ message }}</p>
              }
            </li>
          }
        </ol>
        <button type="button" class="button" data-add-point [disabled]="rubric().criteria.length >= maxPoints" (click)="addCriterion()">+ Добавить пункт</button>
        @if (errors()['rubric:criteria']; as message) { <p class="field-error" role="alert">{{ message }}</p> }
      </fieldset>

      <details class="fine" [open]="fineOpen()">
        <summary>Тонкая настройка</summary>
        <div class="fine-body">
          <app-rubric-text-list [rows]="rubric().misconceptions" (rowsChange)="setMisconceptions($event)"
            legend="Типичные ошибки" rowLabel="Ошибка" addLabel="+ Добавить ошибку" [idPrefix]="idPrefix() + '-mistakes'"
            hint="Неверные утверждения, которые встречаются у учеников. Если ответ утверждает такое, он не будет засчитан."
            [max]="limits.misconceptions.max" [length]="limits.misconceptions.length" [error]="errors()['rubric:misconceptions'] ?? null" />
          <app-rubric-text-list [rows]="rubric().acceptableTerms" (rowsChange)="setTerms($event)"
            legend="Допустимые термины и синонимы" rowLabel="Термин" addLabel="+ Добавить термин" [idPrefix]="idPrefix() + '-terms'"
            hint="Слова, которые можно принять за правильные: синонимы, сокращения, переводы."
            [max]="limits.acceptableTerms.max" [length]="limits.acceptableTerms.length" [error]="errors()['rubric:terms'] ?? null" />
        </div>
      </details>
    `,
    styleUrl: './exercise-fields.css',
    styles: [`
      :host { display: grid; gap: 1.5rem; min-inline-size: 0; }
      .field { display: grid; gap: .5rem; min-inline-size: 0; }
      .points { gap: .9rem; }
      .tier-counts { display: flex; flex-wrap: wrap; gap: .35rem 1.2rem; margin: 0; font: .85rem/1.5 var(--mn-font-mono, ui-monospace, monospace); color: var(--mn-body); }
      .tier-count.off { color: var(--mn-danger); font-weight: 700; }
      .point-list { display: grid; gap: .9rem; margin: 0; padding: 0; list-style: none; }
      .point { display: grid; gap: .6rem; min-inline-size: 0; border: 1px solid var(--mn-rule); border-inline-start: 3px solid var(--mn-ink); padding: .9rem; background: var(--mn-sheet); }
      .point-head { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; justify-content: space-between; }
      .point-title { margin: 0; color: var(--mn-ink); font: 500 1.2rem/1.2 var(--mn-font-display, Georgia, serif); }
      .point-selects { display: flex; flex-wrap: wrap; gap: .75rem 1rem; }
      .select-field { display: grid; gap: .3rem; flex: 1 1 14rem; min-inline-size: 0; }
      .select-field.narrow { flex: 0 1 11rem; }
      select { min-inline-size: 0; max-inline-size: 100%; inline-size: 100%; min-block-size: var(--mn-touch-min, 2.75rem); border: 1px solid var(--mn-field-border); border-radius: var(--mn-radius, 2px); padding: .5rem .6rem; color: inherit; background: var(--mn-sheet); font: 1rem/1.4 var(--mn-font-body, system-ui, sans-serif); }
      .fine summary { min-block-size: var(--mn-touch-min, 2.75rem); display: flex; align-items: center; color: var(--mn-ink); font-weight: 650; cursor: pointer; }
      .fine-body { display: grid; gap: 1.5rem; padding-block-start: .75rem; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AiRubricEditorComponent {
    readonly rubric = model.required<AiRubricDraft>();
    readonly errors = input<DraftErrors>({});
    readonly idPrefix = input('rubric');

    readonly limits = LIMITS.aiRubric;
    readonly tiers = CRITERION_TIERS;
    readonly weights = CRITERION_WEIGHTS;
    readonly tierLabels = TIER_LABELS;
    readonly tierHints = TIER_HINTS;
    readonly weightLabels = WEIGHT_LABELS;
    /** The tier maxima add up to 9: a tenth point can never satisfy the rule. */
    readonly maxPoints = CRITERION_TIERS.reduce((sum, tier) => sum + LIMITS.aiRubric.tiers[tier].max, 0);
    /** Opens by itself when something sits inside, so a filled or invalid list is never hidden. */
    readonly fineOpen = computed(() => this.rubric().misconceptions.length > 0 || this.rubric().acceptableTerms.length > 0
        || this.errors()['rubric:misconceptions'] !== undefined || this.errors()['rubric:terms'] !== undefined);

    private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
    private readonly injector = inject(Injector);

    count(tier: CriterionTier): number { return tierCount(this.rubric(), tier); }
    min(tier: CriterionTier): number { return this.limits.tiers[tier].min; }
    range(tier: CriterionTier): string { return tierRange(tier); }
    tierOff(tier: CriterionTier): boolean {
        const count = this.count(tier);
        return count < this.limits.tiers[tier].min || count > this.limits.tiers[tier].max;
    }
    pointId(criterionId: string, part: string): string { return `${this.idPrefix()}-${criterionId}-${part}`; }

    setReference(referenceAnswer: string): void { this.rubric.update(current => ({ ...current, referenceAnswer })); }

    setDescription(criterionId: string, description: string): void {
        this.rubric.update(current => ({ ...current,
            criteria: current.criteria.map(item => item.criterionId === criterionId ? { ...item, description } : item) }));
    }

    setTier(criterionId: string, value: string): void {
        const tier = CRITERION_TIERS.find(candidate => candidate === value);
        if (tier === undefined) return;
        this.rubric.update(current => ({ ...current,
            criteria: current.criteria.map(item => item.criterionId === criterionId ? { ...item, tier } : item) }));
    }

    setWeight(criterionId: string, value: string): void {
        const weight = CRITERION_WEIGHTS.find(candidate => String(candidate) === value);
        if (weight === undefined) return;
        this.rubric.update(current => ({ ...current,
            criteria: current.criteria.map(item => item.criterionId === criterionId ? { ...item, weight } : item) }));
    }

    addCriterion(): void {
        if (this.rubric().criteria.length >= this.maxPoints) return;
        const criterion = newCriterion(nextTier(this.rubric()));
        this.rubric.update(current => ({ ...current, criteria: [...current.criteria, criterion] }));
        this.focusAfterRender(`#${CSS.escape(this.pointId(criterion.criterionId, 'description'))}`);
    }

    removeCriterion(criterionId: string, index: number): void {
        const criteria = this.rubric().criteria.filter(item => item.criterionId !== criterionId);
        this.rubric.update(current => ({ ...current, criteria }));
        const target = criteria[Math.min(index, criteria.length - 1)];
        this.focusAfterRender(target === undefined ? '[data-add-point]' : `#${CSS.escape(this.pointId(target.criterionId, 'description'))}`);
    }

    setMisconceptions(misconceptions: readonly RubricTextRow[]): void { this.rubric.update(current => ({ ...current, misconceptions })); }
    setTerms(acceptableTerms: readonly RubricTextRow[]): void { this.rubric.update(current => ({ ...current, acceptableTerms })); }

    private focusAfterRender(selector: string): void {
        afterNextRender({ write: () => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus() },
            { injector: this.injector });
    }
}
