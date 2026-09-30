import { ChangeDetectionStrategy, Component, ElementRef, computed, input, output, signal, viewChild } from '@angular/core';
import { MnemaSelectComponent, MnemaSelectOption } from '../../core/controls/mnema-select.component';

let nextMediaSpeedId = 0;

/** Shared controls for material and exercise cues; the browser still decodes and seeks the media. */
@Component({
    selector: 'app-native-media-player',
    imports: [MnemaSelectComponent],
    templateUrl: './native-media-player.component.html',
    styleUrl: './native-media-player.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NativeMediaPlayerComponent {
    readonly speedControlId = `media-speed-${++nextMediaSpeedId}`;
    readonly speedOptions: readonly MnemaSelectOption[] = [
        { value: '0.75', label: '0,75×' }, { value: '1', label: '1×' },
        { value: '1.25', label: '1,25×' }, { value: '1.5', label: '1,5×' }, { value: '2', label: '2×' }
    ];
    readonly kind = input.required<'audio' | 'video'>();
    readonly title = input.required<string>();
    readonly source = input.required<string>();
    readonly poster = input<string | null>(null);
    readonly sourceFailed = output<void>();
    readonly playing = signal(false);
    readonly waiting = signal(false);
    readonly muted = signal(false);
    readonly position = signal(0);
    readonly duration = signal(0);
    readonly error = signal(false);
    readonly speed = signal(1);
    readonly seekMax = computed(() => Math.max(0, this.duration()));
    readonly elapsedText = computed(() => timeLabel(this.position()));
    readonly durationText = computed(() => timeLabel(this.duration()));
    private readonly audio = viewChild<ElementRef<HTMLAudioElement>>('audio');
    private readonly video = viewChild<ElementRef<HTMLVideoElement>>('video');

    toggle(): void {
        const media = this.media();
        if (!media) return;
        if (!media.paused) { media.pause(); return; }
        void media.play().catch(() => { this.error.set(true); this.sourceFailed.emit(); });
    }

    seek(value: string): void {
        const media = this.media();
        const target = Number(value);
        if (!media || !Number.isFinite(target) || !Number.isFinite(media.duration)) return;
        media.currentTime = Math.max(0, Math.min(target, media.duration));
        this.position.set(media.currentTime);
    }

    toggleMute(): void {
        const media = this.media();
        if (!media) return;
        media.muted = !media.muted;
        this.muted.set(media.muted);
    }

    changeSpeed(value: string): void {
        const media = this.media();
        const selected = Number(value);
        if (!media || ![0.75, 1, 1.25, 1.5, 2].includes(selected)) return;
        media.playbackRate = selected;
        this.speed.set(selected);
    }

    async fullscreen(): Promise<void> {
        const video = this.video()?.nativeElement;
        if (video?.requestFullscreen) await video.requestFullscreen().catch(() => undefined);
    }

    sync(): void {
        const media = this.media();
        if (!media) return;
        this.playing.set(!media.paused && !media.ended);
        this.waiting.set(media.readyState < HTMLMediaElement.HAVE_FUTURE_DATA && !media.paused);
        this.muted.set(media.muted);
        this.position.set(Number.isFinite(media.currentTime) ? media.currentTime : 0);
        this.duration.set(Number.isFinite(media.duration) ? media.duration : 0);
        if (media.error) { this.error.set(true); this.sourceFailed.emit(); }
        else if (media.readyState >= HTMLMediaElement.HAVE_METADATA) this.error.set(false);
    }

    private media(): HTMLMediaElement | undefined {
        return this.kind() === 'audio' ? this.audio()?.nativeElement : this.video()?.nativeElement;
    }
}

function timeLabel(seconds: number): string {
    const whole = Math.floor(Math.max(0, Number.isFinite(seconds) ? seconds : 0));
    return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`;
}
