import { ChangeDetectionStrategy, Component, ViewEncapsulation, signal } from '@angular/core';

import { MnemaSelectComponent, MnemaSelectOption } from '../core/controls/mnema-select.component';
import { ChoiceListComponent } from '../content/exercise/choice-list.component';
import { LearnerChoiceOption } from '../content/exercise/exercise-content.models';
import { HoldToDeleteButtonComponent } from '../shared/hold-to-delete-button.component';
import { SegmentedChoiceComponent, SegmentedOption } from '../shared/segmented-choice.component';
import { ToggletipComponent } from '../shared/toggletip.component';
import { SgSpecimenComponent } from './sg-specimen.component';
import { BUTTON_USAGE } from './styleguide.data';

type Strictness = 'SOFT' | 'STRICT';

const OPTION_IDS = ['d4000000-0000-4000-8000-000000000001', 'd4000000-0000-4000-8000-000000000002', 'd4000000-0000-4000-8000-000000000003'];

/** «Фирменные приёмы», «Кнопки», «Поля ввода» and «Выбор»: every example is the app's own class or component with local fixtures. */
@Component({
    selector: 'app-sg-controls',
    encapsulation: ViewEncapsulation.None,
    imports: [SgSpecimenComponent, HoldToDeleteButtonComponent, SegmentedChoiceComponent, ToggletipComponent, MnemaSelectComponent, ChoiceListComponent],
    templateUrl: './sg-controls.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgControlsComponent {
    protected readonly buttonUsage = BUTTON_USAGE;
    protected readonly deleted = signal(false);

    protected readonly title = signal('');
    protected readonly titleInvalid = signal(true);
    protected readonly note = signal('');

    protected readonly strictness = signal<Strictness | null>('SOFT');
    protected readonly strictnessOptions: readonly SegmentedOption<Strictness>[] = [
        { value: 'SOFT', label: 'Мягко', hint: 'Мягко: засчитываем ответ с опечаткой.' },
        { value: 'STRICT', label: 'Строго', hint: 'Строго: ответ должен совпасть с эталоном.' }
    ];

    protected readonly language = signal('ru');
    protected readonly languages: readonly MnemaSelectOption[] = [
        { value: 'ru', label: 'Русский' }, { value: 'en', label: 'Английский' }, { value: 'ja', label: 'Японский' },
        { value: 'de', label: 'Немецкий', disabled: true }
    ];

    protected readonly choiceOptions: readonly LearnerChoiceOption[] = [
        { optionId: OPTION_IDS[0], blocks: [{ kind: 'TEXT', text: 'Канберра' }] },
        { optionId: OPTION_IDS[1], blocks: [{ kind: 'TEXT', text: 'Сидней' }] },
        { optionId: OPTION_IDS[2], blocks: [{ kind: 'TEXT', text: 'Мельбурн' }] }
    ];
    protected readonly picked = signal<readonly string[]>([OPTION_IDS[0]]);

    protected onTitle(event: Event): void {
        const value = (event.target as HTMLInputElement).value;
        this.title.set(value);
        this.titleInvalid.set(value.trim().length === 0);
    }

    protected onNote(event: Event): void { this.note.set((event.target as HTMLTextAreaElement).value); }

    protected pick(optionId: string): void { this.picked.set([optionId]); }
}
