/** Where the selection ends on screen (viewport pixels); a floating box sits next to it. */
export interface AnchorRect { readonly top: number; readonly bottom: number; readonly left: number; readonly right: number; }

/** The style of a fixed box placed next to an anchor: a side, a left edge, a width and the height it may take before it scrolls. */
export interface Placement {
    readonly top: string;
    readonly bottom: string;
    readonly left: string;
    readonly width: string;
    /** Pixels left on the chosen side: the box scrolls inside instead of running off the screen. */
    readonly maxHeight: number;
}

export const EDGE = 8;

/** The viewport, or a plain desktop size where there is none. */
export function viewport(): { readonly width: number; readonly height: number } {
    return typeof window === 'undefined' ? { width: 1024, height: 768 } : { width: window.innerWidth, height: window.innerHeight };
}

/**
 * Places a box of `size` next to `anchor`, inside `view`: below it when the whole box fits there, else above, else on the side with
 * more room (the box then scrolls inside, `maxHeight`), and on a screen too short for either, at the bottom edge over the text. The left
 * edge and the width are clamped to the screen. `gap` is the space between the anchor and the box.
 */
export function placeNear(anchor: AnchorRect | null, size: { readonly width: number; readonly height: number },
                          view: { readonly width: number; readonly height: number }, gap = EDGE): Placement {
    const width = Math.min(size.width, view.width - 2 * EDGE);
    if (anchor === null) {
        return { top: '20vh', bottom: 'auto', left: `${Math.max(EDGE, (view.width - width) / 2)}px`, width: `${width}px`, maxHeight: view.height - 2 * EDGE };
    }
    const left = `${Math.min(Math.max(anchor.left, EDGE), Math.max(EDGE, view.width - width - EDGE))}px`;
    const below = view.height - (anchor.bottom + gap) - EDGE;
    const above = anchor.top - gap - EDGE;
    const atBottom = (): Placement => ({ top: 'auto', bottom: `${EDGE}px`, left, width: `${width}px`, maxHeight: view.height - 2 * EDGE });
    if (below >= size.height) return { top: `${Math.max(EDGE, anchor.bottom + gap)}px`, bottom: 'auto', left, width: `${width}px`, maxHeight: below };
    if (above >= size.height) return { top: 'auto', bottom: `${view.height - anchor.top + gap}px`, left, width: `${width}px`, maxHeight: above };
    const roomy = Math.max(below, above);
    if (roomy < size.height * 0.6) return atBottom();
    return below >= above
        ? { top: `${Math.max(EDGE, anchor.bottom + gap)}px`, bottom: 'auto', left, width: `${width}px`, maxHeight: below }
        : { top: 'auto', bottom: `${view.height - anchor.top + gap}px`, left, width: `${width}px`, maxHeight: above };
}
