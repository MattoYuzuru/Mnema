import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { seededRandom } from '../../shared/seeded-random';
import { SignedMediaSource } from '../../content/rendering/media-playback.api';
import { StudyPresentation, StudyPrompt } from './study.models';

type MatchPrompt = Extract<StudyPrompt, { kind: 'AUDIO_MATCH' }>;
export interface MatchPair { readonly cueId: string; readonly optionId: string; }
const EQUALIZER = { bars: 9, fftSize: 1024, sampleMs: 50, smoothingMs: 90,
    frequencySmoothing: .55, minDecibels: -85, maxDecibels: -15 } as const;
// Logarithmic speech bands: linear Nyquist sampling misses almost all voice detail.
const SPEECH_BANDS_HZ = [80, 160, 300, 550, 950, 1600, 2600, 4000, 6000, 9000] as const;

export function restingBars(seed: string): readonly number[] {
    const random = seededRandom(seed);
    return Array.from({ length: EQUALIZER.bars }, () => .25 + random() * .7);
}

export function speechLevels(frequencies: Uint8Array, sampleRate: number): readonly number[] {
    const binHz = sampleRate / (frequencies.length * 2);
    return SPEECH_BANDS_HZ.slice(0, -1).map((lower, index) => {
        const start = Math.min(frequencies.length - 1, Math.max(1, Math.floor(lower / binHz)));
        const end = Math.min(frequencies.length, Math.max(start + 1, Math.ceil(SPEECH_BANDS_HZ[index + 1] / binHz)));
        let peak = 0; let sum = 0;
        for (let bin = start; bin < end; bin++) {
            peak = Math.max(peak, frequencies[bin]); sum += frequencies[bin];
        }
        return (.7 * peak + .3 * sum / (end - start)) / 255;
    });
}

export function smoothBars(previous: readonly number[], levels: readonly number[], baseline: readonly number[], elapsedMs: number): readonly number[] {
    const amount = 1 - Math.exp(-Math.min(elapsedMs, EQUALIZER.sampleMs * 2) / EQUALIZER.smoothingMs);
    return previous.map((value, index) => {
        const target = .12 + baseline[index] * .12 + levels[index] * .76;
        return value + (target - value) * amount;
    });
}

/** One media element guarantees that selecting a new cue stops the preceding recording. */
@Component({
    selector: 'app-audio-match-board',
    templateUrl: './audio-match-board.component.html',
    styleUrl: './audio-match-board.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AudioMatchBoardComponent {
    readonly seed = input.required<string>();
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
    readonly restBars = computed(() => Object.fromEntries(this.prompt().cues.map(cue =>
        [cue.cueId, restingBars(this.seed() + ':' + cue.cueId)])));
    readonly bars = signal<readonly number[]>([]);
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
        this.bars.set(this.restBars()[cueId]);
        if (media.src !== source.url) media.src = source.url;
        media.currentTime = 0;
        // Analysis is optional; decoding/playback stays usable if Web Audio is unavailable.
        try {
            if (!this.context) {
                this.context = new AudioContext();
                this.analyser = this.context.createAnalyser();
                this.analyser.fftSize = EQUALIZER.fftSize;
                this.analyser.minDecibels = EQUALIZER.minDecibels;
                this.analyser.maxDecibels = EQUALIZER.maxDecibels;
                this.analyser.smoothingTimeConstant = EQUALIZER.frequencySmoothing;
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
        this.bars.set(this.selectedCue() ? this.restBars()[this.selectedCue()!] : []);
    }

    private animate(): void {
        if (this.frame !== null || !this.analyser || !this.frequencies
            || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
        let lastSample: number | null = null;
        const draw = (time: number) => {
            if (!this.playing() || !this.analyser || !this.frequencies) { this.cancelFrame(); return; }
            const elapsed = lastSample === null ? EQUALIZER.sampleMs : time - lastSample;
            if (elapsed >= EQUALIZER.sampleMs) {
                this.analyser.getByteFrequencyData(this.frequencies);
                const baseline = this.restBars()[this.selectedCue()!];
                this.bars.set(smoothBars(this.bars(), speechLevels(this.frequencies, this.context!.sampleRate), baseline, elapsed));
                lastSample = time;
            }
            this.frame = requestAnimationFrame(draw);
        };
        this.frame = requestAnimationFrame(draw);
    }
}
