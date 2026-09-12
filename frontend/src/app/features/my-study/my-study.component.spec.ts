import { ComponentFixtureAutoDetect, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { delay, of } from 'rxjs';

import { DeckApiService } from '../../core/services/deck-api.service';
import { TemplateApiService } from '../../core/services/template-api.service';
import { MyStudyComponent } from './my-study.component';

describe('MyStudyComponent', () => {
    it('renders asynchronously loaded legacy state', async () => {
        const deckApi = jasmine.createSpyObj<DeckApiService>('DeckApiService', ['getMyDecks']);
        const templateApi = jasmine.createSpyObj<TemplateApiService>('TemplateApiService', ['getTemplates']);
        deckApi.getMyDecks.and.returnValue(of({ content: [{ userDeckId: 'deck-1' }] } as any).pipe(delay(0)));
        templateApi.getTemplates.and.returnValue(of({ content: [{ templateId: 'template-1' }] } as any).pipe(delay(0)));

        await TestBed.configureTestingModule({
            imports: [MyStudyComponent],
            providers: [
                { provide: ComponentFixtureAutoDetect, useValue: true },
                { provide: DeckApiService, useValue: deckApi },
                { provide: TemplateApiService, useValue: templateApi },
                { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) }
            ]
        })
            .overrideComponent(MyStudyComponent, {
                set: {
                    imports: [],
                    template: '@if (!loading) { <span>loaded:{{ decks.length }}/{{ templates.length }}</span> }'
                }
            })
            .compileComponents();

        const fixture = TestBed.createComponent(MyStudyComponent);
        await fixture.whenStable();

        expect(fixture.nativeElement.textContent).toContain('loaded:1/1');
    });
});
