import { Directive, InjectionToken, Signal, TemplateRef, computed, inject, input } from '@angular/core';

/** The slot a host draws before or after a top-level block (`placement`): the strip under a rewritten range, a diff, media actions. */
export interface BlockSlotContext {
    readonly $implicit: string;
    readonly placement: 'before' | 'after';
}

/**
 * What a host (the Workshop) draws around the top-level blocks of a document: marks on some of them, some left out (replaced by a
 * slot), and a template drawn before or after others. Plain data, so the renderer stays a renderer; Browse and Study pass none.
 */
export interface BlockOverlay {
    /** Blocks being rewritten: `aria-busy="true"` and the dashed frame (the text stays readable). */
    readonly busy: ReadonlySet<string>;
    /** Blocks to highlight when the browser has no CSS Custom Highlight API. */
    readonly marked: ReadonlySet<string>;
    /** Blocks not drawn (a diff takes their place). */
    readonly hidden: ReadonlySet<string>;
    readonly before: ReadonlySet<string>;
    readonly after: ReadonlySet<string>;
    readonly template: TemplateRef<BlockSlotContext>;
}

/** What the directive needs from the renderer that hosts it; the token breaks the import cycle between the two. */
export interface NativeBlockHost {
    readonly exposeNodeIds: Signal<boolean>;
    readonly overlay: Signal<BlockOverlay | null>;
}

export const NATIVE_BLOCK_HOST = new InjectionToken<NativeBlockHost>('NATIVE_BLOCK_HOST');

/**
 * On the root element of a top-level block: `data-node-id` (only when the host asks for it with `exposeNodeIds`, so Browse and Study
 * never carry node ids) and the Workshop's marks. Blocks below the top level pass `null` and get nothing.
 */
@Directive({
    selector: '[appTopBlock]',
    host: {
        '[attr.data-node-id]': 'shown()?.id ?? null',
        '[attr.aria-busy]': 'shown()?.busy ? "true" : null',
        '[class.is-rewriting]': 'shown()?.busy === true',
        '[class.is-target]': 'shown()?.marked === true'
    }
})
export class TopBlockDirective {
    readonly appTopBlock = input<string | null>(null);
    private readonly host = inject(NATIVE_BLOCK_HOST);
    protected readonly shown = computed(() => {
        const id = this.appTopBlock();
        if (id === null || !this.host.exposeNodeIds()) return null;
        const overlay = this.host.overlay();
        return { id, busy: overlay?.busy.has(id) ?? false, marked: overlay?.marked.has(id) ?? false };
    });
}
