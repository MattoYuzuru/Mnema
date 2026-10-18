import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import journey from '../../../../../contracts/onboarding/journey.json';
import { AuthService } from '../../auth.service';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { OnboardingView } from '../../core/onboarding/onboarding.models';
import { PathPreferences } from '../../core/onboarding/path-preferences';
import { PathSummaryComponent } from './path-summary.component';
import { FakeJourneyStore } from './onboarding-test-data';

const midway = journey.views.pathMidway as OnboardingView;

describe('PathSummaryComponent (the «Путь Mnema» block of the profile)', () => {
    const render = (view: OnboardingView | null) => {
        localStorage.clear();
        const store = new FakeJourneyStore({}, {});
        store.view.set(view);
        TestBed.configureTestingModule({
            providers: [provideRouter([]), { provide: JourneyStore, useValue: store },
                { provide: AuthService, useValue: { user: signal({ accountId: 'acc-1' }) } }]
        });
        const fixture = TestBed.createComponent(PathSummaryComponent);
        fixture.detectChanges();
        return { fixture, root: fixture.nativeElement as HTMLElement };
    };
    afterEach(() => localStorage.clear());

    it('says how far the learner is and links to the page', () => {
        const { root } = render(midway);
        expect(root.querySelector('h2')?.textContent).toBe('Путь Mnema');
        expect(root.querySelector('.total')?.textContent).toBe('5 из 12');
        expect(root.querySelector('[role="progressbar"]')?.getAttribute('aria-valuetext')).toBe('5 из 12');
        const link = root.querySelector('a')!;
        expect(link.textContent).toBe('Открыть «Путь»');
        expect(link.getAttribute('href')).toBe('/profile/path');
        expect(root.querySelector('section')?.getAttribute('aria-labelledby')).toBe(root.querySelector('h2')?.id);
    });

    it('shrinks to the link for a learner who hid «Путь»', () => {
        const { fixture, root } = render(midway);
        TestBed.inject(PathPreferences).setHidden(true);
        fixture.detectChanges();
        expect(root.querySelector('.total')).toBeNull();
        expect(root.querySelector('[role="progressbar"]')).toBeNull();
        expect(root.querySelector('a')?.getAttribute('href')).toBe('/profile/path');
    });

    it('is not on the profile while onboarding is off, or before the first answer', () => {
        expect(render({ ...midway, enabled: false, journey: null, milestones: [], path: null }).root.querySelector('section')).toBeNull();
        TestBed.resetTestingModule();
        expect(render(null).root.querySelector('section')).toBeNull();
    });
});
