import { Component } from '@angular/core';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import { MEDIA_PLAYBACK_RESOLVER, MediaPlaybackResolver } from '../../features/study/media-playback-resolver';
import { SILENT_WAV, fakePlayback } from '../../features/study/study-test-data';
import { LearnerBlock } from './exercise-content.models';
import { LearnerBlocksComponent, mediaName } from './learner-blocks.component';
import { LearnerMediaComponent } from './learner-media.component';

describe('Learner blocks', () => {
    const asset = (suffix: string) => `aaaaaaaa-0000-4000-8000-${suffix.padStart(12, '0')}`;
    let resolver: jasmine.SpyObj<MediaPlaybackResolver>;
    const wav = (label: string) => `${SILENT_WAV}#${label}`;

    beforeEach(() => {
        resolver = jasmine.createSpyObj<MediaPlaybackResolver>('MediaPlaybackResolver', ['resolve']);
        resolver.resolve.and.callFake(fakePlayback);
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
        class Host { assetId = asset('9'); }

        it('shows a retryable status while the file is not ready and resolves again on request', () => {
            resolver.resolve.and.returnValue(of(null));
            const fixture = TestBed.createComponent(Host);
            fixture.detectChanges();
            const root = fixture.nativeElement as HTMLElement;
            expect(root.querySelector('[role="status"]')?.textContent).toContain('Аудио в вопросе: файл пока недоступен');
            resolver.resolve.and.returnValue(of({ url: wav('ready'), expiresAt: '2999-01-01T00:00:00Z', mimeType: 'audio/mp4' }));
            root.querySelector<HTMLButtonElement>('.media-retry')!.click(); fixture.detectChanges();
            expect(root.querySelector('audio')?.getAttribute('src')).toBe(wav('ready'));
            expect(resolver.resolve).toHaveBeenCalledTimes(2);
        });

        it('polls an unready asset, renews a signed URL before it expires and stops when destroyed', fakeAsync(() => {
            resolver.resolve.and.returnValues(of(null),
                of({ url: wav('first'), expiresAt: new Date(Date.now() + 120_000).toISOString(), mimeType: 'audio/mp4' }),
                of({ url: wav('second'), expiresAt: '2999-01-01T00:00:00Z', mimeType: 'audio/mp4' }));
            const fixture = TestBed.createComponent(Host);
            fixture.detectChanges();
            const root = fixture.nativeElement as HTMLElement;
            expect(root.querySelector('audio')).toBeNull();
            tick(4_000); fixture.detectChanges();
            expect(root.querySelector('audio')?.getAttribute('src')).toBe(wav('first'));
            tick(60_000); fixture.detectChanges();
            expect(root.querySelector('audio')?.getAttribute('src')).toBe(wav('second'));
            fixture.destroy();
            tick(20 * 60_000);
            expect(resolver.resolve).toHaveBeenCalledTimes(3);
        }));

        it('retries after a resolver failure and ignores a late answer for a replaced asset', fakeAsync(() => {
            const late = new Subject<{ url: string; expiresAt: string; mimeType: string } | null>();
            resolver.resolve.and.returnValues(throwError(() => new Error('offline')), late);
            const fixture = TestBed.createComponent(Host);
            fixture.detectChanges();
            tick(4_000);
            fixture.componentInstance.assetId = asset('8');
            fixture.componentRef.changeDetectorRef.detectChanges();
            resolver.resolve.and.returnValue(of({ url: wav('new'), expiresAt: '2999-01-01T00:00:00Z', mimeType: 'audio/mp4' }));
            fixture.destroy();
            late.next({ url: wav('stale'), expiresAt: '2999-01-01T00:00:00Z', mimeType: 'audio/mp4' });
            tick(20 * 60_000);
            expect(resolver.resolve).toHaveBeenCalledTimes(2);
        }));
    });
});
