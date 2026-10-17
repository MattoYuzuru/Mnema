import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { BookSearchComponent, rearmBookOpening } from './book-search.component';
import { TYPEWRITER_TIMING } from './typewriter-hint.component';

@Component({
    imports: [BookSearchComponent],
    template: `<app-book-search [mode]="mode()" [(value)]="value" [(expanded)]="expanded" (searched)="found.push($event)" />`
})
class HostComponent {
    readonly mode = signal<'open' | 'closed'>('open');
    readonly value = signal('');
    readonly expanded = signal(false);
    readonly found: string[] = [];
}

describe('BookSearchComponent', () => {
    let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
    let host: HostComponent;
    let root: HTMLElement;

    const create = (mode: 'open' | 'closed' = 'open'): void => {
        fixture = TestBed.createComponent(HostComponent);
        host = fixture.componentInstance;
        host.mode.set(mode);
        root = fixture.nativeElement as HTMLElement;
        fixture.detectChanges();
    };
    const input = (): HTMLInputElement => root.querySelector('input')!;
    const entry = (): HTMLButtonElement => root.querySelector<HTMLButtonElement>('button.entry')!;
    const type = (text: string): void => { input().value = text; input().dispatchEvent(new Event('input')); fixture.detectChanges(); };
    const submit = (): void => { root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); fixture.detectChanges(); };

    beforeEach(() => rearmBookOpening());

    describe('open book', () => {
        it('is one named search landmark with a plain form, a visible label, a real search field and a named submit button', () => {
            create();
            const search = root.querySelector('search')!;
            expect(search.getAttribute('aria-label')).toBe('Поиск колод');
            const form = search.querySelector('form')!;
            expect(form.hasAttribute('role')).toBe(false);
            expect(form.hasAttribute('aria-label')).toBe(false);
            expect(root.querySelectorAll('search, [role=search]')).toHaveLength(1);
            const label = form.querySelector('label')!;
            expect(label.textContent?.trim()).toBe('Найти колоду');
            expect(label.getAttribute('for')).toBe(input().id);
            expect(input().type).toBe('search');
            expect(input().name).toBe('q');
            expect(input().maxLength).toBe(100);
            expect(input().hasAttribute('placeholder')).toBe(false);
            const submitButton = form.querySelector<HTMLButtonElement>('button[type=submit]')!;
            expect(submitButton.getAttribute('aria-label')).toBe('Найти');
            expect(submitButton.querySelector('app-glyph[data-glyph=book-search]')).not.toBeNull();
            expect(form.querySelector('.cover app-glyph[data-glyph=book-open]')).not.toBeNull();
            expect(root.querySelector('.cover')!.getAttribute('aria-hidden')).toBe('true');
            expect(root.querySelector('button.entry')).toBeNull();
            expect(form.hidden).toBe(false);
        });

        it('searches the trimmed text on submit, and an empty submit means browse', () => {
            create();
            type('  испанский  ');
            expect(host.value()).toBe('  испанский  ');
            submit();
            expect(host.found).toEqual(['испанский']);
            type('');
            submit();
            expect(host.found).toEqual(['испанский', '']);
        });

        it('prevents the native submit so the page does not reload', () => {
            create();
            const event = new Event('submit', { cancelable: true });
            root.querySelector('form')!.dispatchEvent(event);
            expect(event.defaultPrevented).toBe(true);
        });

        it('shows the value it is given', () => {
            create();
            host.value.set('латынь');
            fixture.detectChanges();
            expect(input().value).toBe('латынь');
        });

        it('plays the opening once per page load: the first book only, and never again on a re-render', () => {
            create();
            expect(root.querySelector('.spread')!.classList.contains('is-opening')).toBe(true);
            host.value.set('x');
            fixture.detectChanges();
            host.mode.set('closed');
            fixture.detectChanges();
            host.mode.set('open');
            fixture.detectChanges();
            expect(root.querySelector('.spread')!.classList.contains('is-opening')).toBe(true);
            const second = TestBed.createComponent(HostComponent);
            second.detectChanges();
            expect((second.nativeElement as HTMLElement).querySelector('.spread')!.classList.contains('is-opening')).toBe(false);
            rearmBookOpening();
            const third = TestBed.createComponent(HostComponent);
            third.detectChanges();
            expect((third.nativeElement as HTMLElement).querySelector('.spread')!.classList.contains('is-opening')).toBe(true);
        });

        it('does not spend the opening on a closed header entry', () => {
            create('closed');
            expect(root.querySelector('.spread')!.classList.contains('is-opening')).toBe(false);
            const page = TestBed.createComponent(HostComponent);
            page.detectChanges();
            expect((page.nativeElement as HTMLElement).querySelector('.spread')!.classList.contains('is-opening')).toBe(true);
        });
    });

    describe('closed book (header entry)', () => {
        it('is a button with a name, state and the form it controls; the form is hidden', () => {
            create('closed');
            expect(entry().getAttribute('aria-label')).toBe('Открыть поиск колод');
            expect(root.querySelector('search')!.getAttribute('aria-label')).not.toBe(entry().getAttribute('aria-label'));
            expect(entry().getAttribute('aria-expanded')).toBe('false');
            const form = root.querySelector('form')!;
            expect(entry().getAttribute('aria-controls')).toBe(form.id);
            expect(form.hidden).toBe(true);
            expect(entry().querySelector('app-glyph[data-glyph=book-search]')).not.toBeNull();
            expect(root.querySelector('.cover')).toBeNull();
        });

        it('reveals the form inline and moves the focus into the field', () => {
            create('closed');
            entry().click();
            fixture.detectChanges();
            expect(entry().getAttribute('aria-expanded')).toBe('true');
            expect(root.querySelector('form')!.hidden).toBe(false);
            expect(host.expanded()).toBe(true);
            expect(document.activeElement).toBe(input());
        });

        it('folds on Esc, returns the focus to the button and keeps the text', () => {
            create('closed');
            entry().click();
            fixture.detectChanges();
            type('ПДД');
            const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
            input().dispatchEvent(event);
            fixture.detectChanges();
            expect(event.defaultPrevented).toBe(true);
            expect(entry().getAttribute('aria-expanded')).toBe('false');
            expect(root.querySelector('form')!.hidden).toBe(true);
            expect(document.activeElement).toBe(entry());
            expect(host.value()).toBe('ПДД');
        });

        it('folds again when the button is pressed on an open form', () => {
            create('closed');
            entry().click();
            fixture.detectChanges();
            entry().click();
            fixture.detectChanges();
            expect(host.expanded()).toBe(false);
            expect(document.activeElement).toBe(entry());
        });

        it('folds an empty field when the focus leaves the search, but not one with text', () => {
            create('closed');
            const outside = document.createElement('button');
            document.body.append(outside);
            entry().click();
            fixture.detectChanges();
            input().dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: root.querySelector('button[type=submit]') }));
            fixture.detectChanges();
            expect(host.expanded()).toBe(true);
            type('x');
            input().dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: outside }));
            fixture.detectChanges();
            expect(host.expanded()).toBe(true);
            type('');
            input().dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: outside }));
            fixture.detectChanges();
            expect(host.expanded()).toBe(false);
            outside.remove();
        });

        it('stops the Esc it handled from reaching the page (a dialog or menu around it keeps its own)', () => {
            create('closed');
            entry().click();
            fixture.detectChanges();
            const outer = vi.fn();
            root.addEventListener('keydown', outer);
            input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
            expect(outer).not.toHaveBeenCalled();
        });

        it('ignores Esc in the open book', () => {
            create('open');
            const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
            input().dispatchEvent(event);
            expect(event.defaultPrevented).toBe(false);
        });
    });

    describe('typewriter hint', () => {
        beforeEach(() => vi.useFakeTimers());
        afterEach(() => vi.useRealTimers());

        it('types over the empty field and gives way to focus and to a value', () => {
            create();
            vi.advanceTimersByTime(TYPEWRITER_TIMING.startMs);
            fixture.detectChanges();
            expect(root.querySelector('app-typewriter-hint .idea')?.textContent).toBeTruthy();
            input().dispatchEvent(new FocusEvent('focus'));
            fixture.detectChanges();
            expect(root.querySelector('app-typewriter-hint .idea')).toBeNull();
            type('a');
            input().dispatchEvent(new FocusEvent('blur'));
            fixture.detectChanges();
            expect(root.querySelector('app-typewriter-hint .idea')).toBeNull();
            type('');
            vi.advanceTimersByTime(TYPEWRITER_TIMING.startMs);
            fixture.detectChanges();
            expect(root.querySelector('app-typewriter-hint .idea')).not.toBeNull();
        });

        it('never reaches the accessible tree and never fills the field', () => {
            create();
            vi.advanceTimersByTime(60_000);
            fixture.detectChanges();
            expect(root.querySelector('app-typewriter-hint')!.getAttribute('aria-hidden')).toBe('true');
            expect(input().value).toBe('');
        });

        it('stays silent while a closed book is folded', () => {
            create('closed');
            expect(vi.getTimerCount()).toBe(0);
        });
    });
});
