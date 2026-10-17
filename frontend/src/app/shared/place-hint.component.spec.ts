import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { PlaceHintComponent } from './place-hint.component';

@Component({ imports: [PlaceHintComponent], template: `<app-place-hint>Не знаете, что выбрать?</app-place-hint>` })
class HostComponent {}

describe('PlaceHintComponent', () => {
    it('is a note with its text, that never takes the focus', () => {
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        const hint = fixture.nativeElement.querySelector('app-place-hint') as HTMLElement;
        expect(hint.getAttribute('role')).toBe('note');
        expect(hint.textContent).toBe('Не знаете, что выбрать?');
        expect(hint.hasAttribute('tabindex')).toBe(false);
        expect(hint.querySelector('button, a, input')).toBeNull();
    });
});
