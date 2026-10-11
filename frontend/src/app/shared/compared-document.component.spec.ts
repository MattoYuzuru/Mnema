import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { documentOf, nativeNode } from '../content/rendering/native-renderer.fixtures';
import { MediaPlaybackApi } from '../content/rendering/media-playback.api';
import { CompareColumnsComponent } from './compare-columns.component';

import { ComparedDocumentComponent } from './compared-document.component';
import { AUTHOR, MINE, OUTLINED, nodeId } from './update-test-data';

describe('ComparedDocumentComponent', () => {
    function render(side: 'mine' | 'author', withCounterpart = true): HTMLElement {
        const fixture = TestBed.createComponent(ComparedDocumentComponent);
        fixture.componentRef.setInput('document', side === 'mine' ? MINE : AUTHOR);
        fixture.componentRef.setInput('counterpart', withCounterpart ? (side === 'mine' ? AUTHOR : MINE) : null);
        fixture.componentRef.setInput('side', side);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('draws the author\'s version through the renderer with words added underlined and the new block stamped', () => {
        const host = render('author');
        expect(host.querySelector('app-native-document-renderer article.native-document')).not.toBeNull();
        const changed = host.querySelector(`[data-change='changed']`)!;
        expect(changed.querySelector('.stamp')?.textContent).toBe('Изменено');
        expect(changed.querySelector('ins')?.textContent).toContain('устойчивые');
        expect(changed.querySelector('del')).toBeNull();
        const added = host.querySelector(`[data-change='added']`)!;
        expect(added.querySelector('.stamp')?.textContent).toBe('Добавлено');
        expect(host.querySelector('hr')).not.toBeNull();
    });

    describe('where the media comes from', () => {
        const assetId = '31901995-16ea-4f8b-8301-5d8e03004c72';
        const picture = documentOf([nativeNode('image', { assetId, alt: 'Схема' })]);
        const ready = { assetId, state: 'READY', playback: { url: 'https://storage.example/ready.webp', mimeType: 'image/webp', expiresAt: '2099-01-01T00:00:00Z' }, poster: null, download: null };

        it('draws the files named in assetSources by default, and asks the playback API for nothing', () => {
            const read = vi.fn().mockName('MediaPlaybackApi.read');
            TestBed.configureTestingModule({ providers: [{ provide: MediaPlaybackApi, useValue: { read } }] });
            const fixture = TestBed.createComponent(ComparedDocumentComponent);
            fixture.componentRef.setInput('document', picture);
            fixture.componentRef.setInput('side', 'author');
            fixture.componentRef.setInput('assetSources', { [assetId]: 'https://storage.example/given.webp' });
            fixture.detectChanges();
            expect(fixture.nativeElement.querySelector('img').getAttribute('src')).toBe('https://storage.example/given.webp');
            expect(fixture.nativeElement.querySelector('app-native-media-surface')).toBeNull();
            expect(read).not.toHaveBeenCalled();
        });

        it('hands the document to the media surface when it is told whose files they are, and the surface reads them through the provided API', async () => {
            const read = vi.fn().mockName('MediaPlaybackApi.read').mockResolvedValue(ready);
            TestBed.configureTestingModule({ providers: [{ provide: MediaPlaybackApi, useValue: { read } }] });
            const fixture = TestBed.createComponent(ComparedDocumentComponent);
            fixture.componentRef.setInput('document', picture);
            fixture.componentRef.setInput('side', 'mine');
            fixture.componentRef.setInput('mediaAudience', 'owner');
            fixture.detectChanges();
            await new Promise<void>(resolve => setTimeout(resolve, 0));
            await fixture.whenStable();
            fixture.detectChanges();
            expect(read).toHaveBeenCalledWith(assetId);
            expect(fixture.nativeElement.querySelector('app-native-media-surface')).not.toBeNull();
            expect(fixture.nativeElement.querySelector('img').getAttribute('src')).toBe('https://storage.example/ready.webp');
        });

        it('keeps the marks and the stamps of a comparison when the media surface draws the document', () => {
            TestBed.configureTestingModule({ providers: [{ provide: MediaPlaybackApi, useValue: { read: vi.fn().mockResolvedValue(ready) } }] });
            const fixture = TestBed.createComponent(ComparedDocumentComponent);
            fixture.componentRef.setInput('document', AUTHOR);
            fixture.componentRef.setInput('counterpart', MINE);
            fixture.componentRef.setInput('side', 'author');
            fixture.componentRef.setInput('mediaAudience', 'public');
            fixture.detectChanges();
            const host = fixture.nativeElement as HTMLElement;
            expect(host.querySelector(`[data-change='changed'] ins`)?.textContent).toContain('устойчивые');
            expect(host.querySelector(`[data-change='added'] .stamp`)?.textContent).toBe('Добавлено');
            expect(host.querySelector<HTMLElement>(`[data-node-id='${nodeId(4)}']`)?.classList.contains('is-target')).toBe(true);
        });
    });

    it('keeps an untouched paragraph as the renderer draws it, marked by nothing', () => {
        const host = render('author');
        const untouched = [...host.querySelectorAll('p.native-paragraph')].find(node => node.textContent === 'Глагол estar описывает состояние и место.')!;
        expect(untouched).toBeTruthy();
        expect(untouched.classList.contains('is-target')).toBe(false);
        expect(host.querySelectorAll('.stamp')).toHaveLength(2);
    });

    it('shows the reader\'s version with the removed words struck through and the paragraph the author deleted stamped', () => {
        const host = render('mine');
        expect(host.querySelector(`[data-change='changed'] del`)?.textContent).toContain('постоянные');
        expect(host.querySelector(`[data-change='changed'] ins`)).toBeNull();
        expect(host.querySelector(`[data-change='removed'] .stamp`)?.textContent).toBe('Удалено автором');
        const removed = host.querySelector<HTMLElement>(`[data-node-id='${nodeId(3)}']`)!;
        expect(removed.classList.contains('is-target')).toBe(true);
    });

    it('marks a block that is not drawn as text by its node id through the overlay', () => {
        const host = render('author');
        expect(host.querySelector<HTMLElement>(`[data-node-id='${nodeId(4)}']`)?.classList.contains('is-target')).toBe(true);
    });

    it('draws the document plainly when the other side does not exist', () => {
        const host = render('author', false);
        expect(host.querySelectorAll('.stamp')).toHaveLength(0);
        expect(host.querySelector('ins, del')).toBeNull();
        expect(host.querySelectorAll('p.native-paragraph')).toHaveLength(2);
    });

    it('draws its own headings from the level below the document level 1 when it stands alone', () => {
        const fixture = TestBed.createComponent(ComparedDocumentComponent);
        fixture.componentRef.setInput('document', OUTLINED);
        fixture.componentRef.setInput('side', 'mine');
        fixture.detectChanges();
        expect(outline(fixture.nativeElement as HTMLElement)).toEqual([2, 3]);
    });
});

@Component({
    imports: [CompareColumnsComponent, ComparedDocumentComponent],
    template: `
      <app-compare-columns [headingLevel]="level()">
        <app-compared-document compare-mine side="mine" [document]="document" [counterpart]="document" />
        <app-compared-document compare-author side="author" [document]="document" [counterpart]="document" [headingOffset]="explicit()" />
      </app-compare-columns>`
})
class ColumnsHostComponent {
    readonly document = OUTLINED;
    readonly level = signal<3 | 4 | 5>(4);
    readonly explicit = signal<number | null>(null);
}

/** The levels of the headings of an element in reading order, native or `role=heading`. */
function outline(root: HTMLElement): number[] {
    return [...root.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6, [role=heading]')]
        .map(node => node.hasAttribute('aria-level') ? Number(node.getAttribute('aria-level')) : Number(node.tagName.slice(1)));
}

describe('ComparedDocumentComponent inside compare columns', () => {
    function create(level: 3 | 4 | 5) {
        const fixture = TestBed.createComponent(ColumnsHostComponent);
        fixture.componentInstance.level.set(level);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        return { fixture, root, column: (name: 'mine' | 'author') => root.querySelector<HTMLElement>(`.column[data-column='${name}']`)! };
    }

    it.each([[3, [3, 4, 5]], [4, [4, 5, 6]], [5, [5, 6, 7]]] as const)('under column headings at level %i the outline is %j in both columns, never jumping or repeating', (level, expected) => {
        const { column } = create(level);
        expect(outline(column('mine'))).toEqual([...expected]);
        expect(outline(column('author'))).toEqual([...expected]);
    });

    it('lets an explicit offset win', () => {
        const { fixture, column } = create(4);
        fixture.componentInstance.explicit.set(0);
        fixture.detectChanges();
        expect(outline(column('mine'))).toEqual([4, 5, 6]);
        expect(outline(column('author'))).toEqual([4, 2, 3]);
    });

    it('follows a change of the column heading level', () => {
        const { fixture, column } = create(3);
        fixture.componentInstance.level.set(5);
        fixture.detectChanges();
        expect(outline(column('mine'))).toEqual([5, 6, 7]);
    });
});
