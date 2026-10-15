import { ChangeDetectionStrategy, Component, ViewEncapsulation, signal } from '@angular/core';

import { SegmentedChoiceComponent, SegmentedOption } from '../shared/segmented-choice.component';
import { ReadingFrameComponent } from '../shared/reading-frame.component';
import { UsageMeterComponent } from '../shared/usage-meter.component';
import { SgSpecimenComponent } from './sg-specimen.component';

import { DeckInsightsComponent, InsightsState } from '../features/own-decks/hub/deck-insights.component';
import { parseInsights } from '../features/own-decks/hub/deck-hub.models';
import hub from '../../../../contracts/decks/hub.json';

type Sort = 'RECENT' | 'TITLE';

/** «Пример экрана»: a deck page put together only from the catalogue above, with local fixtures. */
@Component({
    selector: 'app-sg-screen',
    encapsulation: ViewEncapsulation.None,
    imports: [SgSpecimenComponent, SegmentedChoiceComponent, UsageMeterComponent, DeckInsightsComponent, ReadingFrameComponent],
    templateUrl: './sg-screen.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgScreenComponent {
    protected readonly insights: InsightsState = { phase: 'ready', insights: parseInsights(hub.insights.response) };
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
