import { FormBuilder } from '@angular/forms';
import { of } from 'rxjs';

import { I18nService } from '../../core/services/i18n.service';
import { ToastService } from '../../core/services/toast.service';
import { DeckProfileComponent } from './deck-profile.component';

describe('DeckProfileComponent', () => {
    let component: DeckProfileComponent;
    let deckApi: jasmine.SpyObj<any>;
    let reviewApi: jasmine.SpyObj<any>;
    let toast: jasmine.SpyObj<ToastService>;
    let i18n: I18nService;

    beforeEach(() => {
        localStorage.removeItem('mnema_language');

        deckApi = jasmine.createSpyObj('DeckApiService', ['patchDeck', 'syncDeck', 'syncDeckTemplate']);
        reviewApi = jasmine.createSpyObj('ReviewApiService', ['updateDeckAlgorithm', 'getDeckAlgorithm']);
        toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info', 'warning', 'show', 'dismiss']);
        i18n = new I18nService();

        component = new DeckProfileComponent(
            { snapshot: { paramMap: { get: () => '' } } } as any,
            jasmine.createSpyObj('Router', ['navigate']),
            deckApi,
            jasmine.createSpyObj('PublicDeckApiService', ['patchPublicDeck', 'getPublicDeck', 'deletePublicDeck']),
            jasmine.createSpyObj('TemplateApiService', ['getTemplate']),
            reviewApi,
            jasmine.createSpyObj('UserApiService', ['getMe']),
            new FormBuilder(),
            jasmine.createSpyObj('ImportApiService', ['createExportJob', 'getJob']),
            jasmine.createSpyObj('MediaApiService', ['resolve']),
            jasmine.createSpyObj('AiApiService', ['listJobs', 'getJob', 'getJobResult', 'cancelJob']),
            i18n,
            toast
        );
    });

    it('shows a success toast after saving deck changes', () => {
        component.userDeckId = 'deck-1';
        component.editForm = new FormBuilder().group({
            displayName: ['Updated deck'],
            displayDescription: [''],
            autoUpdate: [false],
            algorithmId: ['sm2']
        });
        component.originalAlgorithmId = 'sm2';
        component.showEditModal = true;

        deckApi.patchDeck.and.returnValue(of({
            userDeckId: 'deck-1',
            displayName: 'Updated deck'
        }));
        reviewApi.updateDeckAlgorithm.and.returnValue(of({}));

        component.saveEdit();

        expect(toast.success).toHaveBeenCalledWith('deckProfile.saveSuccess');
        expect(component.showEditModal).toBeFalse();
    });

    it('translates AI job statuses with the active language', () => {
        i18n.setLanguage('ru');

        expect(component.formatAiJobStatus('completed')).toBe('Завершено');
        expect(component.formatAiJobStatus('partial_success')).toBe('Частично завершено');
    });

    it('returns an empty label for missing AI job status', () => {
        expect(component.formatAiJobStatus(undefined)).toBe('');
    });

    it('interpolates translation params for retry labels', () => {
        expect(i18n.translate('deckProfile.aiJobsRetryFailed', { count: 3 })).toBe('Retry failed only (3)');
    });

    it('extracts quality gate and usage breakdown from structured AI results', () => {
        const result = {
            mode: 'import_generate',
            qualityGate: {
                auditedDrafts: 6,
                flaggedDrafts: 2,
                repairedDrafts: 2,
                finalFlaggedDrafts: 0,
                qualityScore: 94,
                model: 'qwen3:32b',
                finalItems: [
                    {
                        draftIndex: 1,
                        decision: 'repair',
                        summary: 'Mixed-language title',
                        issues: ['Translation should be fully Russian'],
                        focusFields: ['translation']
                    }
                ]
            },
            sourceCoverage: {
                sourceItemsTotal: 6,
                sourceItemsUsed: 6,
                alteredSourceItems: 1,
                missingSourceIndexes: [],
                missingNumberedItems: [5]
            },
            sourceNormalization: {
                extraction: 'ocr',
                reviewedItems: 6,
                normalizedItems: 2,
                model: 'qwen3:32b'
            },
            usage: {
                textGeneration: {
                    requests: 2,
                    inputTokens: 320,
                    outputTokens: 140,
                    calls: [
                        { cachedInputTokens: 40, reasoningOutputTokens: 12, durationMs: 1_500 },
                        { cachedInputTokens: 20, reasoningOutputTokens: 8, durationMs: 500 }
                    ]
                },
                draftAudit: {
                    requests: 1,
                    inputTokens: 120,
                    outputTokens: 48,
                    calls: [{ durationMs: 900 }]
                },
                sourceNormalization: {
                    requests: 1,
                    inputTokens: 30,
                    outputTokens: 12,
                    calls: [{ durationMs: 400 }]
                },
                tts: {
                    requests: 6,
                    charsGenerated: 240
                }
            }
        };

        expect(component.getAiResultQualityGate(result)?.qualityScore).toBe(94);
        expect(component.getAiResultSourceCoverage(result)?.missingNumberedItems).toEqual([5]);
        expect(component.getAiResultSourceNormalization(result)?.normalizedItems).toBe(2);
        expect(component.getAiQualityReviewItems(result)).toHaveSize(1);
        expect(component.getAiResultUsageStages(result).map(stage => stage.key)).toEqual(['textGeneration', 'sourceNormalization', 'draftAudit', 'tts']);
        expect(component.resolveUsageCachedTokens(component.getAiResultUsageStages(result)[0].summary)).toBe(60);
        expect(component.resolveUsageReasoningTokens(component.getAiResultUsageStages(result)[0].summary)).toBe(20);
        expect(component.resolveUsageDurationMs(component.getAiResultUsageStages(result)[0].summary)).toBe(2_000);
    });

    it('adds quality and source coverage metrics to structured AI summaries', () => {
        const metrics = component.summarizeAiResult({
            mode: 'generate_cards',
            createdCards: 4,
            qualityGate: {
                qualityScore: 88
            },
            sourceCoverage: {
                sourceItemsUsed: 4,
                sourceItemsTotal: 5
            }
        });

        expect(metrics).toContain(jasmine.objectContaining({ label: 'Created', value: '4' }));
        expect(metrics).toContain(jasmine.objectContaining({ label: 'Quality', value: '88/100' }));
        expect(metrics).toContain(jasmine.objectContaining({ label: 'Source used', value: '4/5' }));
    });
});
