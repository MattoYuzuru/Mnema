import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { AiAddCardsModalComponent } from './ai-add-cards-modal.component';
import { AiApiService } from '../../core/services/ai-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { I18nService } from '../../core/services/i18n.service';
import { CreateAiJobRequest } from '../../core/models/ai.models';

describe('AiAddCardsModalComponent', () => {
    let fixture: ComponentFixture<AiAddCardsModalComponent>;
    let component: AiAddCardsModalComponent;
    let aiApi: jasmine.SpyObj<AiApiService>;
    let templateApi: jasmine.SpyObj<TemplateApiService>;

    beforeEach(async () => {
        localStorage.removeItem('mnema_ai_add_cards:deck-1');

        aiApi = jasmine.createSpyObj<AiApiService>('AiApiService', [
            'getRuntimeCapabilities',
            'listProviders',
            'preflightJob',
            'createJob'
        ]);
        templateApi = jasmine.createSpyObj<TemplateApiService>('TemplateApiService', ['getTemplate']);

        aiApi.getRuntimeCapabilities.and.returnValue(of({
            mode: 'user_keys',
            providers: [{
                key: 'openai',
                displayName: 'OpenAI',
                requiresCredential: true,
                text: true,
                stt: true,
                tts: true,
                image: true,
                video: true,
                gif: false
            }]
        }));
        aiApi.listProviders.and.returnValue(of([{
            id: 'credential-1',
            provider: 'openai',
            alias: 'Main',
            status: 'active',
            createdAt: ''
        }]));
        templateApi.getTemplate.and.returnValue(of({
            templateId: 'template-1',
            ownerId: 'owner-1',
            name: 'Vocab',
            description: '',
            isPublic: false,
            createdAt: '',
            updatedAt: '',
            layout: { front: ['word'], back: ['translation', 'audio'] },
            fields: [
                {
                    fieldId: 'field-word',
                    templateId: 'template-1',
                    name: 'word',
                    label: 'Word',
                    fieldType: 'text',
                    isRequired: true,
                    isOnFront: true,
                    orderIndex: 0
                },
                {
                    fieldId: 'field-translation',
                    templateId: 'template-1',
                    name: 'translation',
                    label: 'Translation',
                    fieldType: 'text',
                    isRequired: true,
                    isOnFront: false,
                    orderIndex: 1
                },
                {
                    fieldId: 'field-audio',
                    templateId: 'template-1',
                    name: 'audio',
                    label: 'Audio',
                    fieldType: 'audio',
                    isRequired: false,
                    isOnFront: false,
                    orderIndex: 2
                }
            ]
        } as any));

        await TestBed.configureTestingModule({
            imports: [AiAddCardsModalComponent],
            providers: [
                { provide: AiApiService, useValue: aiApi },
                { provide: TemplateApiService, useValue: templateApi },
                I18nService
            ]
        }).compileComponents();

        fixture = TestBed.createComponent(AiAddCardsModalComponent);
        component = fixture.componentInstance;
        component.userDeckId = 'deck-1';
        component.templateId = 'template-1';
        fixture.detectChanges();
    });

    it('keeps TTS enabled without requiring an explicit TTS model', () => {
        component.selectedFields.set(new Set(['word', 'translation', 'audio']));
        component.prompt.set('Spanish beginner verbs');
        component.ttsEnabled.set(true);
        component.ttsModel.set('');
        component.ttsMappings.set([{ sourceField: 'word', targetField: 'audio' }]);

        const request = (component as unknown as { buildCreateJobRequest: () => CreateAiJobRequest }).buildCreateJobRequest();
        const params = request.params as Record<string, unknown>;
        const tts = params['tts'] as Record<string, unknown>;

        expect(tts['enabled']).toBeTrue();
        expect(tts['model']).toBeUndefined();
        expect(tts['mappings']).toEqual([{ sourceField: 'word', targetField: 'audio' }]);
    });
});
