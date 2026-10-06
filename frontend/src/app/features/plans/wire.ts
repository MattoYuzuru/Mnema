/** Strict wire readers shared by the plans and learning-profile boundaries. Every violation is a `PlansProtocolError`. */
import { HttpResponse } from '@angular/common/http';

import { PlansProtocolError } from './plans.models';

const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/u;

export function protocol(message: string): PlansProtocolError { return new PlansProtocolError(message); }

export function exact(value: unknown, keys: readonly string[]): Record<string, unknown> {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw protocol('Expected object.');
    const object = value as Record<string, unknown>;
    const actual = Object.keys(object);
    if (actual.length !== keys.length || actual.some(key => !keys.includes(key))) throw protocol('Unexpected shape.');
    return object;
}

export function integer(value: unknown, maximum = 1_000_000_000): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0 || value > maximum) throw protocol('Invalid number.');
    return value;
}

export function bool(value: unknown): boolean {
    if (typeof value !== 'boolean') throw protocol('Invalid flag.');
    return value;
}

export function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
    if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) throw protocol('Invalid enum value.');
    return value as T;
}

export function instant(value: unknown): string {
    if (typeof value !== 'string' || !INSTANT.test(value) || Number.isNaN(Date.parse(value))) throw protocol('Invalid timestamp.');
    return value;
}

export function nullable<T>(value: unknown, parse: (value: unknown) => T): T | null { return value === null ? null : parse(value); }

export function text(value: unknown, maximum = 200): string {
    if (typeof value !== 'string' || value.length === 0 || value.length > maximum) throw protocol('Invalid text.');
    return value;
}

/** A private, uncached 200 response: the entitlement and the goal are the owner's alone. */
export function privateOk(response: HttpResponse<unknown>): void {
    if (response.status !== 200) throw protocol('Unexpected status.');
    const directives = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!directives.includes('private') || !directives.includes('no-store')) throw protocol('Response can be cached.');
}
