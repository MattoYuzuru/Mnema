import { TestBed } from '@angular/core/testing';

import { NativeMediaPlayerComponent } from './native-media-player.component';

describe('NativeMediaPlayerComponent', () => {
    it('provides a keyboard-seekable, labeled timeline over a native video element', () => {
        const fixture = TestBed.createComponent(NativeMediaPlayerComponent);
        fixture.componentRef.setInput('kind', 'video');
        fixture.componentRef.setInput('title', 'Схема потока');
        fixture.componentRef.setInput('source', 'https://storage.example/playback.mp4');
        fixture.detectChanges();

        const media = fixture.nativeElement.querySelector('video') as HTMLVideoElement;
        Object.defineProperty(media, 'duration', { configurable: true, value: 120 });
        media.dispatchEvent(new Event('loadedmetadata'));
        fixture.detectChanges();
        const slider = fixture.nativeElement.querySelector('input[type=range]') as HTMLInputElement;
        expect(slider.getAttribute('aria-label')).toContain('Схема потока');
        expect(slider.disabled).toBeFalse();
        slider.value = '42';
        slider.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        expect(media.currentTime).toBe(42);
        expect(slider.getAttribute('aria-valuetext')).toContain('0:42');
        fixture.destroy();
    });

    it('keeps explicit playback and volume controls on audio cues', () => {
        const fixture = TestBed.createComponent(NativeMediaPlayerComponent);
        fixture.componentRef.setInput('kind', 'audio');
        fixture.componentRef.setInput('title', 'Фраза');
        fixture.componentRef.setInput('source', 'https://storage.example/playback.m4a');
        fixture.detectChanges();
        const media = fixture.nativeElement.querySelector('audio') as HTMLAudioElement;
        const play = spyOn(media, 'play').and.resolveTo();
        fixture.nativeElement.querySelector('button[aria-label="Воспроизвести"]').click();
        expect(play).toHaveBeenCalled();
        fixture.nativeElement.querySelector('button[aria-label="Выключить звук"]').click();
        expect(media.muted).toBeTrue();
        fixture.destroy();
    });

    it('clears a signed-source failure once renewed media loads', () => {
        const fixture = TestBed.createComponent(NativeMediaPlayerComponent);
        fixture.componentRef.setInput('kind', 'audio');
        fixture.componentRef.setInput('title', 'Фраза');
        fixture.componentRef.setInput('source', 'https://storage.example/expired');
        fixture.detectChanges();
        const media = fixture.nativeElement.querySelector('audio') as HTMLAudioElement;
        Object.defineProperty(media, 'error', { configurable: true, value: { code: 4 } });
        media.dispatchEvent(new Event('error'));
        fixture.detectChanges();
        expect(fixture.componentInstance.error()).toBeTrue();
        fixture.componentRef.setInput('source', 'https://storage.example/renewed');
        Object.defineProperty(media, 'error', { configurable: true, value: null });
        Object.defineProperty(media, 'readyState', { configurable: true, value: HTMLMediaElement.HAVE_METADATA });
        fixture.detectChanges();
        media.dispatchEvent(new Event('loadedmetadata'));
        fixture.detectChanges();
        expect(fixture.componentInstance.error()).toBeFalse();
        expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
        fixture.destroy();
    });
});
