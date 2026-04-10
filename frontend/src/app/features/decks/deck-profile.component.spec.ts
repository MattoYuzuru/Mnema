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
});
