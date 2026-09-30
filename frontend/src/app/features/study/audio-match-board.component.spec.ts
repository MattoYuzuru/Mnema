import { TestBed } from '@angular/core/testing';
import { AudioMatchBoardComponent } from './audio-match-board.component';

describe('AudioMatchBoardComponent', () => {
    function setup() {
        const fixture = TestBed.createComponent(AudioMatchBoardComponent);
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
