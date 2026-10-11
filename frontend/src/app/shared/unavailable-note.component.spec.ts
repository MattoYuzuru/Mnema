import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { UnavailableNoteComponent } from './unavailable-note.component';

@Component({
    imports: [UnavailableNoteComponent],
    template: `<app-unavailable-note id="reader" /><app-unavailable-note id="owner" audience="owner"><a href="#x">Заменить</a></app-unavailable-note>
      <app-unavailable-note id="exercise" subject="exercise" /><app-unavailable-note id="owner-exercise" subject="exercise" audience="owner" />`
})
class HostComponent {}

describe('UnavailableNoteComponent', () => {
    const text = (root: HTMLElement, id: string): string => [...root.querySelectorAll(`#${id} p, #${id} a`)].map(part => part.textContent!.trim()).join(' ');

    it('says in words what happened, to a reader of a public deck and to the owner', () => {
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(text(root, 'reader')).toBe('Материал недоступен Материал снят по жалобе.');
        expect(text(root, 'owner')).toBe('Материал недоступен Материал снят по жалобе, открыть его нельзя. Напишите новый на его месте или удалите этот. Заменить');
        expect(text(root, 'exercise')).toBe('Упражнение недоступно Упражнение снято по жалобе.');
        expect(text(root, 'owner-exercise')).toBe('Упражнение недоступно Упражнение снято по жалобе. Создайте новое или удалите это.');
    });

    it('draws no empty action row for a reader', () => {
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        const note = (fixture.nativeElement as HTMLElement).querySelector('#reader')!;
        expect(note.querySelector('.actions')?.children).toHaveLength(0);
    });
});
