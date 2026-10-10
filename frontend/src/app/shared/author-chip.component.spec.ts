import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthorChipComponent } from './author-chip.component';

@Component({
    imports: [AuthorChipComponent],
    template: `<app-author-chip [username]="username()" [avatarSrc]="avatar()" />`
})
class HostComponent {
    readonly username = signal<string | null>('anna.k');
    readonly avatar = signal<string | null>('https://auth.example.test/api/accounts/profiles/x/avatar');
}

describe('AuthorChipComponent', () => {
    function render() {
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        return { fixture, root: fixture.nativeElement as HTMLElement };
    }

    it('shows a 20 px photo next to @login, the photo being decorative', () => {
        const { root } = render();
        const image = root.querySelector<HTMLImageElement>('img')!;
        expect(image.getAttribute('src')).toBe('https://auth.example.test/api/accounts/profiles/x/avatar');
        expect(image.getAttribute('alt')).toBe('');
        expect([image.width, image.height]).toEqual([20, 20]);
        expect(image.getAttribute('loading')).toBe('lazy');
        expect(root.querySelector('.login')?.textContent).toBe('@anna.k');
        // the full login for a cut one; the chip itself is not interactive
        expect(root.querySelector('.login')?.getAttribute('title')).toBe('@anna.k');
        expect(root.querySelector('a, button, [tabindex]')).toBeNull();
        expect(root.querySelector('.placeholder')).toBeNull();
    });

    it('shows a neutral lettered disc without a photo', () => {
        const { fixture, root } = render();
        fixture.componentInstance.avatar.set(null);
        fixture.detectChanges();
        expect(root.querySelector('img')).toBeNull();
        const disc = root.querySelector('.placeholder')!;
        expect(disc.textContent).toBe('A');
        expect(disc.getAttribute('aria-hidden')).toBe('true');
        expect(root.querySelector('.login')?.textContent).toBe('@anna.k');
    });

    it('falls back to the disc when the photo fails to load, and tries a new address again', () => {
        const { fixture, root } = render();
        root.querySelector('img')!.dispatchEvent(new Event('error'));
        fixture.detectChanges();
        expect(root.querySelector('img')).toBeNull();
        expect(root.querySelector('.placeholder')).not.toBeNull();
        fixture.componentInstance.avatar.set('https://auth.example.test/api/accounts/profiles/y/avatar');
        fixture.detectChanges();
        expect(root.querySelector('img')).not.toBeNull();
    });

    it('renders nothing without a login', () => {
        const { fixture, root } = render();
        fixture.componentInstance.username.set(null);
        fixture.detectChanges();
        const chip = root.querySelector('app-author-chip')!;
        expect(chip.querySelector('.chip')).toBeNull();
        expect(chip.textContent?.trim()).toBe('');
        expect(chip.querySelector('img, span')).toBeNull();
    });
});
