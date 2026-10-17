import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AuthService } from '../../auth.service';
import { TourAnchorDirective } from '../../core/onboarding/tour-anchor.directive';
import { TourHints } from '../../core/onboarding/tour-hints';
import { TourHostComponent } from '../../core/onboarding/tour-host.component';
import { OnboardingHostComponent } from './onboarding-host.component';
import { Setup, setup } from './onboarding-test-data';

@Component({
    imports: [TourHostComponent, OnboardingHostComponent, TourAnchorDirective],
    template: `
      <header id="masthead"><a href="/decks">Мои колоды</a></header>
      <main id="main">
        <section id="search" mnTourAnchor="community.search"><input id="query" /></section>
        <aside id="aside"><button type="button" id="other">Другое</button></aside>
      </main>
      <app-tour-host />
      <app-onboarding-host />`
})
class PageComponent {}

/** The tour's own surfaces (the card, the offer «Не нашли подходящую?», the finale) stay live while the page behind a step is inert. */
describe('the surfaces of the tour next to an inert page', () => {
    let fixture: ComponentFixture<PageComponent>;
    let s: Setup;
    let root: HTMLElement;
    const settle = async (): Promise<void> => { fixture.detectChanges(); await fixture.whenStable(); TestBed.tick(); fixture.detectChanges(); };

    beforeEach(async () => {
        s = setup({ journey: { state: 'IN_PROGRESS', branch: 'COMMUNITY', stepKey: 'community.search', autoOffer: false, experience: 'NEW' },
            providers: [{ provide: AuthService, useValue: { status: signal('authenticated'), user: signal({ accountId: 'a' }) } }] });
        fixture = TestBed.createComponent(PageComponent);
        root = fixture.nativeElement;
        await settle();
        // A reload folds the in-progress journey into the plaque; the learner continues it.
        s.engine.stop();
        await s.store.act('RESUME');
        await s.flow.startTour('COMMUNITY');
        await settle();
    });

    it('keeps the offer «Не нашли подходящую?» out of every inert ancestor during a step that waits for an action', async () => {
        expect(s.engine.step()?.key).toBe('community.search');
        TestBed.inject(TourHints).stuck.set(true);
        await settle();
        const nudge = root.querySelector('app-tour-nudge')!;
        expect(nudge).not.toBeNull();
        expect(nudge.closest('[inert]')).toBeNull();
        expect(root.querySelector('app-onboarding-host')!.closest('[inert]')).toBeNull();
        // The page is held while the offer is reachable; the target is not.
        expect(root.querySelector('#aside')!.hasAttribute('inert')).toBe(true);
        expect(root.querySelector('#masthead')!.hasAttribute('inert')).toBe(true);
        expect(root.querySelector('#search')!.closest('[inert]')).toBeNull();
        const action = [...nudge.querySelectorAll<HTMLButtonElement>('button')].find(button => button.classList.contains('primary'))!;
        expect(action.closest('[inert]')).toBeNull();
    });
});
