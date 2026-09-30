import { TestBed } from '@angular/core/testing';
import { AudioMatchBoardComponent, restingBars, smoothBars, speechLevels } from './audio-match-board.component';

describe('AudioMatchBoardComponent', () => {
    function setup() {
        const fixture = TestBed.createComponent(AudioMatchBoardComponent);
        fixture.componentRef.setInput('seed', 'exercise-1');
        fixture.componentRef.setInput('prompt', { kind: 'AUDIO_MATCH', instruction: '',
            transcriptAvailable: false, transcriptRevealed: false, cues: [
                { cueId: 'a', assetId: 'asset-a', title: 'Paris' }, { cueId: 'b', assetId: 'asset-b', title: 'Nice' }
            ] });
        fixture.componentRef.setInput('options', [{ optionId: 'nice', text: 'Ницца' }, { optionId: 'paris', text: 'Париж' }]);
        fixture.componentRef.setInput('sources', { 'asset-a': { url: '/a.mp3' }, 'asset-b': { url: '/b.mp3' } });
        fixture.componentRef.setInput('matches', {});
        fixture.detectChanges();
        const media = fixture.nativeElement.querySelector('audio') as HTMLAudioElement;
        const play = spyOn(media, 'play').and.resolveTo(); const pause = spyOn(media, 'pause');
        return { fixture, board: fixture.componentInstance, media, play, pause };
    }

    it('gives each cue a stable independent resting pattern and eases frequency changes', () => {
        expect(restingBars('exercise:a')).toEqual(restingBars('exercise:a'));
        expect(restingBars('exercise:a')).not.toEqual(restingBars('exercise:b'));
        expect(restingBars('exercise:a')).not.toEqual(restingBars('other:a'));
        const initial = Array(9).fill(.25);
        const first = smoothBars(initial, Array(9).fill(1), initial, 50);
        expect(first.every(value => value > .25 && value < .65)).toBeTrue();
        const second = smoothBars(first, Array(9).fill(1), initial, 50);
        expect(second.every((value, index) => value > first[index] && value < 1)).toBeTrue();
    });

    it('distinguishes low speech harmonics and high consonants instead of sampling silent Nyquist bins', () => {
        const low = new Uint8Array(512); low.fill(220, 2, 7);
        const high = new Uint8Array(512); high.fill(240, 90, 130);
        const lowBars = speechLevels(low, 48000);
        const highBars = speechLevels(high, 48000);
        expect(lowBars[0]).toBeGreaterThan(.5); expect(lowBars[8]).toBe(0);
        expect(highBars[0]).toBe(0); expect(highBars[7]).toBeGreaterThan(.5);
        expect(lowBars).not.toEqual(highBars);
        const baseline = restingBars('cue-a');
        const silence = smoothBars(baseline, Array(9).fill(0), baseline, 50);
        expect(new Set(silence).size).toBeGreaterThan(1);
        const active = smoothBars(baseline, highBars, baseline, 50);
        expect(active[7] - silence[7]).toBeGreaterThan(.2);
    });

    it('keeps transcript rows paired with the corresponding text rows', () => {
        const { fixture } = setup();
        fixture.componentRef.setInput('prompt', { kind: 'AUDIO_MATCH', instruction: '', transcriptAvailable: true,
            transcriptRevealed: true, cues: [{ cueId: 'a', assetId: 'asset-a', title: 'Paris', transcript: 'Une très longue transcription qui revient sur plusieurs lignes.' },
                { cueId: 'b', assetId: 'asset-b', title: 'Nice', transcript: 'Nice' }] });
        fixture.detectChanges();
        const columns = [...fixture.nativeElement.querySelectorAll('.match-column')] as HTMLElement[];
        expect(columns).toHaveSize(2);
        const a = columns[0].querySelectorAll('.match-cell')[1].getBoundingClientRect();
        const b = columns[1].querySelectorAll('.match-cell')[1].getBoundingClientRect();
        expect(a.top).toBeCloseTo(b.top, 1);
        fixture.destroy();
    });

    it('restarts a cue and uses a single player when switching to another cue', () => {
        const { fixture, board, media, play, pause } = setup();
        board.listen('a'); media.currentTime = 1; board.listen('a');
        expect(media.currentTime).toBe(0);
        board.listen('b'); expect(media.getAttribute('src')).toBe('/b.mp3');
        expect(play).toHaveBeenCalledTimes(3); expect(pause).toHaveBeenCalledTimes(3);
        expect(board.selectedCue()).toBe('b'); expect(fixture.nativeElement.querySelectorAll('audio')).toHaveSize(1);
        fixture.destroy(); expect(pause).toHaveBeenCalledTimes(4);
    });

    it('emits a proposed pair, then locks only server-confirmed matches', () => {
        const { fixture, board } = setup(); const selected = jasmine.createSpy('selected'); board.pairSelected.subscribe(selected);
        board.choose('paris'); expect(selected).not.toHaveBeenCalled();
        board.listen('a'); board.choose('paris'); expect(selected).toHaveBeenCalledWith({ cueId: 'a', optionId: 'paris' });
        fixture.componentRef.setInput('matches', { a: 'paris' }); fixture.detectChanges();
        expect(board.selectedCue()).toBeNull();
        expect(fixture.nativeElement.querySelector('.cue-tile').disabled).toBeTrue();
        expect(fixture.nativeElement.querySelectorAll('.matched')).toHaveSize(2);
        board.listen('a'); expect(board.selectedCue()).toBeNull(); fixture.destroy();
    });

    it('reports a failed cue and stops playback when the page becomes hidden', async () => {
        const { fixture, board, play, pause } = setup(); const failed = jasmine.createSpy('failed'); board.sourceFailed.subscribe(failed);
        play.and.rejectWith(new Error('unavailable')); board.listen('a'); await fixture.whenStable();
        expect(failed).toHaveBeenCalledWith('asset-a');
        spyOnProperty(document, 'hidden').and.returnValue(true);
        document.dispatchEvent(new Event('visibilitychange')); expect(pause).toHaveBeenCalled(); fixture.destroy();
    });
});
