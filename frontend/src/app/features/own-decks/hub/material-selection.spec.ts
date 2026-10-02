import { MaterialSelection } from './material-selection';

describe('MaterialSelection', () => {
    const keys = ['a', 'b', 'c', 'd', 'e'];
    let selection: MaterialSelection;

    beforeEach(() => {
        selection = new MaterialSelection();
        selection.total.set(50);
    });

    it('toggles single materials and reports the explicit command selection', () => {
        expect(selection.selection()).toBeNull();
        selection.toggle('b', keys);
        selection.toggle('d', keys);
        expect(selection.count()).toBe(2);
        expect(selection.selection()).toEqual({ itemIds: ['b', 'd'] });
        selection.toggle('b', keys);
        expect(selection.isSelected('b')).toBe(false);
        expect(selection.count()).toBe(1);
    });

    it('selects the range between the last toggled material and a shift-clicked one, in both directions', () => {
        selection.toggle('b', keys);
        selection.toggle('d', keys, true);
        expect([...keys].filter(key => selection.isSelected(key))).toEqual(['b', 'c', 'd']);
        selection.toggle('a', keys, true);
        expect(keys.filter(key => selection.isSelected(key))).toEqual(['a', 'b', 'c', 'd']);
        // Shift on a selected material applies the new (unselected) state to the whole range from the last toggled one.
        selection.toggle('c', keys, true);
        expect(keys.filter(key => selection.isSelected(key))).toEqual(['d']);
    });

    it('treats a shift-click without a usable anchor as a plain toggle', () => {
        selection.toggle('c', keys, true);
        expect(selection.selection()).toEqual({ itemIds: ['c'] });
        selection.toggle('e', ['x', 'y'], true);
        expect(selection.isSelected('e')).toBe(true);
        expect(selection.count()).toBe(2);
    });

    it('drives the header checkbox through unchecked, mixed and checked, and clears the loaded rows when all are selected', () => {
        expect(selection.headerState(keys)).toBe('unchecked');
        selection.toggle('a', keys);
        expect(selection.headerState(keys)).toBe('mixed');
        selection.toggleLoaded(keys);
        expect(selection.headerState(keys)).toBe('checked');
        expect(selection.count()).toBe(5);
        selection.toggleLoaded(keys);
        expect(selection.headerState(keys)).toBe('unchecked');
        expect(selection.count()).toBe(0);
        expect(selection.headerState([])).toBe('unchecked');
    });

    it('escalates to every material of the deck and tracks the exceptions', () => {
        selection.toggleLoaded(keys);
        selection.selectAllInDeck();
        expect(selection.allInDeck()).toBe(true);
        expect(selection.count()).toBe(50);
        expect(selection.isSelected('zzz')).toBe(true);
        expect(selection.selection()).toEqual({ allInDeck: true, except: [] });
        selection.toggle('b', keys);
        expect(selection.isSelected('b')).toBe(false);
        expect(selection.count()).toBe(49);
        expect(selection.excludedCount()).toBe(1);
        expect(selection.selection()).toEqual({ allInDeck: true, except: ['b'] });
        expect(selection.headerState(keys)).toBe('mixed');
        // From «mixed» the header checkbox selects the loaded rows again; from «checked» it unselects only the loaded
        // rows and everything not loaded stays selected.
        selection.toggleLoaded(keys);
        expect(selection.count()).toBe(50);
        expect(selection.excludedCount()).toBe(0);
        selection.toggleLoaded(keys);
        expect(selection.count()).toBe(45);
        expect(selection.selection()).toEqual({ allInDeck: true, except: keys });
    });

    it('is empty once everything is excepted, and clear and replace reset every mode', () => {
        selection.total.set(2);
        selection.selectAllInDeck();
        selection.toggle('a', ['a', 'b']);
        selection.toggle('b', ['a', 'b']);
        expect(selection.count()).toBe(0);
        expect(selection.selection()).toBeNull();
        selection.selectAllInDeck();
        selection.replace(['x', 'y']);
        expect(selection.allInDeck()).toBe(false);
        expect(selection.selection()).toEqual({ itemIds: ['x', 'y'] });
        selection.clear();
        expect(selection.empty()).toBe(true);
    });
});
