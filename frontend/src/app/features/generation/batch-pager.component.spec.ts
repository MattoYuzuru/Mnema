import { ComponentFixture, TestBed } from '@angular/core/testing';

import { BatchPagerComponent } from './batch-pager.component';
import { ArtifactSummary, parseArtifactSummary } from './generation.models';
import { artifactWith, ids } from './generation-test-data';

describe('BatchPagerComponent', () => {
    let fixture: ComponentFixture<BatchPagerComponent>;
    let picked: string[];
    const idOf = (position: number) => `a7a70000-0000-4000-8000-${String(position + 1).padStart(12, '0')}`;
    const states = ['PUBLISHED', 'PROPOSED', 'GENERATING', 'QUEUED', 'FAILED', 'REJECTED', 'STALE'];
    const batch = (): ArtifactSummary[] => states.map((state, position) => parseArtifactSummary(artifactWith(idOf(position), position, state)));
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const dots = (): HTMLButtonElement[] => [...root().querySelectorAll<HTMLButtonElement>('.dot')];
    const steps = (): HTMLButtonElement[] => [...root().querySelectorAll<HTMLButtonElement>('.step')];

    function create(selected: string | null = idOf(1), artifacts: ArtifactSummary[] = batch()): void {
        fixture = TestBed.createComponent(BatchPagerComponent);
        fixture.componentRef.setInput('artifacts', artifacts);
        fixture.componentRef.setInput('selectedId', selected);
        picked = [];
        // The page owns the selection: it follows what the pager reports.
        fixture.componentInstance.picked.subscribe(id => {
            picked.push(id);
            fixture.componentRef.setInput('selectedId', id);
            fixture.detectChanges();
        });
        fixture.detectChanges();
    }

    it('is a navigation landmark with one button per material, named by position and status in words', () => {
        create();
        expect(root().querySelector('nav')?.getAttribute('aria-label')).toBe('Материалы партии');
        expect(dots().map(dot => dot.getAttribute('aria-label'))).toEqual([
            'Материал 1 из 7, в колоде', 'Материал 2 из 7, готов', 'Материал 3 из 7, пишется', 'Материал 4 из 7, ждёт очереди',
            'Материал 5 из 7, не удался', 'Материал 6 из 7, отклонён', 'Материал 7 из 7, нужно решение']);
        expect(root().querySelector('.position')?.textContent).toBe('2 из 7');
    });

    it('marks only the current dot with aria-current="step" and makes it the one tab stop', () => {
        create();
        expect(dots().map(dot => dot.getAttribute('aria-current'))).toEqual([null, 'step', null, null, null, null, null]);
        expect(dots().map(dot => dot.tabIndex)).toEqual([-1, 0, -1, -1, -1, -1, -1]);
    });

    it('says the status with a shape and not only with colour: every status draws a different mark', () => {
        create();
        const marks = dots().map(dot => dot.querySelector('svg')!.innerHTML.replace(/\s+/g, ' '));
        expect(new Set(marks).size).toBe(7);
        expect(dots().map(dot => dot.dataset['status'])).toEqual(['done', 'ready', 'writing', 'queued', 'failed', 'rejected', 'stale']);
        expect(dots().every(dot => dot.querySelector('svg')?.getAttribute('aria-hidden') === 'true')).toBe(true);
    });

    it('moves with the arrow keys, Home and End, putting focus on the dot it chose and never leaving the batch', async () => {
        create();
        const press = async (key: string, from = 1) => {
            dots()[from]!.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
            await Promise.resolve();
        };
        document.body.append(root());
        await press('ArrowRight');
        await press('ArrowDown');
        await press('ArrowLeft');
        await press('ArrowUp');
        await press('Home');
        await press('End');
        expect(picked).toEqual([idOf(2), idOf(3), idOf(2), idOf(1), idOf(0), idOf(6)]);
        expect(document.activeElement).toBe(dots()[6]);
        await press('ArrowRight', 6);
        expect(picked).toHaveLength(6);
        await press('Home', 6);
        await press('ArrowLeft', 0);
        expect(picked).toHaveLength(7);
        expect(document.activeElement).toBe(dots()[0]);
        root().remove();
    });

    it('ignores other keys and keys with a modifier, and leaves them to the browser', () => {
        create();
        const other = new KeyboardEvent('keydown', { key: 'a', bubbles: true, cancelable: true });
        const modified = new KeyboardEvent('keydown', { key: 'ArrowRight', ctrlKey: true, bubbles: true, cancelable: true });
        dots()[1]!.dispatchEvent(other);
        dots()[1]!.dispatchEvent(modified);
        expect(other.defaultPrevented).toBe(false);
        expect(modified.defaultPrevented).toBe(false);
        expect(picked).toEqual([]);
    });

    it('selects with a click and has previous and next buttons that stop at the ends', () => {
        create();
        dots()[4]!.click();
        expect(picked).toEqual([idOf(4)]);
        steps()[0]!.click();
        steps()[1]!.click();
        expect(picked).toEqual([idOf(4), idOf(3), idOf(4)]);
        create(idOf(0));
        expect(steps()[0]!.getAttribute('aria-disabled')).toBe('true');
        create(idOf(6));
        expect(steps()[1]!.getAttribute('aria-disabled')).toBe('true');
        steps()[1]!.click();
        expect(picked).toEqual([]);
        expect(steps().map(step => step.getAttribute('aria-label'))).toEqual(['Предыдущий материал', 'Следующий материал']);
    });

    it('has one tab stop (the first dot) before anything is chosen, and counts the batch', () => {
        create(null);
        expect(dots().map(dot => dot.tabIndex)).toEqual([0, -1, -1, -1, -1, -1, -1]);
        expect(root().querySelector('.position')?.textContent).toBe('Всего: 7');
        expect(dots().every(dot => dot.getAttribute('aria-current') === null)).toBe(true);
    });

    it('does nothing for an empty batch', () => {
        create(null, []);
        const list = root().querySelector('.dots')!;
        list.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
        expect(picked).toEqual([]);
        expect(ids.deckId).toBeTruthy();
    });

    it('says «ищу источники» for a material whose research is under way (#299)', () => {
        create();
        fixture.componentRef.setInput('researching', new Set([idOf(3)]));
        fixture.detectChanges();
        expect(dots()[3]!.getAttribute('aria-label')).toBe('Материал 4 из 7, ищу источники');
        expect(dots()[2]!.getAttribute('aria-label')).toContain('пишется');
        expect(dots()[3]!.getAttribute('data-status')).toBe('writing');
    });
});
