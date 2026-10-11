import { ComponentFixture, TestBed } from '@angular/core/testing';

import { TagInputComponent } from './tag-input.component';

describe('TagInputComponent', () => {
    let fixture: ComponentFixture<TagInputComponent>;
    let root: HTMLElement;
    let field: HTMLInputElement;

    const type = (text: string): void => {
        field.value = text;
        field.dispatchEvent(new Event('input'));
        fixture.detectChanges();
    };
    const press = (key: string): KeyboardEvent => {
        const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
        field.dispatchEvent(event);
        fixture.detectChanges();
        return event;
    };
    const tags = (): string[] => [...root.querySelectorAll('.tag-text')].map(item => item.textContent!);
    const error = (): string => root.querySelector<HTMLElement>('.field-error')!.hidden ? '' : root.querySelector('.field-error')!.textContent!;
    const status = (): string => root.querySelector('[role=status]')!.textContent!;

    beforeEach(async () => {
        await TestBed.configureTestingModule({}).compileComponents();
        fixture = TestBed.createComponent(TagInputComponent);
        fixture.componentRef.setInput('label', 'Теги');
        fixture.detectChanges();
        root = fixture.nativeElement as HTMLElement;
        field = root.querySelector('input')!;
    });

    it('names the field with its visible label and describes it with the hint and the count', () => {
        const label = root.querySelector('label')!;
        expect(label.textContent).toBe('Теги');
        expect(label.htmlFor).toBe(field.id);
        const hint = root.querySelector<HTMLElement>(`#${field.getAttribute('aria-describedby')}`)!;
        expect(hint.textContent).toContain('Теги: 0 из 5');
    });

    it('adds the normalized tag on Enter without submitting a form, and announces it', () => {
        type('  JLPT   N5 ');
        const event = press('Enter');
        expect(event.defaultPrevented).toBe(true);
        expect(tags()).toEqual(['jlpt n5']);
        expect(fixture.componentInstance.tags()).toEqual(['jlpt n5']);
        expect(field.value).toBe('');
        expect(status()).toBe('Тег добавлен: jlpt n5. Теги: 1 из 5.');
        expect(root.querySelector('.hint')!.textContent).toContain('Теги: 1 из 5');
    });

    it('adds on a comma, including every tag of a pasted list, and keeps the unfinished tail', () => {
        type('a,');
        expect(tags()).toEqual(['a']);
        type('b, c,d');
        expect(tags()).toEqual(['a', 'b', 'c']);
        expect(field.value).toBe('d');
    });

    it('removes the last tag with Backspace in the empty field only', () => {
        type('one,two,');
        expect(tags()).toEqual(['one', 'two']);
        type('x');
        press('Backspace');
        expect(tags()).toEqual(['one', 'two']);
        type('');
        press('Backspace');
        expect(tags()).toEqual(['one']);
        expect(status()).toBe('Тег убран: two. Теги: 1 из 5.');
    });

    it('gives every tag its own labelled remove button and returns the focus to the field', () => {
        type('one,two,');
        const buttons = [...root.querySelectorAll<HTMLButtonElement>('.tag-remove')];
        expect(buttons.map(button => button.getAttribute('aria-label'))).toEqual(['Убрать тег one', 'Убрать тег two']);
        const focus = vi.spyOn(field, 'focus');
        buttons[0].click();
        fixture.detectChanges();
        expect(tags()).toEqual(['two']);
        expect(focus).toHaveBeenCalled();
    });

    it('refuses an unfit tag in words, marks the field invalid and clears the sentence on the next keystroke', () => {
        type('what?');
        press('Enter');
        expect(error()).toContain('буквы, цифры');
        expect(field.getAttribute('aria-invalid')).toBe('true');
        expect(field.getAttribute('aria-describedby')).toContain(root.querySelector('.field-error')!.id);
        expect(tags()).toEqual([]);
        type('what');
        expect(error()).toBe('');
        expect(field.hasAttribute('aria-invalid')).toBe(false);
    });

    it('refuses a duplicate after normalization and a tag over 32 characters', () => {
        type('Abc,');
        type('ABC');
        press('Enter');
        expect(error()).toBe('Такой тег уже есть.');
        type('я'.repeat(33));
        press('Enter');
        expect(error()).toContain('длиннее 32');
    });

    it('stops at five tags but stays focusable: aria-disabled and read only, never disabled', () => {
        type('a,b,c,d,e,');
        expect(tags()).toHaveLength(5);
        expect(field.getAttribute('aria-disabled')).toBe('true');
        expect(field.readOnly).toBe(true);
        expect(field.disabled).toBe(false);
        type('f');
        press('Enter');
        expect(tags()).toHaveLength(5);
        expect(error()).toContain('не больше 5 тегов');
        type('');
        press('Backspace');
        expect(tags()).toHaveLength(4);
        expect(field.hasAttribute('aria-disabled')).toBe(false);
    });

    it('adds a valid tag that was typed when the field is left, and leaves an unfit text alone', () => {
        type('quiet');
        field.dispatchEvent(new Event('blur'));
        fixture.detectChanges();
        expect(tags()).toEqual(['quiet']);
        type('bad?');
        field.dispatchEvent(new Event('blur'));
        fixture.detectChanges();
        expect(tags()).toEqual(['quiet']);
        expect(field.value).toBe('bad?');
        expect(error()).toBe('');
    });

    it('speaks a refusal in the same polite status as an addition, also when it repeats', () => {
        type('what?');
        press('Enter');
        expect(status()).toBe('В теге можно использовать буквы, цифры, пробел и знаки - _ + # .');
        expect(root.querySelector('[role=status]')!.getAttribute('aria-live')).toBe('polite');
        const first = status();
        type('what?');
        press('Enter');
        expect(status()).not.toBe(first);
        expect(status().trim()).toBe(first);
        type('c#');
        press('Enter');
        expect(status()).toBe('Тег добавлен: c#. Теги: 1 из 5.');
        type('c#');
        press('Enter');
        expect(status()).toBe('Такой тег уже есть.');
    });

    it('leaves Enter, Backspace and commas alone while an IME composes', () => {
        type('нихон');
        const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true, isComposing: true });
        field.dispatchEvent(enter);
        fixture.detectChanges();
        expect(enter.defaultPrevented).toBe(false);
        expect(tags()).toEqual([]);
        expect(field.value).toBe('нихон');
        field.value = 'ni,';
        const composing = new Event('input') as Event & { isComposing: boolean };
        Object.defineProperty(composing, 'isComposing', { value: true });
        field.dispatchEvent(composing);
        expect(tags()).toEqual([]);
        type('x');
        field.value = '';
        const backspace = new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true, cancelable: true, isComposing: true });
        type('a,');
        field.dispatchEvent(backspace);
        expect(tags()).toEqual(['a']);
    });

    it('accepts letters with combining marks like the server does', () => {
        type('हिन्दी');
        press('Enter');
        expect(tags()).toEqual(['हिन्दी']);
        expect(error()).toBe('');
    });
});
