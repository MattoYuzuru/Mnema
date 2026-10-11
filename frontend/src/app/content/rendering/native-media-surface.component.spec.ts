import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { documentOf, nativeNode } from './native-renderer.fixtures';
import { MediaPlaybackApi, MediaPlaybackView } from './media-playback.api';
import { NativeMediaSurfaceComponent } from './native-media-surface.component';

describe('NativeMediaSurfaceComponent', () => {
    beforeEach(() => {
        vi.useFakeTimers();
    });
    afterEach(() => {
        vi.useRealTimers();
    });
    const assetId = '31901995-16ea-4f8b-8301-5d8e03004c72';
    const pending: MediaPlaybackView = {
        assetId, state: 'PROCESSING', playback: null, poster: null, download: null
    };
    const ready: MediaPlaybackView = {
        assetId, state: 'READY',
        playback: { url: 'https://storage.example/ready.webp', mimeType: 'image/webp',
            expiresAt: '2099-01-01T00:00:00Z' }, poster: null, download: null
    };

    it('replaces a pending placeholder after bounded polling and stops at READY', async () => {
        const api = {
            read: vi.fn().mockName("MediaPlaybackApi.read")
        };
        api.read.mockResolvedValue(pending);
        TestBed.configureTestingModule({ providers: [{ provide: MediaPlaybackApi, useValue: api }] });
        const fixture = TestBed.createComponent(NativeMediaSurfaceComponent);
        fixture.componentRef.setInput('document', documentOf([nativeNode('image', {
                assetId, alt: 'Схема API'
            })]));
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
        expect(api.read).toHaveBeenCalledTimes(1);
        expect(api.read).toHaveBeenCalledWith(assetId);
        expect(fixture.nativeElement.textContent).toContain('Готовим файл к просмотру');

        api.read.mockResolvedValue(ready);
        await vi.advanceTimersByTimeAsync(2000);
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('.image-open img')?.getAttribute('src'))
            .toBe('https://storage.example/ready.webp');
        const calls = vi.mocked(api.read).mock.calls.length;
        await vi.advanceTimersByTimeAsync(30000);
        expect(vi.mocked(api.read).mock.calls.length).toBe(calls);
        fixture.destroy();
    });

    it('cancels scheduled status checks when the surface is removed', async () => {
        const api = {
            read: vi.fn().mockName("MediaPlaybackApi.read")
        };
        api.read.mockResolvedValue(pending);
        TestBed.configureTestingModule({ providers: [{ provide: MediaPlaybackApi, useValue: api }] });
        const fixture = TestBed.createComponent(NativeMediaSurfaceComponent);
        fixture.componentRef.setInput('document', documentOf([nativeNode('image', {
                assetId, alt: 'Схема API'
            })]));
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        fixture.destroy();
        await vi.advanceTimersByTimeAsync(30000);
        expect(api.read).toHaveBeenCalledTimes(1);
    });

    it('tells a reader of a shared deck only that the file is unavailable, never that it belongs to another account', async () => {
        for (const [audience, expected] of [['owner', 'не принадлежит этому аккаунту'], ['public', 'Файл недоступен: Схема API']] as const) {
            const api = { read: vi.fn().mockName("MediaPlaybackApi.read") };
            api.read.mockRejectedValue(new HttpErrorResponse({ status: 404 }));
            TestBed.resetTestingModule();
            TestBed.configureTestingModule({ providers: [{ provide: MediaPlaybackApi, useValue: api }] });
            const fixture = TestBed.createComponent(NativeMediaSurfaceComponent);
            fixture.componentRef.setInput('document', documentOf([nativeNode('image', { assetId, alt: 'Схема API' })]));
            fixture.componentRef.setInput('audience', audience);
            fixture.detectChanges();
            await vi.advanceTimersByTimeAsync(0);
            fixture.detectChanges();
            const text = fixture.nativeElement.textContent as string;
            expect(text).toContain(expected);
            if (audience === 'public') expect(text).not.toContain('аккаунту');
            fixture.destroy();
        }
    });
});
