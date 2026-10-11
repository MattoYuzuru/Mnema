import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { documentOf, nativeNode } from '../../../content/rendering/native-renderer.fixtures';
import { DECK_ID, PLAN_VERSION, exerciseSides, keyOf, materialSides, planUnit } from './update-test-data';
import { UnitSides } from './update-plan.models';
import { UpdateUnitSidesComponent } from './update-unit-sides.component';

const CODE = 'Kq7xT3mNpR';
const MINE_ASSET = '31901995-16ea-4f8b-8301-5d8e03004c72';
const AUTHOR_ASSET = '41901995-16ea-4f8b-8301-5d8e03004c72';

const imageDocument = (assetId: string, alt: string) => documentOf([nativeNode('image', { assetId, alt })]);
const ownerView = (assetId: string, url: string) => ({ assetId, state: 'READY', playback: { url, mimeType: 'image/webp', expiresAt: '2099-01-01T00:00:00Z' }, poster: null, download: null });
const publicView = (assetId: string, url: string) => ({ assetId, state: 'READY', playback: { url, mimeType: 'image/webp', expiresAt: '2099-01-01T00:00:00Z' }, poster: null });

describe('UpdateUnitSidesComponent', () => {
    let fixture: ComponentFixture<UpdateUnitSidesComponent>;
    let http: HttpTestingController;
    const root = () => fixture.nativeElement as HTMLElement;
    const column = (name: 'mine' | 'author') => root().querySelector<HTMLElement>(`[data-column=${name}]`)!;
    const unit = planUnit('MATERIAL', 1, 'CONFLICT');
    const ownerPath = `/api/media-assets/${MINE_ASSET}/playback`;
    const publicPath = (assetId: string, code = CODE) => `/api/public/decks/${code}/media/${assetId}`;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => { fixture?.destroy(); http.verify(); });

    async function show(sides: UnitSides, upstreamCode: string | null): Promise<void> {
        fixture = TestBed.createComponent(UpdateUnitSidesComponent);
        fixture.componentRef.setInput('sides', sides);
        fixture.componentRef.setInput('upstreamCode', upstreamCode);
        fixture.detectChanges();
        await fixture.whenStable();
    }

    /** The media surface reads its files asynchronously: let the answers through, then draw. */
    async function settle(): Promise<void> {
        await new Promise<void>(resolve => setTimeout(resolve, 0));
        await fixture.whenStable();
        fixture.detectChanges();
    }

    const materials = (mine = imageDocument(MINE_ASSET, 'Моя схема'), author = imageDocument(AUTHOR_ASSET, 'Схема автора')) => materialSides(unit, mine, author);

    it('reads the files of the reader\'s side through the owner\'s playback route and those of the author\'s side through the public route of the source', async () => {
        await show(materials(), CODE);
        http.expectOne(ownerPath).flush(ownerView(MINE_ASSET, 'https://storage.example/mine.webp'));
        http.expectOne(publicPath(AUTHOR_ASSET)).flush(publicView(AUTHOR_ASSET, 'https://storage.example/author.webp'));
        await settle();
        expect(column('mine').querySelector('img')!.getAttribute('src')).toBe('https://storage.example/mine.webp');
        expect(column('author').querySelector('img')!.getAttribute('src')).toBe('https://storage.example/author.webp');
    });

    it('never asks the owner\'s route for a file of the author, nor the public route for a file of the copy', async () => {
        await show(materials(imageDocument(MINE_ASSET, 'Общая'), imageDocument(MINE_ASSET, 'Общая')), CODE);
        // The same asset on both sides is read once by each side, each by its own route.
        http.expectOne(ownerPath).flush(ownerView(MINE_ASSET, 'https://storage.example/mine.webp'));
        http.expectOne(publicPath(MINE_ASSET)).flush(publicView(MINE_ASSET, 'https://storage.example/author.webp'));
        await settle();
        expect(column('mine').querySelector('img')!.getAttribute('src')).toBe('https://storage.example/mine.webp');
        expect(column('author').querySelector('img')!.getAttribute('src')).toBe('https://storage.example/author.webp');
    });

    it('keeps a file the public route cannot give as «Файл недоступен», without the owner\'s wording, and leaves the other side alone', async () => {
        await show(materials(), CODE);
        http.expectOne(ownerPath).flush(ownerView(MINE_ASSET, 'https://storage.example/mine.webp'));
        http.expectOne(publicPath(AUTHOR_ASSET)).flush({ code: 'RESOURCE_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
        await settle();
        expect(column('author').textContent).toContain('Файл недоступен: Схема автора');
        expect(column('author').textContent).not.toContain('аккаунту');
        expect(column('author').querySelector('img')).toBeNull();
        expect(column('mine').querySelector('img')).not.toBeNull();
    });

    it('says that a file of the reader\'s own side is unavailable in the owner\'s words when the owner\'s route refuses it', async () => {
        await show(materials(), CODE);
        http.expectOne(ownerPath).flush({ code: 'RESOURCE_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
        http.expectOne(publicPath(AUTHOR_ASSET)).flush(publicView(AUTHOR_ASSET, 'https://storage.example/author.webp'));
        await settle();
        expect(column('mine').textContent).toContain('Файл недоступен или не принадлежит этому аккаунту');
    });

    it('uses the public route of the code it is given, and a copy without a code shows the author\'s files as unavailable without asking', async () => {
        await show(materials(), 'ZyXwVuTs98');
        http.expectOne(ownerPath).flush(ownerView(MINE_ASSET, 'https://storage.example/mine.webp'));
        http.expectOne(publicPath(AUTHOR_ASSET, 'ZyXwVuTs98')).flush(publicView(AUTHOR_ASSET, 'https://storage.example/author.webp'));
        await settle();
        fixture.destroy();

        await show(materials(), null);
        http.expectOne(ownerPath).flush(ownerView(MINE_ASSET, 'https://storage.example/mine.webp'));
        await settle();
        http.expectNone(request => request.url.startsWith('/api/public/'));
        expect(column('author').querySelector('img')).toBeNull();
        expect(column('author').textContent).toContain('Схема автора');
    });

    it('draws a material without media with no request at all, and an exercise as plain lines', async () => {
        await show(materialSides(unit, documentOf([nativeNode('paragraph', {}, [nativeNode('text', { text: 'Текст', marks: [] })])]), null), CODE);
        http.expectNone(() => true);
        fixture.destroy();
        const exercise = planUnit('EXERCISE', 2, 'NEW');
        await show(exerciseSides(exercise), CODE);
        http.expectNone(() => true);
        expect(column('mine').textContent).toContain('Нет у вас');
        expect(column('author').textContent).toContain('Что значит hablar?');
        expect(DECK_ID).toHaveLength(36);
        expect(PLAN_VERSION).toContain('sha256:');
        expect(keyOf(2)).toHaveLength(36);
    });
});
