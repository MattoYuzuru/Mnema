import { ChangeDetectionStrategy, Component, ViewEncapsulation, signal } from '@angular/core';

import { MnemaSelectComponent, MnemaSelectOption } from '../core/controls/mnema-select.component';
import { ChoiceListComponent } from '../content/exercise/choice-list.component';
import { LearnerChoiceOption } from '../content/exercise/exercise-content.models';
import { OrderRowComponent } from '../shared/order-row.component';
import { HoldToDeleteButtonComponent } from '../shared/hold-to-delete-button.component';
import { TagInputComponent } from '../shared/tag-input.component';
import { SegmentedChoiceComponent, SegmentedOption } from '../shared/segmented-choice.component';
import { ToggletipComponent } from '../shared/toggletip.component';
import { PlanOptionComponent } from '../features/plans/plan-option.component';
import { PromoApiService } from '../features/promo/promo-api.service';
import { PromoRedeemComponent } from '../features/promo/promo-redeem.component';
import { parsePlans } from '../features/plans/plans-api.service';
import { plansBody } from '../features/plans/plans-test-data';
import { PlanId, PlanPeriod } from '../features/plans/plans.models';
import { DemoPromoApi } from './sg-promo-demo';
import { SgSpecimenComponent } from './sg-specimen.component';
import { BUTTON_USAGE } from './styleguide.data';

type Strictness = 'SOFT' | 'STRICT';

const OPTION_IDS = ['d4000000-0000-4000-8000-000000000001', 'd4000000-0000-4000-8000-000000000002', 'd4000000-0000-4000-8000-000000000003'];

/** «Фирменные приёмы», «Кнопки», «Поля ввода» and «Выбор»: every example is the app's own class or component with local fixtures. */
@Component({
    selector: 'app-sg-controls',
    encapsulation: ViewEncapsulation.None,
    imports: [SgSpecimenComponent, HoldToDeleteButtonComponent, SegmentedChoiceComponent, ToggletipComponent, MnemaSelectComponent, ChoiceListComponent,
        PlanOptionComponent, PromoRedeemComponent, TagInputComponent, OrderRowComponent],
    providers: [{ provide: PromoApiService, useClass: DemoPromoApi }],
    templateUrl: './sg-controls.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgControlsComponent {
    protected readonly buttonUsage = BUTTON_USAGE;
    protected readonly deleted = signal(false);
    protected readonly consentOn = signal(false);
    protected setConsent(event: Event): void { this.consentOn.set((event.target as HTMLInputElement).checked); }

    protected readonly title = signal('');
    protected readonly titleInvalid = signal(true);
    protected readonly note = signal('');

    protected readonly strictness = signal<Strictness | null>('SOFT');
    protected readonly strictnessOptions: readonly SegmentedOption<Strictness>[] = [
        { value: 'SOFT', label: 'Мягко', hint: 'Мягко: засчитываем ответ с опечаткой.' },
        { value: 'STRICT', label: 'Строго', hint: 'Строго: ответ должен совпасть с эталоном.' }
    ];

    protected readonly tiers = parsePlans(plansBody()).plans;
    protected readonly tier = signal<PlanId>('PLUS');
    protected readonly tierPeriod = signal<PlanPeriod>('MONTH');

    protected readonly tags = signal<readonly string[]>(['jlpt n5']);

    protected readonly language = signal('ru');
    protected readonly languages: readonly MnemaSelectOption[] = [
        { value: 'ru', label: 'Русский' }, { value: 'en', label: 'Английский' }, { value: 'ja', label: 'Японский' },
        { value: 'de', label: 'Немецкий', disabled: true }
    ];

    protected readonly topic = signal('japanese');
    protected readonly topics: readonly MnemaSelectOption[] = [
        { value: 'english', label: 'Английский', group: 'Языки' }, { value: 'japanese', label: 'Японский', group: 'Языки' },
        { value: 'biology', label: 'Биология', group: 'Наука' }, { value: 'physics', label: 'Физика', group: 'Наука' },
        { value: 'other', label: 'Другое' }
    ];

    protected readonly choiceOptions: readonly LearnerChoiceOption[] = [
        { optionId: OPTION_IDS[0], blocks: [{ kind: 'TEXT', text: 'Канберра' }] },
        { optionId: OPTION_IDS[1], blocks: [{ kind: 'TEXT', text: 'Сидней' }] },
        { optionId: OPTION_IDS[2], blocks: [{ kind: 'TEXT', text: 'Мельбурн' }] }
    ];
    protected readonly picked = signal<readonly string[]>([OPTION_IDS[0]]);

    protected readonly shelf = signal<readonly { readonly id: string; readonly title: string; readonly listed: boolean; readonly error: string | null }[]>([
        { id: 'Kq7xT3mNpR', title: 'Испанский язык: базовые глаголы', listed: true, error: null },
        { id: 'Zp4WcA8vQe', title: 'Столицы мира', listed: false, error: null },
        { id: 'Mn5bRt2yHd', title: 'Новая колода без названия', listed: true, error: 'Эту колоду нельзя поставить на полку: она не публичная или не опубликована. Уберите её или замените.' }
    ]);
    protected readonly shelfBusy = signal(false);

    protected moveShelf(id: string, step: -1 | 1): void {
        const list = [...this.shelf()], from = list.findIndex(row => row.id === id), to = from + step;
        if (to < 0 || to >= list.length) return;
        [list[from], list[to]] = [list[to], list[from]];
        this.shelf.set(list);
    }
    protected setShelfBusy(event: Event): void { this.shelfBusy.set((event.target as HTMLInputElement).checked); }
    protected removeShelf(id: string): void { this.shelf.update(list => list.filter(row => row.id !== id)); }

    protected onTitle(event: Event): void {
        const value = (event.target as HTMLInputElement).value;
        this.title.set(value);
        this.titleInvalid.set(value.trim().length === 0);
    }

    protected onNote(event: Event): void { this.note.set((event.target as HTMLTextAreaElement).value); }

    protected pick(optionId: string): void { this.picked.set([optionId]); }
}
