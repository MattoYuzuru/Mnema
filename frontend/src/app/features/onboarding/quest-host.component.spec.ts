import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { QuestNotice, QuestPhase, QuestDeckOption } from '../../core/onboarding/quest.models';
import { TourEngine } from '../../core/onboarding/tour-engine.service';
import { QuestHostComponent } from './quest-host.component';
import { QuestLauncher } from './quest-launcher.service';

describe('QuestHostComponent (the chooser in a modal dialog)', () => {
    const request = signal<{ route: string; milestone: string } | null>(null);
    const phase = signal<QuestPhase>('loading');
    const decks = signal<readonly QuestDeckOption[]>([]);
    const notice = signal<QuestNotice | null>(null);
    const selected = signal<string | null>(null);
    const launcher = { request, phase, decks, selected, notice, milestone: signal<string | null>('VOICE_ANSWER'),
        choose: vi.fn(), start: vi.fn(), close: vi.fn(() => request.set(null)), act: vi.fn(), retry: vi.fn() };
    const engineActive = signal(false);

    const render = async () => {
        const fixture = TestBed.createComponent(QuestHostComponent);
        fixture.detectChanges();
        const settle = async (): Promise<void> => { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
        document.body.append(fixture.nativeElement as HTMLElement);
        await settle();
        return { fixture, root: fixture.nativeElement as HTMLElement, settle };
    };

    beforeEach(() => {
        request.set(null);
        phase.set('loading');
        engineActive.set(false);
        Object.values(launcher).forEach(value => { if (typeof value === 'function' && 'mockClear' in value) (value as ReturnType<typeof vi.fn>).mockClear(); });
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: QuestLauncher, useValue: launcher }, { provide: TourEngine, useValue: { active: engineActive } }] });
    });
    afterEach(() => document.body.replaceChildren());

    it('draws nothing until a quest is being prepared', async () => {
        const { root } = await render();
        expect(root.querySelector('dialog')).toBeNull();
    });

    it('opens a modal dialog named by the milestone, with the chooser inside, and puts the focus on its heading', async () => {
        const opener = document.createElement('a');
        opener.href = '#';
        document.body.append(opener);
        opener.focus();
        const { root, settle } = await render();
        request.set({ route: 'QUEST_VOICE', milestone: 'VOICE_ANSWER' });
        await settle();
        const dialog = root.querySelector('dialog')!;
        expect(dialog.hasAttribute('open')).toBe(true);
        expect(dialog.getAttribute('aria-label')).toBe('Ответьте голосом');
        expect(dialog.querySelector('app-quest-chooser h2')?.textContent).toBe('Ответьте голосом');
        expect(document.activeElement).toBe(dialog.querySelector('h2'));
    });

    it('moves the focus to the heading again when the state changes, so the new state is read under the same name', async () => {
        const { root, settle } = await render();
        request.set({ route: 'QUEST_VOICE', milestone: 'VOICE_ANSWER' });
        await settle();
        (document.activeElement as HTMLElement).blur();
        phase.set('blocked');
        notice.set({ text: 'Недоступно.', actions: [] });
        await settle();
        expect(document.activeElement).toBe(root.querySelector('h2'));
    });

    it('asks «Не сейчас» on Escape instead of closing by itself', async () => {
        const { root, settle } = await render();
        request.set({ route: 'QUEST_VOICE', milestone: 'VOICE_ANSWER' });
        await settle();
        const cancel = new Event('cancel', { cancelable: true });
        root.querySelector('dialog')!.dispatchEvent(cancel);
        expect(cancel.defaultPrevented).toBe(true);
        expect(launcher.close).toHaveBeenCalledTimes(1);
    });

    it('returns the focus to the chevron when it closes without a quest, and leaves it to the quest\'s card when one started', async () => {
        const opener = document.createElement('a');
        opener.href = '#';
        document.body.append(opener);
        opener.focus();
        const { settle } = await render();
        request.set({ route: 'QUEST_VOICE', milestone: 'VOICE_ANSWER' });
        await settle();
        expect(document.activeElement).not.toBe(opener);
        request.set(null);
        await settle();
        expect(document.activeElement).toBe(opener);
        opener.blur();
        request.set({ route: 'QUEST_VOICE', milestone: 'VOICE_ANSWER' });
        await settle();
        engineActive.set(true);
        request.set(null);
        await settle();
        expect(document.activeElement).not.toBe(opener);
    });

    it('passes the learner\'s choices to the launcher', async () => {
        const { root, settle } = await render();
        request.set({ route: 'QUEST_VOICE', milestone: 'VOICE_ANSWER' });
        decks.set([{ deckId: 'a', title: 'Японский', sample: false }]);
        selected.set('a');
        phase.set('choose');
        await settle();
        (root.querySelector('button.primary') as HTMLButtonElement).click();
        expect(launcher.start).toHaveBeenCalledTimes(1);
        (root.querySelector('button.quiet') as HTMLButtonElement).click();
        expect(launcher.close).toHaveBeenCalled();
    });
});
