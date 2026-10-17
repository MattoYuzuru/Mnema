import {
    Box,
    CARD_GAP,
    CUTOUT_PADDING,
    cutoutBox,
    isBottomSheet,
    makeRoomDelta,
    placeCard,
    revealDelta,
    sameBox,
    scrimPath
} from './tour-geometry';

const VIEWPORT = { width: 1000, height: 700 };
const CARD = { width: 384, height: 240 };

describe('tour geometry', () => {
    describe('cutoutBox', () => {
        it('grows the target rectangle by the padding on every side', () => {
            expect(cutoutBox({ x: 100, y: 50, width: 200, height: 40 })).toEqual({ x: 92, y: 42, width: 216, height: 56 });
            expect(cutoutBox({ x: 0, y: 0, width: 10, height: 10 }, 4)).toEqual({ x: -4, y: -4, width: 18, height: 18 });
            expect(CUTOUT_PADDING).toBe(8);
        });
    });

    describe('isBottomSheet', () => {
        it('is a sheet at 37.5rem and below, measured in the root font size', () => {
            expect(isBottomSheet(600)).toBe(true);
            expect(isBottomSheet(601)).toBe(false);
            expect(isBottomSheet(320)).toBe(true);
            expect(isBottomSheet(700, 20)).toBe(true);
            expect(isBottomSheet(751, 20)).toBe(false);
        });
    });

    describe('scrimPath', () => {
        it('is the whole viewport when there is no hole', () => {
            expect(scrimPath({ width: 320, height: 200 }, null, 2)).toBe('M0 0H320V200H0Z');
        });

        it('subtracts a rounded hole by the even-odd rule: the second subpath stays inside the first', () => {
            const path = scrimPath({ width: 320, height: 200 }, { x: 20, y: 30, width: 100, height: 40 }, 2);
            const [outer, hole] = path.split('Z').filter(Boolean);
            expect(outer).toBe('M0 0H320V200H0');
            expect(hole.startsWith('M22 30H118A2 2 0 0 1 120 32V68')).toBe(true);
            expect(path.endsWith('Z')).toBe(true);
            // Both subpaths are closed, so the hole is a separate loop.
            expect(path.match(/Z/gu)).toHaveLength(2);
        });

        it('never rounds a corner past half of the smaller side', () => {
            const path = scrimPath({ width: 100, height: 100 }, { x: 10, y: 10, width: 6, height: 40 }, 20);
            expect(path).toContain('A3 3 0 0 1');
            expect(path).not.toContain('A20 20');
        });
    });

    describe('placeCard', () => {
        const hole: Box = { x: 100, y: 100, width: 200, height: 60 };

        it('prefers the requested side and aligns the card with the start of the cut-out', () => {
            expect(placeCard(hole, CARD, VIEWPORT, 'bottom')).toEqual({ left: 100, top: 176, side: 'bottom' });
            expect(placeCard({ ...hole, y: 400 }, CARD, VIEWPORT, 'top')).toEqual({ left: 100, top: 144, side: 'top' });
        });

        it('defaults to below the target', () => {
            expect(placeCard(hole, CARD, VIEWPORT).side).toBe('bottom');
        });

        it('falls back to the opposite side when the preferred one does not fit', () => {
            const low: Box = { x: 100, y: 500, width: 200, height: 60 };
            expect(placeCard(low, CARD, VIEWPORT, 'bottom')).toEqual({ left: 100, top: 500 - CARD_GAP - CARD.height, side: 'top' });
        });

        it('then to the other axis when neither above nor below fits', () => {
            const tall: Box = { x: 100, y: 20, width: 100, height: 660 };
            const placed = placeCard(tall, CARD, VIEWPORT, 'bottom');
            expect(placed.side).toBe('end');
            expect(placed.left).toBe(100 + 100 + CARD_GAP);
        });

        it('slides the card back into the window when it would overhang the edge', () => {
            const right: Box = { x: 900, y: 100, width: 80, height: 40 };
            const placed = placeCard(right, CARD, VIEWPORT, 'bottom');
            expect(placed.side).toBe('bottom');
            expect(placed.left).toBe(VIEWPORT.width - CARD.width - CARD_GAP);
        });

        it('takes the side with the most room and stays inside the window when nothing fits whole', () => {
            const wide: Box = { x: 8, y: 150, width: 984, height: 400 };
            const placed = placeCard(wide, CARD, VIEWPORT, 'bottom');
            expect(placed.side).toBe('overlay');
            expect(placed.left).toBeGreaterThanOrEqual(CARD_GAP);
            expect(placed.top).toBeGreaterThanOrEqual(CARD_GAP);
            expect(placed.top + CARD.height).toBeLessThanOrEqual(VIEWPORT.height - CARD_GAP);
        });

        it('mirrors start and end for a right-to-left page', () => {
            const middle: Box = { x: 450, y: 300, width: 100, height: 100 };
            expect(placeCard(middle, CARD, VIEWPORT, 'start')).toEqual({ left: 450 - CARD_GAP - CARD.width, top: 300, side: 'start' });
            expect(placeCard(middle, CARD, VIEWPORT, 'start', CARD_GAP, true)).toEqual({ left: 550 + CARD_GAP, top: 300, side: 'start' });
            expect(placeCard(middle, CARD, VIEWPORT, 'end')).toEqual({ left: 550 + CARD_GAP, top: 300, side: 'end' });
        });
    });

    describe('revealDelta', () => {
        it('does nothing for a target inside the free band', () => {
            expect(revealDelta({ x: 0, y: 100, width: 50, height: 50 }, 700, 0)).toBe(0);
        });

        it('scrolls up (negative) for a target above the window and down for one below', () => {
            expect(revealDelta({ x: 0, y: -200, width: 50, height: 50 }, 700, 0)).toBe(-216);
            expect(revealDelta({ x: 0, y: 650, width: 50, height: 100 }, 700, 0)).toBe(750 - (700 - CARD_GAP));
        });

        it('keeps the target above the bottom sheet', () => {
            // A 300px sheet leaves 700 - 300 - 16 = 384 for the target's bottom edge.
            expect(revealDelta({ x: 0, y: 400, width: 50, height: 60 }, 700, 300)).toBe(460 - 384);
        });

        it('aligns the top edge of a target taller than the free band', () => {
            expect(revealDelta({ x: 0, y: 300, width: 50, height: 900 }, 700, 0)).toBe(300 - CARD_GAP);
        });
    });

    describe('makeRoomDelta', () => {
        it('puts the target near the top so the card fits below it', () => {
            // stack: 8 + 60 + 8 + 16 + 240 = 332; the target belongs at y = 16 + 8 = 24.
            expect(makeRoomDelta({ x: 0, y: 300, width: 100, height: 60 }, CARD, 700)).toBe(276);
        });

        it('puts the target low so the card fits above it when the card prefers the top', () => {
            expect(makeRoomDelta({ x: 0, y: 100, width: 100, height: 60 }, CARD, 700, 'top')).toBe(100 - (16 + 240 + 16 + 8));
        });

        it('gives up when the target and the card cannot share the window', () => {
            expect(makeRoomDelta({ x: 0, y: 100, width: 100, height: 500 }, CARD, 700)).toBe(0);
        });
    });

    it('compares boxes by value, null included', () => {
        expect(sameBox({ x: 1, y: 2, width: 3, height: 4 }, { x: 1, y: 2, width: 3, height: 4 })).toBe(true);
        expect(sameBox({ x: 1, y: 2, width: 3, height: 4 }, { x: 1, y: 2, width: 3, height: 5 })).toBe(false);
        expect(sameBox(null, null)).toBe(true);
        expect(sameBox(null, { x: 0, y: 0, width: 0, height: 0 })).toBe(false);
    });
});
