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
export type TourGate = 'next' | TourActionGate;

/**
 * A step that waits for `action`. `label` is the name of the control in «Нажмите «{label}», чтобы продолжить»; `null` when the step
 * asks for something that is not one control (the hint line is then left out). `pauses`: the event does not move to the next step;
 * it puts the tour on pause at this step (the learner goes to do the thing, here the study session, and the page resumes the tour).
 */
export interface TourActionGate {
    readonly action: string;
    readonly label: string | null;
    readonly pauses?: boolean;
}

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
    /**
     * A router URL the engine navigates to before it looks for the anchor; a function is asked when the step is entered and may
     * answer `null` (stay where the learner is), so a route can depend on what the learner made on the way (a deck).
     */
    readonly route?: string | (() => string | null);
    /**
     * Shown to learners who have used flashcards before (`FAMILIAR`, `CONFIDENT`). The engine ignores it: the caller that builds
     * the definition keeps the essential steps for them (`tour-definitions.ts`), so the engine stays generic.
     */
    readonly essential?: boolean;
    /**
     * Drawn by another component (a finale screen): `TourHost` shows nothing for it, but the engine still holds the position, the
     * pause and the record. Such steps come last and do not count in «Шаг N из M».
     */
    readonly external?: boolean;
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

export function isActionGate(gate: TourGate): gate is TourActionGate {
    return gate !== 'next';
}
