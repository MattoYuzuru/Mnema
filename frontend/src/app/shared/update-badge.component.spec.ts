import { TestBed } from '@angular/core/testing';

import { UpdateBadgeComponent } from './update-badge.component';

describe('UpdateBadgeComponent', () => {
    it('says «Есть обновление» in words, with a decorative mark that assistive technology skips', () => {
        const fixture = TestBed.createComponent(UpdateBadgeComponent);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;
        expect(host.textContent!.trim()).toBe('Есть обновление');
        expect(host.querySelector('.mark')?.getAttribute('aria-hidden')).toBe('true');
        expect(host.querySelector('.stamp.solid')).not.toBeNull();
    });

    it('is not a live region, so a list of cards does not announce every badge', () => {
        const fixture = TestBed.createComponent(UpdateBadgeComponent);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;
        expect(host.querySelector('[aria-live], [role=status], [role=alert]')).toBeNull();
        expect(host.getAttribute('aria-hidden')).toBeNull();
    });
});
