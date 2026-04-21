import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { ImportDeckModalComponent } from './import-deck-modal.component';
import { ImportApiService } from '../../core/services/import-api.service';
import { I18nService } from '../../core/services/i18n.service';
import { ReviewApiService } from '../../core/services/review-api.service';
import { ImportJobResponse, ImportPreviewResponse, UploadImportSourceResponse } from '../../core/models/import.models';

describe('ImportDeckModalComponent', () => {
    let component: ImportDeckModalComponent;
    let fixture: ComponentFixture<ImportDeckModalComponent>;
    let importApi: jasmine.SpyObj<ImportApiService>;
    let reviewApi: jasmine.SpyObj<ReviewApiService>;

    beforeEach(() => {
        importApi = jasmine.createSpyObj<ImportApiService>('ImportApiService', [
            'uploadSource',
            'preview',
            'createImportJob',
            'createExportJob',
            'getJob'
        ]);
        reviewApi = jasmine.createSpyObj<ReviewApiService>('ReviewApiService', [
            'getDeckAlgorithm',
            'updateDeckAlgorithm'
        ]);

        component = new ImportDeckModalComponent(importApi, reviewApi);
    });

    it('applies the reusable custom scrollbar class to the modal body', async () => {
        await TestBed.configureTestingModule({
            imports: [ImportDeckModalComponent],
            providers: [
                { provide: ImportApiService, useValue: importApi },
                { provide: ReviewApiService, useValue: reviewApi },
                I18nService
            ]
        }).compileComponents();

        fixture = TestBed.createComponent(ImportDeckModalComponent);
        fixture.detectChanges();

        const modalBody = fixture.nativeElement.querySelector('.modal-body') as HTMLDivElement | null;

        expect(modalBody).not.toBeNull();
        expect(modalBody?.classList.contains('mn-scrollbar')).toBeTrue();
    });

    it('initializes editable source field types from import preview', () => {
        const fileInfo: UploadImportSourceResponse = {
            mediaId: 'media-1',
            fileName: 'deck.csv',
            sizeBytes: 24,
            sourceType: 'csv'
        };
        const preview: ImportPreviewResponse = {
            sourceFields: [
                { name: 'Question', fieldType: 'text' },
                { name: 'Image', fieldType: 'image' }
            ],
            targetFields: [],
            suggestedMapping: {},
            sample: [{ Question: 'What is Mnema?', Image: 'cover.png' }]
        };

        component.fileInfo = fileInfo;
        importApi.preview.and.returnValue(of(preview));

        (component as any).loadPreview();

        expect(component.sourceFieldTypes).toEqual({
            Question: 'text',
            Image: 'image'
        });
        expect(component.sourceFieldActive).toEqual({
            Question: true,
            Image: true
        });
    });

    it('sends source field type overrides for active fields on create import', () => {
        const job: ImportJobResponse = {
            jobId: 'job-1',
            jobType: 'import_job',
            status: 'queued',
            sourceType: 'csv',
            mode: 'create_new'
        };

        component.mode = 'create';
        component.fileInfo = {
            mediaId: 'media-1',
            fileName: 'deck.csv',
            sizeBytes: 24,
            sourceType: 'csv'
        };
        component.preview = {
            sourceFields: [
                { name: 'Question', fieldType: 'text' },
                { name: 'Answer', fieldType: 'text' }
            ],
            targetFields: [],
            suggestedMapping: {},
            sample: [{ Question: 'Q', Answer: 'A' }]
        };
        component.deckName = 'Imported deck';
        component.sourceFieldActive = { Question: true, Answer: false };
        component.sourceFieldTypes = { Question: 'markdown', Answer: 'text' };

        importApi.createImportJob.and.returnValue(of(job));
        spyOn(component as any, 'startPolling');

        component.startImport();

        expect(importApi.createImportJob).toHaveBeenCalledWith(jasmine.objectContaining({
            fieldMapping: { Question: 'Question' },
            sourceFieldTypes: { Question: 'markdown' }
        }));
    });
});
