import { TestBed } from '@angular/core/testing';

import { PathProgressComponent } from './path-progress.component';

describe('PathProgressComponent', () => {
    const render = (value: number, max: number) => {
        const fixture = TestBed.createComponent(PathProgressComponent);
        fixture.componentRef.setInput('label', 'Путь Mnema');
        fixture.componentRef.setInput('value', value);
        fixture.componentRef.setInput('max', max);
        fixture.componentRef.setInput('valueText', `${value} из ${max}`);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    };

    it('is a progressbar with a name, a value, a maximum and a text value', () => {
        const bar = render(5, 12).querySelector('[role="progressbar"]')!;
        expect(bar.getAttribute('aria-label')).toBe('Путь Mnema');
        expect(bar.getAttribute('aria-valuemin')).toBe('0');
        expect(bar.getAttribute('aria-valuemax')).toBe('12');
        expect(bar.getAttribute('aria-valuenow')).toBe('5');
        expect(bar.getAttribute('aria-valuetext')).toBe('5 из 12');
    });

    it('fills in proportion, never beyond the track, and is empty for a zero maximum', () => {
        const fill = (value: number, max: number): string => (render(value, max).querySelector('.fill') as HTMLElement).style.inlineSize;
        expect(fill(5, 10)).toBe('50%');
        expect(fill(20, 10)).toBe('100%');
        expect(fill(0, 10)).toBe('0%');
        expect(fill(3, 0)).toBe('0%');
    });
});
