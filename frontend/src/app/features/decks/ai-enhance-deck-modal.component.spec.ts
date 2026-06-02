import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';

import { AiEnhanceDeckModalComponent } from './ai-enhance-deck-modal.component';
import { AiApiService } from '../../core/services/ai-api.service';
import { CardApiService } from '../../core/services/card-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { CreateAiJobRequest } from '../../core/models/ai.models';

describe('AiEnhanceDeckModalComponent', () => {
    let fixture: ComponentFixture<AiEnhanceDeckModalComponent>;
    let component: AiEnhanceDeckModalComponent;
    let aiApi: jasmine.SpyObj<AiApiService>;
    let cardApi: jasmine.SpyObj<CardApiService>;
    let templateApi: jasmine.SpyObj<TemplateApiService>;

    beforeEach(async () => {
        localStorage.removeItem('mnema_ai_enhance:deck-1');

        aiApi = jasmine.createSpyObj<AiApiService>('AiApiService', [
            'getRuntimeCapabilities',
            'listProviders',
            'preflightJob',
            'createJob'
        ]);
        cardApi = jasmine.createSpyObj<CardApiService>('CardApiService', [
            'getMissingFieldSummary',
            'resolveDuplicateGroups'
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
        aiApi.createJob.and.returnValue(of({
            jobId: 'job-1',
            requestId: 'request-1',
            deckId: 'deck-1',
            type: 'generic',
            status: 'queued',
            progress: 0,
            createdAt: ''
        }));
        templateApi.getTemplate.and.returnValue(of({
            templateId: 'template-1',
            ownerId: 'owner-1',
            name: 'Vocab',
            description: '',
            isPublic: false,
            createdAt: '',
            updatedAt: '',
            layout: { front: ['word'], back: ['translation', 'audio', 'image'] },
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
                    fieldId: 'field-audio',
                    templateId: 'template-1',
                    name: 'audio',
                    label: 'Audio',
                    fieldType: 'audio',
                    isRequired: false,
                    isOnFront: false,
                    orderIndex: 1
                },
                {
                    fieldId: 'field-image',
                    templateId: 'template-1',
                    name: 'image',
                    label: 'Image',
                    fieldType: 'image',
                    isRequired: false,
                    isOnFront: false,
                    orderIndex: 2
                }
            ]
        } as any));
        cardApi.getMissingFieldSummary.and.returnValue(of({
            fields: [
                { field: 'audio', missingCount: 2, sampleCards: [] },
                { field: 'image', missingCount: 2, sampleCards: [] }
            ],
            sampleLimit: 3
        } as any));
        cardApi.resolveDuplicateGroups.and.returnValue(of({} as any));

        await TestBed.configureTestingModule({
            imports: [AiEnhanceDeckModalComponent],
            providers: [
                { provide: AiApiService, useValue: aiApi },
                { provide: CardApiService, useValue: cardApi },
                { provide: TemplateApiService, useValue: templateApi },
                { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) }
            ]
        }).compileComponents();

        fixture = TestBed.createComponent(AiEnhanceDeckModalComponent);
        component = fixture.componentInstance;
        component.userDeckId = 'deck-1';
        component.templateId = 'template-1';
        fixture.detectChanges();
    });

    it('uses selected missing fields as the only media generation switches', () => {
        const mediaToggles = fixture.nativeElement.querySelectorAll('.media-toggles input');
        expect(mediaToggles.length).toBe(0);

        component.selectedOptions.set(new Set(['missing_fields']));
        component.selectedMissingFields.set(new Set(['audio']));
        component.fieldLimits.set({ audio: 2, image: 2 });
        component.ttsMappings.set([{ sourceField: 'word', targetField: 'audio' }]);

        const request = (component as unknown as { buildCreateJobRequest: (scope: 'local' | 'global') => CreateAiJobRequest }).buildCreateJobRequest('global');
        const params = request.params as Record<string, unknown>;

        expect(params['updateScope']).toBe('global');
        expect(params['fieldLimits']).toEqual([{ field: 'audio', limit: 2 }]);
        expect(params['tts']).toEqual(jasmine.objectContaining({ enabled: true }));
        expect(params['image']).toBeUndefined();
    });

    it('queues a global job immediately after scope selection', () => {
        component.canApplyGlobal = true;
        component.selectedOptions.set(new Set(['missing_fields']));
        component.selectedMissingFields.set(new Set(['audio']));
        component.fieldLimits.set({ audio: 2 });
        component.ttsMappings.set([{ sourceField: 'word', targetField: 'audio' }]);

        component.confirmScopeAndStart('global');

        expect(aiApi.preflightJob).not.toHaveBeenCalled();
        expect(aiApi.createJob).toHaveBeenCalled();
        const request = aiApi.createJob.calls.mostRecent().args[0];
        const params = request.params as Record<string, unknown>;
        expect(params['updateScope']).toBe('global');
        expect(params['tts']).toEqual(jasmine.objectContaining({ enabled: true }));
    });
});
