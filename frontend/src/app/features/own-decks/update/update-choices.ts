import { UpdateChoice } from '../../../shared/copy-text';
import { ApplyChoice, ReviewUnit, choiceToWire, isDefaultChoice, takesAuthor } from './update-plan.models';

/** The reader's explicit picks, by unit id. A unit absent from the map is undecided (or stands on the default of its class). */
export type Picks = ReadonlyMap<string, UpdateChoice>;

/** Which of the two apply buttons was pressed. */
export type ApplyMode = 'safe' | 'selected';

/**
 * The units that are locked to «Принять» because a unit the reader accepts needs them (`dependsOn`): unit id to the unit that needs it.
 * Only a loaded, undecided-able unit can be locked (a conflict, a unit changed again or a deletion has no «Принять» to lock to).
 */
export function lockedDependencies(units: readonly ReviewUnit[], picks: Picks): ReadonlyMap<string, ReviewUnit> {
    const byId = new Map(units.map(unit => [unit.id, unit] as const));
    const locked = new Map<string, ReviewUnit>();
    for (const unit of units) {
        if (!takesAuthor(picks.get(unit.id))) continue;
        for (const needed of unit.dependsOn) {
            const target = byId.get(needed);
            if (target === undefined || locked.has(needed) || (target.class !== 'new' && target.class !== 'fix')) continue;
            locked.set(needed, unit);
        }
    }
    return locked;
}

/** The choice a row shows: «Принять» on a locked dependency, else what the reader picked, else nothing. */
export function effectiveChoice(unit: ReviewUnit, picks: Picks, locked: ReadonlyMap<string, ReviewUnit>): UpdateChoice | null {
    return locked.has(unit.id) ? 'accept' : picks.get(unit.id) ?? null;
}

/**
 * The explicit choices of the request, in the order of the list. A pick of a unit that is not loaded any more (the list was reread) and
 * a pick on a locked dependency are left out: the server takes the dependency together with the unit that needs it.
 *
 * `safe` is «Принять всё безопасное»: `acceptSafe: true` plus only the picks of new and corrected units that differ from the default
 * (their «Не брать»). A pick on a conflict, a unit changed again or a deletion is never sent by it; those wait for `selected`
 * («Применить выбранное»), which sends every pick, defaults included.
 */
export function explicitChoices(units: readonly ReviewUnit[], picks: Picks, locked: ReadonlyMap<string, ReviewUnit>, mode: ApplyMode): ApplyChoice[] {
    const choices: ApplyChoice[] = [];
    for (const unit of units) {
        const pick = picks.get(unit.id);
        if (pick === undefined || locked.has(unit.id)) continue;
        if (mode === 'safe' && (!isSafeClass(unit) || isDefaultChoice(unit.class, pick))) continue;
        const choice = choiceToWire(unit.class, pick);
        if (choice !== null) choices.push({ unitId: unit.id, choice });
    }
    return choices;
}

function isSafeClass(unit: ReviewUnit): boolean {
    return unit.class === 'new' || unit.class === 'fix';
}

/**
 * The picks «Принять всё безопасное» leaves to «Применить выбранное»: a pick on a conflict, a unit changed again or a deletion that
 * the request would carry (loaded, not locked, a choice its class has).
 */
export function picksForSelected(units: readonly ReviewUnit[], picks: Picks, locked: ReadonlyMap<string, ReviewUnit>): number {
    return units.filter(unit => !isSafeClass(unit) && !locked.has(unit.id) && picks.has(unit.id)
        && choiceToWire(unit.class, picks.get(unit.id)!) !== null).length;
}

/** A unit the reader skips although a unit they accept (or, in `safe` mode, that «Принять всё безопасное» takes) needs. */
export interface BlockedDependency { readonly needed: ReviewUnit; readonly by: ReviewUnit; }

export function skippedDependency(units: readonly ReviewUnit[], picks: Picks, mode: ApplyMode): BlockedDependency | null {
    const byId = new Map(units.map(unit => [unit.id, unit] as const));
    // A locked unit is taken with the unit that needs it, whatever pick it still carries from before it was locked.
    const locked = lockedDependencies(units, picks);
    for (const unit of units) {
        const pick = picks.get(unit.id);
        // «Принять всё безопасное» takes new and corrected units and sends only their own picks; «Применить выбранное» takes what the reader accepted.
        const accepted = mode === 'safe' ? isSafeClass(unit) && pick !== 'skip' : takesAuthor(pick);
        if (!accepted) continue;
        for (const needed of unit.dependsOn) {
            const target = byId.get(needed);
            if (target !== undefined && picks.get(needed) === 'skip' && !locked.has(needed)) return { needed: target, by: unit };
        }
    }
    return null;
}

/** The first dependency that is a material, by title, for the note under a unit that needs it; `null` when it needs none loaded. */
export function dependencyTitle(unit: ReviewUnit, byId: ReadonlyMap<string, ReviewUnit>): string | null {
    for (const needed of unit.dependsOn) {
        const target = byId.get(needed);
        if (target?.wireKind === 'MATERIAL') return target.title;
    }
    return null;
}
