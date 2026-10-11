/**
 * A spec-local stand-in for the Popover API, which jsdom lacks: `showPopover` and `hidePopover` keep an open flag and
 * queue a `toggle` event with `newState`, the way a browser does (asynchronously). It has no top layer, light dismiss or
 * focus restoration; specs that need those belong in the real-browser harness. Install in `beforeEach`, remove in
 * `afterEach`, so specs of components that probe `typeof showPopover` keep seeing the jsdom default.
 */
const opened = new WeakSet<Element>();

function queueToggle(element: Element, newState: 'open' | 'closed'): void {
    queueMicrotask(() => {
        const event = new Event('toggle');
        Object.defineProperty(event, 'newState', { value: newState });
        element.dispatchEvent(event);
    });
}

export function installPopoverShim(): void {
    Object.defineProperties(HTMLElement.prototype, {
        showPopover: {
            configurable: true,
            value(this: HTMLElement): void {
                if (opened.has(this)) throw new DOMException('already open', 'InvalidStateError');
                opened.add(this);
                this.setAttribute('data-popover-open', '');
                queueToggle(this, 'open');
            }
        },
        hidePopover: {
            configurable: true,
            value(this: HTMLElement): void {
                if (!opened.has(this)) throw new DOMException('not open', 'InvalidStateError');
                opened.delete(this);
                this.removeAttribute('data-popover-open');
                queueToggle(this, 'closed');
            }
        }
    });
}

export function removePopoverShim(): void {
    delete (HTMLElement.prototype as Partial<HTMLElement>).showPopover;
    delete (HTMLElement.prototype as Partial<HTMLElement>).hidePopover;
}

/** Whether the shim considers the popover open. */
export function popoverIsOpen(element: Element): boolean {
    return opened.has(element);
}
