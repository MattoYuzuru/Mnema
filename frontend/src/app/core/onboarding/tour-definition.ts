import { STEP_BRANCH } from './onboarding.models';
import { TOUR_ANCHOR_KEYS } from './tour-anchor-keys';
import { TourDefinition, isServerStep } from './tour.models';

/**
 * What is wrong with a definition, as sentences (empty: it is sound): a step key outside the server's registry, a step of another
 * branch than the definition's, a repeated step, an anchor that is not in the registry, a missing title or body, an empty tour.
 * Every definition has a spec that expects no problems, so a renamed key fails there and not for a learner.
 */
export function tourDefinitionProblems(definition: TourDefinition, anchors: readonly string[] = TOUR_ANCHOR_KEYS): readonly string[] {
    const problems: string[] = [];
    if (definition.steps.length === 0) problems.push(`${definition.id}: the tour has no steps`);
    if (definition.quest === true) problems.push(`${definition.id}: a quest is not a tour of the journey (see questDefinitionProblems)`);
    const seen = new Set<string>();
    for (const step of definition.steps) {
        const where = `${definition.id}/${step.key}`;
        if (step.external !== true && definition.steps.slice(0, definition.steps.indexOf(step)).some(earlier => earlier.external === true)) problems.push(`${where}: a step after an external one`);
        if (!isServerStep(step.key)) problems.push(`${where}: unknown step key`);
        else if (definition.branch !== null && STEP_BRANCH[step.key] !== null && STEP_BRANCH[step.key] !== definition.branch) {
            problems.push(`${where}: the step belongs to ${STEP_BRANCH[step.key]}, the tour to ${definition.branch}`);
        }
        if (seen.has(step.key)) problems.push(`${where}: repeated step`);
        seen.add(step.key);
        // An external step is drawn by another component: it has no anchor, title or body of its own to check.
        if (step.external === true) continue;
        if (!anchors.includes(step.anchor)) problems.push(`${where}: anchor ${step.anchor} is not in the registry`);
        if (step.title.trim().length === 0) problems.push(`${where}: no title`);
        const parts = step.body.kind === 'slides' ? step.body.slides : step.body.paragraphs;
        if (parts.length === 0) problems.push(`${where}: no body`);
        if (step.body.kind === 'slides' && parts.length < 2) problems.push(`${where}: a mini-slider needs at least two slides`);
        if (step.gate !== 'next' && (step.gate.action.length === 0 || step.gate.label === '')) problems.push(`${where}: an action gate needs an event and a label or null`);
    }
    return problems;
}
