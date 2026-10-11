import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import type { UpdateChoice } from './copy-text';
import { UpdateUnitRowComponent, UpdateUnitView, choicesFor } from './update-unit-row.component';

@Component({
    imports: [UpdateUnitRowComponent],
    template: `
      <app-update-unit-row #row [unit]="unit()" [(choice)]="choice" [headingLevel]="level()">
        @if (row.expanded()) { <p id="compare-body">Сравнение</p> }
      </app-update-unit-row>`
})
class HostComponent {
    readonly unit = signal<UpdateUnitView>({ id: 'u1', kind: 'material', title: 'Спряжение ser и estar', class: 'fix' });
    readonly choice = signal<UpdateChoice | null>(null);
    readonly level = signal<3 | 4>(3);
}

describe('UpdateUnitRowComponent', () => {
    function create(unit?: Partial<UpdateUnitView>) {
        const fixture = TestBed.createComponent(HostComponent);
        if (unit) fixture.componentInstance.unit.update(current => ({ ...current, ...unit }));
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        return {
            fixture, root, host: fixture.componentInstance,
            radios: () => [...root.querySelectorAll<HTMLInputElement>('input[type=radio]')],
            toggle: () => root.querySelector<HTMLButtonElement>('button[aria-expanded]')!
        };
    }

    it('names the row by its title and says the kind and the class in words', () => {
        const { root } = create();
        const article = root.querySelector('article')!;
        const title = root.querySelector('h3')!;
        expect(title.textContent).toBe('Спряжение ser и estar');
        expect(article.getAttribute('aria-labelledby')).toBe(title.id);
        expect(root.querySelector('.kind')?.textContent!.trim()).toBe('Материал');
        expect(root.querySelector('.meta .stamp')?.textContent).toBe('Исправление');
        expect(root.querySelector('.kind app-glyph')?.getAttribute('aria-hidden')).toBe('true');
    });

    it.each([
        ['material', 'Материал'], ['exercise', 'Упражнение'], ['goal', 'Цель'], ['description', 'Описание колоды'], ['order', 'Порядок материалов']
    ] as const)('names the kind «%s» as «%s»', (kind, word) => {
        expect(create({ kind }).root.querySelector('.kind')?.textContent!.trim()).toBe(word);
    });

    it.each([
        ['new', 'Новое'], ['fix', 'Исправление'], ['conflict', 'Конфликт'], ['deleted', 'Удалено автором'], ['changed-again', 'Снова изменено']
    ] as const)('stamps the class «%s» as «%s»', (unitClass, word) => {
        expect(create({ class: unitClass }).root.querySelector('.meta .stamp')?.textContent).toBe(word);
    });

    it('keeps the whole title in the DOM and wraps it, with no single-line cut', () => {
        const long = 'Очень длинное название материала, которое не помещается в одну строку на узком экране телефона';
        const { root } = create({ title: long });
        expect(root.querySelector('.title')?.textContent).toBe(long);
        expect(root.querySelector('.title')?.getAttribute('title')).toBeNull();
    });

    it('can take a lower title level', () => {
        const { fixture, root, host } = create();
        host.level.set(4);
        fixture.detectChanges();
        expect(root.querySelector('h4.title')).not.toBeNull();
        expect(root.querySelector('h3')).toBeNull();
    });

    it('offers the answers of each class and starts a conflict and a deletion on the safe one', () => {
        expect(choicesFor('new').map(option => option.label)).toEqual(['Принять', 'Не брать']);
        expect(choicesFor('changed-again').map(option => option.label)).toEqual(['Принять', 'Не брать']);
        expect(choicesFor('fix').map(option => option.label)).toEqual(['Принять', 'Не брать']);
        const conflict = create({ class: 'conflict' });
        expect(conflict.radios().map(radio => radio.labels![0]!.textContent!.trim())).toEqual(['Оставить моё', 'Взять вариант автора']);
        expect(conflict.radios().map(radio => radio.checked)).toEqual([true, false]);
        const deleted = create({ class: 'deleted' });
        expect(deleted.radios().map(radio => radio.labels![0]!.textContent!.trim())).toEqual(['Оставить у себя', 'Удалить и у меня']);
        expect(deleted.radios().map(radio => radio.checked)).toEqual([true, false]);
    });

    it('leaves a new or corrected unit undecided until the reader picks', () => {
        const { fixture, radios, host } = create({ class: 'new' });
        expect(radios().map(radio => radio.checked)).toEqual([false, false]);
        radios()[0]!.click();
        fixture.detectChanges();
        expect(host.choice()).toBe('accept');
        expect(radios().map(radio => radio.checked)).toEqual([true, false]);
    });

    it('records the pick of a conflict and a deletion', () => {
        const conflict = create({ class: 'conflict' });
        conflict.radios()[1]!.click();
        conflict.fixture.detectChanges();
        expect(conflict.host.choice()).toBe('take-author');
        const deleted = create({ class: 'deleted' });
        deleted.radios()[1]!.click();
        expect(deleted.host.choice()).toBe('delete');
    });

    it('names the choice by the unit, and asks «Удалить … и у меня?» in view for a deletion', () => {
        const fix = create();
        const hidden = fix.root.querySelector('legend')!;
        expect(hidden.textContent).toBe('Что сделать с «Спряжение ser и estar»');
        expect(hidden.classList.contains('visually-hidden')).toBe(true);
        const deleted = create({ class: 'deleted' });
        const question = deleted.root.querySelector('legend')!;
        expect(question.textContent).toBe('Удалить «Спряжение ser и estar» и у меня?');
        expect(question.classList.contains('visually-hidden')).toBe(false);
    });

    it('names the units the reader needs for it', () => {
        const none = create({ kind: 'exercise', class: 'new' });
        expect(none.root.querySelector('.dependency')).toBeNull();
        const needed = create({ kind: 'exercise', class: 'new', dependency: 'Повелительное наклонение' });
        expect(needed.root.querySelector('.dependency')?.textContent).toBe('Нужен материал «Повелительное наклонение». Он будет добавлен вместе с этим.');
    });

    it('opens the comparison with a button that says the state, controls the body and names the unit', () => {
        const { fixture, root, toggle } = create();
        expect(toggle().getAttribute('aria-expanded')).toBe('false');
        expect(toggle().getAttribute('aria-controls')).toBeNull();
        expect(toggle().textContent!.trim()).toBe('Сравнить');
        expect(toggle().getAttribute('aria-label')).toBe('Сравнить: «Спряжение ser и estar»');
        expect(root.querySelector('#compare-body')).toBeNull();
        toggle().click();
        fixture.detectChanges();
        expect(toggle().getAttribute('aria-expanded')).toBe('true');
        expect(toggle().textContent!.trim()).toBe('Свернуть сравнение');
        expect(toggle().getAttribute('aria-label')).toBe('Свернуть сравнение: «Спряжение ser и estar»');
        const body = root.querySelector<HTMLElement>(`#${toggle().getAttribute('aria-controls')}`)!;
        expect(body.querySelector('#compare-body')).not.toBeNull();
        toggle().click();
        fixture.detectChanges();
        expect(root.querySelector('#compare-body')).toBeNull();
    });

    it('keeps the focus on the disclosure button when it opens and closes', () => {
        const { fixture, toggle } = create();
        toggle().focus();
        toggle().click();
        fixture.detectChanges();
        expect(document.activeElement).toBe(toggle());
    });
});
