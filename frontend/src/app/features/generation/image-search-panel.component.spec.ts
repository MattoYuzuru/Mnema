import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ImageSearchPanelComponent } from './image-search-panel.component';

describe('ImageSearchPanelComponent', () => {
    let fixture: ComponentFixture<ImageSearchPanelComponent>;
    let submitted: string[];
    let cancelled: number;
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const field = (): HTMLInputElement => root().querySelector<HTMLInputElement>('input')!;
    const button = (label: string): HTMLButtonElement => [...root().querySelectorAll('button')].find(held => held.textContent!.trim() === label)!;
    const type = async (text: string): Promise<void> => {
        field().value = text;
        field().dispatchEvent(new Event('input'));
        await fixture.whenStable();
    };
    const key = (init: KeyboardEventInit & { keyCode?: number }): KeyboardEvent => {
        const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
        if (init.keyCode !== undefined) Object.defineProperty(event, 'keyCode', { value: init.keyCode });
        field().dispatchEvent(event);
        return event;
    };

    async function create(inputs: Record<string, unknown> = {}): Promise<void> {
        TestBed.resetTestingModule();
        fixture = TestBed.createComponent(ImageSearchPanelComponent);
        submitted = [];
        cancelled = 0;
        fixture.componentInstance.submitted.subscribe(value => submitted.push(value));
        fixture.componentInstance.cancelled.subscribe(() => cancelled++);
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        window.document.body.appendChild(root());
        fixture.detectChanges();
        await fixture.whenStable();
    }
    afterEach(() => root().remove());

    it('is a group, not a dialog: a visible label, the description of the image as the placeholder, the cost in one quiet line', async () => {
        await create({ placeholder: 'лиса в снегу' });
        const group = root().querySelector('[role="group"]')!;
        expect(group.getAttribute('aria-label')).toBe('Поиск изображения');
        expect(root().querySelector('dialog, [role="dialog"], [popover]')).toBeNull();
        const label = root().querySelector('label')!;
        expect(label.textContent).toBe('Что искать');
        expect(label.getAttribute('for')).toBe(field().id);
        expect(field().placeholder).toBe('лиса в снегу');
        expect(field().maxLength).toBe(200);
        expect(root().querySelector('.panel-cost')!.textContent).toBe('1 кредит из ИИ-бюджета');
        expect(field().getAttribute('aria-describedby')).toBe(root().querySelector('.panel-cost')!.id);
        expect([...root().querySelectorAll('button')].map(held => held.textContent!.trim())).toEqual(['Искать', 'Отмена']);
    });

    it('puts focus in the field and starts with the previous query when asked again', async () => {
        await create({ query: 'лиса зимой' });
        expect(window.document.activeElement).toBe(field());
        expect(field().value).toBe('лиса зимой');
    });

    it('sends the trimmed query with the button and with Enter, and an empty one as an empty string', async () => {
        await create();
        await type('  лиса зимой ');
        button('Искать').click();
        const enter = key({ key: 'Enter' });
        expect(enter.defaultPrevented).toBe(true);
        await type('   ');
        button('Искать').click();
        expect(submitted).toEqual(['лиса зимой', 'лиса зимой', '']);
    });

    it('never sends on Enter while an IME composes, nor on Shift+Enter', async () => {
        await create();
        await type('きつね');
        field().dispatchEvent(new Event('compositionstart'));
        key({ key: 'Enter' });
        field().dispatchEvent(new Event('compositionend'));
        key({ key: 'Enter', isComposing: true });
        key({ key: 'Process', keyCode: 229 });
        key({ key: 'Enter', keyCode: 229 });
        key({ key: 'Enter', shiftKey: true });
        expect(submitted).toEqual([]);
        key({ key: 'Enter' });
        expect(submitted).toEqual(['きつね']);
    });

    it('closes with «Отмена» and Esc, but Esc during a composition only cancels the composition', async () => {
        await create();
        button('Отмена').click();
        expect(cancelled).toBe(1);
        field().dispatchEvent(new Event('compositionstart'));
        key({ key: 'Escape' });
        expect(cancelled).toBe(1);
        field().dispatchEvent(new Event('compositionend'));
        const escape = key({ key: 'Escape' });
        expect(escape.defaultPrevented).toBe(true);
        expect(cancelled).toBe(2);
    });

    it('while the search runs: says so in a status, makes the controls aria-disabled, keeps focus where it is and sends nothing', async () => {
        await create({ query: 'лиса' });
        const status = root().querySelector('[role="status"]')!;
        expect(status.textContent).toBe('');
        fixture.componentRef.setInput('pending', true);
        fixture.detectChanges();
        await fixture.whenStable();
        expect(status.textContent).toBe('Ищу похожие изображения…');
        expect(field().getAttribute('aria-disabled')).toBe('true');
        expect(field().readOnly).toBe(true);
        expect([...root().querySelectorAll('button')].every(held => held.getAttribute('aria-disabled') === 'true')).toBe(true);
        expect(root().querySelector('button[disabled], input[disabled]')).toBeNull();
        expect(window.document.activeElement).toBe(field());
        button('Искать').click();
        button('Отмена').click();
        key({ key: 'Enter' });
        expect(submitted).toEqual([]);
        expect(cancelled).toBe(0);
    });

    it('shows a refusal in a polite live region that is there before it speaks, and ties it to the field', async () => {
        await create();
        const error = root().querySelector<HTMLElement>('.panel-error')!;
        expect(error.getAttribute('aria-live')).toBe('polite');
        expect(error.textContent).toBe('');
        fixture.componentRef.setInput('error', 'Материал обновился.');
        fixture.detectChanges();
        expect(error.textContent).toBe('Материал обновился.');
        expect(field().getAttribute('aria-describedby')).toContain(error.id);
    });
});
