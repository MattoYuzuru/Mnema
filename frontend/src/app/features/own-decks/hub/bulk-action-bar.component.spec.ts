import { ComponentFixture, TestBed } from '@angular/core/testing';

import { BulkActionBarComponent } from './bulk-action-bar.component';

describe('BulkActionBarComponent', () => {
    let fixture: ComponentFixture<BulkActionBarComponent>;
    const root = () => fixture.nativeElement as HTMLElement;

    function open(inputs: Record<string, unknown> = {}) {
        fixture = TestBed.createComponent(BulkActionBarComponent);
        fixture.componentRef.setInput('count', 7);
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        fixture.detectChanges();
        return fixture.componentInstance;
    }
    const buttons = () => Array.from(root().querySelectorAll('button')).map(button => button.textContent?.replace(/\s+/g, ' ').trim());

    beforeEach(() => TestBed.configureTestingModule({ imports: [BulkActionBarComponent] }));

    it('is a labelled region that states the count and the delete action with the count', () => {
        open();
        const region = root().querySelector('section')!;
        expect(region.getAttribute('role')).toBe('region');
        expect(region.getAttribute('aria-label')).toBe('Действия с выбранными');
        expect(root().querySelector('.count')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Выбрано 7 материалов');
        expect(buttons().some(text => text?.startsWith('Удалить выбранные · 7'))).toBe(true);
        expect(buttons()).toContain('Снять выбор');
    });

    it('hides «Упражнения с ИИ для выбранных» unless generation is available', () => {
        open();
        expect(buttons().join('|')).not.toContain('ИИ');
        fixture.componentRef.setInput('generationAvailable', true);
        fixture.detectChanges();
        expect(buttons().join('|')).toContain('Упражнения с ИИ для выбранных');
    });

    it('passes the consequence to the hold button and shows why deletion is unavailable', () => {
        open({ consequence: 'Удалит 7 материалов и 15 упражнений. История занятий сохранится.', deleteDisabled: true, hint: 'Считаем…' });
        const hold = root().querySelector<HTMLButtonElement>('app-hold-to-delete-button button')!;
        expect(hold.disabled).toBe(true);
        expect(hold.getAttribute('aria-describedby')).toBe(root().querySelector('.consequence')?.id);
        expect(root().querySelector('.consequence')?.textContent).toContain('15 упражнений');
        expect(root().querySelector('.hint')?.textContent).toBe('Считаем…');
    });

    it('reports clear, generate and a confirmed deletion', () => {
        const component = open({ generationAvailable: true });
        const seen: string[] = [];
        component.clear.subscribe(() => seen.push('clear'));
        component.generate.subscribe(() => seen.push('generate'));
        component.deleteConfirmed.subscribe(() => seen.push('delete'));
        Array.from(root().querySelectorAll('button')).find(button => button.textContent?.includes('Снять выбор'))!.click();
        Array.from(root().querySelectorAll('button')).find(button => button.textContent?.includes('ИИ'))!.click();
        root().querySelector('app-hold-to-delete-button')!.dispatchEvent(new Event('x'));
        expect(seen).toEqual(['clear', 'generate']);
        const hold = fixture.debugElement.query(element => element.name === 'app-hold-to-delete-button');
        hold.triggerEventHandler('confirmed', undefined);
        expect(seen).toEqual(['clear', 'generate', 'delete']);
    });
});
