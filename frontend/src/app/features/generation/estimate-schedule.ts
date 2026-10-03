import { Observable, Subscription } from 'rxjs';

import { GenerationEstimate, GenerationSpec } from './generation.models';

/** How long a composer waits after the last change before it asks the server for the cost. */
export const ESTIMATE_DEBOUNCE_MS = 400;

/**
 * One estimate per pause, shared by the Materials composer and the exercise builder: starts a timer, then the request, and returns the
 * cleanup that cancels both (an effect calls it before the next change, so only the last request survives).
 */
export function scheduleEstimate(estimate: (spec: GenerationSpec) => Observable<GenerationEstimate>, spec: GenerationSpec,
                                 done: { readonly next: (value: GenerationEstimate) => void; readonly error: (error: unknown) => void }): () => void {
    let request: Subscription | null = null;
    const timer = setTimeout(() => { request = estimate(spec).subscribe(done); }, ESTIMATE_DEBOUNCE_MS);
    return () => { clearTimeout(timer); request?.unsubscribe(); };
}
