import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';

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
    hasUnusedMechanic,
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

/**
 * Five structural widgets of a Deck, each a `<figure>`: a drawing (inline SVG, `role="img"` named by the caption that
 * carries the numbers), a `<details>` with the same data as a real table, and one action. Hatching (SVG `<pattern>`) and
 * outlines tell states apart without colour. The drawings are `@defer (on viewport)`; the caption, table and action are
 * ordinary content. No opens, streaks, time or mastery percentages: only facts the learner can act on.
 */
@Component({
    selector: 'app-deck-insights',
    imports: [DatePipe, RouterLink],
    templateUrl: './deck-insights.component.html',
    styleUrl: './deck-insights.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class DeckInsightsComponent {
    readonly deckId = input.required<string>();
    readonly state = input.required<InsightsState>();
    /** The user chose «Показать» on a widget: the owner lists materials without exercises first. */
    readonly showMissing = output<void>();
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
        const todayDue = insights.dueByDay[0].materials;
        const exercises = Object.values(insights.exercisesByMechanic).reduce((sum, count) => sum + count, 0);
        return {
            insights,
            arc: coverageArc(insights.coverage),
            coverageCaption: coverageSentence(insights.coverage),
            coverageHint: coverageHint(insights.coverage),
            missing: insights.coverage.withoutExercises > 0,
            segments: stateSegments(insights.states),
            statesCaption: statesSentence(insights.states),
            studyLabel: insights.states.DUE > 0 ? 'Учить: к повторению' : insights.states.NOT_STARTED > 0 ? 'Начать с новых материалов'
                : insights.coverage.total === 0 ? '' : 'Учить',
            bars: dueBars(insights.dueByDay),
            dueCaption: dueSentence(insights.dueByDay),
            dueLabel: todayDue > 0 ? 'Начать занятие' : 'Учить',
            mechanics: mechanicBars(insights.exercisesByMechanic),
            mechanicsCaption: mechanicsSentence(insights.exercisesByMechanic),
            mechanicsAction: hasUnusedMechanic(insights.exercisesByMechanic) || (exercises === 0 && insights.coverage.total > 0)
                ? 'pick' as const : 'none' as const,
            capturesCaption: capturesSentence(insights.captures, insights.asOf),
            sheets: Array.from({ length: Math.min(insights.captures.open, 6) }, (_, index) => index)
        };
    });
}
