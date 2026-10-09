import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
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

    it('appends chronological entries by cursor without buttons or duplicate ids', async () => {
        const fixture = TestBed.createComponent(EventsPageComponent); fixture.detectChanges();
        http.expectOne('/api/events').flush({ items: [publicEvent()], nextCursor: 'older' });
        await fixture.whenStable(); fixture.detectChanges();
        const root: HTMLElement = fixture.nativeElement;
        expect(root.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-07');
        expect(root.textContent).not.toContain('Более ранние');
        fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
        fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
        http.expectOne('/api/events?cursor=older').flush({ items: [publicEvent(), { ...publicEvent(), eventId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', title: 'Раннее событие', eventDate: '2026-10-01' }], nextCursor: null });
        await fixture.whenStable(); fixture.detectChanges();
        expect(root.querySelectorAll('article').length).toBe(2);
        expect(root.querySelectorAll('article')[0]?.textContent).toContain('Новый редактор');
        expect(root.querySelectorAll('article')[1]?.textContent).toContain('Раннее событие');
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

    it('retains published entries and retries a failed continuation explicitly', async () => {
        const fixture = TestBed.createComponent(EventsPageComponent); fixture.detectChanges();
        http.expectOne('/api/events').flush({ items: [publicEvent()], nextCursor: 'older' });
        await fixture.whenStable(); fixture.detectChanges();
        fixture.debugElement.query(By.css('app-auto-load')).triggerEventHandler('loadNext');
        http.expectOne('/api/events?cursor=older').flush({}, { status: 503, statusText: 'Unavailable' });
        await fixture.whenStable(); fixture.detectChanges();
        expect(fixture.nativeElement.querySelectorAll('article')).toHaveLength(1);
        fixture.nativeElement.querySelector('app-auto-load button').click();
        http.expectOne('/api/events?cursor=older').flush({ items: [], nextCursor: null });
        await fixture.whenStable(); fixture.detectChanges();
        expect(fixture.nativeElement.querySelectorAll('article')).toHaveLength(1);
        expect(fixture.nativeElement.querySelector('app-auto-load button')).toBeNull();
    });
});
