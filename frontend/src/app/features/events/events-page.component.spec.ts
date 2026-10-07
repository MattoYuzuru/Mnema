import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { EventsPageComponent } from './events-page.component';
import { publicEvent } from './events-test-data';

describe('public events page', () => {
    let http: HttpTestingController;
    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    it('shows chronological entries and replaces each page of 50 instead of an unbounded list', async () => {
        const fixture = TestBed.createComponent(EventsPageComponent); fixture.detectChanges();
        http.expectOne('/api/events').flush({ items: [publicEvent()], nextCursor: 'older' });
        await fixture.whenStable(); fixture.detectChanges();
        const root: HTMLElement = fixture.nativeElement;
        expect(root.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-07');
        (Array.from(root.querySelectorAll('button')).find(button => button.textContent?.includes('Более ранние')) as HTMLButtonElement).click();
        http.expectOne('/api/events?cursor=older').flush({ items: [{ ...publicEvent(), title: 'Раннее событие', eventDate: '2026-10-01' }], nextCursor: null });
        await fixture.whenStable(); fixture.detectChanges();
        expect(root.querySelectorAll('article').length).toBe(1); expect(root.querySelector('article')?.textContent).toContain('Раннее событие');
        (Array.from(root.querySelectorAll('button')).find(button => button.textContent?.includes('Более новые')) as HTMLButtonElement).click();
        http.expectOne('/api/events').flush({ items: [publicEvent()], nextCursor: 'older' });
        await fixture.whenStable(); fixture.detectChanges();
        expect(root.querySelector('article')?.textContent).toContain('Новый редактор');
    });

    it('provides retry on outage and a calm empty state', async () => {
        const fixture = TestBed.createComponent(EventsPageComponent); fixture.detectChanges();
        http.expectOne('/api/events').flush({}, { status: 503, statusText: 'Unavailable' });
        await fixture.whenStable(); fixture.detectChanges();
        const root: HTMLElement = fixture.nativeElement;
        expect(root.querySelector('[role=alert]')?.textContent).toContain('Не удалось');
        (root.querySelector('button') as HTMLButtonElement).click();
        http.expectOne('/api/events').flush({ items: [], nextCursor: null });
        await fixture.whenStable(); fixture.detectChanges();
        expect(root.querySelector('.empty-state')?.textContent).toContain('Первая запись');
    });
});
