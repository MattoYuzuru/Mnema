import { ChangeDetectionStrategy, Component, ViewEncapsulation, signal } from '@angular/core';

import { SegmentedChoiceComponent, SegmentedOption } from '../shared/segmented-choice.component';
import { ReadingFrameComponent } from '../shared/reading-frame.component';
import { UsageMeterComponent } from '../shared/usage-meter.component';
import { SgSpecimenComponent } from './sg-specimen.component';

import { DeckInsightsComponent, InsightsState } from '../features/own-decks/hub/deck-insights.component';
import { parseInsights } from '../features/own-decks/hub/deck-hub.models';

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
    protected readonly insights: InsightsState = {
        phase: 'ready',
        insights: parseInsights({
            deckId: '11111111-1111-4111-8111-111111111111',
            deckRevisionId: '33333333-3333-4333-8333-333333333333',
            deckVersion: '7',
            asOf: '2026-10-02T09:00:00Z',
            timezone: 'Europe/Moscow',
            coverage: { total: 10, withExercises: 7, withoutExercises: 3 },
            states: { NOT_STARTED: 4, LEARNING: 2, DUE: 2, ON_TRACK: 2 },
            dueByDay: [
                { date: '2026-10-02', materials: 2 },
                { date: '2026-10-03', materials: 0 },
                { date: '2026-10-04', materials: 1 },
                { date: '2026-10-05', materials: 0 },
                { date: '2026-10-06', materials: 0 },
                { date: '2026-10-07', materials: 1 },
                { date: '2026-10-08', materials: 0 }
            ],
            exercisesByMechanic: { SELF_CHECK: 3, FREE_RESPONSE: 4, CLOZE: 1, CHOICE: 6, MATCH: 0, ORDER: 0, CATEGORIZE: 0 },
            captures: { open: 4, oldestOpenCreatedAt: '2026-09-12T12:00:00.123456Z' }
        })
    };
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
