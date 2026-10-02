import { Component, Type, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { NativeDocument } from '../../content/native-document';
import { CategorizeItemsEditorComponent, CategoryGroupsEditorComponent } from './categorize-editors.component';
import { CategorizeDraft, OrderDraft, SlotContext, buildSpec, emptyDrafts, newCategorizeItem, newCategory, newOrderItem, validateDraft } from './exercise-draft';
import { NativeMediaUploadApi } from './native-media-upload.api';
import { OrderEditorComponent } from './order-editor.component';

const context: SlotContext = {
    document: { formatVersion: 1, root: { id: '00000000-0000-4000-8000-000000000003', type: 'doc', version: 1, attrs: {}, content: [] } } as unknown as NativeDocument,
    memberKey: '44444444-4444-4444-8444-444444444444', itemRevisionId: '55555555-5555-4555-8555-555555555555', projections: []
};

function setup<T>(host: Type<T>): ComponentFixture<T> {
    TestBed.configureTestingModule({ providers: [{ provide: NativeMediaUploadApi, useValue: {
                    policy: vi.fn().mockName("api.policy")
                } }] });
    const fixture = TestBed.createComponent(host);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    return fixture;
}

describe('OrderEditorComponent', () => {
    @Component({ imports: [OrderEditorComponent], template: `<app-order-editor [(draft)]="draft" [context]="context" [errors]="errors()" idPrefix="order" />` })
    class Host {
        draft = signal<OrderDraft>({ prompt: [], items: [newOrderItem(), newOrderItem()] });
        context = context;
        errors = signal<Record<string, string>>({});
    }

    let fixture: ComponentFixture<Host>;
    const root = () => fixture.nativeElement as HTMLElement;
    const items = () => fixture.componentInstance.draft().items;
    const texts = () => items().map(item => item.blocks[0].kind === 'TEXT' ? item.blocks[0].text : item.blocks[0].kind);
    const refresh = () => { fixture.detectChanges(); fixture.detectChanges(); };
    const click = (selector: string, index = 0) => { root().querySelectorAll<HTMLElement>(selector)[index].click(); refresh(); };
    const source = (value: string) => {
        const details = root().querySelector('details')!;
        details.open = true;
        const area = root().querySelector<HTMLTextAreaElement>('#order-source')!;
        area.value = value;
        area.dispatchEvent(new Event('input'));
        refresh();
    };
    const note = () => root().querySelector('[data-helper-note]')?.textContent?.trim() ?? null;

    beforeEach(() => { fixture = setup(Host); });
    afterEach(() => fixture.nativeElement.remove());

    it('explains that the list is the key, that every segment is used once and that other orders are not detected', () => {
        const rules = root().querySelector('#order-rules')!.textContent!.replace(/\s+/g, ' ');
        expect(rules).toContain('этот порядок — эталон');
        expect(rules).toContain('Каждый сегмент используется ровно один раз');
        expect(rules).toContain('другие порядки, даже осмысленные, Mnema не определяет');
        expect(rules).toContain('Элементов: 2–12');
        expect(rules).toContain('до 1000 знаков');
    });

    it('splits text into words with punctuation attached and keeps repeated words as separate items, in order', () => {
        source('Это очень, очень важно.');
        click('[data-split-words]');
        expect(texts()).toEqual(['Это', 'очень,', 'очень', 'важно.']);
        expect(new Set(items().map(item => item.itemId)).size).toBe(4);
        expect(note()).toContain('Создано элементов: 4');
        expect(root().querySelectorAll('[data-item]').length).toBe(4);
        expect(root().querySelector<HTMLTextAreaElement>('#order-source')!.value).toBe('');
    });

    it('splits Chinese text, which has no spaces, into several items', () => {
        source('我喜欢学习中文。');
        click('[data-split-words]');
        expect(items().length).toBeGreaterThan(2);
        expect(texts().join('')).toBe('我喜欢学习中文。');
    });

    it('splits by lines, keeping indentation, and never silently replaces what the author wrote', () => {
        source('for (;;) {\n    x++;\n}');
        click('[data-split-lines]');
        expect(texts()).toEqual(['for (;;) {', '    x++;', '}']);
        // Authored content now exists: a new proposal asks first.
        source('один\nдва');
        click('[data-split-lines]');
        expect(texts()).toEqual(['for (;;) {', '    x++;', '}']);
        expect(root().querySelector('[role="alertdialog"]')?.textContent).toContain('Заменить текущие элементы (3)?');
        click('[data-cancel-replace]');
        expect(texts()).toEqual(['for (;;) {', '    x++;', '}']);
        click('[data-split-lines]');
        click('[data-confirm-replace]');
        expect(texts()).toEqual(['один', 'два']);
    });

    it('refuses a proposal over 12 or under 2 parts with an explanation and changes nothing', () => {
        source(Array.from({ length: 13 }, (_, index) => `часть ${index}`).join('\n'));
        click('[data-split-lines]');
        expect(note()).toContain('Получилось частей: 13, а в упражнении не больше 12');
        expect(items().length).toBe(2);
        expect(texts()).toEqual(['', '']);
        source('одна');
        click('[data-split-lines]');
        expect(note()).toContain('Нужно не меньше 2');
        expect(root().querySelector('[data-helper-note]')?.getAttribute('role')).toBe('alert');
    });

    it('stays a whole, working form without Intl.Segmenter: words are disabled with the reason, lines and manual editing work', () => {
        fixture.destroy();
        const intl = Intl as unknown as {
            Segmenter?: unknown;
        };
        const original = intl.Segmenter;
        intl.Segmenter = undefined;
        try {
            TestBed.resetTestingModule();
            fixture = setup(Host);
            source('Привет мир');
            expect(root().querySelector<HTMLButtonElement>('[data-split-words]')!.disabled).toBe(true);
            expect(root().querySelector('[data-no-segmenter]')?.textContent).toContain('нет разбиения на слова');
            expect(root().querySelector('[data-split-words]')!.getAttribute('aria-describedby')).toBe('order-no-segmenter');
            expect(root().querySelector<HTMLButtonElement>('[data-split-lines]')!.disabled).toBe(false);
            click('[data-add-item]');
            expect(items().length).toBe(3);
            fixture.componentInstance.draft.set({ prompt: [], items: [newOrderItem('Привет мир'), newOrderItem('ещё')] });
            refresh();
            const area = root().querySelector<HTMLTextAreaElement>('[data-item] textarea')!;
            area.value = 'Привет';
            area.dispatchEvent(new Event('input'));
            refresh();
            expect(texts()[0]).toBe('Привет');
        }
        finally {
            intl.Segmenter = original;
        }
    });

    it('moves items with the arrows, keeps the authored order as the key and returns focus to the moved item', async () => {
        fixture.componentInstance.draft.set({ prompt: [], items: ['а', 'б', 'в'].map(value => newOrderItem(value)) });
        refresh();
        const ids = items().map(item => item.itemId);
        click('[data-item] [data-move="down"]');
        expect(texts()).toEqual(['б', 'а', 'в']);
        await fixture.whenStable();
        expect(document.activeElement).toBe(root().querySelector(`[data-item="${ids[0]}"] [data-move="down"]`));
        const spec = buildSpec('ORDER', { ...emptyDrafts(), ORDER: fixture.componentInstance.draft() }, { memberKey: context.memberKey, itemRevisionId: context.itemRevisionId }, true);
        expect(spec.type === 'ORDER' && spec.answerKey.sequence).toEqual([ids[1], ids[0], ids[2]]);
        expect(root().querySelector('[data-move-note]')?.textContent).toContain('позицию 2 из 3');
        expect(root().querySelector<HTMLButtonElement>('[data-item] [data-move="up"]')!.disabled).toBe(true);
    });

    it('merges a text item with the next one (a space, or a line break for multi-line text) and splits at the cursor', () => {
        fixture.componentInstance.draft.set({ prompt: [], items: ['Hello,', 'world!', 'for (;;) {\n    x++;', '}'].map(value => newOrderItem(value)) });
        refresh();
        click('[data-merge]', 0);
        expect(texts()).toEqual(['Hello, world!', 'for (;;) {\n    x++;', '}']);
        click('[data-merge]', 1);
        expect(texts()).toEqual(['Hello, world!', 'for (;;) {\n    x++;\n}']);
        expect(root().querySelectorAll<HTMLButtonElement>('[data-merge]')[1].disabled).toBe(true); // last item
        expect(root().querySelectorAll<HTMLButtonElement>('[data-merge]')[0].disabled).toBe(true); // only two left: nothing may go below 2

        fixture.componentInstance.draft.set({ prompt: [], items: [newOrderItem('Hello, world!'), newOrderItem('Bye')] });
        refresh();
        const area = root().querySelector<HTMLTextAreaElement>('[data-item] textarea')!;
        expect(root().querySelector<HTMLButtonElement>('[data-split]')!.disabled).toBe(true); // no cursor yet
        area.focus();
        area.setSelectionRange(6, 6);
        area.dispatchEvent(new Event('select', { bubbles: true }));
        refresh();
        expect(root().querySelector<HTMLButtonElement>('[data-split]')!.disabled).toBe(false);
        click('[data-split]');
        expect(texts()).toEqual(['Hello,', 'world!', 'Bye']);
        const ids = items().map(item => item.itemId);
        expect(new Set(ids).size).toBe(3);
    });

    it('refuses to split at the very start or end, and to merge or split media items without losing them', () => {
        const media = { itemId: newOrderItem().itemId, blocks: [{ kind: 'IMAGE' as const, assetId: 'aaaaaaaa-0000-4000-8000-000000000003', alt: 'Кадр' }] };
        fixture.componentInstance.draft.set({ prompt: [], items: [newOrderItem('Слово'), media, newOrderItem('Хвост')] });
        refresh();
        const merges = [...root().querySelectorAll<HTMLButtonElement>('[data-merge]')];
        expect(merges.map(button => button.disabled)).toEqual([true, true, true]);
        const area = root().querySelector<HTMLTextAreaElement>('[data-item] textarea')!;
        area.focus();
        area.setSelectionRange(0, 0);
        area.dispatchEvent(new Event('select', { bubbles: true }));
        refresh();
        expect(root().querySelector<HTMLButtonElement>('[data-split]')!.disabled).toBe(true);
        area.setSelectionRange(5, 5);
        area.dispatchEvent(new Event('select', { bubbles: true }));
        refresh();
        expect(root().querySelector<HTMLButtonElement>('[data-split]')!.disabled).toBe(true);
        expect(items()[1]).toEqual(media);
    });

    it('asks for at least two different items: identical tiles, or tiles differing only in an author-only title, count once', () => {
        const check = (draft: OrderDraft) => validateDraft('ORDER', { ...emptyDrafts(), ORDER: draft }, context);
        const same = { prompt: [], items: ['очень', 'очень'].map(value => newOrderItem(value)) };
        expect(check(same)['items']).toBe('Добавьте хотя бы два разных элемента — одинаковые плитки взаимозаменяемы.');
        const asset = 'aaaaaaaa-0000-4000-8000-000000000001';
        const audio = (title: string) => ({ itemId: newOrderItem().itemId, blocks: [{ kind: 'AUDIO' as const, assetId: asset, title }] });
        expect(check({ prompt: [], items: [audio('Запись один'), audio('Запись два')] })['items']).toContain('два разных элемента');
        expect(check({ prompt: [], items: [newOrderItem('очень'), newOrderItem('Очень')] })['items']).toBeUndefined();
        // Nothing is piled on top of a field problem that already explains the situation.
        expect(check({ prompt: [], items: [newOrderItem(''), newOrderItem('')] })['items']).toBeUndefined();
        const message = 'Добавьте хотя бы два разных элемента';
        fixture.componentInstance.draft.set(same);
        fixture.componentInstance.errors.set({ items: message + ' — одинаковые плитки взаимозаменяемы.' });
        refresh();
        expect(root().querySelector('.field-error[role="alert"]')?.textContent).toContain(message);
    });

    it('keeps 2 to 12 items, marks identical items as interchangeable and shows slot problems', () => {
        for (let index = 0; index < 10; index++)
            click('[data-add-item]');
        expect(items().length).toBe(12);
        expect(root().querySelector<HTMLButtonElement>('[data-add-item]')!.disabled).toBe(true);
        for (let index = 0; index < 10; index++)
            click('[data-remove]');
        expect(items().length).toBe(2);
        expect(root().querySelector<HTMLButtonElement>('[data-remove]')!.disabled).toBe(true);
        fixture.componentInstance.draft.set({ prompt: [], items: ['очень', 'очень', 'важно'].map(value => newOrderItem(value)) });
        refresh();
        expect(root().querySelectorAll('[data-duplicate]').length).toBe(2);
        fixture.componentInstance.errors.set({ items: 'Нужно от 2 до 12 элементов.', ['item:' + items()[0].itemId]: 'Блок 1: Введите текст блока или удалите его.' });
        refresh();
        expect(root().textContent).toContain('Нужно от 2 до 12 элементов.');
        expect(root().textContent).toContain('Введите текст блока');
    });
});

describe('CATEGORIZE editors', () => {
    @Component({
        imports: [CategoryGroupsEditorComponent, CategorizeItemsEditorComponent],
        template: `<app-category-groups-editor [(draft)]="draft" [context]="context" [errors]="errors()" idPrefix="groups" />
          <app-categorize-items-editor [(draft)]="draft" [context]="context" [errors]="errors()" idPrefix="items" />`
    })
    class Host {
        draft = signal<CategorizeDraft>({ prompt: [], categories: [], items: [] });
        context = context;
        errors = signal<Record<string, string>>({});
    }

    let fixture: ComponentFixture<Host>;
    const root = () => fixture.nativeElement as HTMLElement;
    const draft = () => fixture.componentInstance.draft();
    const refresh = () => { fixture.detectChanges(); fixture.detectChanges(); };
    const click = (selector: string, index = 0) => { root().querySelectorAll<HTMLElement>(selector)[index].click(); refresh(); };
    const groupCard = (categoryId: string) => root().querySelector<HTMLElement>(`[data-category="${categoryId}"]`)!;
    const labels = () => draft().categories.map(group => group.label);

    function seed(): {
        groups: string[];
        items: string[];
    } {
        const groups = [newCategory('Существительное'), newCategory('Глагол'), newCategory('Наречие')];
        const items = [newCategorizeItem(groups[0].categoryId), newCategorizeItem(groups[1].categoryId), newCategorizeItem(groups[0].categoryId)];
        fixture.componentInstance.draft.set({ prompt: [], categories: groups,
            items: items.map((item, index) => ({ ...item, blocks: [{ kind: 'TEXT' as const, text: `элемент ${index + 1}` }] })) });
        refresh();
        return { groups: groups.map(group => group.categoryId), items: items.map(item => item.itemId) };
    }
    const assignedTo = (itemId: string) => draft().items.find(item => item.itemId === itemId)?.categoryId;

    beforeEach(() => { fixture = setup(Host); });
    afterEach(() => fixture.nativeElement.remove());

    it('adds, renames and reorders groups within 2 to 6 and shows the label counter and conflicts', () => {
        for (let index = 0; index < 6; index++)
            click('[data-add-category]');
        expect(draft().categories.length).toBe(6);
        expect(root().querySelector<HTMLButtonElement>('[data-add-category]')!.disabled).toBe(true);
        const first = draft().categories[0].categoryId;
        const input = root().querySelector<HTMLInputElement>(`#groups-label-${first}`)!;
        input.value = 'Глагол';
        input.dispatchEvent(new Event('input'));
        refresh();
        expect(labels()[0]).toBe('Глагол');
        expect(groupCard(first).querySelector('.counter')?.textContent?.trim()).toBe('6 / 80');
        fixture.componentInstance.errors.set({ ['category:' + first]: 'Это название уже занято другой группой: у групп должны быть разные названия.' });
        refresh();
        expect(groupCard(first).querySelector('[role="alert"]')?.textContent).toContain('уже занято');
        expect(input.getAttribute('aria-invalid')).toBe('true');
        click('[data-category] [data-move="down"]');
        expect(draft().categories[1].categoryId).toBe(first);
        for (let index = 0; index < 4; index++)
            click('[data-category] [data-remove]');
        expect(draft().categories.length).toBe(2);
        expect(root().querySelector<HTMLButtonElement>('[data-category] [data-remove]')!.disabled).toBe(true);
    });

    it('removes an empty group at once', () => {
        const { groups } = seed();
        click(`[data-category="${groups[2]}"] [data-remove]`);
        expect(labels()).toEqual(['Существительное', 'Глагол']);
        expect(root().querySelector('[data-note]')?.textContent).toContain('Группа «Наречие» удалена.');
        expect(draft().items.length).toBe(3);
    });

    it('asks before removing a group with items and offers to move them or remove them: nothing is lost silently', () => {
        const { groups, items } = seed();
        click(`[data-category="${groups[0]}"] [data-remove]`);
        const panel = groupCard(groups[0]).querySelector('.removal')!;
        expect(panel.textContent).toContain('В группе «Существительное» элементов: 2');
        expect(labels()).toEqual(['Существительное', 'Глагол', 'Наречие']);
        expect(draft().items.length).toBe(3);
        expect(panel.querySelector<HTMLButtonElement>('[data-reassign]')!.disabled).toBe(true); // a target is required
        // Cancel keeps everything and returns to the button.
        click(`[data-category="${groups[0]}"] [data-cancel-removal]`);
        expect(groupCard(groups[0]).querySelector('.removal')).toBeNull();
        expect(assignedTo(items[0])).toBe(groups[0]);
        expect(labels().length).toBe(3);
    });

    it('moves the items of a removed group to the chosen one: no dangling id, no lost item', () => {
        const { groups, items } = seed();
        click(`[data-category="${groups[0]}"] [data-remove]`);
        const trigger = groupCard(groups[0]).querySelector<HTMLButtonElement>('.removal [role="combobox"]')!;
        const options = () => [...document.querySelectorAll('[role="option"]')].map(option => option.textContent);
        trigger.click();
        refresh();
        expect(options()).toEqual(['Выберите группу', 'Глагол', 'Наречие']); // never the group being removed
        (document.querySelectorAll('[role="option"]')[2] as HTMLElement).click();
        refresh();
        click(`[data-category="${groups[0]}"] [data-reassign]`);
        expect(labels()).toEqual(['Глагол', 'Наречие']);
        expect(draft().items.length).toBe(3);
        expect(assignedTo(items[0])).toBe(groups[2]);
        expect(assignedTo(items[2])).toBe(groups[2]);
        expect(assignedTo(items[1])).toBe(groups[1]);
        expect(draft().items.every(item => draft().categories.some(group => group.categoryId === item.categoryId))).toBe(true);
        expect(root().querySelector('[data-note]')?.textContent).toContain('элементы (2) перенесены в «Наречие»');
    });

    it('removes a group together with its items only after the explicit confirmation and says how many', () => {
        const { groups, items } = seed();
        click(`[data-category="${groups[0]}"] [data-remove]`);
        expect(groupCard(groups[0]).querySelector('[data-delete-items]')?.textContent).toContain('Удалить группу и элементы: 2');
        click(`[data-category="${groups[0]}"] [data-delete-items]`);
        expect(labels()).toEqual(['Глагол', 'Наречие']);
        expect(draft().items.map(item => item.itemId)).toEqual([items[1]]);
        expect(root().querySelector('[data-note]')?.textContent).toContain('Группа «Существительное» и её элементы (2) удалены.');
    });

    it('assigns an item to exactly one group with the group select and keeps an unassigned item visible as such', () => {
        const { groups, items } = seed();
        expect(root().querySelectorAll('[data-item] [role="combobox"]').length).toBe(3);
        fixture.componentInstance.draft.update(current => ({ ...current, items: [...current.items, newCategorizeItem()] }));
        refresh();
        const fresh = draft().items[3];
        const trigger = root().querySelector<HTMLButtonElement>(`#items-group-${fresh.itemId}`)!;
        expect(trigger.textContent).toContain('Выберите группу');
        trigger.click();
        refresh();
        (document.querySelectorAll('[role="option"]')[2] as HTMLElement).click();
        refresh();
        expect(assignedTo(fresh.itemId)).toBe(groups[1]);
        expect(assignedTo(items[0])).toBe(groups[0]);
        const spec = buildSpec('CATEGORIZE', { ...emptyDrafts(), CATEGORIZE: draft() }, { memberKey: context.memberKey, itemRevisionId: context.itemRevisionId }, true);
        expect(spec.type === 'CATEGORIZE' && spec.answerKey.assignments.length).toBe(4);
    });

    it('adds, reorders and removes items within 2 to 12', () => {
        seed();
        for (let index = 0; index < 9; index++)
            click('[data-add-item]');
        expect(draft().items.length).toBe(12);
        expect(root().querySelector<HTMLButtonElement>('[data-add-item]')!.disabled).toBe(true);
        const firstId = draft().items[0].itemId;
        click('[data-item] [data-move="down"]');
        expect(draft().items[1].itemId).toBe(firstId);
        for (let index = 0; index < 10; index++)
            click('[data-item] [data-remove]');
        expect(draft().items.length).toBe(2);
    });

    it('validates groups and items: labels unique after case folding, every item in a group, nothing dangling', () => {
        const { groups, items } = seed();
        const check = () => validateDraft('CATEGORIZE', { ...emptyDrafts(), CATEGORIZE: draft() }, { ...context, projections: [] });
        expect(check()).toEqual({});
        fixture.componentInstance.draft.update(current => ({ ...current,
            categories: current.categories.map((group, index) => index === 1 ? { ...group, label: '  СУЩЕСТВИТЕЛЬНОЕ ' } : group) }));
        refresh();
        expect(check()['category:' + groups[1]]).toContain('уже занято');
        fixture.componentInstance.draft.update(current => ({ ...current,
            categories: current.categories.map((group, index) => index === 1 ? { ...group, label: 'я'.repeat(81) } : group) }));
        expect(check()['category:' + groups[1]]).toContain('не длиннее 80');
        fixture.componentInstance.draft.update(current => ({ ...current,
            categories: current.categories.map((group, index) => index === 1 ? { ...group, label: ' ' } : group) }));
        expect(check()['category:' + groups[1]]).toContain('Назовите группу');
        fixture.componentInstance.draft.update(current => ({ ...current,
            categories: current.categories.map((group, index) => index === 1 ? { ...group, label: '\u200b\u00a0' } : group) }));
        expect(check()['category:' + groups[1]]).toContain('видимый знак');
        for (const clash of ['Straße|STRASSE', 'Σ|ς', 'Caf\u00e9|Cafe\u0301', 'Глагол|\u200bГЛАГОЛ\u3000']) {
            const [first, second] = clash.split('|');
            fixture.componentInstance.draft.update(current => ({ ...current,
                categories: current.categories.map((group, index) => index === 0 ? { ...group, label: first } : index === 1 ? { ...group, label: second } : group) }));
            expect(check()['category:' + groups[1]], clash).toContain('уже занято');
        }
        fixture.componentInstance.draft.update(current => ({ ...current,
            items: current.items.map(item => item.itemId === items[0] ? { ...item, categoryId: null } : item) }));
        expect(check()['assignment:' + items[0]]).toBe('Выберите группу для этого элемента.');
        fixture.componentInstance.draft.update(current => ({ ...current,
            items: current.items.map(item => item.itemId === items[1] ? { ...item, categoryId: 'ca000000-0000-4000-8000-0000000000ff' } : item) }));
        expect(check()['assignment:' + items[1]]).toBe('Выберите группу для этого элемента.');
        // An empty distractor group is fine: the third group has no items and produced no error.
        expect(Object.keys(check()).some(key => key.includes(groups[2]))).toBe(false);
    });
});
