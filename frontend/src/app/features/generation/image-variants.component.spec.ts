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
        fixture.componentInstance.chosen.subscribe(value => chosen.push(value));
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
        expect(images.map(image => image.getAttribute('alt'))).toEqual(['Красная лиса в снегу', 'Fox 2', 'Fox 3']);
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

    it('asks for the choice of another image once, and not for the one that is already chosen', async () => {
        await create();
        radios()[0]!.click();
        radios()[1]!.click();
        expect(chosen).toEqual(['ca0d0000-0000-4000-8000-000000000002']);
    });

    it('while a choice is sent: the group is aria-disabled (never disabled: focus stays), the picked card shows checked and a change is cancelled', async () => {
        await create({ selecting: 'ca0d0000-0000-4000-8000-000000000003' });
        expect(root().querySelector('fieldset')!.getAttribute('aria-disabled')).toBe('true');
        expect(root().querySelector('input[disabled], fieldset[disabled]')).toBeNull();
        expect(radios().map(radio => radio.checked)).toEqual([false, false, true]);
        expect(radios().every(radio => radio.getAttribute('aria-disabled') === 'true')).toBe(true);
        radios()[1]!.focus();
        radios()[1]!.click();
        expect(chosen).toEqual([]);
        expect(radios()[1]!.checked).toBe(false);
        expect(window.document.activeElement === radios()[1] || window.document.activeElement === window.document.body).toBe(true);
        fixture.componentRef.setInput('selecting', null);
        fixture.componentRef.setInput('busy', true);
        fixture.detectChanges();
        radios()[1]!.click();
        expect(chosen).toEqual([]);
    });

    it('puts a refused choice back where the server holds it and says why in a live region tied to the group', async () => {
        await create({ selecting: 'ca0d0000-0000-4000-8000-000000000002' });
        expect(radios().map(radio => radio.checked)).toEqual([false, true, false]);
        fixture.componentRef.setInput('selecting', null);
        fixture.componentRef.setInput('error', 'Материал обновился.');
        fixture.detectChanges();
        await fixture.whenStable();
        expect(radios().map(radio => radio.checked)).toEqual([true, false, false]);
        const error = root().querySelector<HTMLElement>('.error')!;
        expect(error.getAttribute('aria-live')).toBe('polite');
        expect(error.textContent).toBe('Материал обновился.');
        expect(root().querySelector('fieldset')!.getAttribute('aria-describedby')).toBe(error.id);
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
