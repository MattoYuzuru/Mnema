import { EDGE, placeNear, viewport } from './place-near';

const view = { width: 1000, height: 800 };
const size = { width: 400, height: 300 };

describe('placing a box next to the selection', () => {
    it('sits below the anchor when the whole box fits, with the rest of the screen as its limit', () => {
        const placed = placeNear({ top: 100, bottom: 120, left: 300, right: 400 }, size, view);
        expect(placed).toEqual({ top: '128px', bottom: 'auto', left: '300px', width: '400px', maxHeight: 664 });
    });

    it('goes above when it does not fit below but fits above', () => {
        const placed = placeNear({ top: 600, bottom: 620, left: 300, right: 400 }, size, view);
        expect(placed).toMatchObject({ top: 'auto', bottom: '208px', maxHeight: 584 });
    });

    it('takes the side with more room, and scrolls inside, when neither holds it whole but one is nearly enough', () => {
        const placed = placeNear({ top: 250, bottom: 270, left: 300, right: 400 }, { width: 400, height: 500 }, view);
        expect(placed.top).toBe('278px');
        expect(placed.maxHeight).toBe(514);
        const above = placeNear({ top: 450, bottom: 600, left: 300, right: 400 }, { width: 400, height: 500 }, view);
        expect(above).toMatchObject({ top: 'auto', maxHeight: 434 });
    });

    it('is at the bottom edge when the screen is too short for either side', () => {
        const placed = placeNear({ top: 100, bottom: 700, left: 300, right: 400 }, size, view);
        expect(placed).toMatchObject({ top: 'auto', bottom: `${EDGE}px`, maxHeight: 784 });
    });

    it('clamps the left edge and the width to the screen, and centres a box that has no anchor', () => {
        expect(placeNear({ top: 100, bottom: 120, left: 950, right: 990 }, size, view).left).toBe('592px');
        expect(placeNear({ top: 100, bottom: 120, left: -20, right: 10 }, size, view).left).toBe('8px');
        expect(placeNear({ top: 100, bottom: 120, left: 0, right: 10 }, size, { width: 300, height: 800 }).width).toBe('284px');
        expect(placeNear(null, size, view)).toMatchObject({ top: '20vh', left: '300px' });
    });

    it('knows the viewport of the window', () => {
        expect(viewport()).toEqual({ width: window.innerWidth, height: window.innerHeight });
    });
});
