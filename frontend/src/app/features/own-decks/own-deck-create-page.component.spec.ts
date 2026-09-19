import { WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { DeckMutationState, OwnDecksStore } from './own-decks.store';
import { OwnDeckCreatePageComponent } from './own-deck-create-page.component';
import { OwnDeckRecoveryService } from './own-deck-recovery.service';

describe('OwnDeckCreatePageComponent', () => {
    let fixture: ComponentFixture<OwnDeckCreatePageComponent>;
    let store: jasmine.SpyObj<OwnDecksStore>;
    let recovery: jasmine.SpyObj<OwnDeckRecoveryService>;
    let mutation: WritableSignal<DeckMutationState>;

    beforeEach(async () => {
        store = jasmine.createSpyObj<OwnDecksStore>('OwnDecksStore', [
            'startCreate', 'retryMutation', 'retryAsNewCommand', 'recoverMutation'
        ]);
        recovery = jasmine.createSpyObj<OwnDeckRecoveryService>('OwnDeckRecoveryService', ['restore', 'save', 'clear']);
        recovery.restore.and.returnValue(null);
        mutation = signal<DeckMutationState>({ phase: 'idle' });
        Object.defineProperty(store, 'mutationState', { value: mutation.asReadonly() });
        await TestBed.configureTestingModule({
            imports: [OwnDeckCreatePageComponent],
            providers: [provideRouter([]), { provide: OwnDeckRecoveryService, useValue: recovery }]
        }).overrideComponent(OwnDeckCreatePageComponent, {
            set: { providers: [{ provide: OwnDecksStore, useValue: store }] }
        }).compileComponents();
        fixture = TestBed.createComponent(OwnDeckCreatePageComponent);
        fixture.detectChanges();
    });

    it('focuses the invalid title and does not submit blank metadata', async () => {
        fixture.componentInstance.form.setValue({ title: '   ', description: '' });
        fixture.componentInstance.submit();
        fixture.detectChanges();
        await Promise.resolve();

        expect(store.startCreate).not.toHaveBeenCalled();
        expect(document.activeElement?.id).toBe('create-title');
    });

    it('submits exact whitespace and line breaks without normalizing metadata', () => {
        fixture.componentInstance.form.setValue({ title: '  Моя колода  ', description: ' первая\nвторая ' });
        fixture.componentInstance.submit();

        expect(store.startCreate).toHaveBeenCalledOnceWith({
            title: '  Моя колода  ', description: ' первая\nвторая '
        });
        expect(recovery.save).toHaveBeenCalledWith(
            { operation: 'create' },
            { title: '  Моя колода  ', description: ' первая\nвторая ' },
            null
        );
    });

    it('preserves a title newline entered through the real DOM control', () => {
        const title = (fixture.nativeElement as HTMLElement).querySelector<HTMLTextAreaElement>('#create-title');
        expect(title).not.toBeNull();
        title!.value = 'Строка первая\nСтрока вторая';
        title!.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText' }));

        expect(fixture.componentInstance.form.controls.title.value).toBe('Строка первая\nСтрока вторая');
    });

    it('locks an unknown-outcome draft and describes it without claiming non-creation', () => {
        mutation.set({
            phase: 'error',
            pending: {
                operation: 'create',
                command: {
                    commandId: '123e4567-e89b-42d3-a456-426614174000',
                    metadata: { title: 'Исходный ввод', description: '' }
                }
            },
            failure: { kind: 'network', status: 0, code: null },
            replayRefreshFailed: false,
            replayDeckId: null,
            conflictRefreshFailed: false
        });
        fixture.detectChanges();

        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector<HTMLTextAreaElement>('#create-title')?.readOnly).toBeTrue();
        expect(root.textContent).toContain('Создание не подтверждено');
        expect(root.textContent).not.toContain('Колода не создана');
    });
});
