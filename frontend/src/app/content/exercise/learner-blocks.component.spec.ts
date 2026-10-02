import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import { MEDIA_PLAYBACK_RESOLVER, MediaPlaybackResolver } from '../../features/study/media-playback-resolver';
import { SILENT_WAV, fakePlayback } from '../../features/study/study-test-data';
import { LearnerBlock } from './exercise-content.models';
import { LearnerBlocksComponent, mediaName } from './learner-blocks.component';
import { LearnerMediaComponent } from './learner-media.component';
import { type SpyObj } from '../../../testing/mocks';

describe('Learner blocks', () => {
    beforeEach(() => {
        vi.useFakeTimers({ advanceTimeDelta: 1, shouldAdvanceTime: true });
    });
    afterEach(() => {
        vi.useRealTimers();
    });
    const asset = (suffix: string) => `aaaaaaaa-0000-4000-8000-${suffix.padStart(12, '0')}`;
    let resolver: SpyObj<MediaPlaybackResolver>;
    const wav = (label: string) => `${SILENT_WAV}#${label}`;

    beforeEach(() => {
        resolver = {
            resolve: vi.fn().mockName("MediaPlaybackResolver.resolve")
        };
        resolver.resolve.mockImplementation(fakePlayback);
        TestBed.configureTestingModule({ providers: [{ provide: MEDIA_PLAYBACK_RESOLVER, useValue: resolver }] });
    });

    function render(blocks: readonly LearnerBlock[], suffix = '', mediaOnly = false) {
        const fixture = TestBed.createComponent(LearnerBlocksComponent);
        fixture.componentRef.setInput('blocks', blocks);
        fixture.componentRef.setInput('nameSuffix', suffix);
        fixture.componentRef.setInput('mediaOnly', mediaOnly);
        fixture.detectChanges();
        return fixture;
    }

    it('generates neutral names for media and numbers repeated kinds', () => {
        expect(mediaName('AUDIO', 1, ', вариант 2')).toBe('Аудио, вариант 2');
        expect(mediaName('VIDEO', 1, ' в вопросе')).toBe('Видео в вопросе');
        expect(mediaName('AUDIO', 2, ' в вопросе')).toBe('Аудио 2 в вопросе');
        const fixture = render([
            { kind: 'AUDIO', assetId: asset('1'), transcriptAvailable: false },
            { kind: 'AUDIO', assetId: asset('2'), transcriptAvailable: false },
            { kind: 'VIDEO', assetId: asset('3'), transcriptAvailable: false }
        ], ' в вопросе');
        const names = [...(fixture.nativeElement as HTMLElement).querySelectorAll('audio, video')].map(media => media.getAttribute('aria-label'));
        expect(names).toEqual(['Аудио в вопросе', 'Аудио 2 в вопросе', 'Видео в вопросе']);
    });

    it('keeps newlines and indentation of text and shows a revealed transcript but never an unrevealed one', () => {
        const code = 'if (x) {\n    return y;\n}';
        const fixture = render([{ kind: 'TEXT', text: code },
            { kind: 'AUDIO', assetId: asset('1'), transcriptAvailable: true },
            { kind: 'VIDEO', assetId: asset('2'), transcriptAvailable: true, transcript: 'Текст видео' }]);
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('.learner-text')?.textContent).toBe(code);
        expect(getComputedStyle(root.querySelector('.learner-text')!).whiteSpace).toBe('pre-wrap');
        expect(root.querySelectorAll('.learner-transcript').length).toBe(1);
        expect(root.querySelector('.learner-transcript')?.textContent).toContain('Текст видео');
    });

    it('renders only media when asked to, and image alternatives from the learner block', () => {
        const fixture = render([{ kind: 'TEXT', text: 'скрыто' }, { kind: 'IMAGE', assetId: asset('3'), alt: 'Осадок' }], '', true);
        const root = fixture.nativeElement as HTMLElement;
        expect(root.textContent).not.toContain('скрыто');
        expect(root.querySelector('img')?.getAttribute('alt')).toBe('Осадок');
    });

    it('renders YouTube behind the consent card with the author title', () => {
        const fixture = render([{ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: 'Опыт' }]);
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('app-native-youtube figcaption')?.textContent).toBe('Опыт');
        expect(root.querySelector('iframe')).toBeNull();
    });

    describe('media loading', () => {
        @Component({ imports: [LearnerMediaComponent], template: '<app-learner-media kind="audio" [assetId]="assetId" name="Аудио в вопросе" />' })
        class Host {
            assetId = asset('9');
        }

        it('shows a retryable status while the file is not ready and resolves again on request', () => {
            resolver.resolve.mockReturnValue(of(null));
            const fixture = TestBed.createComponent(Host);
            fixture.detectChanges();
            const root = fixture.nativeElement as HTMLElement;
            expect(root.querySelector('[role="status"]')?.textContent).toContain('Аудио в вопросе: файл пока недоступен');
            resolver.resolve.mockReturnValue(of({ url: wav('ready'), expiresAt: '2999-01-01T00:00:00Z', mimeType: 'audio/mp4' }));
            root.querySelector<HTMLButtonElement>('.media-retry')!.click();
            fixture.detectChanges();
            expect(root.querySelector('audio')?.getAttribute('src')).toBe(wav('ready'));
            expect(resolver.resolve).toHaveBeenCalledTimes(2);
        });

        it('polls an unready asset, renews a signed URL before it expires and stops when destroyed', async () => {
            resolver.resolve.mockReturnValueOnce(of(null)).mockReturnValueOnce(of({ url: wav('first'), expiresAt: new Date(Date.now() + 120000).toISOString(), mimeType: 'audio/mp4' })).mockReturnValueOnce(of({ url: wav('second'), expiresAt: '2999-01-01T00:00:00Z', mimeType: 'audio/mp4' }));
            const fixture = TestBed.createComponent(Host);
            fixture.detectChanges();
            const root = fixture.nativeElement as HTMLElement;
            expect(root.querySelector('audio')).toBeNull();
            await vi.advanceTimersByTimeAsync(4000);
            fixture.detectChanges();
            expect(root.querySelector('audio')?.getAttribute('src')).toBe(wav('first'));
            await vi.advanceTimersByTimeAsync(60000);
            fixture.detectChanges();
            expect(root.querySelector('audio')?.getAttribute('src')).toBe(wav('second'));
            fixture.destroy();
            await vi.advanceTimersByTimeAsync(20 * 60000);
            expect(resolver.resolve).toHaveBeenCalledTimes(3);
        });

        it('retries after a resolver failure and ignores a late answer for a replaced asset', async () => {
            const late = new Subject<{
                url: string;
                expiresAt: string;
                mimeType: string;
            } | null>();
            resolver.resolve.mockReturnValueOnce(throwError(() => new Error('offline'))).mockReturnValueOnce(late);
            const fixture = TestBed.createComponent(Host);
            fixture.detectChanges();
            await vi.advanceTimersByTimeAsync(4000);
            fixture.componentInstance.assetId = asset('8');
            fixture.componentRef.changeDetectorRef.detectChanges();
            resolver.resolve.mockReturnValue(of({ url: wav('new'), expiresAt: '2999-01-01T00:00:00Z', mimeType: 'audio/mp4' }));
            fixture.destroy();
            late.next({ url: wav('stale'), expiresAt: '2999-01-01T00:00:00Z', mimeType: 'audio/mp4' });
            await vi.advanceTimersByTimeAsync(20 * 60000);
            expect(resolver.resolve).toHaveBeenCalledTimes(2);
        });
    });
});
