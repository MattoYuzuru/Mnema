import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';

import contract from '../../../../../../contracts/moderation/complaints.json';
import { DeckModerationComponent } from './deck-moderation.component';
import { ModerationApiService, parseDeckModeration } from './moderation-api.service';

@Component({ imports: [DeckModerationComponent], template: `<app-deck-moderation [deckId]="deckId()" />` })
class HostComponent {
    readonly deckId = signal(contract.ownerNotice.answer.deckId);
}

describe('DeckModerationComponent', () => {
    let notice: ReturnType<typeof vi.fn>;
    let fragment: BehaviorSubject<string | null>;

    function render(): { fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>; root: HTMLElement } {
        const fixture = TestBed.createComponent(HostComponent);
        document.body.append(fixture.nativeElement as HTMLElement);
        fixture.detectChanges();
        return { fixture, root: fixture.nativeElement as HTMLElement };
    }

    beforeEach(() => {
        notice = vi.fn();
        fragment = new BehaviorSubject<string | null>(null);
        TestBed.configureTestingModule({ providers: [{ provide: ModerationApiService, useValue: { notice } }, { provide: ActivatedRoute, useValue: { fragment } }] });
    });
    afterEach(() => document.querySelectorAll('app-root, ng-component').forEach(element => element.remove()));

    it('draws nothing while the notice loads, for a clean deck and when the read fails', () => {
        const pending = new Subject<never>();
        notice.mockReturnValue(pending);
        const loading = render();
        expect(loading.root.querySelector('app-moderation-notice')).toBeNull();
        notice.mockReturnValue(of(parseDeckModeration(contract.ownerNotice.clean)));
        expect(render().root.querySelector('section')).toBeNull();
        notice.mockReturnValue(throwError(() => new Error('down')));
        expect(render().root.querySelector('section')).toBeNull();
    });

    it('shows the hidden deck and the counts of what was taken down', () => {
        notice.mockReturnValue(of(parseDeckModeration(contract.ownerNotice.answer)));
        const { root } = render();
        const text = root.textContent!.replace(/\s+/g, ' ');
        expect(notice).toHaveBeenCalledWith(contract.ownerNotice.answer.deckId);
        expect(text).toContain('Колода скрыта из Сообщества');
        expect(text).toContain('Материалы сняты по жалобе');
        expect(text).toContain('Снято: 2 материала и 1 упражнение.');
    });

    it('reads again for another deck and forgets the notice of the previous one', () => {
        notice.mockReturnValueOnce(of(parseDeckModeration(contract.ownerNotice.answer)));
        const { fixture, root } = render();
        expect(root.querySelector('section')).not.toBeNull();
        const other = { ...contract.ownerNotice.clean, deckId: '22222222-2222-4222-8222-222222222222' };
        notice.mockReturnValueOnce(of(parseDeckModeration(other)));
        fixture.componentInstance.deckId.set(other.deckId);
        fixture.detectChanges();
        expect(root.querySelector('section')).toBeNull();
        expect(notice).toHaveBeenLastCalledWith(other.deckId);
    });

    it('puts the focus on the «Модерация» heading when the link of the notification asked for it (#moderation), once', async () => {
        fragment.next('moderation');
        notice.mockReturnValue(of(parseDeckModeration(contract.ownerNotice.answer)));
        const { fixture, root } = render();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(document.activeElement).toBe(root.querySelector('h2'));
        (document.activeElement as HTMLElement).blur();
        fixture.detectChanges();
        expect(document.activeElement).not.toBe(root.querySelector('h2'));
    });

    it('leaves the focus alone without the fragment', async () => {
        notice.mockReturnValue(of(parseDeckModeration(contract.ownerNotice.answer)));
        const { fixture, root } = render();
        await fixture.whenStable();
        expect(document.activeElement).not.toBe(root.querySelector('h2'));
    });
});
