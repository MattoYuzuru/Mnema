import { TestBed } from '@angular/core/testing';

import { TourPauseComponent } from './tour-pause.component';

describe('TourPauseComponent', () => {
    function render(inline = false) {
        TestBed.configureTestingModule({ imports: [TourPauseComponent] });
        const fixture = TestBed.createComponent(TourPauseComponent);
        fixture.componentRef.setInput('inline', inline);
        fixture.detectChanges();
        return { fixture, host: fixture.nativeElement as HTMLElement, component: fixture.componentInstance };
    }

    it('is a named region that says the tour is paused and offers the way back', () => {
        const { host } = render();
        const region = host.querySelector('[role=region]')!;
        expect(region.getAttribute('aria-label')).toBe('Знакомство на паузе');
        expect(region.textContent).toContain('Знакомство на паузе');
        expect(host.querySelector('button')!.textContent).toBe('Продолжить знакомство');
    });

    it('resumes from the button and takes focus on request', () => {
        const { host, component } = render();
        let resumed = 0;
        component.resumed.subscribe(() => resumed++);
        component.focusResume();
        const button = host.querySelector('button')!;
        expect(document.activeElement).toBe(button);
        button.click();
        expect(resumed).toBe(1);
    });

    it('can be drawn in the flow', () => {
        expect(render(true).host.classList.contains('inline')).toBe(true);
        TestBed.resetTestingModule();
        expect(render(false).host.classList.contains('inline')).toBe(false);
    });

    it('says «Задание на паузе» and «Продолжить задание» for a side quest', () => {
        TestBed.configureTestingModule({ imports: [TourPauseComponent] });
        const fixture = TestBed.createComponent(TourPauseComponent);
        fixture.componentRef.setInput('quest', true);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;
        expect(host.querySelector('[role=region]')?.getAttribute('aria-label')).toBe('Задание на паузе');
        expect(host.querySelector('button')?.textContent).toBe('Продолжить задание');
    });
});
