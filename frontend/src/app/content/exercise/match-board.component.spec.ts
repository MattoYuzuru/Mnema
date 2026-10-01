import { TestBed } from '@angular/core/testing';

import { MEDIA_PLAYBACK_RESOLVER } from '../../features/study/media-playback-resolver';
import { fakePlayback } from '../../features/study/study-test-data';
import { LearnerMatchItem } from './exercise-content.models';
import { MatchBoardComponent, MatchPair, itemName } from './match-board.component';

describe('MatchBoardComponent', () => {
    const asset = (suffix: string) => `aaaaaaaa-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const left: LearnerMatchItem[] = [
        { itemId: '1e000000-0000-4000-8000-000000000001', blocks: [{ kind: 'TEXT', text: 'der Hund' }] },
        { itemId: '1e000000-0000-4000-8000-000000000002', blocks: [{ kind: 'AUDIO', assetId: asset('1'), transcriptAvailable: false }] }
    ];
    const right: LearnerMatchItem[] = [
        { itemId: '7e000000-0000-4000-8000-000000000002', blocks: [{ kind: 'VIDEO', assetId: asset('2'), transcriptAvailable: false }] },
        { itemId: '7e000000-0000-4000-8000-000000000001', blocks: [{ kind: 'IMAGE', assetId: asset('3'), alt: 'Собака' }] }
    ];

    beforeEach(() => TestBed.configureTestingModule({ providers: [{ provide: MEDIA_PLAYBACK_RESOLVER,
        useValue: { resolve: fakePlayback } }] }));

    function create() {
        const fixture = TestBed.createComponent(MatchBoardComponent);
        fixture.componentRef.setInput('left', left);
        fixture.componentRef.setInput('right', right);
        fixture.detectChanges();
        return fixture;
    }

    it('names items by text, image description or a neutral generated label', () => {
        expect(itemName(left[0].blocks, 'left', 0)).toBe('der Hund');
        expect(itemName(left[1].blocks, 'left', 1)).toBe('Аудио, слева 2');
        expect(itemName(right[0].blocks, 'right', 0)).toBe('Видео, справа 1');
        expect(itemName(right[1].blocks, 'right', 1)).toBe('Собака');
    });

    it('pairs left then right, toggles the left selection and guides when the order is wrong', () => {
        const fixture = create();
        const root = fixture.nativeElement as HTMLElement;
        const pairs: MatchPair[] = [];
        fixture.componentInstance.pairSelected.subscribe(pair => pairs.push(pair));
        const leftButtons = root.querySelectorAll<HTMLButtonElement>('button[data-side="left"]');
        const rightButtons = root.querySelectorAll<HTMLButtonElement>('button[data-side="right"]');
        expect(leftButtons[0].getAttribute('aria-label')).toBe('Выбрать: der Hund');
        expect(rightButtons[1].getAttribute('aria-label')).toBe('Выбрать: Собака');

        rightButtons[1].click(); fixture.detectChanges();
        expect(root.querySelector('[role="status"]')?.textContent).toContain('Сначала выберите элемент слева');
        leftButtons[0].click(); fixture.detectChanges();
        expect(leftButtons[0].getAttribute('aria-pressed')).toBe('true');
        leftButtons[0].click(); fixture.detectChanges();
        expect(leftButtons[0].getAttribute('aria-pressed')).toBe('false');
        leftButtons[0].click(); rightButtons[1].click();
        expect(pairs).toEqual([{ leftId: left[0].itemId, rightId: right[1].itemId }]);
    });

    it('blocks pairing while busy, shows confirmed pairs and returns focus to the next free item', async () => {
        const fixture = create();
        document.body.appendChild(fixture.nativeElement);
        try {
            const root = fixture.nativeElement as HTMLElement;
            fixture.componentRef.setInput('busy', true); fixture.detectChanges();
            expect(root.querySelector('[role="status"]')?.textContent).toContain('Проверяем пару');
            root.querySelector<HTMLButtonElement>('button[data-side="left"]')!.click(); fixture.detectChanges();
            expect(fixture.componentInstance.selectedLeft()).toBeNull();
            fixture.componentRef.setInput('busy', false); fixture.detectChanges();

            fixture.componentInstance.selectedLeft.set(left[0].itemId);
            fixture.componentRef.setInput('matches', { [left[0].itemId]: right[1].itemId }); fixture.detectChanges();
            await fixture.whenStable();
            const lefts = root.querySelectorAll<HTMLButtonElement>('button[data-side="left"]');
            const rights = root.querySelectorAll<HTMLButtonElement>('button[data-side="right"]');
            expect(lefts[0].disabled).toBeTrue();
            expect(rights[1].disabled).toBeTrue();
            expect(lefts[0].textContent?.trim()).toBe('Пара 1 найдена');
            expect(rights[1].getAttribute('aria-label')).toBe('Собака: пара 1 найдена');
            expect(document.activeElement).toBe(lefts[1]);
            expect(root.querySelector('[role="status"]')?.textContent).toContain('Пара найдена');
        } finally { fixture.nativeElement.remove(); }
    });

    it('highlights the wrong pair on both sides', () => {
        const fixture = create();
        fixture.componentRef.setInput('wrongPair', { leftId: left[1].itemId, rightId: right[0].itemId });
        fixture.componentInstance.selectedLeft.set(left[1].itemId);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelectorAll('.is-wrong').length).toBe(2);
        expect(root.querySelector('[role="status"]')?.textContent).toContain('Эта пара не подходит');
    });
});
