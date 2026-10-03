import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AiPromptAsk, AiPromptWindowComponent } from './ai-prompt-window.component';

describe('AiPromptWindowComponent', () => {
    let fixture: ComponentFixture<AiPromptWindowComponent>;
    let asks: AiPromptAsk[];
    let aborts: number;
    let dismissals: boolean[];
    let showPopover: ReturnType<typeof vi.fn>;
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const field = (): HTMLTextAreaElement => root().querySelector('textarea')!;
    const named = (label: string): HTMLButtonElement => [...root().querySelectorAll('button')].find(button => button.textContent!.trim() === label)!;

    async function create(inputs: Record<string, unknown> = {}): Promise<void> {
        showPopover = vi.fn();
        Object.defineProperty(HTMLElement.prototype, 'showPopover', { value: showPopover, configurable: true, writable: true });
        TestBed.resetTestingModule();
        fixture = TestBed.createComponent(AiPromptWindowComponent);
        fixture.componentRef.setInput('mode', 'popover');
        fixture.componentRef.setInput('quote', 'Первый абзац.');
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        asks = []; aborts = 0; dismissals = [];
        fixture.componentInstance.ask.subscribe(ask => asks.push(ask));
        fixture.componentInstance.retract.subscribe(() => { aborts++; });
        fixture.componentInstance.dismissed.subscribe(restore => dismissals.push(restore));
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
    }
    const type = (value: string): void => { field().value = value; field().dispatchEvent(new Event('input')); fixture.detectChanges(); };
    const key = (init: KeyboardEventInit & { keyCode?: number }): KeyboardEvent => {
        const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
        if (init.keyCode !== undefined) Object.defineProperty(event, 'keyCode', { value: init.keyCode });
        field().dispatchEvent(event);
        return event;
    };

    afterEach(() => { delete (HTMLElement.prototype as { showPopover?: unknown }).showPopover; });

    describe('as a floating window', () => {
        it('is a non-modal dialog in the top layer, labelled «Попросить Мнему», and takes focus into its field', async () => {
            await create();
            const surface = root().querySelector<HTMLElement>('[popover]')!;
            expect(surface.getAttribute('popover')).toBe('manual');
            expect(surface.getAttribute('role')).toBe('dialog');
            expect(surface.getAttribute('aria-modal')).toBe('false');
            expect(root().querySelector('#' + surface.getAttribute('aria-labelledby'))?.textContent).toBe('Попросить Мнему');
            expect(showPopover).toHaveBeenCalled();
            expect(document.activeElement).toBe(field());
            expect(root().querySelector('.window-quote')?.textContent).toBe('Выделено: «Первый абзац.»');
            expect(root().querySelector('label')?.textContent).toBe('Что изменить?');
            expect(root().querySelector('label')?.getAttribute('for')).toBe(field().id);
            expect(root().querySelector('dialog')).toBeNull();
        });

        it('offers four presets, a field with enterkeyhint and no microphone', async () => {
            await create();
            expect([...root().querySelectorAll('.chip')].map(chip => chip.textContent!.trim())).toEqual(['Проще', 'Короче', 'Пример', 'Подробнее']);
            expect(field().getAttribute('enterkeyhint')).toBe('send');
            expect(field().getAttribute('maxlength')).toBe('2000');
            expect(root().querySelector('[aria-label*="икрофон"]')).toBeNull();
        });

        it('sends a preset at once, with what is typed as its instruction, and a sentence as a free request', async () => {
            await create();
            named('Короче').click();
            expect(asks).toEqual([{ preset: 'SHORTER', instruction: null }]);
            type('  без примеров  ');
            named('Пример').click();
            expect(asks[1]).toEqual({ preset: 'EXAMPLE', instruction: 'без примеров' });
            named('Отправить').click();
            expect(asks[2]).toEqual({ preset: null, instruction: 'без примеров' });
        });

        it('sends on Enter, never on Shift+Enter, and never while an IME composes or on the legacy keyCode 229', async () => {
            await create();
            type('проще');
            expect(key({ key: 'Enter', shiftKey: true }).defaultPrevented).toBe(false);
            expect(key({ key: 'Enter', isComposing: true }).defaultPrevented).toBe(false);
            expect(key({ key: 'Enter', keyCode: 229 }).defaultPrevented).toBe(false);
            expect(key({ key: 'a' }).defaultPrevented).toBe(false);
            expect(asks).toEqual([]);
            expect(key({ key: 'Enter' }).defaultPrevented).toBe(true);
            expect(asks).toEqual([{ preset: null, instruction: 'проще' }]);
        });

        it('sends nothing from an empty field, and the submit button says it is not ready (aria-disabled, still focusable)', async () => {
            await create();
            expect(named('Отправить').getAttribute('aria-disabled')).toBe('true');
            named('Отправить').click();
            key({ key: 'Enter' });
            type('   ');
            key({ key: 'Enter' });
            expect(asks).toEqual([]);
            type('да');
            expect(named('Отправить').getAttribute('aria-disabled')).toBeNull();
        });

        it('keeps the button pressable on an empty field while the budget does not fit, so the press can explain', async () => {
            await create({ limited: true });
            expect(named('Отправить').getAttribute('aria-disabled')).toBeNull();
            named('Отправить').click();
            expect(asks).toEqual([{ preset: null, instruction: null }]);
        });

        it('does not let Enter on a checkbox or range submit the form', async () => {
            await create();
            const form = root().querySelector('form')!;
            const box = document.createElement('input');
            box.type = 'checkbox';
            form.appendChild(box);
            const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
            box.dispatchEvent(event);
            expect(event.defaultPrevented).toBe(true);
        });

        it('waits while a request is on its way: read-only field, «Отправляю…», no second send, and «Отменить» takes it back', async () => {
            await create({ sending: true, instruction: 'проще' });
            expect(field().readOnly).toBe(true);
            expect(named('Отправляю…').getAttribute('aria-disabled')).toBe('true');
            named('Отправляю…').click();
            named('Проще').click();
            key({ key: 'Enter' });
            expect(asks).toEqual([]);
            named('Отменить').click();
            expect(aborts).toBe(1);
        });

        it('says the cost and why a request was refused, and tells the field about both', async () => {
            await create({ cost: '≈ 0,3 % лимита', error: 'Мнема ещё переписывает предыдущий фрагмент — дождитесь окончания.' });
            expect(root().querySelector('.window-cost')?.textContent).toBe('≈ 0,3 % лимита');
            const error = root().querySelector('.window-error')!;
            expect(error.textContent).toContain('ещё переписывает');
            expect(error.getAttribute('aria-live')).toBe('polite');
            expect(field().getAttribute('aria-describedby')).toBe(`${root().querySelector('.window-cost')!.id} ${error.id}`);
            await create();
            expect(root().querySelector('.window-cost')).toBeNull();
            expect(field().getAttribute('aria-describedby')).toBeNull();
        });

        it('asks to be closed with focus returned on Esc and on «×», and the host keeps what was typed', async () => {
            await create();
            type('слишком сложно');
            root().querySelector('[popover]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            root().querySelector<HTMLButtonElement>('.window-close')!.click();
            expect(dismissals).toEqual([true, true]);
            expect(fixture.componentInstance.instruction()).toBe('слишком сложно');
        });

        it('sits under the end of the selection, inside the viewport, above it when there is no room below, and limits its height to the room', async () => {
            vi.stubGlobal('innerWidth', 1000);
            vi.stubGlobal('innerHeight', 800);
            await create({ anchor: { top: 100, bottom: 120, left: 300, right: 400 } });
            let style = root().querySelector<HTMLElement>('[popover]')!.style;
            expect([style.top, style.left, style.inlineSize, style.maxBlockSize]).toEqual(['128px', '300px', '416px', '664px']);
            await create({ anchor: { top: 100, bottom: 120, left: 900, right: 990 } });
            expect(root().querySelector<HTMLElement>('[popover]')!.style.left).toBe('576px');
            await create({ anchor: { top: 700, bottom: 720, left: 4, right: 40 } });
            style = root().querySelector<HTMLElement>('[popover]')!.style;
            expect([style.top, style.bottom, style.left, style.maxBlockSize]).toEqual(['auto', '108px', '8px', '684px']);
            await create({ anchor: { top: 100, bottom: 700, left: 300, right: 400 } });
            style = root().querySelector<HTMLElement>('[popover]')!.style;
            expect([style.top, style.bottom, style.maxBlockSize]).toEqual(['auto', '8px', '784px']);
            vi.stubGlobal('innerWidth', 300);
            await create({ anchor: { top: 10, bottom: 30, left: 0, right: 20 } });
            expect(root().querySelector<HTMLElement>('[popover]')!.style.inlineSize).toBe('284px');
            await create({ anchor: null });
            expect(root().querySelector<HTMLElement>('[popover]')!.style.top).toBe('20vh');
        });

        it('is placed again with the height its content really takes: a message that makes it taller moves it where it fits', async () => {
            vi.stubGlobal('innerWidth', 1000);
            vi.stubGlobal('innerHeight', 800);
            let height = 200;
            Object.defineProperty(HTMLElement.prototype, 'scrollHeight', { configurable: true, get: () => height });
            try {
                await create({ anchor: { top: 400, bottom: 420, left: 300, right: 400 } });
                expect(root().querySelector<HTMLElement>('[popover]')!.style.top).toBe('428px');
                height = 500;
                fixture.componentRef.setInput('error', 'Длинное объяснение, из-за которого окно стало выше.');
                await fixture.whenStable();
                fixture.detectChanges();
                await fixture.whenStable();
                fixture.detectChanges();
                const style = root().querySelector<HTMLElement>('[popover]')!.style;
                expect([style.top, style.bottom]).toEqual(['auto', '408px']);
            } finally {
                delete (HTMLElement.prototype as { scrollHeight?: unknown }).scrollHeight;
            }
        });

        it('keeps the empty error line in the accessibility tree: it is not hidden, only taken out of the layout', async () => {
            await create();
            const error = root().querySelector<HTMLElement>('.window-error')!;
            expect(error.textContent).toBe('');
            expect(error.hasAttribute('hidden')).toBe(false);
            expect(error.getAttribute('aria-live')).toBe('polite');
        });

        it('does not close on Esc while an IME composes (the composition is what Esc cancels), by event flag, keyCode or composition events', async () => {
            await create();
            const surface = root().querySelector('[popover]')!;
            const esc = (init: KeyboardEventInit & { keyCode?: number } = {}): void => {
                const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, ...init });
                if (init.keyCode !== undefined) Object.defineProperty(event, 'keyCode', { value: init.keyCode });
                surface.dispatchEvent(event);
            };
            esc({ isComposing: true });
            esc({ keyCode: 229 });
            field().dispatchEvent(new Event('compositionstart', { bubbles: true }));
            esc();
            expect(dismissals).toEqual([]);
            field().dispatchEvent(new Event('compositionend', { bubbles: true }));
            esc();
            expect(dismissals).toEqual([true]);
        });
    });

    describe('as a bottom sheet', () => {
        it('is a modal dialog opened with showModal, focus on its title so the keyboard does not hide the presets, and enterkeyhint=send', async () => {
            await create({ mode: 'sheet' });
            const dialog = root().querySelector('dialog')!;
            expect(dialog.hasAttribute('open')).toBe(true);
            expect(root().querySelector('#' + dialog.getAttribute('aria-labelledby'))?.textContent).toBe('Попросить Мнему');
            expect(document.activeElement).toBe(root().querySelector('.window-title'));
            expect(field().getAttribute('enterkeyhint')).toBe('send');
            expect(root().querySelector('[popover]')).toBeNull();
            expect(root().querySelector('.window-keys')).toBeNull();
            named('Проще').click();
            expect(asks).toEqual([{ preset: 'SIMPLER', instruction: null }]);
        });

        it('closes on Esc (the native cancel is taken over), on «×» and on the backdrop alike, and not on a click inside or on its padding', async () => {
            await create({ mode: 'sheet' });
            const dialog = root().querySelector('dialog')!;
            const cancel = new Event('cancel', { cancelable: true });
            dialog.dispatchEvent(cancel);
            expect(cancel.defaultPrevented).toBe(true);
            expect(dismissals).toEqual([true]);
            field().click();
            root().querySelector<HTMLElement>('.sheet-body')!.click();
            expect(dismissals).toEqual([true]);
            // The dialog element itself is the backdrop: it closes the sheet as Esc does (focus goes back, the typed request stays).
            dialog.click();
            expect(dismissals).toEqual([true, true]);
            root().querySelector<HTMLButtonElement>('.window-close')!.click();
            expect(dismissals).toEqual([true, true, true]);
        });

        it('does not close on the native cancel of an IME composition', async () => {
            await create({ mode: 'sheet' });
            field().dispatchEvent(new Event('compositionstart', { bubbles: true }));
            const cancel = new Event('cancel', { cancelable: true });
            root().querySelector('dialog')!.dispatchEvent(cancel);
            expect(cancel.defaultPrevented).toBe(true);
            expect(dismissals).toEqual([]);
        });
    });
});
