import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { DeckInsights, MATERIAL_STATES } from './deck-hub.models';
import { STATE_LABELS } from './deck-hub.text';
import {
    DUE_COLUMN,
    DUE_PLOT_HEIGHT,
    MECHANIC_PLOT_WIDTH,
    MECHANIC_ROW,
    STATE_BAR_WIDTH,
    capturesSentence,
    coverageArc,
    coverageHint,
    coverageSentence,
    dueBars,
    dueSentence,
    mechanicBars,
    mechanicsSentence,
    stateSegments,
    statesSentence
} from './insight-charts';

/** Insights are loaded by the hub page; a failure here never hides the Deck, the list or any action. */
export type InsightsState =
    | { readonly phase: 'loading' }
    | { readonly phase: 'error' }
    | { readonly phase: 'ready'; readonly insights: DeckInsights };

let nextInsights = 0;

/** Пять графиков в общем листе; подписи и легенды сохраняют все числовые данные. */
@Component({
    selector: 'app-deck-insights',
    templateUrl: './deck-insights.component.html',
    styleUrl: './deck-insights.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class DeckInsightsComponent {
    readonly state = input.required<InsightsState>();
    readonly retry = output<void>();

    protected readonly uid = `mn-insights-${nextInsights++}`;
    protected readonly states = MATERIAL_STATES;
    protected readonly stateLabels = STATE_LABELS;
    protected readonly stateBarWidth = STATE_BAR_WIDTH;
    protected readonly dueColumn = DUE_COLUMN;
    protected readonly duePlotHeight = DUE_PLOT_HEIGHT;
    protected readonly mechanicWidth = MECHANIC_PLOT_WIDTH;
    protected readonly mechanicRow = MECHANIC_ROW;

    protected readonly view = computed(() => {
        const state = this.state();
        if (state.phase !== 'ready') return null;
        const insights = state.insights;
        return {
            insights,
            arc: coverageArc(insights.coverage),
            coverageCaption: coverageSentence(insights.coverage),
            coverageHint: coverageHint(insights.coverage),
            segments: stateSegments(insights.states),
            statesCaption: statesSentence(insights.states),
            bars: dueBars(insights.dueByDay),
            dueCaption: dueSentence(insights.dueByDay),
            mechanics: mechanicBars(insights.exercisesByMechanic),
            mechanicsCaption: mechanicsSentence(insights.exercisesByMechanic),
            capturesCaption: capturesSentence(insights.captures, insights.asOf),
            sheets: Array.from({ length: Math.min(insights.captures.open, 6) }, (_, index) => index)
        };
    });
}
