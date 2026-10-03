import { ComponentFixture, TestBed } from '@angular/core/testing';

import { noteIds } from './generation-test-data';
import { NoteOverridesComponent } from './note-overrides.component';
import { ComposerSource, NoteOverrideMap } from './note-sources';

describe('NoteOverridesComponent', () => {
    let fixture: ComponentFixture<NoteOverridesComponent>;
    let latest: NoteOverrideMap;
    const noteAt = (noteId: string, label: string): ComposerSource => ({ label, spec: { role: 'SOURCE', type: 'NOTE', noteId, noteRowVersion: '3' } });
    const notes = [noteAt(noteIds.first, 'Глаголы движения'), noteAt(noteIds.second, '行く и 来る')];
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const rows = (): HTMLElement[] => [...root().querySelectorAll<HTMLElement>('.row')];
    const trigger = (row: number, kind: 'effort' | 'image' | 'audio'): HTMLButtonElement =>
        rows()[row]!.querySelector<HTMLButtonElement>(`button[role=combobox][id$="-${kind}-${row}"]`)!;
    const summaryText = (): string => root().querySelector('details.per-note > summary .count')!.textContent!.trim();

    function choose(button: HTMLButtonElement, label: string): void {
        button.click();
        fixture.detectChanges();
        [...root().querySelectorAll<HTMLElement>('[role=option]')].find(option => option.textContent!.trim() === label)!.click();
        fixture.detectChanges();
    }

    function create(options: { image?: boolean; audio?: boolean; value?: NoteOverrideMap } = {}): void {
        TestBed.resetTestingModule();
        fixture = TestBed.createComponent(NoteOverridesComponent);
        fixture.componentRef.setInput('notes', notes);
        fixture.componentRef.setInput('imageAvailable', options.image ?? true);
        fixture.componentRef.setInput('audioAvailable', options.audio ?? true);
        fixture.componentRef.setInput('value', options.value ?? {});
        latest = options.value ?? {};
        fixture.componentInstance.value.subscribe(value => { latest = value; fixture.componentRef.setInput('value', value); });
        fixture.detectChanges();
    }

    it('is a closed disclosure with one row per note, every control at «Как для всех», and a summary that says nothing is set', () => {
        create();
        const details = root().querySelector<HTMLDetailsElement>('details.per-note')!;
        expect(details.open).toBe(false);
        expect(details.querySelector('summary .title')?.textContent).toBe('Настроить для каждой заметки отдельно');
        expect(summaryText()).toBe('Все заметки — с общими настройками');
        expect(rows().map(row => row.querySelector('.excerpt')?.textContent)).toEqual(['Глаголы движения', '行く и 来る']);
        expect(trigger(0, 'effort').getAttribute('aria-label')).toBe('Подробность для заметки «Глаголы движения»: Как для всех');
        expect(rows()[0]!.querySelector('details.media summary')?.textContent).toBe('Вложения: как для всех');
        expect(root().querySelector('.reset')).toBeNull();
    });

    it('records only the changed member of the changed note, and counts the notes set apart', () => {
        create();
        choose(trigger(1, 'effort'), 'Подробно');
        expect(latest).toEqual({ [noteIds.second]: { effort: 'DETAILED', imageSearch: null, audio: null } });
        expect(summaryText()).toBe('1 заметка настроена отдельно');
        expect(trigger(1, 'effort').getAttribute('aria-label')).toContain('Подробно');
        expect(trigger(0, 'effort').getAttribute('aria-label')).toContain('Как для всех');
        choose(trigger(0, 'audio'), 'Да');
        expect(latest[noteIds.first]).toEqual({ effort: null, imageSearch: null, audio: true });
        expect(summaryText()).toBe('2 заметки настроены отдельно');
        expect(rows()[0]!.querySelector('details.media summary')?.textContent).toBe('Вложения: свои');
    });

    it('drops a note from the map when its last own setting goes back to «Как для всех», and resets a row on request', () => {
        create();
        choose(trigger(0, 'effort'), 'Кратко');
        choose(trigger(0, 'effort'), 'Как для всех');
        expect(latest).toEqual({});
        choose(trigger(1, 'image'), 'Нет');
        expect(latest[noteIds.second]).toEqual({ effort: null, imageSearch: false, audio: null });
        const reset = rows()[1]!.querySelector<HTMLButtonElement>('.reset')!;
        expect(reset.getAttribute('aria-label')).toBe('Сбросить настройки заметки «行く и 来る»');
        reset.click();
        fixture.detectChanges();
        expect(latest).toEqual({});
        expect(summaryText()).toBe('Все заметки — с общими настройками');
    });

    it('shows the existing drafts, and disables an attachment the server does not offer with the reason', () => {
        create({ image: false, audio: false, value: { [noteIds.first]: { effort: 'SHORT', imageSearch: null, audio: null } } });
        expect(trigger(0, 'effort').getAttribute('aria-label')).toContain('Кратко');
        expect(trigger(0, 'image').disabled).toBe(true);
        expect(trigger(0, 'audio').disabled).toBe(true);
        expect(rows()[0]!.querySelector('.media-controls .hint')?.textContent).toContain('Недоступное сейчас выключено');
        create();
        expect(trigger(0, 'image').disabled).toBe(false);
        expect(rows()[0]!.querySelector('.media-controls .hint')).toBeNull();
    });
});
