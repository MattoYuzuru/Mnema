import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { ToastService } from '../../../core/notifications/toast.service';
import { lastCall, spyObj, type SpyObj } from '../../../../testing/mocks';
import { ItemApiService } from '../../authoring/item-api.service';
import { ItemPage, ItemSummary } from '../../authoring/authoring.models';
import { PublicationApiService } from './publication-api.service';
import { PublicationChecklistComponent } from './publication-checklist.component';
import { PublicationDraftStore } from './publication-draft.store';
import { PublicationCommand, PublicationState, Topic } from './publication.models';
import { neverPublished, topicDirectory, written } from './publication-test-data';

const MATERIAL = '55555555-5555-4555-8555-555555555555';
const EXERCISE = '66666666-6666-4666-8666-666666666666';

describe('PublicationChecklistComponent', () => {
    let fixture: ComponentFixture<PublicationChecklistComponent>;
    let root: HTMLElement;
    let api: SpyObj<PublicationApiService>;
    let items: SpyObj<ItemApiService>;
    let toasts: ToastService;
    const deckId = '11111111-1111-4111-8111-111111111111';

    const ready = (overrides: Partial<PublicationState['checklist']> = {}): PublicationState => {
        const base = neverPublished();
        return { ...base, checklist: { ...base.checklist, publicProfile: true, ...overrides } };
    };
    const item = (title: string): HTMLElement =>
        [...root.querySelectorAll<HTMLElement>('.checklist-item')].find(row => row.querySelector('.checklist-title')!.textContent!.trim() === title)!;
    const stateWord = (title: string): string => item(title).querySelector('.checklist-state')!.textContent!.replace(/\s+/g, ' ').trim();
    const submit = (): HTMLButtonElement => root.querySelector<HTMLButtonElement>('.checklist-actions .primary')!;
    const settle = async (): Promise<void> => { await fixture.whenStable(); fixture.detectChanges(); };
    const choose = async (controlId: string, label: string): Promise<void> => {
        root.querySelector<HTMLButtonElement>(`[id$="${controlId}"]`)!.click();
        fixture.detectChanges();
        [...root.querySelectorAll<HTMLElement>('[role=option]')].find(option => option.textContent!.trim() === label)!.click();
        await settle();
    };

    async function create(state: PublicationState, keepDraft = false): Promise<void> {
        if (!keepDraft) TestBed.inject(PublicationDraftStore).clear(deckId);
        fixture = TestBed.createComponent(PublicationChecklistComponent);
        fixture.componentRef.setInput('deckId', deckId);
        fixture.componentRef.setInput('state', state);
        fixture.detectChanges();
        root = fixture.nativeElement as HTMLElement;
        await settle();
    }

    beforeEach(async () => {
        api = spyObj<PublicationApiService>({
            read: vi.fn().mockName('PublicationApiService.read'),
            save: vi.fn().mockName('PublicationApiService.save'),
            topics: vi.fn().mockName('PublicationApiService.topics')
        });
        api.topics.mockReturnValue(of(topicDirectory()));
        items = spyObj<ItemApiService>({ list: vi.fn().mockName('ItemApiService.list') });
        await TestBed.configureTestingModule({
            providers: [provideRouter([]), { provide: PublicationApiService, useValue: api }, { provide: ItemApiService, useValue: items }]
        }).compileComponents();
        toasts = TestBed.inject(ToastService);
        vi.spyOn(toasts, 'echo');
    });

    describe('items', () => {
        it('lists every requirement with its state in words and a fix next to it', async () => {
            await create(neverPublished());
            expect(stateWord('Описание')).toBe('✓ Готово');
            expect(stateWord('Тема')).toBe('○ Нужно сделать');
            expect(stateWord('Язык')).toBe('✓ Готово');
            expect(stateWord('Уровень (необязательно)')).toBe('По желанию');
            expect(stateWord('Теги (необязательно)')).toBe('По желанию');
            expect(stateWord('Публичный профиль')).toBe('○ Нужно сделать');
            expect(stateWord('Картинки')).toBe('✓ Готово');
            expect(root.querySelector('h3')!.textContent).toBe('Сделать колоду публичной');
            expect(root.querySelector('h2')).toBeNull();
            expect(root.querySelectorAll('h4')).toHaveLength(7);
            const profile = item('Публичный профиль').querySelector<HTMLAnchorElement>('a')!;
            expect(profile.getAttribute('aria-describedby')).toBe(item('Публичный профиль').querySelector('.checklist-state')!.id);
            expect(profile.getAttribute('href')).toBe('/profile#public-profile-settings');
            expect(profile.textContent).toBe('Открыть профиль');
        });

        it('moves the focus to its heading when it opens', async () => {
            fixture = TestBed.createComponent(PublicationChecklistComponent);
            fixture.componentRef.setInput('deckId', deckId);
            fixture.componentRef.setInput('state', neverPublished());
            document.body.append(fixture.nativeElement);
            fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(fixture.nativeElement.querySelector('h3'));
            fixture.nativeElement.remove();
        });

        it('asks the host to open the deck editor when the description is missing', async () => {
            await create(neverPublished({ checklist: { ...neverPublished().checklist, description: false } }));
            const edit = vi.fn();
            fixture.componentInstance.editDescription.subscribe(edit);
            expect(stateWord('Описание')).toBe('○ Нужно сделать');
            item('Описание').querySelector('button')!.click();
            expect(edit).toHaveBeenCalledOnce();
        });

        it('turns the profile item done when the profile is public', async () => {
            await create(ready());
            expect(stateWord('Публичный профиль')).toBe('✓ Готово');
            expect(item('Публичный профиль').querySelector('a')).toBeNull();
        });

        it('lists blocked images with the material name and the reason, and links to fix them', async () => {
            const page = (nextCursor: string | null, titles: [string, string][]): ItemPage => ({
                deckId, deckRevisionId: deckId, deckVersion: '1', total: 2, exemplars: { count: 0, limit: 10 }, nextCursor,
                items: titles.map(([memberKey, title]) => ({ memberKey, title }) as ItemSummary)
            });
            items.list.mockReturnValueOnce(of(page('c1', [['77777777-7777-4777-8777-777777777777', 'Другой']])))
                .mockReturnValueOnce(of(page(null, [[MATERIAL, 'Семья']])));
            await create(ready({
                blockedMedia: [{ memberKey: MATERIAL, exerciseId: null, reason: 'NC_LICENSE' }, { memberKey: null, exerciseId: EXERCISE, reason: 'NC_LICENSE' }]
            }));
            expect(stateWord('Картинки')).toBe('○ Нужно сделать');
            const rows = [...item('Картинки').querySelectorAll('li')];
            expect(rows[0].textContent).toContain('Материал «Семья»: картинка с лицензией, запрещающей коммерческое использование.');
            expect(rows[0].querySelector('a')!.getAttribute('href')).toBe(`/decks/${deckId}/materials/${MATERIAL}/edit`);
            expect(rows[0].querySelector('a')!.getAttribute('aria-label')).toBe('Открыть материал «Семья»');
            expect(rows[1].querySelector('a')!.getAttribute('aria-label')).toBe('Открыть упражнение');
            expect(rows[0].querySelector('a')!.getAttribute('aria-describedby')).toBe(item('Картинки').querySelector('.checklist-state')!.id);
            expect(rows[1].textContent).toContain('Упражнение:');
            expect(rows[1].querySelector('a')!.getAttribute('href')).toBe(`/decks/${deckId}/exercises/${EXERCISE}/edit`);
            expect(items.list).toHaveBeenCalledTimes(2);
        });

        it('falls back to the plain word when the material list cannot be read', async () => {
            items.list.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
            await create(ready({ blockedMedia: [{ memberKey: MATERIAL, exerciseId: null, reason: 'NC_LICENSE' }] }));
            expect(item('Картинки').querySelector('li')!.textContent).toContain('Материал: картинка');
        });

        it('shows the catalog threshold as information that never blocks', async () => {
            await create(ready());
            const note = root.querySelector('[role=note]')!.textContent!.replace(/\s+/g, ' ');
            expect(note).toContain('В «Сообщество» колода попадёт, когда в ней будет не меньше 10 материалов и хотя бы одно упражнение.');
            expect(note).toContain('материалов 3, упражнений 2');
            expect(root.querySelector('.checklist')!.textContent).not.toContain('Сообщество');
            const met = neverPublished();
            await create({ ...met, checklist: { ...met.checklist, catalogThreshold: { ...met.checklist.catalogThreshold, met: true, materials: 42, exercises: 40 } } });
            expect(root.querySelector('[role=note]')!.textContent).toContain('Колода подходит для раздела «Сообщество»: материалов 42, упражнений 40.');
        });
    });

    describe('topic, language, level and tags', () => {
        it('groups the topics by their top level with real group headings, and offers the server suggestions first', async () => {
            await create(ready());
            const suggestions = [...item('Тема').querySelectorAll<HTMLButtonElement>('.suggest button')];
            expect(suggestions.map(button => button.textContent)).toEqual(['Японский']);
            root.querySelector<HTMLButtonElement>('[id$="topic-select"]')!.click();
            fixture.detectChanges();
            const options = [...root.querySelectorAll<HTMLElement>('[role=option]')];
            expect(options.map(option => option.textContent!.trim())).toEqual(['Английский', 'Японский', 'Другое']);
            expect(options.every(option => option.getAttribute('aria-disabled') === null)).toBe(true);
            const group = root.querySelector<HTMLElement>('[role=group].group')!;
            expect(root.querySelector(`#${group.getAttribute('aria-labelledby')}`)!.textContent).toBe('Языки');
            expect([...group.querySelectorAll('[role=option]')].map(option => option.textContent)).toEqual(['Английский', 'Японский']);
        });

        it('picks a suggestion with one press and marks it pressed', async () => {
            await create(ready());
            const suggestion = item('Тема').querySelector<HTMLButtonElement>('.suggest button')!;
            expect(suggestion.hasAttribute('aria-pressed')).toBe(false);
            suggestion.click();
            await settle();
            expect(suggestion.hasAttribute('aria-pressed')).toBe(false);
            expect(stateWord('Тема')).toBe('✓ Готово');
            expect(root.querySelector('[id$="topic-select"]')!.textContent).toContain('Японский');
        });

        it('pre-fills the language with the guess and lets it be changed, keeping a stored language that is not in the list', async () => {
            await create(ready());
            expect(root.querySelector('[id$="language-select"]')!.textContent).toContain('Русский');
            expect(item('Язык').textContent).toContain('Язык определён автоматически');
            await choose('language-select', 'Японский');
            expect(root.querySelector('[id$="language-select"]')!.textContent).toContain('Японский');
            const stored = ready();
            await create({ ...stored, metadata: { ...stored.metadata, contentLanguage: 'sw' } });
            expect(root.querySelector('[id$="language-select"]')!.textContent).toContain('Суахили');
            expect(item('Язык').textContent).toContain('Выберите язык');
        });

        it('leaves the language empty and not done when the server is unsure', async () => {
            await create({ ...ready(), suggested: { contentLanguage: null, topicIds: [] } });
            expect(stateWord('Язык')).toBe('○ Нужно сделать');
            expect(root.querySelector('.suggest')).toBeNull();
        });

        it('says when the topic directory cannot be loaded and loads it again', async () => {
            api.topics.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
            await create(ready());
            expect(item('Тема').textContent).toContain('Не удалось загрузить список тем.');
            const retry = [...item('Тема').querySelectorAll('button')].find(button => button.textContent === 'Загрузить темы снова')!;
            retry.click();
            await settle();
            expect(item('Тема').querySelector('app-mnema-select')).not.toBeNull();
        });

        it('says that the topics are loading', async () => {
            api.topics.mockReturnValue(new Subject<readonly Topic[]>());
            await create(ready());
            expect(item('Тема').querySelector('[role=status]')!.textContent).toBe('Загружаем темы…');
        });
    });

    describe('publishing', () => {
        const pickTopic = async (): Promise<void> => {
            item('Тема').querySelector<HTMLButtonElement>('.suggest button')!.click();
            await settle();
        };

        it('stays inert and says what is missing in the description of the button, without a second alert, and sends nothing', async () => {
            await create(neverPublished());
            expect(submit().getAttribute('aria-disabled')).toBe('true');
            expect(submit().disabled).toBe(false);
            expect(root.textContent).toContain('Чтобы опубликовать, закройте пункты: тема, публичный профиль.');
            expect(submit().getAttribute('aria-describedby')).toBe(root.querySelector('[id$="-summary"]')!.id);
            submit().click();
            await settle();
            expect(api.save).not.toHaveBeenCalled();
            expect(root.querySelector('[role=alert]')).toBeNull();
        });

        it('treats each blocking item on its own: description, topic, language, profile, blocked images', async () => {
            const blocker = { memberKey: MATERIAL, exerciseId: null, reason: 'NC_LICENSE' as const };
            const cases: [Partial<PublicationState['checklist']>, string][] = [
                [{ description: false }, 'описание'],
                [{ publicProfile: false }, 'публичный профиль'],
                [{ blockedMedia: [blocker] }, 'картинки']
            ];
            for (const [override, word] of cases) {
                items.list.mockReturnValue(of({ items: [], nextCursor: null } as unknown as ItemPage));
                await create(ready(override));
                await pickTopic();
                expect(submit().getAttribute('aria-disabled'), word).toBe('true');
                expect(root.querySelector('[id$="-summary"]')!.textContent, word).toBe(`Чтобы опубликовать, закройте пункты: ${word}.`);
            }
            await create({ ...ready(), suggested: { contentLanguage: null, topicIds: ['japanese'] } });
            await pickTopic();
            expect(root.querySelector('[id$="-summary"]')!.textContent).toBe('Чтобы опубликовать, закройте пункты: язык.');
        });

        it('becomes pressable when everything passes and sends the whole public state with the head it saw', async () => {
            await create(ready());
            await pickTopic();
            await choose('level-select', 'A2');
            const tags = root.querySelector<HTMLInputElement>('app-tag-input input')!;
            tags.value = 'JLPT N5,';
            tags.dispatchEvent(new Event('input'));
            await settle();
            expect(submit().getAttribute('aria-disabled')).toBeNull();
            const published = ready();
            api.save.mockImplementation((_deck, _version, command) => of(written({ ...published, visibility: 'PUBLIC' }, command.commandId)));
            const emitted: PublicationState[] = [];
            fixture.componentInstance.published.subscribe(state => emitted.push(state));
            submit().click();
            await settle();
            const [deck, version, command] = lastCall(api.save) as [string, string, PublicationCommand];
            expect(deck).toBe(deckId);
            expect(version).toBe('0');
            expect(command).toMatchObject({
                visibility: 'PUBLIC', requestsEnabled: true,
                metadata: { topicId: 'japanese', contentLanguage: 'ru', targetLanguage: null, level: 'A2', tags: ['jlpt n5'] },
                publish: { expectedHeadRevisionId: neverPublished().headRevisionId, releaseNote: null }
            });
            expect(toasts.echo).toHaveBeenCalledWith('Колода стала публичной');
            expect(emitted).toHaveLength(1);
            expect(emitted[0].visibility).toBe('PUBLIC');
        });

        it('shows the busy state and sends once', async () => {
            await create(ready());
            await pickTopic();
            const response = new Subject<never>();
            api.save.mockReturnValue(response);
            submit().click();
            submit().click();
            await settle();
            expect(api.save).toHaveBeenCalledOnce();
            expect(submit().textContent!.trim()).toBe('Публикуем…');
            expect(submit().getAttribute('aria-busy')).toBe('true');
        });

        it('marks the items the server names after a 409 and asks the host to re-read', async () => {
            await create(ready());
            await pickTopic();
            const refresh = vi.fn();
            fixture.componentInstance.refresh.subscribe(refresh);
            api.save.mockReturnValue(throwError(() => new HttpErrorResponse({
                status: 409, error: { code: 'PUBLICATION_REQUIREMENTS', failed: ['topic', 'publicProfile'] }
            })));
            submit().click();
            await settle();
            expect(item('Тема').getAttribute('data-state')).toBe('failed');
            expect(item('Публичный профиль').getAttribute('data-state')).toBe('failed');
            expect(stateWord('Публичный профиль')).toBe('! Не принято');
            expect(root.querySelector('[role=alert]')!.textContent).toContain('не хватает: тема, публичный профиль');
            expect(refresh).toHaveBeenCalledOnce();
        });

        it('clears the server mark of an item once it is changed', async () => {
            await create(ready());
            await pickTopic();
            api.save.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'PUBLICATION_REQUIREMENTS', failed: ['topic'] } })));
            submit().click();
            await settle();
            expect(item('Тема').getAttribute('data-state')).toBe('failed');
            await choose('topic-select', 'Английский');
            expect(item('Тема').getAttribute('data-state')).toBe('done');
        });

        it('asks the host to re-read after a stale version and says so', async () => {
            await create(ready());
            await pickTopic();
            const refresh = vi.fn();
            fixture.componentInstance.refresh.subscribe(refresh);
            api.save.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 412, error: { code: 'VERSION_CONFLICT' } })));
            submit().click();
            await settle();
            expect(root.querySelector('[role=alert]')!.textContent).toContain('Колода изменилась');
            expect(refresh).toHaveBeenCalledOnce();
        });

        it('retries a lost request with the same command, and a changed form with a new one', async () => {
            await create(ready());
            await pickTopic();
            api.save.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
            submit().click();
            await settle();
            expect(root.querySelector('[role=alert]')!.textContent).toContain('Проверьте соединение');
            const first = (api.save.mock.lastCall![2] as PublicationCommand).commandId;
            submit().click();
            await settle();
            expect((api.save.mock.lastCall![2] as PublicationCommand).commandId).toBe(first);
            await choose('level-select', 'B1');
            submit().click();
            await settle();
            expect((api.save.mock.lastCall![2] as PublicationCommand).commandId).not.toBe(first);
        });

        it('asks the host to re-read after a replayed answer instead of trusting the historical state', async () => {
            await create(ready());
            await pickTopic();
            api.save.mockImplementation((_deck, _version, command) => of(written(ready(), command.commandId, true)));
            const order: string[] = [];
            const published = vi.fn();
            fixture.componentInstance.replayed.subscribe(() => order.push('replayed'));
            fixture.componentInstance.refresh.subscribe(() => order.push('refresh'));
            fixture.componentInstance.published.subscribe(published);
            submit().click();
            await settle();
            expect(order).toEqual(['replayed', 'refresh']);
            expect(published).not.toHaveBeenCalled();
        });
    });

    describe('keyboard', () => {
        it('closes on Escape, but not while a select is open or a request runs', async () => {
            await create(ready());
            const cancelled = vi.fn();
            fixture.componentInstance.cancelled.subscribe(cancelled);
            const topic = root.querySelector<HTMLButtonElement>('[id$="topic-select"]')!;
            topic.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
            fixture.detectChanges();
            expect(root.querySelector('[role=listbox]')).not.toBeNull();
            topic.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
            fixture.detectChanges();
            expect(root.querySelector('[role=listbox]')).toBeNull();
            expect(cancelled).not.toHaveBeenCalled();
            const tags = root.querySelector<HTMLInputElement>('app-tag-input input')!;
            tags.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
            expect(cancelled).not.toHaveBeenCalled();
            topic.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
            expect(cancelled).toHaveBeenCalledOnce();
        });

        it('keeps Cancel working and does nothing while publishing', async () => {
            await create(ready());
            item('Тема').querySelector<HTMLButtonElement>('.suggest button')!.click();
            await settle();
            const cancelled = vi.fn();
            fixture.componentInstance.cancelled.subscribe(cancelled);
            const cancel = [...root.querySelectorAll<HTMLButtonElement>('.checklist-actions button')].find(button => button.textContent!.trim() === 'Отмена')!;
            cancel.click();
            expect(cancelled).toHaveBeenCalledOnce();
            api.save.mockReturnValue(new Subject<never>());
            submit().click();
            await settle();
            expect(cancel.getAttribute('aria-disabled')).toBe('true');
            cancel.click();
            root.querySelector('section')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            expect(cancelled).toHaveBeenCalledOnce();
        });

        it('reaches every control with the keyboard: native buttons, links, a combobox and a text field', async () => {
            await create(ready());
            const stops = root.querySelectorAll('button, a[href], input, [role=combobox]');
            expect(stops.length).toBeGreaterThan(6);
            root.querySelectorAll('[tabindex]').forEach(element => expect(element.getAttribute('tabindex')).toBe('-1'));
        });
    });

    describe('draft', () => {
        it('restores the topic, language, level and tags when the panel is opened again, even after another component was destroyed', async () => {
            await create(ready());
            item('Тема').querySelector<HTMLButtonElement>('.suggest button')!.click();
            await choose('language-select', 'Японский');
            await choose('level-select', 'B2');
            const tags = root.querySelector<HTMLInputElement>('app-tag-input input')!;
            tags.value = 'n5,';
            tags.dispatchEvent(new Event('input'));
            await settle();
            fixture.destroy();

            expect(TestBed.inject(PublicationDraftStore).get(deckId)).toEqual({ topicId: 'japanese', language: 'ja', level: 'B2', tags: ['n5'] });
            await create(ready(), true);
            expect(root.querySelector('[id$="topic-select"]')!.textContent).toContain('Японский');
            expect(root.querySelector('[id$="language-select"]')!.textContent).toContain('Японский');
            expect(root.querySelector('[id$="level-select"]')!.textContent).toContain('B2');
            expect([...root.querySelectorAll('.tag-text')].map(tag => tag.textContent)).toEqual(['n5']);
            expect(root.textContent).not.toContain('Язык определён автоматически');
        });

        it('forgets the draft once the deck is published', async () => {
            await create(ready());
            item('Тема').querySelector<HTMLButtonElement>('.suggest button')!.click();
            await settle();
            api.save.mockImplementation((_deck, _version, command) => of(written({ ...ready(), visibility: 'PUBLIC' }, command.commandId)));
            submit().click();
            await settle();
            expect(TestBed.inject(PublicationDraftStore).get(deckId)).toBeNull();
        });

        it('clears the failure message of an attempt once nothing is missing any more', async () => {
            await create(ready());
            item('Тема').querySelector<HTMLButtonElement>('.suggest button')!.click();
            await settle();
            api.save.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'PUBLICATION_REQUIREMENTS', failed: ['topic'] } })));
            submit().click();
            await settle();
            expect(root.querySelector('[role=alert]')).not.toBeNull();
            expect(submit().getAttribute('aria-disabled')).toBe('true');
            await choose('topic-select', 'Английский');
            expect(submit().getAttribute('aria-disabled')).toBeNull();
            expect(root.querySelector('[role=alert]')).toBeNull();
        });
    });
});
