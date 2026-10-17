import { TestBed } from '@angular/core/testing';

import { ReplayTourComponent } from './replay-tour.component';
import { setup } from './onboarding-test-data';

describe('ReplayTourComponent', () => {
    const render = async (options: Parameters<typeof setup>[0] = {}) => {
        const s = setup(options);
        const fixture = TestBed.createComponent(ReplayTourComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        return { s, fixture, root: fixture.nativeElement as HTMLElement };
    };

    it.each([
        ['a new account that was offered the tour', { }],
        ['one that skipped it', { state: 'SKIPPED' }],
        ['one that finished it', { state: 'COMPLETED' }],
        ['an account before the release (not in the cohort)', { state: 'NOT_OFFERED', eligible: false, autoOffer: false }],
        ['the holdout', { autoOffer: false }]
    ] as const)('is offered to %s', async (_name, journey) => {
        const { root } = await render({ journey });
        expect(root.querySelector('button')!.textContent).toBe('Пройти знакомство заново');
    });

    it('is not offered while onboarding is off', async () => {
        const { root } = await render({ journey: null });
        expect(root.querySelector('button')).toBeNull();
    });

    it('replays: REPLAY at the branch choice, no survey', async () => {
        const { s, root } = await render({ journey: { state: 'COMPLETED', autoOffer: false } });
        root.querySelector('button')!.click();
        await vi.waitFor(() => expect(s.flow.card()).toBe('choose'));
        expect(s.store.calls).toEqual([['REPLAY', { stepKey: 'choose' }]]);
        expect(s.flow.replaying()).toBe(true);
    });
});
