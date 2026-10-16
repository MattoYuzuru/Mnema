import { DEMO_TOUR } from '../../styleguide/sg-tour-demo';
import { TOUR_ANCHOR_KEYS } from './tour-anchor-keys';
import { tourDefinitionProblems } from './tour-definition';
import { paragraphs, slides } from './tour-rich-text';
import { TourDefinition, TourStep } from './tour.models';

const step = (overrides: Partial<TourStep> = {}): TourStep => ({
    key: 'sample.hub', anchor: 'hub.header', title: 'Заголовок', body: paragraphs('Текст.'), gate: 'next', ...overrides
});
const tour = (steps: readonly TourStep[], branch: TourDefinition['branch'] = 'SAMPLE'): TourDefinition => ({ id: 't', branch, steps });

describe('tourDefinitionProblems', () => {
    it('finds nothing wrong with the styleguide demonstration tour', () => {
        expect(tourDefinitionProblems(DEMO_TOUR)).toEqual([]);
    });

    it('keeps the anchor registry free of repeats', () => {
        expect(new Set(TOUR_ANCHOR_KEYS).size).toBe(TOUR_ANCHOR_KEYS.length);
    });

    it('accepts shared steps in a branch tour and a branch-less tour of shared steps', () => {
        expect(tourDefinitionProblems(tour([step(), step({ key: 'finale.ai', anchor: 'hub.start' })]))).toEqual([]);
        expect(tourDefinitionProblems(tour([step({ key: 'finale.done' })], null))).toEqual([]);
    });

    it('reports a step key outside the registry of the server', () => {
        expect(tourDefinitionProblems(tour([step({ key: 'tour.unknown' as TourStep['key'] })]))).toEqual(['t/tour.unknown: unknown step key']);
    });

    it('reports a step of another branch', () => {
        expect(tourDefinitionProblems(tour([step({ key: 'own.material' })]))).toEqual(['t/own.material: the step belongs to OWN, the tour to SAMPLE']);
    });

    it('reports a repeated step and an empty tour', () => {
        expect(tourDefinitionProblems(tour([step(), step()]))).toEqual(['t/sample.hub: repeated step']);
        expect(tourDefinitionProblems(tour([]))).toEqual(['t: the tour has no steps']);
    });

    it('reports an anchor that is not in the registry, so a renamed anchor fails here and not for a learner', () => {
        expect(tourDefinitionProblems(tour([step({ anchor: 'hub.renamed' })]))).toEqual(['t/sample.hub: anchor hub.renamed is not in the registry']);
        expect(tourDefinitionProblems(tour([step({ anchor: 'custom.one' })]), ['custom.one'])).toEqual([]);
    });

    it('reports a missing title, a missing body, a one-slide slider and a half-defined gate', () => {
        expect(tourDefinitionProblems(tour([step({ title: ' ' })]))).toEqual(['t/sample.hub: no title']);
        expect(tourDefinitionProblems(tour([step({ body: { kind: 'paragraphs', paragraphs: [] } })]))).toEqual(['t/sample.hub: no body']);
        expect(tourDefinitionProblems(tour([step({ body: slides('Один.') })]))).toEqual(['t/sample.hub: a mini-slider needs at least two slides']);
        expect(tourDefinitionProblems(tour([step({ gate: { action: '', label: 'Добавить к себе' } })]))).toEqual(['t/sample.hub: an action gate needs an event and a label']);
    });
});
