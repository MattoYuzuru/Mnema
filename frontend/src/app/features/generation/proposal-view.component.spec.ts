import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { MediaPlaybackApi } from '../../content/rendering/media-playback.api';
import { CAPABILITIES_UNAVAILABLE } from '../authoring/capabilities-api.service';
import { ARRIVE_MS, ProposalViewComponent } from './proposal-view.component';
import { Arrival, DraftBlocks } from './workshop-events';
import { DetailEntry, WorkshopSessionStore } from './workshop-session.store';
import { ArtifactSummary, SessionState, parseArtifactDetail, parseArtifactSummary, parseEventsPage } from './generation.models';
import { artifactDetailWithNote, artifactWith, clone, eventsEnvelope, examples, ids, wireBlocks } from './generation-test-data';

describe('ProposalViewComponent', () => {
    let fixture: ComponentFixture<ProposalViewComponent>;
    const emitted: string[] = [];
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const buttons = (): string[] => [...root().querySelectorAll('.proposal-actions button, .proposal-actions a')].map(button => button.textContent!.trim());
    const click = (label: string): void => [...root().querySelectorAll<HTMLElement>('.proposal-actions button, .proposal-actions a')]
        .find(button => button.textContent!.trim() === label)!.click();
    const summary = (state: string, overrides: Record<string, unknown> = {}): ArtifactSummary =>
        parseArtifactSummary(artifactWith(ids.first, 0, state, overrides));
    const entry = (state = 'PROPOSED', overrides: Record<string, unknown> = {}, phase: DetailEntry['phase'] = 'ready'): DetailEntry => ({
        phase, forRevision: ids.revision, stale: false,
        detail: parseArtifactDetail({ ...clone(examples['artifactDetailItem']), state, ...overrides }) });
    const draft = (...texts: string[]): DraftBlocks => {
        const blocks = parseEventsPage(eventsEnvelope([wireBlocks(1, 0, 1, ...texts)], '1')).events[0];
        if (blocks?.type !== 'BLOCKS_APPENDED') throw new Error('blocks expected');
        return { generation: 1, blocks: blocks.blocks };
    };

    function create(artifact: ArtifactSummary, extra: Record<string, unknown> = {}, session: SessionState = 'REVIEW'): void {
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: MediaPlaybackApi, useValue: { read: () => new Promise(() => {}) } },
            { provide: WorkshopSessionStore, useValue: { edits: signal({}), busy: signal(new Set<string>()) } }] });
        fixture = TestBed.createComponent(ProposalViewComponent);
        fixture.componentRef.setInput('artifact', artifact);
        fixture.componentRef.setInput('index', 2);
        fixture.componentRef.setInput('total', 10);
        fixture.componentRef.setInput('deckId', ids.deckId);
        fixture.componentRef.setInput('sessionState', session);
        for (const [name, value] of Object.entries(extra)) fixture.componentRef.setInput(name, value);
        emitted.length = 0;
        for (const name of ['approve', 'reject', 'undo', 'handoff', 'retry', 'reload'] as const) {
            fixture.componentInstance[name].subscribe(() => emitted.push(name));
        }
        fixture.detectChanges();
    }

    afterEach(() => vi.useRealTimers());

    describe('while it is waiting or being written', () => {
        it('shows a queued material as waiting, busy and without actions', () => {
            create(summary('QUEUED', { title: '', currentRevisionId: null }));
            expect(root().querySelector('article')?.getAttribute('aria-busy')).toBe('true');
            expect(root().querySelector('h2')?.textContent).toBe('Материал 3 из 10, ждёт очереди');
            expect(root().textContent).toContain('Мнема поставила материал в очередь');
            expect(root().querySelector('.skeleton')?.getAttribute('aria-hidden')).toBe('true');
            expect(buttons()).toEqual([]);
        });

        it('shows the blocks written so far as a growing document, and a skeleton before the first block', () => {
            create(summary('GENERATING', { currentRevisionId: null }));
            expect(root().textContent).toContain('Мнема пишет материал');
            fixture.componentRef.setInput('draft', draft('Раз', 'Два'));
            fixture.detectChanges();
            expect(root().querySelectorAll('.draft .native-document > p')).toHaveLength(2);
            expect(root().querySelector('.skeleton')).toBeNull();
            expect(root().querySelector('article')?.getAttribute('aria-busy')).toBe('true');
        });

        it('is not a live region and announces nothing by itself while it is written', () => {
            create(summary('GENERATING', { currentRevisionId: null }), { draft: draft('Раз') });
            expect(root().querySelector('[aria-live], [role=status], [role=alert]')).toBeNull();
        });
    });

    describe('a block arriving', () => {
        const arrival = (token: number, fromIndex: number, artifactId = ids.first): Arrival => ({ artifactId, fromIndex, token });

        it('marks the new blocks with is-arriving for at most 600 ms and then takes the mark away', async () => {
            vi.useFakeTimers();
            create(summary('GENERATING', { currentRevisionId: null }), { draft: draft('Раз', 'Два', 'Три') });
            fixture.componentRef.setInput('arrival', arrival(1, 1));
            fixture.detectChanges();
            await fixture.whenStable();
            const blocks = [...root().querySelectorAll('.draft .native-document > *')];
            expect(blocks.map(block => block.classList.contains('is-arriving'))).toEqual([false, true, true]);
            vi.advanceTimersByTime(ARRIVE_MS);
            expect(blocks.some(block => block.classList.contains('is-arriving'))).toBe(false);
            expect(ARRIVE_MS).toBeLessThanOrEqual(600);
        });

        it('shows them at once for reduced motion, and ignores an arrival of another material or one it has seen', async () => {
            vi.useFakeTimers();
            create(summary('GENERATING', { currentRevisionId: null }), { draft: draft('Раз', 'Два') });
            const matchMedia = vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList);
            fixture.componentRef.setInput('arrival', arrival(1, 0));
            fixture.detectChanges();
            await fixture.whenStable();
            expect(root().querySelector('.is-arriving')).toBeNull();
            matchMedia.mockReturnValue({ matches: false } as MediaQueryList);
            fixture.componentRef.setInput('arrival', arrival(2, 0, ids.second));
            fixture.detectChanges();
            await fixture.whenStable();
            expect(root().querySelector('.is-arriving')).toBeNull();
            fixture.componentRef.setInput('arrival', arrival(3, 0));
            fixture.detectChanges();
            await fixture.whenStable();
            expect(root().querySelectorAll('.is-arriving')).toHaveLength(2);
            vi.advanceTimersByTime(ARRIVE_MS);
            fixture.componentRef.setInput('arrival', arrival(3, 0));
            fixture.detectChanges();
            await fixture.whenStable();
            expect(root().querySelector('.is-arriving')).toBeNull();
        });

        it('leaves the media blocks out of the draft (their frames hold the place) and counts only the text blocks when it marks the new ones', async () => {
            vi.useFakeTimers();
            const text = draft('Раз', 'Два', 'Три').blocks;
            const image = { id: '00000000-0000-4000-8000-0000000009aa', type: 'image', version: 1, attrs: { assetId: ids.second, alt: 'схема' }, content: [] };
            create(summary('GENERATING', { currentRevisionId: null }), { draft: { generation: 1, blocks: [text[0]!, image, text[1]!, text[2]!] } });
            fixture.componentRef.setInput('arrival', arrival(1, 2));
            fixture.detectChanges();
            await fixture.whenStable();
            const blocks = [...root().querySelectorAll('.draft .native-document > *')];
            expect(blocks).toHaveLength(3);
            expect(blocks.some(block => block.tagName === 'FIGURE')).toBe(false);
            expect(blocks.map(block => block.classList.contains('is-arriving'))).toEqual([false, true, true]);
        });

        it('does not animate what arrived before it was shown', async () => {
            vi.useFakeTimers();
            create(summary('GENERATING', { currentRevisionId: null }), { draft: draft('Раз'), arrival: arrival(7, 0) });
            await fixture.whenStable();
            expect(root().querySelector('.is-arriving')).toBeNull();
        });

        it('clears its timers when it is destroyed', async () => {
            vi.useFakeTimers();
            create(summary('GENERATING', { currentRevisionId: null }), { draft: draft('Раз') });
            fixture.componentRef.setInput('arrival', arrival(1, 0));
            fixture.detectChanges();
            await fixture.whenStable();
            fixture.destroy();
            expect(vi.getTimerCount()).toBe(0);
        });
    });

    describe('a proposal', () => {
        it('shows the stored revision and offers «Одобрить и далее →» as the primary action, then «Править самому» and «Отклонить»', () => {
            create(summary('PROPOSED'), { entry: entry() });
            expect(root().querySelector('.final app-native-media-surface, .final app-native-document-renderer')).not.toBeNull();
            expect(root().querySelector('article')?.getAttribute('aria-busy')).toBe('false');
            expect(buttons()).toEqual(['Одобрить и далее →', 'Править самому', 'Отклонить']);
            expect(root().querySelector('.proposal-actions .button.primary')?.textContent?.trim()).toBe('Одобрить и далее →');
            click('Одобрить и далее →');
            click('Править самому');
            click('Отклонить');
            expect(emitted).toEqual(['approve', 'handoff', 'reject']);
            expect(root().querySelector('.proposal-actions')?.getAttribute('role')).toBe('group');
        });

        it('says above the material that a selection can be rewritten, only where it can (a proposal in a running session)', () => {
            const hint = (): string | undefined => root().querySelector('.selection-hint')?.textContent?.trim();
            create(summary('PROPOSED'), { entry: entry() });
            expect(hint()).toBe('Выделите фрагмент текста, чтобы попросить Мнему переписать его. С клавиатуры: Shift+F10 или клавиша меню; Tab после выделения тоже доходит до кнопки, но после ссылок и плееров самого блока.');
            expect(root().querySelector('.selection-hint')!.nextElementSibling?.classList.contains('final')).toBe(true);
            create(summary('PROPOSED'), { entry: entry() }, 'CANCELLED');
            expect(hint()).toBeUndefined();
            create(summary('REVISING'), { entry: entry('REVISING') });
            expect(hint()).toBeUndefined();
            create(summary('PROPOSED', { currentRevisionId: '4e700000-0000-4000-8000-0000000000aa' }), { entry: entry() });
            expect(hint()).toBeUndefined();
            create(summary('PROPOSED'), { entry: null });
            expect(hint()).toBeUndefined();
        });

        it('hands the server capabilities to the media actions under the audio of the material', () => {
            create(summary('PROPOSED'), { entry: entry() });
            const speech = (): HTMLElement => root().querySelector<HTMLElement>('.media-actions')!;
            expect(speech().querySelector('.media-unavailable')).not.toBeNull();
            expect([...speech().querySelectorAll('.strip-button')].map(button => button.textContent!.trim())).toEqual(['Озвучить заново', 'Убрать']);
            create(summary('PROPOSED'), { entry: entry(), capabilities: { ...CAPABILITIES_UNAVAILABLE, textToSpeech: { available: true, reason: null } } });
            expect(speech().querySelector('.media-unavailable')).toBeNull();
        });

        it('waits for the stored revision before it lets the user approve or edit it', () => {
            create(summary('PROPOSED'), { entry: null });
            expect(root().textContent).toContain('Загружаем материал');
            const [approve, handoff, reject] = [...root().querySelectorAll<HTMLButtonElement>('.proposal-actions button')];
            const blocked = (button: HTMLElement) => button.getAttribute('aria-disabled') === 'true';
            expect([blocked(approve!), blocked(handoff!), blocked(reject!)]).toEqual([true, true, false]);
            approve!.click();
            expect(emitted).toEqual([]);
            expect(root().textContent).toContain('Материал обновился. Загружаем новую версию');
            expect(approve!.getAttribute('aria-describedby')).toBe('approve-wait');
        });

        it('does not let the user approve a revision other than the one the summary names', () => {
            create(summary('PROPOSED', { currentRevisionId: '4e700000-0000-4000-8000-0000000000aa' }), { entry: entry() });
            expect(root().querySelector<HTMLButtonElement>('.proposal-actions .primary')!.getAttribute('aria-disabled')).toBe('true');
        });

        it('keeps «Одобрить» waiting while media is on its way, saying so and showing a paper frame that holds the place', () => {
            const slot = { slotKey: 'a1', kind: 'IMAGE', nodeId: '00000000-0000-4000-8000-000000000006', assetId: '00000000-0000-4000-a000-000000000001',
                state: 'GENERATING', errorCode: null };
            create(summary('PROPOSED', { mediaSlotCounts: { total: 1, ready: 0, failed: 0 } }), { entry: entry('PROPOSED', { mediaSlots: [slot], mediaSlotCounts: { total: 1, ready: 0, failed: 0 } }) });
            expect(root().querySelector<HTMLButtonElement>('.proposal-actions .primary')!.getAttribute('aria-disabled')).toBe('true');
            expect(root().querySelector('#approve-wait')?.textContent).toContain('Медиа ещё готовятся');
            const frames = [...root().querySelectorAll('.slots .slot')];
            expect(frames).toHaveLength(1);
            expect(frames[0]!.textContent).toContain('Подбираем изображение…');
            expect(root().querySelector('.slots')?.getAttribute('aria-label')).toBe('Медиа материала');
        });

        it('says when media failed and what to do about it, and frames the slots only by counts while the text is still being written', () => {
            create(summary('PROPOSED', { mediaSlotCounts: { total: 2, ready: 0, failed: 1 } }), { entry: entry('PROPOSED', { mediaSlots: [], mediaSlotCounts: { total: 2, ready: 0, failed: 1 } }) });
            expect(root().querySelector('#approve-wait')?.textContent).toContain('Правьте материал сами');
            const frames = [...root().querySelectorAll('.slots .slot')];
            expect(frames.map(frame => frame.textContent!.trim())).toEqual(['Не удалось подготовить медиа', 'Медиа в работе']);
            expect(frames[0]!.classList.contains('is-failed')).toBe(true);
        });

        it('shows no frame for media that is ready', () => {
            create(summary('PROPOSED'), { entry: entry() });
            expect(root().querySelector('.slots')).toBeNull();
            expect(root().querySelector('#approve-wait')).toBeNull();
        });

        it('disables every action while a command is running', () => {
            create(summary('PROPOSED'), { entry: entry(), busy: true });
            expect([...root().querySelectorAll<HTMLButtonElement>('.proposal-actions button')].every(button => button.getAttribute('aria-disabled') === 'true')).toBe(true);
            root().querySelector<HTMLButtonElement>('.proposal-actions button')!.click();
            expect(emitted).toEqual([]);
        });

        it('offers no approve in a session that does not allow it', () => {
            create(summary('PROPOSED'), { entry: entry() }, 'CLOSED');
            expect(root().querySelector<HTMLButtonElement>('.proposal-actions .primary')!.getAttribute('aria-disabled')).toBe('true');
            expect(buttons()).toEqual(['Одобрить и далее →']);
        });

        it('keeps a revising material readable and says it is being rewritten', () => {
            create(summary('REVISING'), { entry: entry('REVISING') });
            expect(root().querySelector('article')?.getAttribute('aria-busy')).toBe('true');
            expect(root().textContent).toContain('Мнема переписывает материал');
            expect(root().querySelector('.final')).not.toBeNull();
            expect(buttons()).toEqual([]);
        });

        it('tells the user to reload when the stored revision cannot be read, and shows the draft meanwhile if there is one', () => {
            create(summary('PROPOSED'), { entry: { phase: 'error', detail: null, forRevision: ids.revision, stale: false } });
            expect(root().querySelector('.notice.error')?.getAttribute('role')).toBe('alert');
            root().querySelector<HTMLButtonElement>('.notice.error button')!.click();
            expect(emitted).toEqual(['reload']);
            create(summary('PROPOSED'), { entry: { phase: 'loading', detail: null, forRevision: ids.revision, stale: false }, draft: draft('Раз') });
            expect(root().querySelectorAll('.draft p')).toHaveLength(1);
        });

        it('only lets an exercise be rejected: its preview and approval belong to a later task', () => {
            create(summary('PROPOSED', { targetKind: 'EXERCISE' }), { entry: entry('PROPOSED', { ...clone(examples['artifactDetailExercise']), artifactId: ids.first,
                currentRevisionId: ids.revision, revision: { ...clone(examples['artifactDetailExercise']).revision, revisionId: ids.revision } }) });
            expect(root().textContent).toContain('Предпросмотр упражнений появится позже');
            expect(buttons()).toEqual(['Отклонить']);
        });
    });

    describe('a failed material', () => {
        it('shows the reason in words and that the limit was not charged, and offers to retry or to write it oneself', () => {
            create(summary('FAILED', { errorCode: 'PROVIDER_UNAVAILABLE', currentRevisionId: null }));
            expect(root().querySelector('.notice.error')?.textContent).toContain('Сервис ИИ временно недоступен.');
            expect(root().querySelector('.notice.error')?.textContent).toContain('За этот материал лимит не списан.');
            expect(buttons()).toEqual(['Попробовать снова', 'Написать самому']);
            expect(root().querySelector('.proposal-actions a')?.getAttribute('href')).toBe(`/decks/${ids.deckId}/materials/new?write=1`);
            click('Попробовать снова');
            expect(emitted).toEqual(['retry']);
        });

        it('does not offer a retry after a refusal, nor in a stopped session', () => {
            create(summary('FAILED', { errorCode: 'REFUSAL', currentRevisionId: null }));
            expect(buttons()).toEqual(['Написать самому']);
            create(summary('FAILED', { errorCode: 'CANCELLED', currentRevisionId: null }), {}, 'CANCELLED');
            expect(buttons()).toEqual(['Написать самому']);
            expect(root().querySelector('.notice.error')?.textContent).not.toContain('лимит не списан');
        });
    });

    describe('other states', () => {
        it('shows a rejected material read-only with «Вернуть»', () => {
            create(summary('REJECTED'), { entry: entry('REJECTED') });
            expect(root().textContent).toContain('Вы отклонили этот материал');
            expect(buttons()).toEqual(['Вернуть']);
            click('Вернуть');
            expect(emitted).toEqual(['undo']);
        });

        it('asks for a decision about a stale material: write again, edit it oneself or reject it', () => {
            create(summary('STALE'), { entry: entry('STALE') });
            expect(root().textContent).toContain('Заметка изменилась, пока писался материал');
            expect(buttons()).toEqual(['Попробовать снова', 'Править самому', 'Отклонить']);
            click('Попробовать снова');
            expect(emitted).toEqual(['retry']);
        });

        it('links an approved material to its page and a handed-off one to the editor', () => {
            create(summary('PUBLISHED', { publishedRef: { kind: 'ITEM', memberKey: '44444444-4444-4444-8444-444444444444',
                itemRevisionId: '55555555-5555-4555-8555-555555555555', ordinal: 4 } }));
            expect(root().querySelector('.notice a')?.getAttribute('href')).toBe(`/decks/${ids.deckId}/materials/44444444-4444-4444-8444-444444444444`);
            create(summary('HANDED_OFF'));
            expect(root().querySelector('.notice a')?.getAttribute('href')).toBe(`/decks/${ids.deckId}/materials/new?write=1`);
            create(summary('HANDED_OFF'), { handoffDraftId: 'd4af7000-0000-4000-8000-000000000001' });
            expect(root().querySelector('.notice a')?.getAttribute('href')).toBe(`/decks/${ids.deckId}/materials/new?write=1&draft=d4af7000-0000-4000-8000-000000000001`);
            create(summary('PUBLISHED', { publishedRef: null }));
            expect(root().querySelector('.notice a')).toBeNull();
        });

        it('moves focus to its title on request, and the title can take it without being a tab stop', () => {
            create(summary('PROPOSED'), { entry: entry() });
            document.body.append(root());
            fixture.componentInstance.focusHeading();
            expect(document.activeElement).toBe(root().querySelector('h2'));
            expect(root().querySelector('h2')?.getAttribute('tabindex')).toBe('-1');
            root().remove();
            expect(signal(1)()).toBe(1);
        });
    });

    describe('the note it was written from (#290)', () => {
        const withStatus = (status: string | null, state = 'PROPOSED'): DetailEntry => ({ phase: 'ready', forRevision: ids.revision, stale: false,
            detail: parseArtifactDetail({ ...artifactDetailWithNote(status), state }) });
        const tag = (): HTMLElement | null => root().querySelector<HTMLElement>('.note-changed-tag');

        it('says «заметка изменилась» in the header when the pinned note changed, and that the text was written from the earlier one', () => {
            create(summary('PROPOSED'), { entry: withStatus('CHANGED') });
            expect(tag()?.textContent).toBe('заметка изменилась');
            expect(root().querySelector('header')?.contains(tag())).toBe(true);
            expect(root().querySelector('.note-changed')?.textContent).toContain('Материал написан по её прежней версии.');
            expect(tag()?.closest('[role], [aria-live]')).toBeNull();
        });

        it('stays silent for a note that is current, archived, deleted, not reported or not loaded yet, and for another source', () => {
            for (const status of ['CURRENT', 'ARCHIVED', 'DELETED', null]) {
                create(summary('PROPOSED'), { entry: withStatus(status) });
                expect(tag(), String(status)).toBeNull();
            }
            create(summary('PROPOSED'));
            expect(tag()).toBeNull();
            create(summary('PROPOSED'), { entry: { phase: 'ready', forRevision: ids.revision, stale: false,
                detail: parseArtifactDetail({ ...clone(examples['artifactDetailItem']), sourceRefs: [{ type: 'ITEM', memberKey: ids.first, itemRevisionId: ids.revision }] }) } });
            expect(tag()).toBeNull();
        });

        it('does not repeat itself for a stale material, which already says its note changed, and changes nothing in the actions', () => {
            create(summary('STALE'), { entry: withStatus('CHANGED', 'STALE') });
            expect(tag()).toBeNull();
            expect(root().textContent).toContain('Заметка изменилась, пока писался материал.');
            create(summary('PROPOSED'), { entry: withStatus('CHANGED') });
            expect(buttons()).toEqual(['Одобрить и далее →', 'Править самому', 'Отклонить']);
        });
    });

    describe('web research (#299)', () => {
        const research = (count: number): Record<string, unknown> => ({ requests: 2, results: Array.from({ length: count }, (_, index) => ({
            n: index + 1, url: `https://example.org/${index + 1}`, title: `Источник ${index + 1}`, provider: 'YANDEX' })) });
        const checked = (): string | null => root().querySelector('.research-line')?.textContent?.replace(/\u00a0/g, ' ') ?? null;

        it('says «Ищу источники…» while the material waits for its research, in the heading word and the body, queued or not', () => {
            create(summary('QUEUED', { title: '', currentRevisionId: null }), { researching: true });
            expect(root().querySelector('h2')?.textContent).toBe('Материал 3 из 10, ищу источники');
            expect(root().textContent).toContain('Ищу источники…');
            expect(root().textContent).not.toContain('поставила материал в очередь');
            create(summary('GENERATING', { currentRevisionId: null }), { researching: true });
            expect(root().textContent).toContain('Ищу источники…');
            create(summary('GENERATING', { currentRevisionId: null }));
            expect(root().textContent).toContain('Мнема пишет материал');
            expect(root().textContent).not.toContain('Ищу источники');
        });

        it('shows a quiet «Проверено по N источникам» from the research of the artifact, and nothing for none', () => {
            create(summary('PROPOSED'), { entry: entry('PROPOSED', { research: research(3) }) });
            expect(checked()).toBe('Проверено по 3 источникам');
            create(summary('PROPOSED'), { entry: entry('PROPOSED', { research: research(1) }) });
            expect(checked()).toBe('Проверено по 1 источнику');
            create(summary('PROPOSED'), { entry: entry('PROPOSED', { research: research(21) }) });
            expect(checked()).toBe('Проверено по 21 источнику');
            create(summary('PROPOSED'), { entry: entry('PROPOSED', { research: research(0) }) });
            expect(checked()).toBeNull();
            create(summary('PROPOSED'), { entry: entry('PROPOSED', { research: null }) });
            expect(checked()).toBeNull();
            create(summary('PROPOSED'));
            expect(checked()).toBeNull();
        });
    });
});
