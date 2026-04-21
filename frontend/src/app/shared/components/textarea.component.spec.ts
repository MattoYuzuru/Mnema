import { ComponentFixture, TestBed } from '@angular/core/testing';

import { TextareaComponent } from './textarea.component';

describe('TextareaComponent', () => {
    let fixture: ComponentFixture<TextareaComponent>;
    let component: TextareaComponent;

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [TextareaComponent]
        }).compileComponents();

        fixture = TestBed.createComponent(TextareaComponent);
        component = fixture.componentInstance;
    });

    it('applies the reusable custom scrollbar class to the textarea element', () => {
        fixture.detectChanges();

        const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement | null;

        expect(textarea).not.toBeNull();
        expect(textarea?.classList.contains('mn-scrollbar')).toBeTrue();
    });

    it('propagates user input through the control value accessor callback', () => {
        const onChange = jasmine.createSpy('onChange');
        component.registerOnChange(onChange);
        fixture.detectChanges();

        const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
        textarea.value = 'Updated note';
        textarea.dispatchEvent(new Event('input'));

        expect(onChange).toHaveBeenCalledWith('Updated note');
        expect(component.value).toBe('Updated note');
    });
});
