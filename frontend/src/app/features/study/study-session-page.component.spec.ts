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
        api = jasmine.createSpyObj<StudyApiService>('StudyApiService', ['start', 'read', 'submit']);
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
        fixture = TestBed.createComponent(StudySessionPageComponent); fixture.detectChanges();
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

    it('reveals self-check reference before offering four behavioral ratings', () => {
        api.start.and.returnValue(of({ value: session('SELF_CHECK'), replayed: false }));
        api.submit.and.returnValue(of({ value: outcome('PARTIAL'), replayed: false }));
        fixture = TestBed.createComponent(StudySessionPageComponent); fixture.detectChanges();
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

    it('retains and replays the exact command after an unknown network outcome', () => {
        api.start.and.returnValue(of({ value: session('TYPED'), replayed: false }));
        api.submit.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
        fixture = TestBed.createComponent(StudySessionPageComponent); fixture.detectChanges();
        fixture.componentInstance.setTypedAnswer('memory'); fixture.componentInstance.submitTyped(); fixture.detectChanges();
        const first = api.submit.calls.mostRecent().args[2];
        expect(fixture.nativeElement.textContent).toContain('безопасно повторите ту же попытку');

        api.submit.and.returnValue(of({ value: outcome('CORRECT', first), replayed: true }));
        fixture.componentInstance.retryPending();
        expect(api.submit.calls.mostRecent().args[2]).toBe(first);
        expect(recovery.save).toHaveBeenCalledWith({ deckId: deck.deckId, sessionId, pending: first });
    });

    it('renders labelled controls without horizontal overflow at 320/390/1440 and RTL', () => {
        api.start.and.returnValue(of({ value: session('TYPED'), replayed: false }));
        fixture = TestBed.createComponent(StudySessionPageComponent); fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement; root.style.display = 'block'; root.dir = 'rtl';
        expect(root.querySelector('label[for="typed-answer"]')).not.toBeNull();
        for (const width of [320, 390, 1440]) {
            root.style.width = `${width}px`; document.documentElement.style.fontSize = width === 320 ? '32px' : '16px';
            fixture.detectChanges(); expect(root.scrollWidth).toBeLessThanOrEqual(width + 1);
        }
        document.documentElement.style.fontSize = ''; root.removeAttribute('dir');
    });

    function presentation(type: 'TYPED' | 'SELF_CHECK'): StudyPresentation {
        return { presentationId, nonce: 'abcdefghijklmnop', ordinal: 0, exerciseRevisionId: id('4'), type,
            objectiveId: id('5'), objectiveRevisionId: id('6'), learningEpoch: '0', reference: 'memory',
            prompt: { kind: 'TEXT', text: 'What remains?' }, options: [], bindings: [{ bindingId: id('7'),
                role: 'ASSESSED', memberKey: id('8'), itemRevisionId: id('9'), ordinal: 0,
                nodeIds: [id('10')], display: { kind: 'NODE_TEXT' } }],
            evaluator: { id: type === 'TYPED' ? 'deterministic-text' : 'self-check', version: '1' } };
    }

    function session(type: 'TYPED' | 'SELF_CHECK'): ReadyStudySession {
        return { sessionId, deckId: deck.deckId, mode: 'SCHEDULED', status: 'ACTIVE', timezone: 'Europe/Moscow',
            localStudyDate: '2026-09-20', deckRevisionId: id('11'), exerciseGenerationId: id('12'),
            selectionPolicyVersion: 'deck-due-new-v1', reducer: { id: 'mnema-baseline', version: '1',
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
