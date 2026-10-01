import { DestroyRef, Directive, ElementRef, inject } from '@angular/core';

/**
 * Keeps at most one audio or video element playing inside the host. Starting another one pauses the rest,
 * so several recordings never overlap unless the learner explicitly pauses and starts them again.
 */
@Directive({ selector: '[appExclusivePlayback]' })
export class ExclusivePlaybackDirective {
    constructor() {
        const host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
        // Media events do not bubble, so the capture phase is the only way to observe every element.
        const onPlay = (event: Event): void => {
            const target = event.target;
            if (!(target instanceof HTMLMediaElement)) return;
            host.querySelectorAll<HTMLMediaElement>('audio, video').forEach(media => {
                if (media !== target && !media.paused) media.pause();
            });
        };
        host.addEventListener('play', onPlay, true);
        inject(DestroyRef).onDestroy(() => host.removeEventListener('play', onPlay, true));
    }
}
