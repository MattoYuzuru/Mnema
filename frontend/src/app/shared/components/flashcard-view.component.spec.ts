import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { MediaApiService, ResolvedMedia } from '../../core/services/media-api.service';
import { CardTemplateDTO } from '../../core/models/template.models';
import { FlashcardViewComponent } from './flashcard-view.component';

describe('FlashcardViewComponent', () => {
    let fixture: ComponentFixture<FlashcardViewComponent>;
    let component: FlashcardViewComponent;
    let mediaApi: jasmine.SpyObj<MediaApiService>;

    const audioMediaId = '11111111-1111-1111-1111-111111111111';
    const audioUrl = 'https://cdn.mnema.test/audio.mp3';
    const imageMediaId = '22222222-2222-2222-2222-222222222222';
    const imageUrl = 'https://cdn.mnema.test/image.png';

    beforeEach(async () => {
        mediaApi = jasmine.createSpyObj<MediaApiService>('MediaApiService', ['resolve', 'toUrlMap']);
        mediaApi.resolve.and.callFake((mediaIds: string[]) =>
            of(mediaIds.map(resolvedMedia).filter((item): item is ResolvedMedia => item !== null))
        );
        mediaApi.toUrlMap.and.callFake((items: ResolvedMedia[]) =>
            Object.fromEntries(items.map(item => [item.mediaId, item.url]))
        );

        await TestBed.configureTestingModule({
            imports: [FlashcardViewComponent],
            providers: [{ provide: MediaApiService, useValue: mediaApi }]
        }).compileComponents();

        fixture = TestBed.createComponent(FlashcardViewComponent);
        component = fixture.componentInstance;
    });

    it('renders Anki cards from template layout when imported content has no per-card payload', async () => {
        component.template = template();
        component.content = {
            Word: '교통사고',
            Meaning: 'traffic accident'
        };
        component.side = 'front';

        await (component as any).refreshView();
        fixture.detectChanges();

        const host = fixture.nativeElement as HTMLElement;
        expect(host.querySelector('.anki-card')).not.toBeNull();
        expect(host.querySelector('.term')?.textContent).toContain('교통사고');
        expect(host.querySelector('style')?.textContent).toContain('.term');
    });

    it('shows media fields added after Anki import even when stored Anki html does not reference them', async () => {
        component.template = template();
        component.content = {
            Word: '교통사고',
            Meaning: 'traffic accident',
            Audio: { mediaId: audioMediaId, kind: 'audio' },
            _anki: {
                front: '<div class="term">교통사고</div>',
                back: '<div class="meaning">traffic accident</div>',
                css: '.card { color: black; }'
            }
        };
        component.side = 'back';

        await (component as any).refreshView();
        fixture.detectChanges();

        const host = fixture.nativeElement as HTMLElement;
        const audio = host.querySelector('.mn-anki-extra-field-audio audio') as HTMLAudioElement | null;
        expect(audio).not.toBeNull();
        expect(audio?.getAttribute('src')).toBe(audioUrl);
    });

    it('shows media fields when stored Anki html has blank media template slots', async () => {
        component.template = templateWithMediaSlots();
        component.content = {
            Word: '교통사고',
            Meaning: 'traffic accident',
            Audio: { mediaId: audioMediaId, kind: 'audio' },
            Image: { mediaId: imageMediaId, kind: 'image' },
            _anki: {
                front: '<div class="term">교통사고</div>',
                back: '<div class="meaning">traffic accident</div><div class="image"></div>',
                css: '.card { color: black; }'
            }
        };
        component.side = 'back';

        await (component as any).refreshView();
        fixture.detectChanges();

        const host = fixture.nativeElement as HTMLElement;
        const audio = host.querySelector('.mn-anki-extra-field-audio audio') as HTMLAudioElement | null;
        const image = host.querySelector('.mn-anki-extra-field-image img') as HTMLImageElement | null;
        expect(audio).not.toBeNull();
        expect(audio?.getAttribute('src')).toBe(audioUrl);
        expect(image).not.toBeNull();
        expect(image?.getAttribute('src')).toBe(imageUrl);
    });

    function template(): CardTemplateDTO {
        return {
            templateId: 'template-1',
            ownerId: 'user-1',
            name: 'Topik I',
            description: '',
            isPublic: false,
            createdAt: '2026-06-03T00:00:00Z',
            updatedAt: '2026-06-03T00:00:00Z',
            layout: {
                front: ['Word'],
                back: ['Meaning'],
                renderMode: 'anki',
                anki: {
                    frontTemplate: '<div class="term">{{Word}}</div>',
                    backTemplate: '{{FrontSide}}<div class="meaning">{{Meaning}}</div>',
                    css: '.card { color: black; } .term { font-size: 2rem; }'
                }
            },
            fields: [
                field('Word', 'Word', 'text', true, 0),
                field('Meaning', 'Meaning', 'text', false, 1),
                field('Audio', 'Audio', 'audio', false, 2)
            ]
        };
    }

    function templateWithMediaSlots(): CardTemplateDTO {
        return {
            ...template(),
            layout: {
                front: ['Word', 'Audio'],
                back: ['Meaning', 'Image', 'Audio'],
                renderMode: 'anki',
                anki: {
                    frontTemplate: '<div class="term">{{Word}}</div>{{Audio}}',
                    backTemplate: '{{FrontSide}}<div class="meaning">{{Meaning}}</div><div class="image">{{Image}}</div>{{Audio}}',
                    css: '.card { color: black; } .term { font-size: 2rem; }'
                }
            },
            fields: [
                field('Word', 'Word', 'text', true, 0),
                field('Meaning', 'Meaning', 'text', false, 1),
                field('Audio', 'Audio', 'audio', true, 2),
                field('Image', 'Image', 'image', false, 3)
            ]
        };
    }

    function field(name: string, label: string, fieldType: string, isOnFront: boolean, orderIndex: number) {
        return {
            fieldId: `field-${name}`,
            templateId: 'template-1',
            name,
            label,
            fieldType,
            isRequired: false,
            isOnFront,
            orderIndex
        };
    }

    function resolvedMedia(mediaId: string): ResolvedMedia | null {
        if (mediaId === audioMediaId) {
            return {
                mediaId: audioMediaId,
                kind: 'card_audio',
                url: audioUrl,
                mimeType: 'audio/mpeg',
                sizeBytes: 1000,
                expiresAt: '2026-06-03T01:00:00Z'
            };
        }
        if (mediaId === imageMediaId) {
            return {
                mediaId: imageMediaId,
                kind: 'card_image',
                url: imageUrl,
                mimeType: 'image/png',
                sizeBytes: 1000,
                expiresAt: '2026-06-03T01:00:00Z'
            };
        }
        return null;
    }
});
