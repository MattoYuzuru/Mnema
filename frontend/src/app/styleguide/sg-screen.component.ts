import { ChangeDetectionStrategy, Component, ViewEncapsulation, signal } from '@angular/core';

import { SegmentedChoiceComponent, SegmentedOption } from '../shared/segmented-choice.component';
import { UsageMeterComponent } from '../shared/usage-meter.component';
import { SgSpecimenComponent } from './sg-specimen.component';

type Sort = 'RECENT' | 'TITLE';

/** «Пример экрана»: a deck page put together only from the catalogue above, with local fixtures. */
@Component({
    selector: 'app-sg-screen',
    encapsulation: ViewEncapsulation.None,
    imports: [SgSpecimenComponent, SegmentedChoiceComponent, UsageMeterComponent],
    templateUrl: './sg-screen.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgScreenComponent {
    protected readonly sort = signal<Sort | null>('RECENT');
    protected readonly sortOptions: readonly SegmentedOption<Sort>[] = [
        { value: 'RECENT', label: 'Недавние' },
        { value: 'TITLE', label: 'По названию' }
    ];
    protected readonly materials = [
        { title: 'Частицы は и が', note: 'Две правки сегодня', fresh: true },
        { title: 'Счётные суффиксы', note: 'Сохранён 3 октября', fresh: false },
        { title: 'Вежливая форма глагола', note: 'Сохранён 28 сентября', fresh: false }
    ];
}
