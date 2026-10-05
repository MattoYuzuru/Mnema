import { ComponentFixture, TestBed } from '@angular/core/testing';

import { MediaPlaybackApi } from '../../content/rendering/media-playback.api';
import { ImageCandidate, parseArtifactDetail } from './generation.models';
import { ImageVariantsComponent } from './image-variants.component';
import { clone, examples } from './generation-test-data';

const candidatesOf = (change: (candidates: any[]) => void = () => undefined): ImageCandidate[] => {
    const detail = clone(examples['artifactDetailItem']);
    const slot = clone(examples['mediaSlotImageSearch']);
    change(slot.candidates);
    detail['mediaSlots'] = [slot];
    return [...parseArtifactDetail(detail).mediaSlots[0]!.candidates];
};

describe('ImageVariantsComponent', () => {
    let fixture: ComponentFixture<ImageVariantsComponent>;
    let chosen: string[];
    let read: ReturnType<typeof vi.fn>;
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const radios = (): HTMLInputElement[] => [...root().querySelectorAll<HTMLInputElement>('input[type="radio"]')];
    const cards = (): HTMLElement[] => [...root().querySelectorAll<HTMLElement>('li.card')];

    async function create(inputs: Record<string, unknown> = {}): Promise<void> {
        TestBed.resetTestingModule();
        read = vi.fn((assetId: string) => Promise.resolve({ assetId, state: 'READY', poster: null, download: null,
            playback: { url: `https://media.example/${assetId}.jpg`, expiresAt: '2030-01-01T00:00:00Z', mimeType: 'image/jpeg' } }));
        TestBed.configureTestingModule({ providers: [{ provide: MediaPlaybackApi, useValue: { read } }] });
        fixture = TestBed.createComponent(ImageVariantsComponent);
        chosen = [];
        fixture.componentInstance.commit.subscribe(value => chosen.push(value));
        fixture.componentRef.setInput('candidates', candidatesOf());
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
    }

    it('is a native radio group named «Варианты»: one radio per READY candidate, the chosen one checked, the picture inside the label', async () => {
        await create();
        const set = root().querySelector('fieldset')!;
        expect(set.querySelector('legend')!.textContent).toBe('Варианты');
        expect(radios()).toHaveLength(3);
        expect(new Set(radios().map(radio => radio.name)).size).toBe(1);
        expect(radios().map(radio => radio.checked)).toEqual([true, false, false]);
        for (const radio of radios()) {
            expect(radio.closest('label')!.querySelector('app-image-candidate-thumb')).not.toBeNull();
        }
        expect(cards().map(card => card.querySelector('.card-source')!.textContent)).toEqual(['Pixabay · Ann', 'Wikimedia Commons · Jörg Hempel', 'Wikimedia Commons']);
        expect(cards().map(card => card.querySelector('.card-license')!.firstChild!.textContent)).toEqual(['Pixabay Content License', 'CC BY-SA 4.0', 'CC0 1.0']);
        expect(cards()[0]!.querySelector('.card-chosen')!.textContent).toBe('Выбрано');
        expect(cards()[1]!.querySelector('.card-chosen')).toBeNull();
    });

    it('draws a candidate through the owner-scoped asset API, never a link to the stock site', async () => {
        await create();
        expect(read.mock.calls.map(call => call[0]).sort()).toEqual(['00000000-0000-4000-a000-000000000011', '00000000-0000-4000-a000-000000000012',
            '00000000-0000-4000-a000-000000000013']);
        const images = [...root().querySelectorAll('img')];
        expect(images.map(image => image.getAttribute('src'))).toEqual(
            ['00000000-0000-4000-a000-000000000011', '00000000-0000-4000-a000-000000000012', '00000000-0000-4000-a000-000000000013']
                .map(id => `https://media.example/${id}.jpg`));
        // The byline names the card; a picture inside the label would only repeat it.
        expect(images.map(image => image.getAttribute('alt'))).toEqual(['', '', '']);
        expect(root().innerHTML).not.toMatch(/pixabay\.com\/[^"]*\.(jpg|png)/u);
    });

    it('marks a share-alike license with «BY-SA» and a toggletip that says what it asks of the owner, and nothing for the others', async () => {
        await create();
        expect([...root().querySelectorAll('.badge')].map(badge => badge.textContent)).toEqual(['BY-SA']);
        expect(cards()[1]!.querySelector('.badge')).not.toBeNull();
        const tips = root().querySelectorAll('app-toggletip');
        expect(tips).toHaveLength(1);
        expect(cards()[1]!.contains(tips[0]!)).toBe(true);
        expect(tips[0]!.querySelector('.bubble')!.textContent).toBe('Если вы измените изображение, распространяйте его на тех же условиях.');
        expect(tips[0]!.querySelector('button')!.getAttribute('aria-label')).toBe('Подробнее: BY-SA');
    });

    it('links to the page of the image in a new tab with noopener, outside the label, and never renders a link that is not https', async () => {
        await create({ candidates: candidatesOf(list => { list[2].sourcePageUrl = 'http://commons.wikimedia.org/wiki/File:Fox_3.jpg'; }) });
        const links = [...root().querySelectorAll<HTMLAnchorElement>('a')];
        expect(links.map(link => link.getAttribute('href'))).toEqual(['https://pixabay.com/photos/fox-1/', 'https://commons.wikimedia.org/wiki/File:Fox_2.jpg']);
        for (const link of links) {
            expect(link.getAttribute('target')).toBe('_blank');
            expect(link.getAttribute('rel')).toBe('noopener noreferrer');
            expect(link.textContent).toContain('Страница изображения');
            expect(link.closest('label')).toBeNull();
        }
        expect(cards()[2]!.querySelector('a')).toBeNull();
        expect(root().innerHTML).not.toContain('http://');
    });

    it('offers only the READY candidates, at most twelve', async () => {
        await create({ candidates: candidatesOf(list => { list[1].state = 'VERIFYING'; list[2].state = 'FAILED'; }) });
        expect(radios()).toHaveLength(1);
        const many = Array.from({ length: 12 }, (_, index) => ({ ...candidatesOf()[1]!, candidateId: `ca0d0000-0000-4000-8000-0000000001${String(index).padStart(2, '0')}`, chosen: false }));
        await create({ candidates: many });
        expect(radios()).toHaveLength(12);
    });

    const button = (): HTMLButtonElement => root().querySelector<HTMLButtonElement>('.commit-button')!;

    it('moves only a local choice with the arrow keys and clicks: no request until «Использовать это изображение»', async () => {
        await create();
        expect(button().textContent!.trim()).toBe('Использовать это изображение');
        expect(button().getAttribute('aria-disabled')).toBe('true');
        button().click();
        radios()[1]!.click();
        radios()[2]!.click();
        fixture.detectChanges();
        expect(chosen).toEqual([]);
        expect(radios().map(radio => radio.checked)).toEqual([false, false, true]);
        expect(button().getAttribute('aria-disabled')).toBeNull();
        button().click();
        expect(chosen).toEqual(['ca0d0000-0000-4000-8000-000000000003']);
    });

    it('keeps the button disabled while the local choice is the chosen one again', async () => {
        await create();
        radios()[1]!.click();
        fixture.detectChanges();
        radios()[0]!.click();
        fixture.detectChanges();
        expect(button().getAttribute('aria-disabled')).toBe('true');
        button().click();
        expect(chosen).toEqual([]);
    });

    it('while the choice is saved: says «Сохраняю выбор…» in a status inside the group, aria-disabled (never disabled), and ignores clicks', async () => {
        await create({ selecting: 'ca0d0000-0000-4000-8000-000000000003' });
        const set = root().querySelector('fieldset')!;
        expect(set.getAttribute('aria-disabled')).toBe('true');
        expect(set.querySelector('[role="status"]')!.textContent).toBe('Сохраняю выбор…');
        expect(root().querySelector('input[disabled], fieldset[disabled]')).toBeNull();
        expect(radios().every(radio => radio.getAttribute('aria-disabled') === 'true')).toBe(true);
        expect(button().getAttribute('aria-disabled')).toBe('true');
        radios()[1]!.click();
        button().click();
        expect(chosen).toEqual([]);
        expect(radios()[1]!.checked).toBe(false);
        fixture.componentRef.setInput('selecting', null);
        fixture.componentRef.setInput('busy', true);
        fixture.detectChanges();
        expect(set.querySelector('[role="status"]')!.textContent).toBe('');
        radios()[1]!.click();
        expect(radios()[1]!.checked).toBe(false);
    });

    it('keeps the pick after a refusal and says why in a live region tied to the group; a new answer resets it to what the server holds', async () => {
        await create();
        radios()[1]!.click();
        fixture.componentRef.setInput('error', 'Материал обновился.');
        fixture.detectChanges();
        expect(radios().map(radio => radio.checked)).toEqual([false, true, false]);
        const error = root().querySelector<HTMLElement>('.error')!;
        expect(error.getAttribute('aria-live')).toBe('polite');
        expect(error.textContent).toBe('Материал обновился.');
        expect(root().querySelector('fieldset')!.getAttribute('aria-describedby')).toBe(error.id);
        fixture.componentRef.setInput('candidates', candidatesOf(list => { list[0].chosen = false; list[2].chosen = true; }));
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(radios().map(radio => radio.checked)).toEqual([false, false, true]);
    });

    it('gives each radio a quiet name: «Вариант N из M», the byline and the license; the mark «Выбрано» and the loading note are for the eyes only', async () => {
        await create();
        const labels = cards().map(card => card.querySelector('label')!);
        expect(labels.map(label => label.querySelector('.sr-only')!.textContent)).toEqual(['Вариант 1 из 3. ', 'Вариант 2 из 3. ', 'Вариант 3 из 3. ']);
        expect(cards()[0]!.querySelector('.card-chosen')!.getAttribute('aria-hidden')).toBe('true');
        expect(root().querySelectorAll('.thumb-note[aria-hidden="true"]').length).toBe(root().querySelectorAll('.thumb-note').length);
    });

    it('names each link to a page by its card, so a list of links is not three times «Страница изображения»', async () => {
        await create();
        expect([...root().querySelectorAll('a')].map(link => link.getAttribute('aria-label'))).toEqual([
            'Страница изображения: Pixabay · Ann (в новой вкладке)', 'Страница изображения: Wikimedia Commons · Jörg Hempel (в новой вкладке)',
            'Страница изображения: Wikimedia Commons (в новой вкладке)']);
    });

    it('re-reads a picture that failed to draw once, then says so in words', async () => {
        await create({ candidates: candidatesOf().slice(0, 1) });
        const image = root().querySelector('img')!;
        image.dispatchEvent(new Event('error'));
        await fixture.whenStable();
        expect(read).toHaveBeenCalledTimes(2);
        root().querySelector('img')!.dispatchEvent(new Event('error'));
        fixture.detectChanges();
        expect(root().querySelector('img')).toBeNull();
        expect(root().querySelector('.thumb-note')!.textContent).toBe('Не удалось показать изображение.');
    });

    it('says in words when a file is not ready, rejected or cannot be read', async () => {
        TestBed.resetTestingModule();
        const answers = [{ assetId: 'a', state: 'VERIFYING', playback: null }, { assetId: 'b', state: 'REJECTED', playback: null }];
        const api = { read: vi.fn().mockResolvedValueOnce(answers[0]).mockResolvedValueOnce(answers[1]).mockRejectedValueOnce(new Error('offline')) };
        TestBed.configureTestingModule({ providers: [{ provide: MediaPlaybackApi, useValue: api }] });
        fixture = TestBed.createComponent(ImageVariantsComponent);
        fixture.componentRef.setInput('candidates', candidatesOf());
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        expect([...root().querySelectorAll('.thumb-note')].map(note => note.textContent)).toEqual(['Проверяем файл…', 'Изображение недоступно.', 'Не удалось загрузить изображение.']);
    });
});
