import { inertOutside } from './tour-inert';

describe('inertOutside', () => {
    let root: HTMLElement;

    beforeEach(() => {
        root = document.createElement('div');
        root.innerHTML = `
          <header id="masthead"></header>
          <main id="main"><section id="a"></section><section id="wrap"><p id="before"></p><button id="target"></button><p id="after"></p></section></main>
          <footer id="footer"></footer>
          <app-tour-host id="host"><dialog id="card"></dialog></app-tour-host>
          <app-toast-region id="toasts"></app-toast-region>`;
        document.body.append(root);
    });
    afterEach(() => root.remove());

    const inert = (): string[] => [...root.querySelectorAll('[inert]')].map(element => element.id).sort();

    it('makes every sibling along the path to the target inert and leaves the target and the kept surfaces live', () => {
        inertOutside(root.querySelector('#target')!, [root.querySelector('#host'), root.querySelector('#toasts')]);
        expect(inert()).toEqual(['a', 'after', 'before', 'footer', 'masthead']);
    });

    it('does not mark the path itself or a surface that holds a kept element', () => {
        inertOutside(root.querySelector('#target')!, [root.querySelector('#card')]);
        // The host holds the card: it stays live, its own siblings do not.
        expect(root.querySelector('#host')!.hasAttribute('inert')).toBe(false);
        expect(root.querySelector('#main')!.hasAttribute('inert')).toBe(false);
        expect(root.querySelector('#target')!.hasAttribute('inert')).toBe(false);
        expect(root.querySelector('#toasts')!.hasAttribute('inert')).toBe(true);
    });

    it('undoes exactly what it marked and leaves an element that was already inert as it was', () => {
        root.querySelector('#footer')!.setAttribute('inert', '');
        const restore = inertOutside(root.querySelector('#target')!, [root.querySelector('#host')]);
        expect(inert()).toContain('masthead');
        restore();
        expect(inert()).toEqual(['footer']);
    });

    it('accepts a missing kept element', () => {
        const restore = inertOutside(root.querySelector('#target')!, [null]);
        expect(inert()).toContain('host');
        restore();
        expect(inert()).toEqual([]);
    });

    describe('open popovers', () => {
        const opened = (...elements: HTMLElement[]): void => {
            vi.spyOn(document, 'querySelectorAll').mockImplementation(((selector: string) => selector === ':popover-open' ? elements : root.querySelectorAll(selector)) as typeof document.querySelectorAll);
        };

        it('closes a popover that is not on the way to the target, so it does not stay open and inert', () => {
            const menu = root.querySelector<HTMLElement>('#footer')!;
            menu.hidePopover = vi.fn();
            opened(menu);
            inertOutside(root.querySelector('#target')!, [root.querySelector('#host')]);
            expect(menu.hidePopover).toHaveBeenCalledTimes(1);
        });

        it('leaves a popover that holds the target or a kept surface, such as the toast region', () => {
            const holder = root.querySelector<HTMLElement>('#wrap')!;
            const toasts = root.querySelector<HTMLElement>('#toasts')!;
            holder.hidePopover = vi.fn();
            toasts.hidePopover = vi.fn();
            opened(holder, toasts);
            inertOutside(root.querySelector('#target')!, [root.querySelector('#host'), toasts]);
            expect(holder.hidePopover).not.toHaveBeenCalled();
            expect(toasts.hidePopover).not.toHaveBeenCalled();
        });

        it('does nothing where the browser does not know the selector (jsdom throws for it)', () => {
            expect(() => inertOutside(root.querySelector('#target')!, [])).not.toThrow();
        });
    });
});
