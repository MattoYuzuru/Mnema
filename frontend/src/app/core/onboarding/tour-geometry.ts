import { TourPlacement } from './tour.models';

/** Pure geometry of the spotlight. jsdom has no layout, so every rule here is tested with numbers; the browser harness checks the rest. */

export interface Box {
    readonly x: number;
    readonly y: number;
    readonly width: number;
    readonly height: number;
}

export interface Size {
    readonly width: number;
    readonly height: number;
}

export interface Point {
    readonly left: number;
    readonly top: number;
}

/** Room around the target inside the cut-out, in px. */
export const CUTOUT_PADDING = 8;
/** Space between the cut-out and the card, and between the card and the viewport edge, in px. */
export const CARD_GAP = 16;
/** At this width, in rem, and below the card is a bottom sheet (the layout's phone breakpoint). */
export const SHEET_MAX_WIDTH_REM = 37.5;

export type CardSide = 'top' | 'bottom' | 'start' | 'end';

export interface CardPlacement extends Point {
    /** The side of the cut-out the card sits on; `overlay` when no side has room and the card is clamped into the viewport. */
    readonly side: CardSide | 'overlay';
}

/** The cut-out: the target's rectangle grown by `padding` on every side. */
export function cutoutBox(rect: Box, padding: number = CUTOUT_PADDING): Box {
    return { x: rect.x - padding, y: rect.y - padding, width: rect.width + padding * 2, height: rect.height + padding * 2 };
}

/** Whether the card is a bottom sheet: the viewport is 37.5 rem wide or narrower, with `rem` the root font size in px. */
export function isBottomSheet(viewportWidth: number, rem = 16): boolean {
    return viewportWidth <= SHEET_MAX_WIDTH_REM * rem;
}

function rounded(box: Box, radius: number): string {
    const r = Math.max(0, Math.min(radius, box.width / 2, box.height / 2));
    const { x, y, width: w, height: h } = box;
    return `M${x + r} ${y}H${x + w - r}A${r} ${r} 0 0 1 ${x + w} ${y + r}V${y + h - r}A${r} ${r} 0 0 1 ${x + w - r} ${y + h}H${x + r}`
        + `A${r} ${r} 0 0 1 ${x} ${y + h - r}V${y + r}A${r} ${r} 0 0 1 ${x + r} ${y}Z`;
}

/**
 * The veil as one path: the whole viewport with the cut-out subtracted by the even-odd rule. Painted with `fill-rule="evenodd"`, the
 * hole is not painted and so not hit-testable: a click on the target reaches it, a click on the veil does nothing. Without a hole
 * (the anchor is not on the screen yet) the veil is the plain viewport.
 */
export function scrimPath(viewport: Size, hole: Box | null, radius: number): string {
    const outer = `M0 0H${viewport.width}V${viewport.height}H0Z`;
    return hole === null ? outer : `${outer}${rounded(hole, radius)}`;
}

function fits(box: Box, viewport: Size, margin: number): boolean {
    return box.x >= margin && box.y >= margin && box.x + box.width <= viewport.width - margin && box.y + box.height <= viewport.height - margin;
}

function clamp(value: number, low: number, high: number): number {
    return Math.max(low, Math.min(value, Math.max(low, high)));
}

/** The sides in the order they are tried: the preferred, its opposite, then the other axis. */
function sideOrder(preferred: CardSide): readonly CardSide[] {
    const opposite: Record<CardSide, CardSide> = { top: 'bottom', bottom: 'top', start: 'end', end: 'start' };
    const crossing: readonly CardSide[] = preferred === 'top' || preferred === 'bottom' ? ['end', 'start'] : ['bottom', 'top'];
    return [preferred, opposite[preferred], ...crossing];
}

/**
 * Where the card goes. The preferred side of the cut-out is tried first, then the opposite one, then the other axis; the card is
 * aligned with the cut-out's start edge and slid back into the viewport when it would overhang. When no side has room for the whole
 * card without covering the cut-out, it goes on the side with the most free space and is clamped into the viewport (`overlay`).
 * `rtl` mirrors `start`/`end`.
 */
export function placeCard(hole: Box, card: Size, viewport: Size, preferred: TourPlacement = 'bottom', gap: number = CARD_GAP, rtl = false): CardPlacement {
    const left = (value: number): number => clamp(value, gap, viewport.width - card.width - gap);
    const top = (value: number): number => clamp(value, gap, viewport.height - card.height - gap);
    const physical = (side: CardSide): 'top' | 'bottom' | 'left' | 'right' =>
        side === 'start' ? (rtl ? 'right' : 'left') : side === 'end' ? (rtl ? 'left' : 'right') : side;
    const at = (side: CardSide): Point => {
        switch (physical(side)) {
            case 'bottom': return { left: left(hole.x), top: hole.y + hole.height + gap };
            case 'top': return { left: left(hole.x), top: hole.y - gap - card.height };
            case 'right': return { left: hole.x + hole.width + gap, top: top(hole.y) };
            case 'left': return { left: hole.x - gap - card.width, top: top(hole.y) };
        }
    };
    const order = sideOrder(preferred);
    for (const side of order) {
        const point = at(side);
        if (fits({ x: point.left, y: point.top, width: card.width, height: card.height }, viewport, gap)) return { ...point, side };
    }
    // Nothing fits whole: take the side with the most free space and keep the card inside the viewport.
    const room: Record<CardSide, number> = {
        bottom: viewport.height - (hole.y + hole.height), top: hole.y,
        start: rtl ? viewport.width - (hole.x + hole.width) : hole.x, end: rtl ? hole.x : viewport.width - (hole.x + hole.width)
    };
    const best = order.reduce((winner, side) => (room[side] > room[winner] ? side : winner), order[0]);
    const point = at(best);
    return { left: left(point.left), top: top(point.top), side: 'overlay' };
}

/**
 * How far the page must scroll (positive: content moves up) for the target to sit inside the free band of the viewport: below the
 * top margin and above `reservedBottom` (the bottom sheet) plus the margin. A target taller than the band is aligned to its top.
 */
export function revealDelta(target: Box, viewportHeight: number, reservedBottom: number, margin: number = CARD_GAP): number {
    const top = margin;
    const bottom = viewportHeight - reservedBottom - margin;
    if (target.y < top) return target.y - top;
    if (target.y + target.height > bottom) return Math.min(target.y + target.height - bottom, target.y - top);
    return 0;
}

/**
 * How far to scroll (positive: content moves up) so the target and the card fit one above the other: used when the card would
 * otherwise cover the target. For a card preferred above the target the target goes to the lower part of the window, otherwise to the
 * upper part. `0` when the two cannot fit the window together (the card then overlays the target as little as it can).
 */
export function makeRoomDelta(target: Box, card: Size, viewportHeight: number, preferred: TourPlacement = 'bottom', gap: number = CARD_GAP, padding: number = CUTOUT_PADDING): number {
    const stack = padding + target.height + padding + gap + card.height;
    if (stack + gap * 2 > viewportHeight) return 0;
    const desired = preferred === 'top' ? gap + card.height + gap + padding : gap + padding;
    return target.y - desired;
}

export function sameBox(a: Box | null, b: Box | null): boolean {
    if (a === null || b === null) return a === b;
    return a.x === b.x && a.y === b.y && a.width === b.width && a.height === b.height;
}
