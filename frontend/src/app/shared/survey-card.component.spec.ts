import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { SurveyCardComponent } from './survey-card.component';

@Component({
    imports: [SurveyCardComponent],
    template: `<app-survey-card title="Для чего вам Mnema?" [question]="question()"><p class="body">Тело</p><button actions type="button">Дальше</button><p note>Строка</p></app-survey-card>`
})
class HostComponent { readonly question = signal<number | null>(2); }

describe('SurveyCardComponent', () => {
    function render() {
        TestBed.configureTestingModule({ imports: [HostComponent] });
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        return { fixture, root: fixture.nativeElement as HTMLElement, host: fixture.componentInstance };
    }

    it('names the card by its heading and projects the body, the actions and the note', () => {
        const { root } = render();
        const section = root.querySelector('section')!;
        expect(section.getAttribute('aria-labelledby')).toBe(root.querySelector('h2')!.id);
        expect(root.querySelector('h2')!.getAttribute('tabindex')).toBe('-1');
        expect(root.querySelector('.body')!.textContent).toBe('Тело');
        expect(root.querySelector('.actions button')!.textContent).toBe('Дальше');
        expect(root.querySelector('[note]')!.textContent).toBe('Строка');
    });

    it('shows the question number and a progress bar of the questions behind the learner', () => {
        const { root } = render();
        expect(root.querySelector('.eyebrow')!.textContent).toBe('Вопрос 2 из 4');
        const bar = root.querySelector('[role=progressbar]')!;
        expect([bar.getAttribute('aria-valuemin'), bar.getAttribute('aria-valuemax'), bar.getAttribute('aria-valuenow')]).toEqual(['0', '4', '1']);
        expect(bar.getAttribute('aria-valuetext')).toBe('1 из 4');
        expect(root.querySelector<HTMLElement>('.progress-fill')!.style.inlineSize).toBe('25%');
    });

    it('is only a titled sheet without a question', () => {
        const { fixture, root, host } = render();
        host.question.set(null);
        fixture.detectChanges();
        expect(root.querySelector('[role=progressbar]')).toBeNull();
        expect(root.querySelector('.eyebrow')).toBeNull();
    });

    it('takes the focus to its heading on request', () => {
        const { root } = render();
        root.querySelector('app-survey-card')!.dispatchEvent(new Event('x'));
        const card = TestBed.createComponent(SurveyCardComponent);
        card.componentRef.setInput('title', 'Заголовок');
        card.detectChanges();
        card.componentInstance.focusHeading();
        expect(document.activeElement).toBe(card.nativeElement.querySelector('h2'));
    });
});
