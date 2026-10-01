import { TestBed } from '@angular/core/testing';

import { MEDIA_PLAYBACK_RESOLVER } from '../../features/study/media-playback-resolver';
import { fakePlayback } from '../../features/study/study-test-data';
import { ChoiceListComponent, optionLabel } from './choice-list.component';
import { LearnerChoiceOption } from './exercise-content.models';

describe('ChoiceListComponent', () => {
    const asset = (suffix: string) => `aaaaaaaa-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const options: LearnerChoiceOption[] = [
        { optionId: 'dddddddd-dddd-4ddd-8ddd-ddddddddddd1', blocks: [{ kind: 'TEXT', text: 'Текст' }] },
        { optionId: 'dddddddd-dddd-4ddd-8ddd-ddddddddddd2', blocks: [{ kind: 'AUDIO', assetId: asset('1'), transcriptAvailable: false }] },
        { optionId: 'dddddddd-dddd-4ddd-8ddd-ddddddddddd3', blocks: [{ kind: 'VIDEO', assetId: asset('2'), transcriptAvailable: false }] },
        { optionId: 'dddddddd-dddd-4ddd-8ddd-ddddddddddd4', blocks: [{ kind: 'IMAGE', assetId: asset('3'), alt: 'Осадок' }] }
    ];

    beforeEach(() => TestBed.configureTestingModule({ providers: [{ provide: MEDIA_PLAYBACK_RESOLVER,
        useValue: { resolve: fakePlayback } }] }));

    it('names options by text, image description or a generated neutral label', () => {
        expect(options.map((option, index) => optionLabel(option, index))).toEqual(['Текст', 'Аудио, вариант 2', 'Видео, вариант 3', 'Осадок']);
    });

    it('emits the toggled option, supports radios and checkboxes and disables the whole group when asked', () => {
        const fixture = TestBed.createComponent(ChoiceListComponent);
        fixture.componentRef.setInput('options', options);
        fixture.componentRef.setInput('selectionMode', 'SINGLE');
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        const selected: string[] = [];
        fixture.componentInstance.selectedChange.subscribe(value => selected.push(value));
        expect(root.querySelectorAll('input[type="radio"]').length).toBe(4);
        expect(root.querySelector('legend')?.textContent).toBe('Выберите один вариант');
        root.querySelectorAll<HTMLInputElement>('input')[1].click();
        expect(selected).toEqual([options[1].optionId]);

        fixture.componentRef.setInput('selectionMode', 'MULTIPLE');
        fixture.componentRef.setInput('disabled', true);
        fixture.detectChanges();
        expect(root.querySelectorAll('input[type="checkbox"]').length).toBe(4);
        expect(root.querySelector('legend')?.textContent).toBe('Выберите все подходящие варианты');
        expect(root.querySelector('fieldset')?.disabled).toBeTrue();
    });

    it('marks correct options after evaluation and keeps every player outside its label', () => {
        const fixture = TestBed.createComponent(ChoiceListComponent);
        fixture.componentRef.setInput('options', options);
        fixture.componentRef.setInput('selectionMode', 'MULTIPLE');
        fixture.componentRef.setInput('selected', [options[0].optionId]);
        fixture.componentRef.setInput('correctIds', [options[0].optionId, options[2].optionId]);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelectorAll('.is-correct').length).toBe(2);
        expect(root.querySelectorAll('.is-selected').length).toBe(1);
        expect(root.querySelector('.is-correct label')?.textContent).toContain('(правильный ответ)');
        expect(root.querySelectorAll('label button, label audio, label video, label img').length).toBe(0);
        expect(root.querySelector<HTMLInputElement>('input')?.getAttribute('data-answer-control')).toBe('');
    });
});
