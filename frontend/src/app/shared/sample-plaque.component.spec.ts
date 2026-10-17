import { TestBed } from '@angular/core/testing';

import { SamplePlaqueComponent } from './sample-plaque.component';

describe('SamplePlaqueComponent', () => {
    it('is the stamp «Учебная» and the approved sentence, as plain text with no role or live region', () => {
        const fixture = TestBed.createComponent(SamplePlaqueComponent);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('.stamp')?.textContent).toBe('Учебная');
        expect(root.querySelector('p')?.textContent).toBe('УчебнаяУчебная колода: образец, к которому можно возвращаться');
        expect(root.querySelector('[role], [aria-live]')).toBeNull();
        expect(root.querySelector('p > span:not(.stamp)')?.textContent).toBe('Учебная колода: образец, к которому можно возвращаться');
    });
});
