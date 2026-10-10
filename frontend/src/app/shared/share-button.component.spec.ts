import { TestBed } from '@angular/core/testing';

import { ShareButtonComponent } from './share-button.component';
import { ShareLinkFieldComponent } from './share-link-field.component';
import { ShareLinkService, ShareOutcome } from './share-link.service';

describe('ShareButtonComponent', () => {
    let share: ReturnType<typeof vi.fn<(url: string, title: string) => Promise<ShareOutcome>>>;

    function create(): { root: HTMLElement; click: () => Promise<void> } {
        share = vi.fn<(url: string, title: string) => Promise<ShareOutcome>>().mockResolvedValue('copied');
        TestBed.configureTestingModule({ providers: [{ provide: ShareLinkService, useValue: { share } }] });
        const fixture = TestBed.createComponent(ShareButtonComponent);
        fixture.componentRef.setInput('url', 'https://mnema.app/d/AbCdEfGh12/x');
        fixture.componentRef.setInput('title', 'Падежи');
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        return {
            root,
            async click() {
                root.querySelector('button')!.click();
                await fixture.whenStable();
                fixture.detectChanges();
            }
        };
    }

    it('is a named button with the share glyph that shares the link and the title', async () => {
        const { root, click } = create();
        const button = root.querySelector('button')!;
        expect(button.type).toBe('button');
        expect(button.textContent).toContain('Поделиться');
        expect(button.querySelector('app-glyph')?.getAttribute('data-glyph')).toBe('share');
        await click();
        expect(share).toHaveBeenCalledWith('https://mnema.app/d/AbCdEfGh12/x', 'Падежи');
        expect(root.querySelector('app-share-link-field')).toBeNull();
    });

    it('shows no field after a cancelled share', async () => {
        const { root, click } = create();
        share.mockResolvedValue('cancelled');
        await click();
        expect(root.querySelector('app-share-link-field')).toBeNull();
    });

    it('shows the link in a read-only field, named after the deck, when copying failed', async () => {
        const { root, click } = create();
        share.mockResolvedValue('failed');
        await click();
        const input = root.querySelector<HTMLInputElement>('app-share-link-field input')!;
        expect(input.readOnly).toBe(true);
        expect(input.type).toBe('url');
        expect(input.getAttribute('spellcheck')).toBe('false');
        expect(input.getAttribute('aria-describedby')).toBe(root.querySelector('app-share-link-field .hint')?.id);
        expect(input.value).toBe('https://mnema.app/d/AbCdEfGh12/x');
        expect(root.querySelector(`label[for="${input.id}"]`)?.textContent).toBe('Ссылка на колоду «Падежи»');
        share.mockResolvedValue('copied');
        await click();
        expect(root.querySelector('app-share-link-field')).toBeNull();
    });

    it('moves the focus to the field when copying failed, so the learner lands on the link', async () => {
        const { root, click } = create();
        document.body.append(root);
        try {
            share.mockResolvedValue('failed');
            root.querySelector('button')!.focus();
            await click();
            expect(document.activeElement).toBe(root.querySelector('app-share-link-field input'));
        } finally {
            root.remove();
        }
    });
});

describe('ShareLinkFieldComponent', () => {
    it('selects the whole link, by selection range, when it is focused', () => {
        const fixture = TestBed.createComponent(ShareLinkFieldComponent);
        fixture.componentRef.setInput('url', 'https://mnema.app/d/x');
        fixture.detectChanges();
        const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input')!;
        const range = vi.spyOn(input, 'setSelectionRange');
        input.dispatchEvent(new Event('focus'));
        expect(range).toHaveBeenCalledWith(0, 'https://mnema.app/d/x'.length);
    });

    it('takes the focus once it is rendered', () => {
        const fixture = TestBed.createComponent(ShareLinkFieldComponent);
        fixture.componentRef.setInput('url', 'https://mnema.app/d/x');
        document.body.append(fixture.nativeElement as HTMLElement);
        try {
            fixture.detectChanges();
            TestBed.tick();
            expect(document.activeElement).toBe((fixture.nativeElement as HTMLElement).querySelector('input'));
        } finally {
            (fixture.nativeElement as HTMLElement).remove();
        }
    });
});
