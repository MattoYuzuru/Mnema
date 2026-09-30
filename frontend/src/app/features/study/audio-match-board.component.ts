import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { SignedMediaSource } from '../../content/rendering/media-playback.api';
import { StudyPresentation, StudyPrompt } from './study.models';

type MatchPrompt = Extract<StudyPrompt, { kind: 'AUDIO_MATCH' }>;
export interface MatchPair { readonly cueId: string; readonly optionId: string; }
const REST_BARS = [.3, .6, .85, .5, 1, .65, .9, .55, .3];

/** One media element guarantees that selecting a new cue stops the preceding recording. */
@Component({
    selector: 'app-audio-match-board',
    templateUrl: './audio-match-board.component.html',
    styleUrl: './audio-match-board.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AudioMatchBoardComponent {
    readonly prompt = input.required<MatchPrompt>();
    readonly options = input.required<StudyPresentation['options']>();
    readonly sources = input.required<Readonly<Record<string, SignedMediaSource>>>();
    readonly matches = input.required<Readonly<Partial<Record<string, string>>>>();
    readonly busy = input(false);
    readonly wrongPair = input<MatchPair | null>(null);
    readonly pairSelected = output<MatchPair>();
    readonly sourceFailed = output<string>();
    readonly selectedCue = signal<string | null>(null);
    readonly playing = signal(false);
    readonly restBars = REST_BARS;
    readonly bars = signal<readonly number[]>(REST_BARS);
    readonly matchedOptions = computed(() => new Set(Object.values(this.matches())));
    readonly audio = viewChild<ElementRef<HTMLAudioElement>>('audio');
    private readonly destroyRef = inject(DestroyRef);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private context: AudioContext | null = null;
    private analyser: AnalyserNode | null = null;
    private frequencies: Uint8Array<ArrayBuffer> | null = null;
    private frame: number | null = null;
    private playEpoch = 0;

    constructor() {
        effect(() => {
            const selected = this.selectedCue();
            if (selected && this.matches()[selected]) {
                this.stop();
                this.selectedCue.set(null);
                afterNextRender(() => {
                    if (!this.destroyRef.destroyed) this.host.nativeElement
                        .querySelector<HTMLButtonElement>('.cue-tile:not(:disabled)')?.focus();
                }, { injector: this.injector });
            }
        });
        const hidden = () => { if (document.hidden) this.stop(); };
        document.addEventListener('visibilitychange', hidden);
        this.destroyRef.onDestroy(() => {
            document.removeEventListener('visibilitychange', hidden);
            this.stop();
            this.audio()?.nativeElement.removeAttribute('src');
            void this.context?.close();
        });
    }

    listen(cueId: string): void {
        if (this.busy() || this.matches()[cueId]) return;
        const cue = this.prompt().cues.find(value => value.cueId === cueId);
        const source = cue ? this.sources()[cue.assetId] : null;
        const media = this.audio()?.nativeElement;
        if (!cue || !source || !media) return;
        this.stop();
        this.selectedCue.set(cueId);
        if (media.src !== source.url) media.src = source.url;
        media.currentTime = 0;
        // Analysis is optional; decoding/playback stays usable if Web Audio is unavailable.
        try {
            if (!this.context) {
                this.context = new AudioContext();
                this.analyser = this.context.createAnalyser();
                this.analyser.fftSize = 64;
                this.frequencies = new Uint8Array(this.analyser.frequencyBinCount);
                this.context.createMediaElementSource(media).connect(this.analyser);
                this.analyser.connect(this.context.destination);
            }
            if (this.context.state === 'suspended') void this.context.resume().catch(() => undefined);
        } catch { this.analyser = null; }
        const epoch = ++this.playEpoch;
        void media.play().catch(() => {
            if (epoch !== this.playEpoch || this.destroyRef.destroyed) return;
            this.stop(); this.sourceFailed.emit(cue.assetId);
        });
    }

    choose(optionId: string): void {
        const cueId = this.selectedCue();
        if (cueId && !this.busy() && !this.matchedOptions().has(optionId)) this.pairSelected.emit({ cueId, optionId });
    }

    sync(): void {
        const media = this.audio()?.nativeElement;
        this.playing.set(!!media && !media.paused && !media.ended);
        if (this.playing()) this.animate(); else this.cancelFrame();
    }

    fail(): void {
        const cue = this.prompt().cues.find(value => value.cueId === this.selectedCue());
        this.stop();
        if (cue) this.sourceFailed.emit(cue.assetId);
    }

    private stop(): void {
        ++this.playEpoch;
        this.audio()?.nativeElement.pause();
        this.playing.set(false);
        this.cancelFrame();
    }

    private cancelFrame(): void {
        if (this.frame !== null) cancelAnimationFrame(this.frame);
        this.frame = null;
        this.bars.set(REST_BARS);
    }

    private animate(): void {
        if (this.frame !== null || !this.analyser || !this.frequencies
            || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
        const draw = () => {
            if (!this.playing() || !this.analyser || !this.frequencies) { this.cancelFrame(); return; }
            this.analyser.getByteFrequencyData(this.frequencies);
            const data = this.frequencies;
            this.bars.set(REST_BARS.map((_, index) => .18 + data[Math.floor(index * data.length / REST_BARS.length)] / 255 * .82));
            this.frame = requestAnimationFrame(draw);
        };
        this.frame = requestAnimationFrame(draw);
    }
}
