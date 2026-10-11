import { UpdateChoice } from '../../../shared/copy-text';
import { dependencyTitle, effectiveChoice, explicitChoices, lockedDependencies, picksForSelected, skippedDependency } from './update-choices';
import { ReviewUnit, choiceToWire, isDefaultChoice, takesAuthor, tabCounts, tabOf, toReviewUnit, lastRankOf } from './update-plan.models';
import { keyOf, planUnit } from './update-test-data';

const unit = (...args: Parameters<typeof planUnit>): ReviewUnit => toReviewUnit(planUnit(...args));
const picks = (entries: Record<string, UpdateChoice>) => new Map(Object.entries(entries));

describe('the choices of the update review', () => {
    const material = unit('MATERIAL', 1, 'NEW');
    const objective = unit('OBJECTIVE', 2, 'NEW');
    const exercise = unit('EXERCISE', 3, 'NEW', { dependsOn: [material.id, objective.id] });
    const fix = unit('MATERIAL', 4, 'SAFE');
    const conflict = unit('MATERIAL', 5, 'CONFLICT');
    const deleted = unit('MATERIAL', 6, 'DELETED');
    const again = unit('MATERIAL', 7, 'ASK_AGAIN');
    const all = [material, objective, exercise, fix, conflict, deleted, again];

    it('maps every choice of a class to the wire and refuses a choice the class does not have', () => {
        expect(choiceToWire('new', 'accept')).toBe('ACCEPT');
        expect(choiceToWire('fix', 'skip')).toBe('DECLINE');
        expect(choiceToWire('changed-again', 'keep-mine')).toBe('KEEP_MINE');
        expect(choiceToWire('changed-again', 'take-author')).toBe('ACCEPT');
        expect(choiceToWire('changed-again', 'skip')).toBeNull();
        expect(choiceToWire('changed-again', 'accept')).toBeNull();
        expect(choiceToWire('conflict', 'keep-mine')).toBe('KEEP_MINE');
        expect(choiceToWire('conflict', 'take-author')).toBe('ACCEPT');
        expect(choiceToWire('deleted', 'keep')).toBe('KEEP');
        expect(choiceToWire('deleted', 'delete')).toBe('DELETE');
        expect(choiceToWire('conflict', 'accept')).toBeNull();
        expect(choiceToWire('deleted', 'skip')).toBeNull();
        expect(choiceToWire('new', 'keep')).toBeNull();
    });

    it('knows the defaults of the classes: a conflict and a unit changed again keep what the reader has', () => {
        expect(isDefaultChoice('new', 'accept')).toBe(true);
        expect(isDefaultChoice('fix', 'accept')).toBe(true);
        expect(isDefaultChoice('new', 'skip')).toBe(false);
        expect(isDefaultChoice('conflict', 'keep-mine')).toBe(true);
        expect(isDefaultChoice('deleted', 'keep')).toBe(true);
        expect(isDefaultChoice('changed-again', 'keep-mine')).toBe(true);
        expect(isDefaultChoice('changed-again', 'take-author')).toBe(false);
        expect(takesAuthor('accept')).toBe(true);
        expect(takesAuthor('take-author')).toBe(true);
        expect(takesAuthor('keep-mine')).toBe(false);
        expect(takesAuthor(null)).toBe(false);
    });

    it('sends only the declines of new and corrected units for acceptSafe, and all picks for the selected apply, in list order', () => {
        const chosen = picks({ [again.id]: 'take-author', [deleted.id]: 'keep', [conflict.id]: 'take-author', [fix.id]: 'accept', [material.id]: 'skip' });
        const none = new Map<string, ReviewUnit>();
        // «Принять всё безопасное» carries only the declines of new and corrected units; picks on conflicts, units changed again and deletions wait.
        expect(explicitChoices(all, chosen, none, 'safe')).toEqual([{ unitId: material.id, choice: 'DECLINE' }]);
        expect(picksForSelected(all, chosen, none)).toBe(3);
        expect(explicitChoices(all, chosen, none, 'selected')).toEqual([
            { unitId: material.id, choice: 'DECLINE' }, { unitId: fix.id, choice: 'ACCEPT' }, { unitId: conflict.id, choice: 'ACCEPT' },
            { unitId: deleted.id, choice: 'KEEP' }, { unitId: again.id, choice: 'ACCEPT' }
        ]);
    });

    it('sends a unit changed again as KEEP_MINE or ACCEPT and never as DECLINE', () => {
        expect(explicitChoices(all, picks({ [again.id]: 'keep-mine' }), new Map(), 'selected')).toEqual([{ unitId: again.id, choice: 'KEEP_MINE' }]);
        expect(explicitChoices(all, picks({ [again.id]: 'keep-mine' }), new Map(), 'safe')).toEqual([]);
        expect(explicitChoices(all, picks({ [again.id]: 'skip' }), new Map(), 'selected')).toEqual([]);
    });

    it('does not send the stored pick of a locked unit, and counts only sendable picks of the other classes for the note', () => {
        const chosen = picks({ [exercise.id]: 'accept', [material.id]: 'skip', [conflict.id]: 'keep-mine', [fix.id]: 'skip', [deleted.id]: 'skip' });
        const locks = lockedDependencies(all, chosen);
        expect(explicitChoices(all, chosen, locks, 'selected').map(choice => choice.unitId)).toEqual([exercise.id, fix.id, conflict.id]);
        expect(explicitChoices(all, chosen, locks, 'safe')).toEqual([{ unitId: fix.id, choice: 'DECLINE' }]);
        // «Не брать» is no answer of a deletion: it is not a pick the other button would send.
        expect(picksForSelected(all, chosen, locks)).toBe(1);
        expect(picksForSelected(all, picks({}), new Map())).toBe(0);
        expect(picksForSelected([material, fix], picks({ [material.id]: 'skip' }), new Map())).toBe(0);
    });

    it('leaves out a pick of a unit that is not loaded and a pick that does not fit its class', () => {
        expect(explicitChoices([fix], picks({ [material.id]: 'accept', [fix.id]: 'keep-mine' }), new Map(), 'selected')).toEqual([]);
    });

    it('locks what an accepted unit needs, only among loaded units that can be accepted, and shows it as accepted', () => {
        const locked = lockedDependencies(all, picks({ [exercise.id]: 'accept' }));
        expect([...locked.keys()]).toEqual([material.id, objective.id]);
        expect(locked.get(material.id)).toBe(exercise);
        expect(effectiveChoice(material, picks({ [material.id]: 'skip' }), locked)).toBe('accept');
        expect(effectiveChoice(fix, picks({ [fix.id]: 'skip' }), locked)).toBe('skip');
        expect(effectiveChoice(fix, picks({}), locked)).toBeNull();
        expect(lockedDependencies(all, picks({ [exercise.id]: 'skip' })).size).toBe(0);
        expect(lockedDependencies([exercise], picks({ [exercise.id]: 'accept' })).size).toBe(0);
        const needsConflict = unit('EXERCISE', 8, 'NEW', { dependsOn: [conflict.id, again.id, deleted.id] });
        expect(lockedDependencies([conflict, again, deleted, needsConflict], picks({ [needsConflict.id]: 'accept' })).size).toBe(0);
        // A pick on a locked unit is not sent.
        expect(explicitChoices(all, picks({ [exercise.id]: 'accept', [material.id]: 'skip' }), locked, 'selected')).toEqual([{ unitId: exercise.id, choice: 'ACCEPT' }]);
    });

    it('finds a skipped unit that an accepted one needs, and in safe mode one that «Принять всё безопасное» would take', () => {
        // A unit the accepted one locks is taken with it, whatever 'skip' it still carries from before it was locked.
        expect(skippedDependency(all, picks({ [exercise.id]: 'accept', [material.id]: 'skip' }), 'selected')).toBeNull();
        expect(skippedDependency(all, picks({ [exercise.id]: 'accept', [material.id]: 'skip', [objective.id]: 'skip' }), 'safe')).toBeNull();
        expect(skippedDependency(all, picks({ [material.id]: 'skip', [objective.id]: 'skip' }), 'safe')).toEqual({ needed: material, by: exercise });
        // «Принять всё безопасное» does not send a pick on a conflict, so an accepted conflict takes nothing with it.
        const needsMaterial = unit('MATERIAL', 9, 'CONFLICT', { dependsOn: [material.id] });
        expect(skippedDependency([material, needsMaterial], picks({ [needsMaterial.id]: 'take-author', [material.id]: 'skip' }), 'safe')).toBeNull();
        expect(skippedDependency(all, picks({ [material.id]: 'skip' }), 'selected')).toBeNull();
        expect(skippedDependency(all, picks({ [material.id]: 'skip' }), 'safe')).toEqual({ needed: material, by: exercise });
        expect(skippedDependency(all, picks({ [material.id]: 'skip', [exercise.id]: 'skip' }), 'safe')).toBeNull();
        expect(skippedDependency(all, picks({}), 'safe')).toBeNull();
    });

    it('titles the note of a unit by the first material it needs that is loaded', () => {
        const byId = new Map(all.map(candidate => [candidate.id, candidate] as const));
        expect(dependencyTitle(exercise, byId)).toBe(material.title);
        expect(dependencyTitle(material, byId)).toBeNull();
        expect(dependencyTitle(exercise, new Map())).toBeNull();
    });

    it('puts the conflicts and the units changed again on one tab, and counts the tabs from the summary', () => {
        expect(all.map(candidate => tabOf(candidate))).toEqual(['new', 'new', 'new', 'fix', 'conflict', 'deleted', 'conflict']);
        expect(tabCounts({ newMaterials: 2, newExercises: 3, fixes: 4, conflicts: 5, deletedByAuthor: 6, askAgain: 7 })).toEqual({ new: 5, fix: 4, conflict: 12, deleted: 6 });
        expect([lastRankOf('new'), lastRankOf('fix'), lastRankOf('conflict'), lastRankOf('deleted')]).toEqual([0, 1, 4, 3]);
        expect(keyOf(1)).toHaveLength(36);
    });
});
