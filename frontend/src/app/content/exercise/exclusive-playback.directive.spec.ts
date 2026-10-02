import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ExclusivePlaybackDirective } from './exclusive-playback.directive';

describe('ExclusivePlaybackDirective', () => {
    @Component({ imports: [ExclusivePlaybackDirective], template: `
      <div appExclusivePlayback><audio id="a"></audio><video id="b"></video></div><audio id="outside"></audio>` })
    class Host {
    }

    it('pauses the other media inside the host when one starts and leaves foreign media alone', () => {
        const fixture = TestBed.createComponent(Host);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        const first = root.querySelector<HTMLAudioElement>('#a')!;
        const second = root.querySelector<HTMLVideoElement>('#b')!;
        const outside = root.querySelector<HTMLAudioElement>('#outside')!;
        const pauses = [first, second, outside].map(media => {
            vi.spyOn(media, 'paused', 'get').mockReturnValue(false);
            return vi.spyOn(media, 'pause').mockReturnValue(undefined);
        });
        second.dispatchEvent(new Event('play'));
        expect(pauses[0]).toHaveBeenCalledTimes(1);
        expect(pauses[1]).not.toHaveBeenCalled();
        outside.dispatchEvent(new Event('play'));
        expect(pauses[2]).not.toHaveBeenCalled();
        root.querySelector('div')!.dispatchEvent(new Event('play'));
        fixture.destroy();
    });
});
