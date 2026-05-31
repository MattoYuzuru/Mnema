import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { AiEnhanceCardModalComponent } from './ai-enhance-card-modal.component';
import { AiApiService } from '../../core/services/ai-api.service';
import { CardApiService } from '../../core/services/card-api.service';
import { I18nService } from '../../core/services/i18n.service';
import { CardTemplateDTO } from '../../core/models/template.models';
import { UserCardDTO } from '../../core/models/user-card.models';
import { CreateAiJobRequest } from '../../core/models/ai.models';

describe('AiEnhanceCardModalComponent', () => {
    let fixture: ComponentFixture<AiEnhanceCardModalComponent>;
    let component: AiEnhanceCardModalComponent;
    let aiApi: jasmine.SpyObj<AiApiService>;
    let cardApi: jasmine.SpyObj<CardApiService>;

    const template: CardTemplateDTO = {
        templateId: 'template-1',
        ownerId: 'owner-1',
        name: 'Vocab',
        description: '',
        isPublic: true,
        createdAt: '',
        updatedAt: '',
        layout: { front: ['front'], back: ['image'] },
        fields: [
            {
                fieldId: 'field-front',
                templateId: 'template-1',
                name: 'front',
                label: 'Front',
                fieldType: 'text',
                isRequired: true,
                isOnFront: true,
                orderIndex: 0
            },
            {
                fieldId: 'field-image',
                templateId: 'template-1',
                name: 'image',
                label: 'Image',
                fieldType: 'image',
                isRequired: false,
                isOnFront: false,
                orderIndex: 1
            }
        ]
    };

    const card: UserCardDTO = {
        userCardId: 'card-1',
        publicCardId: 'public-card-1',
        isCustom: true,
        isDeleted: false,
        effectiveContent: {
            front: 'sharp courtroom vocabulary visual',
            image: ''
        }
    };

    beforeEach(async () => {
        aiApi = jasmine.createSpyObj<AiApiService>('AiApiService', [
            'getRuntimeCapabilities',
            'listProviders',
            'preflightJob',
            'createJob',
            'getJob',
            'getJobResult'
        ]);
        cardApi = jasmine.createSpyObj<CardApiService>('CardApiService', ['getUserCard']);
        aiApi.getRuntimeCapabilities.and.returnValue(of({
            mode: 'user_keys',
            providers: [
                {
                    key: 'openai',
                    displayName: 'OpenAI',
                    requiresCredential: true,
                    text: true,
                    stt: true,
                    tts: true,
                    image: true,
                    video: true,
                    gif: false
                }
            ]
        }));
        aiApi.listProviders.and.returnValue(of([
            {
                id: 'credential-1',
                provider: 'openai',
                alias: 'Main',
                status: 'active',
                createdAt: ''
            }
        ]));

        await TestBed.configureTestingModule({
            imports: [AiEnhanceCardModalComponent],
            providers: [
                { provide: AiApiService, useValue: aiApi },
                { provide: CardApiService, useValue: cardApi },
                I18nService
            ]
        }).compileComponents();

        fixture = TestBed.createComponent(AiEnhanceCardModalComponent);
        component = fixture.componentInstance;
        component.userDeckId = 'deck-1';
        component.deckName = 'Suits TV series Vocab';
        component.card = card;
        component.template = template;
        fixture.detectChanges();
    });

    it('uses reusable scrollbar and liquid glass checkbox styles in the modal', () => {
        const modalBody = fixture.nativeElement.querySelector('.modal-body') as HTMLDivElement | null;
        const checkboxLabels = fixture.nativeElement.querySelectorAll('.missing-toggles .glass-checkbox');

        expect(modalBody?.classList.contains('mn-scrollbar')).toBeTrue();
        expect(checkboxLabels.length).toBeGreaterThan(0);
    });

    it('sends explicit image source and target mapping for missing media fields', () => {
        const request = (component as unknown as { buildFillMissingRequest: () => CreateAiJobRequest }).buildFillMissingRequest();
        const params = request.params as Record<string, unknown>;
        const image = params['image'] as Record<string, unknown>;

        expect(params['fields']).toEqual(['image']);
        expect(image['mappings']).toEqual([
            { sourceField: 'front', targetField: 'image' }
        ]);
    });
});
