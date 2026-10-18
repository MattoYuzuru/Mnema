import { TestBed } from '@angular/core/testing';

import { PathHintCardComponent } from './path-hint-card.component';

describe('PathHintCardComponent', () => {
    const render = (inputs: Record<string, unknown> = {}) => {
        const fixture = TestBed.createComponent(PathHintCardComponent);
        fixture.componentRef.setInput('title', 'Сохраните заметку «На потом»');
        fixture.componentRef.setInput('text', 'Сохраните сюда мысль или ссылку — разберёте её потом.');
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        const acknowledged = vi.fn();
        fixture.componentInstance.acknowledged.subscribe(acknowledged);
        fixture.detectChanges();
        return { fixture, root: fixture.nativeElement as HTMLElement, acknowledged };
    };

    it('is a non-modal dialog named by its heading and described by its sentence, with «Понятно»', () => {
        const { root } = render();
        const dialog = root.querySelector('[role="dialog"]')!;
        expect(dialog.getAttribute('aria-modal')).toBe('false');
        const heading = dialog.querySelector('h2')!;
        expect(dialog.getAttribute('aria-labelledby')).toBe(heading.id);
        expect(heading.textContent).toBe('Сохраните заметку «На потом»');
        expect(root.querySelector(`#${dialog.getAttribute('aria-describedby')}`)?.textContent).toBe('Сохраните сюда мысль или ссылку — разберёте её потом.');
        expect(root.querySelector('button')?.textContent).toBe('Понятно');
        // Not a native modal: the page behind stays live.
        expect(root.querySelector('dialog')).toBeNull();
    });

    it('takes the focus on the heading, which is not a tab stop', () => {
        const { fixture, root } = render();
        document.body.append(root);
        fixture.componentInstance.focusHeading();
        expect(document.activeElement).toBe(root.querySelector('h2'));
        expect(root.querySelector('h2')?.getAttribute('tabindex')).toBe('-1');
        root.remove();
    });

    it('says «Понятно» through `acknowledged`', () => {
        const { root, acknowledged } = render();
        (root.querySelector('button') as HTMLButtonElement).click();
        expect(acknowledged).toHaveBeenCalledTimes(1);
    });

    it('floats at the place the host measured, with the side the arrow points from, and is in the flow otherwise', () => {
        const floating = render({ floating: true, at: { left: 40, top: 120 }, side: 'bottom' });
        expect(floating.root.classList.contains('floating')).toBe(true);
        expect(floating.root.style.left).toBe('40px');
        expect(floating.root.style.top).toBe('120px');
        expect(floating.root.getAttribute('data-side')).toBe('bottom');
        expect(floating.root.style.opacity).toBe('');
        // Before the host has measured, the card is laid out but not seen (transparent, not hidden: a hidden element cannot take the focus).
        expect(render({ floating: true }).root.style.opacity).toBe('0');
        const inline = render();
        expect(inline.root.classList.contains('floating')).toBe(false);
        expect(inline.root.getAttribute('data-side')).toBeNull();
    });
});
