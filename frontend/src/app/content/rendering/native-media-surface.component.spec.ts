import { TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';

import { documentOf, nativeNode } from './native-renderer.fixtures';
import { MediaPlaybackApi, MediaPlaybackView } from './media-playback.api';
import { NativeMediaSurfaceComponent } from './native-media-surface.component';

describe('NativeMediaSurfaceComponent', () => {
    const assetId = '31901995-16ea-4f8b-8301-5d8e03004c72';
    const pending: MediaPlaybackView = {
        assetId, state: 'PROCESSING', playback: null, poster: null, download: null
    };
    const ready: MediaPlaybackView = {
        assetId, state: 'READY',
        playback: { url: 'https://storage.example/ready.webp', mimeType: 'image/webp',
            expiresAt: '2099-01-01T00:00:00Z' }, poster: null, download: null
    };

    it('replaces a pending placeholder after bounded polling and stops at READY', fakeAsync(() => {
        const api = jasmine.createSpyObj<MediaPlaybackApi>('MediaPlaybackApi', ['read']);
        api.read.and.resolveTo(pending);
        TestBed.configureTestingModule({ providers: [{ provide: MediaPlaybackApi, useValue: api }] });
        const fixture = TestBed.createComponent(NativeMediaSurfaceComponent);
        fixture.componentRef.setInput('document', documentOf([nativeNode('image', {
            assetId, alt: 'Схема API'
        })]));
        fixture.detectChanges();
        flushMicrotasks();
        fixture.detectChanges();
        expect(api.read).toHaveBeenCalledOnceWith(assetId);
        expect(fixture.nativeElement.textContent).toContain('Готовим файл к просмотру');

        api.read.and.resolveTo(ready);
        tick(2_000);
        flushMicrotasks();
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('.image-open img')?.getAttribute('src'))
            .toBe('https://storage.example/ready.webp');
        const calls = api.read.calls.count();
        tick(30_000);
        expect(api.read.calls.count()).toBe(calls);
        fixture.destroy();
    }));

    it('cancels scheduled status checks when the surface is removed', fakeAsync(() => {
        const api = jasmine.createSpyObj<MediaPlaybackApi>('MediaPlaybackApi', ['read']);
        api.read.and.resolveTo(pending);
        TestBed.configureTestingModule({ providers: [{ provide: MediaPlaybackApi, useValue: api }] });
        const fixture = TestBed.createComponent(NativeMediaSurfaceComponent);
        fixture.componentRef.setInput('document', documentOf([nativeNode('image', {
            assetId, alt: 'Схема API'
        })]));
        fixture.detectChanges();
        flushMicrotasks();
        fixture.destroy();
        tick(30_000);
        expect(api.read).toHaveBeenCalledTimes(1);
    }));
});
