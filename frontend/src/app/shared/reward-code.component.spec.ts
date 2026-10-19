import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { RewardCodeComponent } from './reward-code.component';

@Component({ imports: [RewardCodeComponent], template: '<app-reward-code [code]="code()" [tone]="tone()" />' })
class HostComponent {
    readonly code = signal('K7PM-W2QX-H9RT');
    readonly tone = signal<'paper' | 'night'>('paper');
}

describe('RewardCodeComponent (the personal code of the reward)', () => {
    const render = async () => {
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        const root = fixture.nativeElement as HTMLElement;
        return {
            fixture, root, host: fixture.componentInstance,
            field: root.querySelector<HTMLInputElement>('input')!, button: root.querySelector<HTMLButtonElement>('button')!,
            status: root.querySelector<HTMLElement>('[role="status"]')!
        };
    };
    const writeText = (impl: () => Promise<void>): ReturnType<typeof vi.fn> => {
        const spy = vi.fn(impl);
        Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: spy } });
        return spy;
    };
    afterEach(() => Reflect.deleteProperty(navigator, 'clipboard'));

    it('is a read-only monospace field with the code, named by a visible label', async () => {
        const { field, root } = await render();
        expect(field.value).toBe('K7PM-W2QX-H9RT');
        expect(field.readOnly).toBe(true);
        const label = root.querySelector<HTMLLabelElement>('label')!;
        expect(label.textContent).toBe('Ваш промокод');
        expect(label.htmlFor).toBe(field.id);
        expect(field.getAttribute('spellcheck')).toBe('false');
        expect(field.getAttribute('autocomplete')).toBe('off');
    });

    it('selects the whole code when it is focused or pressed, so it can be copied by hand', async () => {
        const { field } = await render();
        field.focus();
        field.dispatchEvent(new FocusEvent('focus'));
        expect([field.selectionStart, field.selectionEnd]).toEqual([0, 14]);
        field.setSelectionRange(2, 2);
        field.click();
        expect([field.selectionStart, field.selectionEnd]).toEqual([0, 14]);
    });

    it('copies the code on the learner\'s press and says so in a status that is already on the page', async () => {
        const { button, status } = await render();
        expect(status.textContent).toBe('');
        const copy = writeText(() => Promise.resolve());
        button.click();
        await vi.waitFor(() => expect(status.textContent).toBe('Промокод скопирован.'));
        expect(copy).toHaveBeenCalledExactlyOnceWith('K7PM-W2QX-H9RT');
    });

    it('leaves the code selected and says to copy it by hand when the browser refuses', async () => {
        const { button, status, field } = await render();
        writeText(() => Promise.reject(new Error('denied')));
        button.click();
        await vi.waitFor(() => expect(status.textContent).toBe('Браузер не дал скопировать сам. Промокод выделен — скопируйте его.'));
        expect(document.activeElement).toBe(field);
        expect([field.selectionStart, field.selectionEnd]).toEqual([0, 14]);
    });

    it('does the same when there is no clipboard at all', async () => {
        const { button, status } = await render();
        button.click();
        await vi.waitFor(() => expect(status.textContent).toContain('скопируйте его'));
    });

    it('follows a new code and wears the night tone only when asked', async () => {
        const { fixture, host, field, root } = await render();
        host.code.set('AAAA-BBBB');
        host.tone.set('night');
        fixture.detectChanges();
        expect(field.value).toBe('AAAA-BBBB');
        expect(root.querySelector('app-reward-code')!.classList.contains('night')).toBe(true);
    });
});
