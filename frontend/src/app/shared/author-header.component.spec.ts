import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { installPopoverShim, removePopoverShim } from '../../testing/popover-shim';
import { AuthorHeaderComponent, AuthorProfile } from './author-header.component';
import { ShareLinkService, ShareOutcome } from './share-link.service';

const AUTHOR: AuthorProfile = {
    accountId: 'a-1', profileUsername: 'anna.k', displayName: 'Анна Ковалёва', bio: 'Учу испанский.\nПишу колоды.', avatarSrc: '/assets/demo/wave-dense.svg'
};

@Component({
    imports: [AuthorHeaderComponent],
    template: `<app-author-header [author]="author()" profileUrl="https://mnema.app/u/anna.k" [learningNow]="learning()" [isSelf]="self()" (complain)="complaints = complaints + 1" />`
})
class HostComponent {
    readonly author = signal<AuthorProfile>(AUTHOR);
    readonly learning = signal(0);
    readonly self = signal(false);
    complaints = 0;
}

describe('AuthorHeaderComponent', () => {
    let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
    let host: HostComponent;
    let root: HTMLElement;
    let share: ReturnType<typeof vi.fn<(url: string, title: string) => Promise<ShareOutcome>>>;

    beforeEach(() => {
        installPopoverShim();
        share = vi.fn<(url: string, title: string) => Promise<ShareOutcome>>().mockResolvedValue('copied');
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ShareLinkService, useValue: { share } }] });
        fixture = TestBed.createComponent(HostComponent);
        host = fixture.componentInstance;
        fixture.detectChanges();
        root = fixture.nativeElement as HTMLElement;
    });
    afterEach(() => removePopoverShim());

    const items = (): HTMLButtonElement[] => [...root.querySelectorAll<HTMLButtonElement>('[role=menuitem]')];
    const set = (patch: Partial<AuthorProfile>): void => { host.author.set({ ...AUTHOR, ...patch }); fixture.detectChanges(); };

    it('shows the 64 px photo, the name as h1, the login and the bio', () => {
        const photo = root.querySelector('img.face')!;
        expect(photo.getAttribute('width')).toBe('64');
        expect(photo.getAttribute('alt')).toBe('');
        expect(root.querySelector('h1')!.textContent).toBe('Анна Ковалёва');
        expect(root.querySelector('.login')!.textContent).toBe('@anna.k');
        expect(root.querySelectorAll('h1')).toHaveLength(1);
        // The page moves the focus to its title: a heading takes it only with a tabindex.
        expect(root.querySelector('h1')!.getAttribute('tabindex')).toBe('-1');
    });

    it('lowers the heading where the header is not the page title', () => {
        const other = TestBed.createComponent(AuthorHeaderComponent);
        other.componentRef.setInput('author', AUTHOR);
        other.componentRef.setInput('profileUrl', 'https://mnema.app/u/anna.k');
        other.componentRef.setInput('headingLevel', 4);
        other.detectChanges();
        expect((other.nativeElement as HTMLElement).querySelector('h4.name')!.textContent).toBe('Анна Ковалёва');
        expect((other.nativeElement as HTMLElement).querySelector('h1')).toBeNull();
    });

    it('uses @login as the h1 when there is no display name, and does not repeat it', () => {
        set({ displayName: null });
        expect(root.querySelector('h1')!.textContent).toBe('@anna.k');
        expect(root.querySelector('.login')).toBeNull();
    });

    it('draws the letter disc without a photo and when the photo fails, and tries a new address again', () => {
        set({ avatarSrc: null });
        expect(root.querySelector('img')).toBeNull();
        expect(root.querySelector('.placeholder')!.textContent).toBe('А');
        expect(root.querySelector('.placeholder')!.getAttribute('aria-hidden')).toBe('true');
        set({ avatarSrc: '/a.png' });
        root.querySelector('img')!.dispatchEvent(new Event('error'));
        fixture.detectChanges();
        expect(root.querySelector('img')).toBeNull();
        expect(root.querySelector('.placeholder')).not.toBeNull();
        set({ avatarSrc: '/b.png' });
        expect(root.querySelector('img')!.getAttribute('src')).toBe('/b.png');
    });

    it('takes the letter of the login when there is no name', () => {
        set({ avatarSrc: null, displayName: null, profileUsername: 'zed' });
        expect(root.querySelector('.placeholder')!.textContent).toBe('Z');
    });

    it('shows the bio as plain text: line breaks kept, no HTML, no links', () => {
        expect(root.querySelector('.bio')!.textContent).toBe('Учу испанский.\nПишу колоды.');
        set({ bio: '<b>жирно</b> https://example.com' });
        const bio = root.querySelector('.bio')!;
        expect(bio.textContent).toBe('<b>жирно</b> https://example.com');
        expect(bio.querySelector('b, a')).toBeNull();
    });

    it('draws no bio when there is none', () => {
        set({ bio: null });
        expect(root.querySelector('.bio')).toBeNull();
    });

    it('says «Учат сейчас» only from 10 up, rounded, with the full words for a screen reader', () => {
        expect(root.querySelector('.learning')).toBeNull();
        host.learning.set(9);
        fixture.detectChanges();
        expect(root.querySelector('.learning')).toBeNull();
        host.learning.set(10);
        fixture.detectChanges();
        expect(root.querySelector('.learning')!.textContent).toContain('Учат сейчас: 10');
        host.learning.set(1234);
        fixture.detectChanges();
        expect(root.querySelector('.learning [aria-hidden]')!.textContent).toBe('1,2 тыс.');
        expect(root.querySelector('.learning .visually-hidden')!.textContent).toBe('1,2 тысячи');
    });

    describe('for another reader', () => {
        it('offers «Поделиться» and «Пожаловаться» in a menu named by the profile', () => {
            expect(root.querySelector('app-action-menu button[aria-haspopup=menu]')!.getAttribute('aria-label')).toBe('Действия с профилем «Анна Ковалёва»');
            expect(items().map(item => item.textContent?.trim())).toEqual(['Поделиться', 'Пожаловаться']);
            expect(root.querySelector('.self')).toBeNull();
        });

        it('shares the address of the profile', async () => {
            items()[0].click();
            await fixture.whenStable();
            expect(share).toHaveBeenCalledWith('https://mnema.app/u/anna.k', 'Анна Ковалёва');
            expect(root.querySelector('app-share-link-field')).toBeNull();
        });

        it('shows the link in a field when copying failed', async () => {
            share.mockResolvedValue('failed');
            items()[0].click();
            await fixture.whenStable();
            fixture.detectChanges();
            expect(root.querySelector<HTMLInputElement>('app-share-link-field input')!.value).toBe('https://mnema.app/u/anna.k');
            expect(root.querySelector('app-share-link-field label')!.textContent).toBe('Ссылка на профиль');
        });

        it('reports the complaint instead of opening anything itself', () => {
            items()[1].click();
            expect(host.complaints).toBe(1);
            expect(share).not.toHaveBeenCalled();
        });
    });

    describe('on one\'s own page', () => {
        beforeEach(() => { host.self.set(true); fixture.detectChanges(); });

        it('has no complaint, and says how others see the profile with a link to the settings', () => {
            expect(items().map(item => item.textContent?.trim())).toEqual(['Поделиться']);
            const notice = root.querySelector('.notice.self')!;
            expect(notice.querySelector('p')!.textContent).toBe('Так ваш профиль видят другие');
            expect(notice.querySelector('a')!.textContent).toBe('Настроить профиль');
            expect(notice.querySelector('a')!.getAttribute('href')).toBe('/profile');
        });
    });
});
