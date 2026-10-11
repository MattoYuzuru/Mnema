import { TestBed } from '@angular/core/testing';

import { ToastService } from '../core/notifications/toast.service';
import { SHARE_MENU_ITEM, ShareLinkService } from './share-link.service';

const URL_ = 'https://mnema.app/d/AbCdEfGh12/ispanskie-glagoly';
const TITLE = 'Испанские глаголы';

describe('ShareLinkService', () => {
    const view = document.defaultView!;
    let echo: ReturnType<typeof vi.spyOn>;
    let echoError: ReturnType<typeof vi.spyOn>;
    let service: ShareLinkService;

    function setNavigator(members: Record<string, unknown>): void {
        for (const [name, value] of Object.entries(members)) {
            Object.defineProperty(view.navigator, name, { value, configurable: true, writable: true });
        }
    }

    function coarse(matches: boolean): void {
        vi.spyOn(view, 'matchMedia').mockImplementation(query => ({ matches: matches && query === '(pointer: coarse)', media: query }) as MediaQueryList);
    }

    beforeEach(() => {
        const toasts = TestBed.inject(ToastService);
        echo = vi.spyOn(toasts, 'echo').mockImplementation(() => undefined);
        echoError = vi.spyOn(toasts, 'echoError').mockImplementation(() => undefined);
        service = TestBed.inject(ShareLinkService);
    });
    afterEach(() => {
        for (const name of ['share', 'canShare', 'clipboard']) delete (view.navigator as unknown as Record<string, unknown>)[name];
    });

    it('offers the «Поделиться» menu row with the share glyph', () => {
        expect(SHARE_MENU_ITEM).toEqual({ id: 'share', label: 'Поделиться', glyph: 'share' });
    });

    describe('on a touch device with the Web Share API', () => {
        let share: ReturnType<typeof vi.fn>;
        let writeText: ReturnType<typeof vi.fn>;

        beforeEach(() => {
            share = vi.fn().mockResolvedValue(undefined);
            writeText = vi.fn().mockResolvedValue(undefined);
            setNavigator({ share, canShare: vi.fn().mockReturnValue(true), clipboard: { writeText } });
            coarse(true);
        });

        it('opens the system sheet with the link and the title only, no message text', async () => {
            expect(await service.share(URL_, TITLE)).toBe('shared');
            expect(share).toHaveBeenCalledOnce();
            expect(share).toHaveBeenCalledWith({ url: URL_, title: TITLE });
            expect(Object.keys(share.mock.calls[0][0] as object).sort()).toEqual(['title', 'url']);
            expect(writeText).not.toHaveBeenCalled();
            expect(echo).not.toHaveBeenCalled();
        });

        it('asks canShare about the link', async () => {
            await service.share(URL_, TITLE);
            expect(view.navigator.canShare).toHaveBeenCalledWith({ url: URL_ });
        });

        it('stays silent when the learner closes the sheet', async () => {
            share.mockRejectedValue(new DOMException('closed', 'AbortError'));
            expect(await service.share(URL_, TITLE)).toBe('cancelled');
            expect(echo).not.toHaveBeenCalled();
            expect(echoError).not.toHaveBeenCalled();
            expect(writeText).not.toHaveBeenCalled();
        });

        it('copies the link when the sheet refuses for another reason', async () => {
            share.mockRejectedValue(new DOMException('no activation', 'NotAllowedError'));
            expect(await service.share(URL_, TITLE)).toBe('copied');
            expect(writeText).toHaveBeenCalledWith(URL_);
            expect(echo).toHaveBeenCalledWith('Ссылка скопирована');
        });

        it('copies when canShare says the link cannot be shared', async () => {
            setNavigator({ canShare: vi.fn().mockReturnValue(false) });
            expect(await service.share(URL_, TITLE)).toBe('copied');
            expect(share).not.toHaveBeenCalled();
        });

        it('shares when the browser has share but no canShare', async () => {
            setNavigator({ canShare: undefined });
            expect(await service.share(URL_, TITLE)).toBe('shared');
        });
    });

    describe('on a computer', () => {
        it('copies even when the browser could share, because the pointer is not coarse', async () => {
            const share = vi.fn();
            const writeText = vi.fn().mockResolvedValue(undefined);
            setNavigator({ share, canShare: () => true, clipboard: { writeText } });
            coarse(false);
            expect(await service.share(URL_, TITLE)).toBe('copied');
            expect(writeText).toHaveBeenCalledWith(URL_);
            expect(share).not.toHaveBeenCalled();
            expect(echo).toHaveBeenCalledWith('Ссылка скопирована');
        });

        it('copies on a touch device whose browser has no Web Share API', async () => {
            const writeText = vi.fn().mockResolvedValue(undefined);
            setNavigator({ clipboard: { writeText } });
            coarse(true);
            expect(await service.share(URL_, TITLE)).toBe('copied');
        });

        it('reports an error toast and the outcome «failed» when the clipboard rejects', async () => {
            setNavigator({ clipboard: { writeText: vi.fn().mockRejectedValue(new DOMException('denied', 'NotAllowedError')) } });
            coarse(false);
            expect(await service.share(URL_, TITLE)).toBe('failed');
            expect(echo).not.toHaveBeenCalled();
            expect(echoError).toHaveBeenCalledOnce();
            expect(String(echoError.mock.calls[0][0])).toContain('Не удалось скопировать');
        });

        it('fails the same way when there is no clipboard at all (an insecure page)', async () => {
            coarse(false);
            expect(await service.share(URL_, TITLE)).toBe('failed');
            expect(echoError).toHaveBeenCalledOnce();
        });

        it('never reaches for execCommand', async () => {
            const exec = vi.fn();
            Object.defineProperty(document, 'execCommand', { value: exec, configurable: true });
            try {
                coarse(false);
                await service.share(URL_, TITLE);
                expect(exec).not.toHaveBeenCalled();
            } finally {
                delete (document as unknown as Record<string, unknown>)['execCommand'];
            }
        });
    });
});
