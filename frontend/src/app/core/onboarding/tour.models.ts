import { Branch, StepKey } from './onboarding.models';

/** A run of text; `key` marks a highlighted key word (bold, ink, underlined in forced colours), never colour alone. */
export interface TextSegment {
    readonly text: string;
    readonly key: boolean;
}

export type RichText = readonly TextSegment[];

/** The body of a step: paragraphs read together, or the slides of a mini-slider (explanations of one place, not new steps). */
export type TourBody =
    | { readonly kind: 'paragraphs'; readonly paragraphs: readonly RichText[] }
    | { readonly kind: 'slides'; readonly slides: readonly RichText[] };

/**
 * `'next'`: the learner moves on with «Дальше». `{ action, label }`: the step waits for the target component to emit `action`
 * through `TourEvents`; `label` is the name of the control in «Нажмите «{label}», чтобы продолжить».
 */
export type TourGate = 'next' | { readonly action: string; readonly label: string };

export type TourPlacement = 'top' | 'bottom' | 'start' | 'end';

export interface TourStep {
    /** The key the server records (`journey.json` registry). */
    readonly key: StepKey;
    /** A key of `TOUR_ANCHOR_KEYS`: the element registered with `mnTourAnchor`. */
    readonly anchor: string;
    readonly title: string;
    readonly body: TourBody;
    readonly gate: TourGate;
    /** The side of the target the card prefers; the others are tried when it does not fit. */
    readonly placement?: TourPlacement;
    /** A router URL the engine navigates to before it looks for the anchor. */
    readonly route?: string;
    /** Data for the definitions and their tests: a step the branch cannot do without. The engine never skips a step by itself. */
    readonly essential?: boolean;
}

export interface TourDefinition {
    readonly id: string;
    /** The branch of the journey the steps belong to; `null` for a tour of shared steps only. */
    readonly branch: Branch | null;
    readonly steps: readonly TourStep[];
}

/** How a run ended. */
export interface TourEnd {
    readonly definitionId: string;
    /** `finished`: the learner chose «Завершить знакомство»; `completed`: «Дальше» on the last step; `stopped`: the page took the tour down. */
    readonly reason: 'finished' | 'completed' | 'stopped';
}

export function isActionGate(gate: TourGate): gate is { readonly action: string; readonly label: string } {
    return gate !== 'next';
}
