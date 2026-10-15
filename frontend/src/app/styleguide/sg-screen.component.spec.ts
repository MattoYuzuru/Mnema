import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';

import hub from '../../../../contracts/decks/hub.json';
import { DeckInsightsComponent } from '../features/own-decks/hub/deck-insights.component';
import { parseInsights } from '../features/own-decks/hub/deck-hub.models';
import { SgScreenComponent } from './sg-screen.component';

describe('SgScreenComponent', () => {
    it('keeps the local chart specimen consistent with the shared wire fixture', async () => {
        await TestBed.configureTestingModule({ providers: [provideRouter([])] }).compileComponents();
        const fixture = TestBed.createComponent(SgScreenComponent);
        fixture.detectChanges();
        const insights = fixture.debugElement.query(By.directive(DeckInsightsComponent)).componentInstance as DeckInsightsComponent;
        expect(insights.state()).toEqual({ phase: 'ready', insights: parseInsights(hub.insights.response) });
    });
});
