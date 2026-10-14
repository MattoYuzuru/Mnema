import { TestBed } from '@angular/core/testing';

import { COMMUNITY_LAUNCH, COMMUNITY_LAUNCHED, isCommunityLaunched } from './community-launch';

describe('community launch flag', () => {
    it('is off until the enabling pull request of the epic flips it, and the token carries the constant', () => {
        expect(COMMUNITY_LAUNCHED).toBe(false);
        expect(TestBed.inject(COMMUNITY_LAUNCH)).toBe(COMMUNITY_LAUNCHED);
    });

    it('can be provided the other way for a spec', () => {
        TestBed.configureTestingModule({ providers: [{ provide: COMMUNITY_LAUNCH, useValue: true }] });
        expect(TestBed.inject(COMMUNITY_LAUNCH)).toBe(true);
    });

    it('is opened at runtime only by a configuration that says so (the loopback browser harness; resolveAppConfig drops the key elsewhere)', () => {
        expect(isCommunityLaunched({ communityLaunched: true })).toBe(true);
        expect(isCommunityLaunched({ communityLaunched: false })).toBe(COMMUNITY_LAUNCHED);
        expect(isCommunityLaunched({})).toBe(COMMUNITY_LAUNCHED);
    });
});
