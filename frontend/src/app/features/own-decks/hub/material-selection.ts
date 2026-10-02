import { computed, signal } from '@angular/core';

import { DeletionSelection } from './deck-hub.models';

export type SelectAllState = 'checked' | 'mixed' | 'unchecked';

/**
 * Which materials of the deck are selected. Two shapes, like the server command: an explicit set of member keys, or
 * «everything in the deck» minus an exception set (a long deck is never fully loaded, so «all» cannot be a list of
 * keys). `total` is the deck's material count; the owner keeps it current. Pure signal state, no DOM.
 */
export class MaterialSelection {
    readonly total = signal(0);
    private readonly explicit = signal<ReadonlySet<string>>(new Set());
    private readonly excluded = signal<ReadonlySet<string>>(new Set());
    private readonly everything = signal(false);
    private anchor: string | null = null;

    /** True while the selection means «all materials of the deck» (minus the exceptions). */
    readonly allInDeck = this.everything.asReadonly();
    readonly count = computed(() => this.everything() ? Math.max(0, this.total() - this.excluded().size) : this.explicit().size);
    readonly empty = computed(() => this.count() === 0);
    /** Materials taken out of an «all in deck» selection. */
    readonly excludedCount = computed(() => this.excluded().size);

    /** The command's selection, or null while nothing is selected. */
    readonly selection = computed<DeletionSelection | null>(() => {
        if (this.count() === 0) return null;
        return this.everything()
            ? { allInDeck: true, except: [...this.excluded()] }
            : { itemIds: [...this.explicit()] };
    });

    isSelected(key: string): boolean {
        return this.everything() ? !this.excluded().has(key) : this.explicit().has(key);
    }

    /**
     * Toggles one material. With `range` the materials between the last toggled one and this one (in `order`, the
     * visible order) take the new state of this one, like a file list; when there is no usable anchor it is a plain toggle.
     */
    toggle(key: string, order: readonly string[], range = false): void {
        const next = !this.isSelected(key);
        const from = this.anchor === null ? -1 : order.indexOf(this.anchor);
        const to = order.indexOf(key);
        const keys = range && from >= 0 && to >= 0 ? order.slice(Math.min(from, to), Math.max(from, to) + 1) : [key];
        this.apply(keys, next);
        this.anchor = key;
    }

    /** State of the «Выбрать все» checkbox over the materials that are loaded now. */
    headerState(loaded: readonly string[]): SelectAllState {
        if (loaded.length === 0) return 'unchecked';
        const selected = loaded.filter(key => this.isSelected(key)).length;
        if (selected === 0) return 'unchecked';
        return selected === loaded.length ? 'checked' : 'mixed';
    }

    /** The header checkbox: selects every loaded material, or clears them when all are already selected. */
    toggleLoaded(loaded: readonly string[]): void {
        this.apply(loaded, this.headerState(loaded) !== 'checked');
        this.anchor = null;
    }

    selectAllInDeck(): void {
        this.everything.set(true);
        this.excluded.set(new Set());
        this.anchor = null;
    }

    /** Replaces the selection with exactly these materials (for example the ones a partial deletion left). */
    replace(keys: readonly string[]): void {
        this.everything.set(false);
        this.excluded.set(new Set());
        this.explicit.set(new Set(keys));
        this.anchor = null;
    }

    clear(): void { this.replace([]); }

    private apply(keys: readonly string[], selected: boolean): void {
        const target = this.everything() ? this.excluded : this.explicit;
        // In «all» mode the set holds the exceptions, so selecting a key removes it from the set.
        const add = this.everything() ? !selected : selected;
        const next = new Set(target());
        for (const key of keys) {
            if (add) next.add(key); else next.delete(key);
        }
        target.set(next);
    }
}
