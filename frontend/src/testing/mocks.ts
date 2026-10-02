import type { Mock } from 'vitest';

/**
 * Typed partial test double: every method of `T` is a Vitest `Mock` with `T`'s own signature.
 * Replaces Jasmine's `SpyObj`; only the members a spec actually stubs exist at runtime.
 */
export type SpyObj<T> = {
    [K in keyof T]: T[K] extends (...args: infer A) => infer R ? Mock<(...args: A) => R> : T[K];
};

/** Builds a {@link SpyObj} from the members a spec stubs; unlisted members stay undefined at runtime. */
export function spyObj<T>(members: { [K in keyof T]?: unknown }): SpyObj<T> {
    return members as SpyObj<T>;
}

/** Arguments of the most recent call; fails the spec loudly when the mock was never called. */
export function lastCall<A extends unknown[]>(mock: Mock<(...args: A) => unknown>): A {
    const args = mock.mock.lastCall;
    if (args === undefined) {
        throw new Error('expected the mock to have been called');
    }
    return args;
}
