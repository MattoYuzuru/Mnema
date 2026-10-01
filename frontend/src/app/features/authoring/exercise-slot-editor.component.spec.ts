import { TestBed } from '@angular/core/testing';

import { AuthoringBlock, COMPACT_SLOT, PROMPT_SLOTS } from '../../content/exercise/exercise-content.models';
import { NativeDocument } from '../../content/native-document';
import { NativeMediaUploadApi } from './native-media-upload.api';
import { SlotContext } from './exercise-draft';
import { ExerciseSlotEditorComponent } from './exercise-slot-editor.component';

describe('ExerciseSlotEditorComponent', () => {
    const member = '44444444-4444-4444-8444-444444444444';
    const revision = '55555555-5555-4555-8555-555555555555';
    const node = '00000000-0000-4000-8000-000000000003';
    const asset = 'aaaaaaaa-0000-4000-8000-000000000001';
    const context: SlotContext = {
        document: { formatVersion: 1, root: { id: node, type: 'doc', version: 1, attrs: {}, content: [] } } as unknown as NativeDocument,
        memberKey: member, itemRevisionId: revision, projections: [{ nodeId: node, label: 'Ядро', text: 'Ядро хранит ДНК' }]
    };

    beforeEach(() => TestBed.configureTestingModule({ providers: [{ provide: NativeMediaUploadApi, useValue: jasmine.createSpyObj('api', ['policy']) }] }));

    function create(blocks: AuthoringBlock[], spec = PROMPT_SLOTS.CHOICE, error: string | null = null, showProblems = false) {
        const fixture = TestBed.createComponent(ExerciseSlotEditorComponent);
        fixture.componentRef.setInput('blocks', blocks);
        fixture.componentRef.setInput('spec', spec);
        fixture.componentRef.setInput('label', 'Условие');
        fixture.componentRef.setInput('context', context);
        fixture.componentRef.setInput('idPrefix', 'slot');
        fixture.componentRef.setInput('error', error);
        fixture.componentRef.setInput('showProblems', showProblems);
        fixture.detectChanges();
        return fixture;
    }

    it('shows the profile limits next to the field and lists every block kind it supports', () => {
        const fixture = create([{ kind: 'TEXT', text: 'a' }]);
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('legend')?.textContent).toBe('Условие');
        expect(root.querySelector('.hint')?.textContent).toContain('Блоков: 1–8');
        expect(root.querySelector('.hint')?.textContent).toContain('до 4000 знаков');
        const buttons = [...root.querySelectorAll('.add-row button')].map(button => button.textContent?.trim());
        expect(buttons).toEqual(['+ Текст', '+ Фрагмент материала', 'Добавить изображение', 'Добавить аудио', 'Добавить видео', 'Записать аудио', '+ YouTube']);
    });

    it('edits text verbatim, edits media labels and clears an empty transcript', () => {
        const fixture = create([{ kind: 'TEXT', text: 'a' }, { kind: 'AUDIO', assetId: asset, title: 'Слово' }, { kind: 'VIDEO', assetId: asset.replace('1', '2'), title: 'Клип' },
            { kind: 'IMAGE', assetId: asset.replace('1', '3'), alt: '' }]);
        const root = fixture.nativeElement as HTMLElement;
        const blocks = () => fixture.componentInstance.blocks();
        const text = root.querySelector<HTMLTextAreaElement>('#slot-text-0')!;
        text.value = '  отступ\n\tкод '; text.dispatchEvent(new Event('input'));
        expect(blocks()[0]).toEqual({ kind: 'TEXT', text: '  отступ\n\tкод ' });

        const transcript = root.querySelector<HTMLTextAreaElement>('#slot-transcript-1')!;
        transcript.value = 'Слово'; transcript.dispatchEvent(new Event('input'));
        expect(blocks()[1]).toEqual({ kind: 'AUDIO', assetId: asset, title: 'Слово', transcript: 'Слово' });
        transcript.value = ''; transcript.dispatchEvent(new Event('input'));
        expect(Object.keys(blocks()[1])).not.toContain('transcript');

        const title = root.querySelector<HTMLInputElement>('#slot-title-2')!;
        title.value = 'Новое'; title.dispatchEvent(new Event('input'));
        expect(blocks()[2]).toEqual(jasmine.objectContaining({ title: 'Новое' }));
        const alt = root.querySelector<HTMLInputElement>('#slot-alt-3')!;
        alt.value = 'Схема'; alt.dispatchEvent(new Event('input'));
        expect(blocks()[3]).toEqual(jasmine.objectContaining({ alt: 'Схема' }));
    });

    it('moves and removes blocks and keeps focus on a control that still exists', async () => {
        const fixture = create([{ kind: 'TEXT', text: 'one' }, { kind: 'TEXT', text: 'two' }, { kind: 'TEXT', text: 'three' }]);
        document.body.appendChild(fixture.nativeElement);
        try {
            const root = fixture.nativeElement as HTMLElement;
            root.querySelector<HTMLButtonElement>('button[aria-label="Опустить блок 1 ниже"]')!.click(); fixture.detectChanges();
            expect(fixture.componentInstance.blocks().map(block => block.kind === 'TEXT' ? block.text : '')).toEqual(['two', 'one', 'three']);
            expect(root.querySelector<HTMLButtonElement>('button[aria-label="Поднять блок 1 выше"]')?.disabled).toBeTrue();
            root.querySelector<HTMLButtonElement>('button[aria-label="Удалить блок 3"]')!.click(); fixture.detectChanges();
            await fixture.whenStable();
            expect(fixture.componentInstance.blocks().length).toBe(2);
            expect(document.activeElement).not.toBe(document.body);
        } finally { fixture.nativeElement.remove(); }
    });

    it('selects a material fragment, resolves its text and flags a block pinned to an older revision', () => {
        const stale = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
        const fixture = create([{ kind: 'MATERIAL', memberKey: member, itemRevisionId: stale, nodeId: node }]);
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('.notice.warning')?.textContent).toContain('прежней версией');
        expect(root.querySelector('.preview-text')?.textContent).toBe('Ядро хранит ДНК');
        fixture.componentInstance.patch(0, { itemRevisionId: revision });
        fixture.detectChanges();
        expect(root.querySelector('.notice.warning')).toBeNull();
        const missing = create([{ kind: 'MATERIAL', memberKey: member, itemRevisionId: revision, nodeId: '' }]);
        expect(missing.nativeElement.querySelector('.preview-text')).toBeNull();
    });

    it('limits compact slots, refuses a second media block and reports problems only after a failed save', () => {
        const fixture = create([{ kind: 'TEXT', text: '' }], COMPACT_SLOT, 'Нужно исправить', false);
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('[role="alert"]')?.textContent).toContain('Нужно исправить');
        expect(root.querySelector('.field-error:not([role])')).toBeNull();
        fixture.componentRef.setInput('showProblems', true); fixture.detectChanges();
        expect(root.querySelector('textarea')?.getAttribute('aria-invalid')).toBe('true');
        expect(root.textContent).toContain('Введите текст блока или удалите его');

        fixture.componentInstance.onAsset({ kind: 'image', assetId: asset });
        fixture.componentInstance.onAsset({ kind: 'audio', assetId: asset.replace('1', '2') });
        fixture.detectChanges();
        expect(fixture.componentInstance.blocks().map(block => block.kind)).toEqual(['TEXT', 'IMAGE']);
        expect(root.textContent).toContain('уже есть изображение, аудио или видео');
    });

    it('refuses to add past the maximum and hides YouTube where the profile forbids it', () => {
        const eight = Array.from({ length: 8 }, (_, index): AuthoringBlock => ({ kind: 'TEXT', text: String(index) }));
        const fixture = create(eight);
        const root = fixture.nativeElement as HTMLElement;
        expect([...root.querySelectorAll<HTMLButtonElement>('.add-row button')].every(button => button.disabled)).toBeTrue();
        fixture.componentInstance.add('TEXT');
        expect(fixture.componentInstance.blocks().length).toBe(8);
        fixture.componentInstance.onAsset({ kind: 'video', assetId: asset });
        expect(fixture.componentInstance.notice()).toBe('Достигнут предел блоков.');
        const compact = create([], COMPACT_SLOT);
        expect(compact.nativeElement.textContent).not.toContain('YouTube');
        compact.componentInstance.onAsset({ kind: 'video', assetId: asset });
        expect(compact.componentInstance.blocks()[0]).toEqual({ kind: 'VIDEO', assetId: asset, title: 'Видео' });
    });

    it('mounts its own media picker only after an explicit click and closes it after a choice', () => {
        const fixture = create([{ kind: 'TEXT', text: 'a' }]);
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('app-native-media-upload')).toBeNull();
        button(root, 'Добавить аудио').click(); fixture.detectChanges();
        expect(root.querySelector('.media-picker')?.hasAttribute('hidden')).toBeFalse();
        expect(root.querySelector('app-native-media-upload')).not.toBeNull();
        fixture.componentInstance.onAsset({ kind: 'audio', assetId: asset }); fixture.detectChanges();
        expect(root.querySelector('.media-picker')?.hasAttribute('hidden')).toBeTrue();
        expect(fixture.componentInstance.blocks()[1]).toEqual({ kind: 'AUDIO', assetId: asset, title: 'Аудио' });
    });

    const button = (root: ParentNode, label: string) => [...root.querySelectorAll<HTMLButtonElement>('.add-row button')]
        .find(candidate => candidate.textContent?.trim() === label)!;

    describe('labelled media actions', () => {
        it('each action opens the one shared picker filtered to its kind and pressing it again closes it and returns focus', () => {
            const fixture = create([{ kind: 'TEXT', text: 'a' }]);
            const root = fixture.nativeElement as HTMLElement;
            document.body.appendChild(root);
            const image = button(root, 'Добавить изображение');
            image.focus(); image.click(); fixture.detectChanges();
            const instance = () => fixture.debugElement.query(el => el.name === 'app-native-media-upload').componentInstance;
            expect(instance().kind()).toBe('image');
            expect(image.getAttribute('aria-expanded')).toBe('true');
            expect(root.querySelectorAll('app-native-media-upload').length).toBe(1);
            button(root, 'Добавить видео').click(); fixture.detectChanges();
            expect(instance().kind()).toBe('video');
            expect(root.querySelectorAll('app-native-media-upload').length).toBe(1);
            expect(image.getAttribute('aria-expanded')).toBe('false');
            const video = button(root, 'Добавить видео');
            video.focus(); video.click(); fixture.detectChanges();
            expect(root.querySelector('.media-picker')?.hasAttribute('hidden')).toBeTrue();
            expect(document.activeElement).toBe(video);
            root.remove();
        });

        it('moves focus into the picker when it opens and only shows the recorder for audio', () => {
            const fixture = create([{ kind: 'TEXT', text: 'a' }]);
            const root = fixture.nativeElement as HTMLElement;
            document.body.appendChild(root);
            button(root, 'Добавить изображение').click(); fixture.detectChanges(); fixture.detectChanges();
            expect(document.activeElement).toBe(root.querySelector('.media-picker'));
            expect(root.querySelector('.media-picker')?.textContent).not.toContain('Записать аудио');
            expect(root.querySelector('.media-picker')?.textContent).toContain('Открыть камеру');
            button(root, 'Добавить аудио').click(); fixture.detectChanges();
            expect(root.querySelector('.media-picker')?.textContent).toContain('Записать аудио');
            expect(root.querySelector('.media-picker')?.textContent).not.toContain('Открыть камеру');
            root.remove();
        });

        it('«Записать аудио» opens the picker in recording mode and starts the recorder on that click only', () => {
            const fixture = create([{ kind: 'TEXT', text: 'a' }]);
            const root = fixture.nativeElement as HTMLElement;
            const getUserMedia = spyOn(navigator.mediaDevices, 'getUserMedia').and.rejectWith(new DOMException('no', 'NotAllowedError'));
            button(root, 'Добавить аудио').click(); fixture.detectChanges(); fixture.detectChanges();
            expect(getUserMedia).not.toHaveBeenCalled();
            button(root, 'Записать аудио').click(); fixture.detectChanges(); fixture.detectChanges();
            expect(getUserMedia).toHaveBeenCalledTimes(1);
            expect(fixture.componentInstance.pickerKind()).toBe('audio');
            expect(fixture.componentInstance.recordMode()).toBeTrue();
        });

        it('respects the slot profile: a COMPACT slot with a media block offers no further media or recording', () => {
            const compact = create([{ kind: 'TEXT', text: 'a' }, { kind: 'AUDIO', assetId: asset, title: 'x' }], COMPACT_SLOT);
            const root = compact.nativeElement as HTMLElement;
            for (const label of ['Добавить изображение', 'Добавить аудио', 'Добавить видео', 'Записать аудио']) {
                expect(button(root, label).disabled).withContext(label).toBeTrue();
            }
            const open = create([{ kind: 'TEXT', text: 'a' }], COMPACT_SLOT);
            for (const label of ['Добавить изображение', 'Добавить аудио', 'Добавить видео', 'Записать аудио']) {
                expect(button(open.nativeElement, label).disabled).withContext(label).toBeFalse();
            }
        });
    });
});
