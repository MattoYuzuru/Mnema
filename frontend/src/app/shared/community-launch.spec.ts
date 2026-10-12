import { TestBed } from '@angular/core/testing';

import { COMMUNITY_LAUNCH, COMMUNITY_LAUNCHED } from './community-launch';

describe('community launch flag', () => {
    it('is off until the enabling pull request of the epic flips it, and the token carries the constant', () => {
        expect(COMMUNITY_LAUNCHED).toBe(false);
        expect(TestBed.inject(COMMUNITY_LAUNCH)).toBe(COMMUNITY_LAUNCHED);
    });

    it('can be provided the other way for a spec', () => {
        TestBed.configureTestingModule({ providers: [{ provide: COMMUNITY_LAUNCH, useValue: true }] });
        expect(TestBed.inject(COMMUNITY_LAUNCH)).toBe(true);
    });
});
