import { of } from 'rxjs';

import { I18nService } from '../../core/services/i18n.service';
import { VisualTemplateBuilderComponent } from './visual-template-builder.component';

describe('VisualTemplateBuilderComponent', () => {
    let component: VisualTemplateBuilderComponent;
    let templateApi: jasmine.SpyObj<any>;
    let router: jasmine.SpyObj<any>;
    let wizardState: jasmine.SpyObj<any>;

    beforeEach(() => {
        localStorage.removeItem('mnema_visual_builder_draft');
        localStorage.removeItem('mnema_language');

        templateApi = jasmine.createSpyObj('TemplateApiService', ['createTemplate']);
        router = jasmine.createSpyObj('Router', ['navigate']);
        wizardState = jasmine.createSpyObj('DeckWizardStateService', ['setTemplateId', 'setCurrentStep']);

        templateApi.createTemplate.and.returnValue(of({ templateId: 'template-1' }));
        router.navigate.and.returnValue(Promise.resolve(true));

        component = new VisualTemplateBuilderComponent(
            router,
            templateApi,
            wizardState,
            new I18nService()
        );
    });

    it('creates stable field names from final visible labels', () => {
        component.templateName = 'Geography';
        component.frontFields = [
            {
                tempId: 'front-1',
                name: 'text',
                type: 'text',
                label: ' Capital ',
                helpText: '',
                required: true
            } as any,
            {
                tempId: 'front-2',
                name: 'image',
                type: 'text',
                label: 'Capital',
                helpText: '',
                required: false
            } as any
        ];
        component.backFields = [
            {
                tempId: 'back-1',
                name: 'rich_text',
                type: 'rich_text',
                label: 'Answer',
                helpText: '',
                required: true
            } as any
        ];

        component.saveTemplate();

        const request = templateApi.createTemplate.calls.mostRecent().args[0];
        expect(request.layout.front).toEqual(['capital', 'capital_2']);
        expect(request.layout.back).toEqual(['answer']);
        expect(request.fields.map((field: { name: string; label: string }) => [field.name, field.label])).toEqual([
            ['capital', 'Capital'],
            ['capital_2', 'Capital'],
            ['answer', 'Answer']
        ]);
    });

    it('uses the palette label when a dropped field label is still blank', () => {
        component.templateName = 'Language';
        component.frontFields = [
            {
                tempId: 'front-1',
                name: '',
                type: 'text',
                label: '',
                helpText: '',
                required: true
            } as any
        ];
        component.backFields = [
            {
                tempId: 'back-1',
                name: '',
                type: 'markdown',
                label: 'Details',
                helpText: '',
                required: true
            } as any
        ];

        component.saveTemplate();

        const request = templateApi.createTemplate.calls.mostRecent().args[0];
        expect(request.fields[0]).toEqual(jasmine.objectContaining({
            name: 'text',
            label: 'Text'
        }));
    });
});
