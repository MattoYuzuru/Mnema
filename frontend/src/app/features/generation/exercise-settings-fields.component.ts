import { ChangeDetectionStrategy, Component, computed, input, model, signal } from '@angular/core';

import { Mechanic } from '../../content/exercise/exercise-content.models';
import { SegmentedChoiceComponent, SegmentedOption } from '../../shared/segmented-choice.component';
import {
    BuilderValue, MECHANIC_CHOICES, PRIORITY_OPTIONS, QUANTITY_OPTIONS, QuantityMode, percentText, perTargetText, toggleMechanic
} from './exercise-builder';

/**
 * The choices of an exercise request, shared by the exercise builder (#291) and by the chips of «Попросить Мнему…» (#294): the
 * mechanics («Авто» or a set), what comes first (only for several materials), and how many. It owns no state: the host passes the
 * value and gets the next one back, so both pages build the very same request from it. The host decides which quantity modes it offers
 * (the intent knows an exact number and «Авто»; the builder also a share of the limit).
 *
 * The plan table of a Workshop (#295) uses the very same mechanics chips for each row of its plan (`showAuto` off: a row needs at least one
 * mechanic, so there is no «Авто» and the last one cannot be unchecked; `showQuantity` off: the count is the row's own control; `allowed`: the
 * mechanics the spec allows).
 */
@Component({
    selector: 'app-exercise-settings-fields',
    imports: [SegmentedChoiceComponent],
    templateUrl: './exercise-settings-fields.component.html',
    styleUrl: './exercise-settings-fields.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ExerciseSettingsFieldsComponent {
    /** Two-way: the choice a click makes is the value at once, so two clicks in a row (before the host renders) never work from a stale one. */
    readonly value = model.required<BuilderValue>();
    /** Prefix of the ids and radio group names; unique per host on the page. */
    readonly uid = input.required<string>();
    /** «Что сначала» is shown for a selection of several materials only. */
    readonly showPriority = input(false);
    /** The quantity modes on offer; the builder offers all three. */
    readonly quantityModes = input<readonly QuantityMode[]>(['AUTO', 'EXACT', 'BUDGET_PERCENT']);
    /** «Авто» is offered. Off for a plan row: the choice is a non-empty set. */
    readonly showAuto = input(true);
    /** «Сколько упражнений» is offered. */
    readonly showQuantity = input(true);
    /** The mechanics on offer, in the registry order; `null` is all of them. */
    readonly allowed = input<readonly Mechanic[] | null>(null);
    /** The name of the group of mechanics, for a screen reader that hears several of them on one page. */
    readonly legend = input('Типы упражнений');
    /** The legend is only for assistive technology (the plan row already shows whose mechanics these are), and the standing hint is left out. */
    readonly quiet = input(false);
    /** Says why the last mechanic stays checked, in the plan row. */
    protected readonly keptOne = signal(false);

    protected readonly mechanicChoices = computed(() => {
        const allowed = this.allowed();
        return allowed === null ? MECHANIC_CHOICES : MECHANIC_CHOICES.filter(choice => allowed.includes(choice.value));
    });
    protected readonly priorityOptions = PRIORITY_OPTIONS;
    protected readonly perTargetText = perTargetText;
    protected readonly percentText = percentText;
    protected readonly auto = computed(() => this.value().mechanics.length === 0);
    protected readonly quantityOptions = computed<readonly SegmentedOption<QuantityMode>[]>(() =>
        QUANTITY_OPTIONS.filter(option => this.quantityModes().includes(option.value)));

    protected setAuto(event: Event): void {
        const input = event.target as HTMLInputElement;
        // «Авто» is the state of no mechanic chosen: checking it clears them; unchecking it with none chosen changes nothing.
        if (!input.checked) { input.checked = true; return; }
        this.patch({ mechanics: [] });
    }

    protected setMechanic(mechanic: Mechanic, event: Event): void {
        const input = event.target as HTMLInputElement;
        const mechanics = toggleMechanic(this.value().mechanics, mechanic, input.checked);
        // Without «Авто» an empty set means nothing: the last mechanic stays, and the hint says why.
        if (!this.showAuto() && mechanics.length === 0) { input.checked = true; this.keptOne.set(true); return; }
        this.keptOne.set(false);
        this.patch({ mechanics });
    }

    protected hasMechanic(mechanic: Mechanic): boolean {
        return this.value().mechanics.includes(mechanic);
    }

    protected setPriority(priority: BuilderValue['priority'] | null): void {
        if (priority !== null) this.patch({ priority });
    }

    protected setQuantityMode(mode: QuantityMode | null): void {
        if (mode !== null) this.patch({ quantityMode: mode });
    }

    protected setPerTarget(event: Event): void {
        this.patch({ perTarget: Number((event.target as HTMLInputElement).value) });
    }

    protected setPercent(event: Event): void {
        this.patch({ percent: Number((event.target as HTMLInputElement).value) });
    }

    private patch(change: Partial<BuilderValue>): void {
        this.value.set({ ...this.value(), ...change });
    }
}
