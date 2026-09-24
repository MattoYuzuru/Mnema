import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { OwnDeck } from '../own-decks/own-deck.models';
import { StudyApiService } from './study-api.service';
import { AttemptCommand, AttemptOutcome, ReadyStudySession, StudyPresentation } from './study.models';
import { StudyRecoveryService } from './study-recovery.service';
import { StudySessionPageComponent } from './study-session-page.component';

describe('StudySessionPageComponent', () => {
    const id = (suffix: string) => `00000000-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const deck = metadataFixture.detail as unknown as OwnDeck;
    const sessionId = id('2');
    const presentationId = id('3');
    let api: jasmine.SpyObj<StudyApiService>;
    let recovery: jasmine.SpyObj<StudyRecoveryService>;
    let fixture: ComponentFixture<StudySessionPageComponent>;
    let now = 1000;

    beforeEach(() => {
        const decks = jasmine.createSpyObj<OwnDecksApiService>('OwnDecksApiService', ['detail']);
        decks.detail.and.returnValue(of(deck));
        api = jasmine.createSpyObj<StudyApiService>('StudyApiService',
            ['start', 'read', 'refill', 'submit', 'progress', 'replaySources', 'restart']);
        api.progress.and.returnValue(of({ asOf: '2026-09-20T10:00:00Z', items: [], nextCursor: null }));
        api.replaySources.and.returnValue(of({ asOf: '2026-09-20T10:00:00Z', localStudyDate: '2026-09-20', items: [] }));
        recovery = jasmine.createSpyObj<StudyRecoveryService>('StudyRecoveryService', ['restore', 'save', 'clear', 'now']);
        recovery.restore.and.returnValue(null); recovery.now.and.callFake(() => now);
        const router = jasmine.createSpyObj<Router>('Router', ['navigate']); router.navigate.and.resolveTo(true);
        TestBed.configureTestingModule({ providers: [
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId: deck.deckId }) } } },
            { provide: Router, useValue: router }, { provide: OwnDecksApiService, useValue: decks },
            { provide: StudyApiService, useValue: api }, { provide: StudyRecoveryService, useValue: recovery }
        ] });
    });

    it('keeps the typed reference hidden until the exact response is committed, then explains checking', () => {
        api.start.and.returnValue(of({ value: session('TYPED'), replayed: false }));
        api.submit.and.returnValue(of({ value: outcome('CORRECT'), replayed: false }));
        createStarted();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.textContent).toContain('What remains?');
        expect(root.textContent).not.toContain('memory');

        fixture.componentInstance.setTypedAnswer(' Memory '); now = 4200;
        fixture.componentInstance.submitTyped(); fixture.detectChanges();

        const command = api.submit.calls.mostRecent().args[2];
        expect(command.response).toEqual({ kind: 'TEXT', text: ' Memory ' });
        expect(command.durationMs).toBe(3200);
        expect(root.textContent).toContain('memory');
        expect(root.textContent).toContain('регистр не учитывается');
        expect(root.querySelector('#feedback-title')).not.toBeNull();
    });

    it('moves focus after each rendered Study state change', async () => {
        api.start.and.returnValue(of({ value: session('SELF_CHECK'), replayed: false }));
        api.submit.and.returnValue(of({ value: outcome('CORRECT'), replayed: false }));
        fixture = TestBed.createComponent(StudySessionPageComponent);
        const host = fixture.nativeElement as HTMLElement;
        document.body.appendChild(host);
        try {
            fixture.detectChanges();
            fixture.componentInstance.startScheduled('STANDARD'); fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(host.querySelector('[data-answer-control]'));

            fixture.componentInstance.reveal(); fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(host.querySelector('[data-first-rating]'));

            fixture.componentInstance.rate('FULL'); fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(host.querySelector('#feedback-title'));
        } finally { host.remove(); }
    });

    it('reveals self-check reference before offering four behavioral ratings', () => {
        api.start.and.returnValue(of({ value: session('SELF_CHECK'), replayed: false }));
        api.submit.and.returnValue(of({ value: outcome('PARTIAL'), replayed: false }));
        createStarted();
        expect(api.submit).not.toHaveBeenCalled();
        fixture.componentInstance.reveal(); fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.textContent).toContain('memory');
        expect(root.textContent).toContain('Не вспомнил');
        expect(root.textContent).toContain('Вспомнил полностью');

        fixture.componentInstance.rate('PARTIAL');
        const command = api.submit.calls.mostRecent().args[2];
        expect(command.response).toEqual({ kind: 'SELF_CHECK', rating: 'PARTIAL' });
        expect(command.hintsUsed).toEqual(['REVEAL']);
    });

    it('renders one cloze blank and caps the exact attempt after a grapheme hint', () => {
        api.start.and.returnValue(of({ value: session('CLOZE_SINGLE'), replayed: false }));
        api.submit.and.returnValue(of({ value: outcome('CORRECT'), replayed: false }));
        createStarted();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('label[for="cloze-answer"]')).not.toBeNull();
        expect(root.textContent).not.toContain('memory');

        fixture.componentInstance.showClozeHint(); fixture.detectChanges();
        expect(root.textContent).toContain('начинается с «m»');
        fixture.componentInstance.setTypedAnswer('memory');
        fixture.componentInstance.submitTyped();
        const command = api.submit.calls.mostRecent().args[2];
        expect(command.response).toEqual({ kind: 'TEXT', text: 'memory' });
        expect(command.hintsUsed).toEqual(['REVEAL_FIRST_GRAPHEME']);
    });

    it('uses native single-choice radios and submits only the selected server option', () => {
        api.start.and.returnValue(of({ value: session('SINGLE_CHOICE'), replayed: false }));
        api.submit.and.returnValue(of({ value: outcome('CORRECT'), replayed: false }));
        createStarted();
        const root = fixture.nativeElement as HTMLElement;
        const radios = root.querySelectorAll<HTMLInputElement>('input[type="radio"]');
        expect(radios.length).toBe(2);
        expect(root.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBeTrue();

        fixture.componentInstance.selectOption(radios[0].value);
        fixture.componentInstance.submitChoice();
        expect(api.submit.calls.mostRecent().args[2].response)
            .toEqual({ kind: 'CHOICE', optionId: radios[0].value });
        expect(api.submit.calls.mostRecent().args[2].hintsUsed).toEqual([]);
    });

    it('retains and replays the exact command after an unknown network outcome', () => {
        api.start.and.returnValue(of({ value: session('TYPED'), replayed: false }));
        api.submit.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
        createStarted();
        fixture.componentInstance.setTypedAnswer('memory'); fixture.componentInstance.submitTyped(); fixture.detectChanges();
        const first = api.submit.calls.mostRecent().args[2];
        expect(fixture.nativeElement.textContent).toContain('безопасно повторите ту же попытку');

        api.submit.and.returnValue(of({ value: outcome('CORRECT', first), replayed: true }));
        fixture.componentInstance.retryPending();
        expect(api.submit.calls.mostRecent().args[2]).toBe(first);
        expect(recovery.save).toHaveBeenCalledWith({ deckId: deck.deckId, sessionId, pending: first });
    });

    it('connects completion replay, practice, explainable progress and confirmed restart', () => {
        const terminal: ReadyStudySession = { ...session('TYPED'), status: 'COMPLETE', presentations: [] };
        api.start.and.returnValue(of({ value: terminal, replayed: false }));
        api.progress.and.returnValue(of({ asOf: '2026-09-20T10:00:00Z', nextCursor: null, items: [{
            memberKey: id('20'), itemRevisionId: id('21'), state: 'DUE',
            objectiveCoverage: { enabled: 2, introduced: 1, assessed: 1 },
            lastAssessedAt: '2026-09-19T10:00:00Z', nextDue: '2026-09-20T09:00:00Z'
        }] }));
        api.replaySources.and.returnValue(of({ asOf: '2026-09-20T10:00:00Z', localStudyDate: '2026-09-20',
            items: [{ sessionId: id('30'), completedAt: '2026-09-20T09:00:00Z', presentationCount: 1 }] }));
        api.restart.and.returnValue(of({ value: { commandId: id('31'), restartedAt: '2026-09-20T10:00:00Z',
            objectiveCount: 2, learningEpochs: [{ objectiveId: id('22'), learningEpoch: '1' },
                { objectiveId: id('23'), learningEpoch: '1' }] }, replayed: false }));
        spyOn(window, 'confirm').and.returnValue(true);

        createStarted();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.textContent).toContain('Без условного процента');
        expect(root.textContent).toContain('Пора повторить');
        expect(root.textContent).not.toContain('%');

        fixture.componentInstance.startReplay();
        expect(api.start.calls.mostRecent().args[2]).toEqual({ mode: 'REPLAY', sourceSessionId: id('30') });
        fixture.componentInstance.setIncludeNewPractice(true);
        fixture.componentInstance.setPracticeOrder('WEAKEST_FIRST');
        fixture.componentInstance.startPractice();
        expect(api.start.calls.mostRecent().args[2]).toEqual({ mode: 'PRACTICE', includeNew: true,
            order: 'WEAKEST_FIRST' });

        fixture.componentInstance.restartMaterial(fixture.componentInstance.progress()[0]);
        expect(window.confirm).toHaveBeenCalled();
        expect(api.restart).toHaveBeenCalledWith(deck.deckId, jasmine.any(String), [id('20')]);
    });

    it('renders labelled controls without horizontal overflow at 320/390/1440 and RTL', () => {
        api.start.and.returnValue(of({ value: session('TYPED'), replayed: false }));
        createStarted();
        const root = fixture.nativeElement as HTMLElement; root.style.display = 'block'; root.dir = 'rtl';
        expect(root.querySelector('label[for="typed-answer"]')).not.toBeNull();
        for (const width of [320, 390, 1440]) {
            root.style.width = `${width}px`; document.documentElement.style.fontSize = width === 320 ? '32px' : '16px';
            fixture.detectChanges(); expect(root.scrollWidth).toBeLessThanOrEqual(width + 1);
        }
        document.documentElement.style.fontSize = ''; root.removeAttribute('dir');
    });

    it('offers honest session presets before issuing a scheduled command', () => {
        api.start.and.returnValue(of({ value: session('TYPED'), replayed: false }));
        fixture = TestBed.createComponent(StudySessionPageComponent); fixture.detectChanges();
        expect(api.start).not.toHaveBeenCalled();
        expect(fixture.nativeElement.textContent).toContain('До 10 заданий');
        expect(fixture.nativeElement.textContent).toContain('не больше двух новых');

        fixture.componentInstance.startScheduled('QUICK');
        expect(api.start.calls.mostRecent().args[2]).toEqual({ mode: 'SCHEDULED', preset: 'QUICK' });
    });

    function createStarted(): void {
        fixture = TestBed.createComponent(StudySessionPageComponent);
        fixture.detectChanges();
        fixture.componentInstance.startScheduled('STANDARD');
        fixture.detectChanges();
    }

    function presentation(type: StudyPresentation['type']): StudyPresentation {
        const assessed = { bindingId: id('7'), role: 'ASSESSED' as const, memberKey: id('8'),
            itemRevisionId: id('9'), ordinal: 0, nodeIds: [id('10')], display: { kind: 'NODE_TEXT' } };
        const options = type === 'SINGLE_CHOICE'
            ? [{ optionId: id('15'), text: 'memory' }, { optionId: id('16'), text: 'forgetting' }] : [];
        const bindings = type === 'SINGLE_CHOICE' ? [assessed,
            { ...assessed, bindingId: id('15'), role: 'OPTION' as const, ordinal: 1 },
            { ...assessed, bindingId: id('16'), role: 'OPTION' as const, ordinal: 2, nodeIds: [id('17')] }
        ] : [assessed];
        return { presentationId, nonce: 'abcdefghijklmnop', ordinal: 0, exerciseRevisionId: id('4'), type,
            objectiveId: id('5'), objectiveRevisionId: id('6'), learningEpoch: '0', reference: 'memory',
            prompt: { kind: 'TEXT', text: 'What remains?' }, options, bindings,
            evaluator: { id: type === 'SELF_CHECK' ? 'self-check'
                : type === 'SINGLE_CHOICE' ? 'deterministic-choice' : 'deterministic-text', version: '1' } };
    }

    function session(type: StudyPresentation['type']): ReadyStudySession {
        return { sessionId, deckId: deck.deckId, mode: 'SCHEDULED', status: 'ACTIVE', timezone: 'Europe/Moscow',
            localStudyDate: '2026-09-20', deckRevisionId: id('11'), exerciseGenerationId: id('12'),
            selectionPolicyVersion: 'deck-due-new-v2', budget: { maxPresentations: 20, maxNewObjectives: 5 },
            issuedCount: 1, reducer: { id: 'mnema-baseline', version: '1',
                configId: id('13'), configHash: `sha256:${'a'.repeat(64)}` }, seed: '42', nextCursor: null,
            expiresAt: '2026-09-21T10:00:00Z', presentations: [presentation(type)] };
    }

    function outcome(result: 'CORRECT' | 'PARTIAL', command?: AttemptCommand): AttemptOutcome {
        return { attemptId: command?.attemptId ?? id('14'), presentationId, mode: 'SCHEDULED', status: 'ASSESSED',
            feedback: { result, reference: result === 'CORRECT' ? 'memory' : null,
                appliedRules: result === 'CORRECT' ? ['TRIM', 'CASE_FOLD'] : ['SELF_REPORT'], reasonCodes: [] },
            canonicalEffects: true, transition: { beforeLevel: 0, afterLevel: 1, nextDue: '2026-09-21T10:00:00Z' } };
    }
});
