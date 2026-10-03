import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';

import { MnemaSelectComponent, MnemaSelectOption } from '../../core/controls/mnema-select.component';
import { EFFORTS, Effort } from './generation.models';
import { EFFORT_OPTIONS } from './generation-view';
import {
    ComposerSource, NO_OVERRIDE, NoteOverrideDraft, NoteOverrideMap, customizedCount, customizedSummary, isCustomized, sourceKey
} from './note-sources';

const AS_FOR_ALL = 'default';

const EFFORT_CHOICES: readonly MnemaSelectOption[] = [
    { value: AS_FOR_ALL, label: 'Как для всех' },
    ...EFFORT_OPTIONS.map(option => ({ value: option.value, label: option.label }))
];

const SWITCH_CHOICES: readonly MnemaSelectOption[] = [
    { value: AS_FOR_ALL, label: 'Как для всех' }, { value: 'on', label: 'Да' }, { value: 'off', label: 'Нет' }
];

let nextOverrides = 0;

function switchValue(value: boolean | null): string {
    return value === null ? AS_FOR_ALL : value ? 'on' : 'off';
}

function readSwitch(value: string): boolean | null {
    return value === 'on' ? true : value === 'off' ? false : null;
}

/**
 * «Настроить для каждой заметки отдельно» (AI-08, #290): a closed `<details>` with one row per note. A row has the start of
 * the note, a compact select for the effort and a nested disclosure for the attachments; every control starts at «Как для
 * всех». The summary says how many notes carry their own settings. It only edits the drafts: the composer turns them into
 * the sparse `overrides` of the spec.
 */
@Component({
    selector: 'app-note-overrides',
    imports: [MnemaSelectComponent],
    template: `
      <details class="per-note">
        <summary>
          <span class="title">Настроить для каждой заметки отдельно</span>
          <span class="count" [class.is-set]="count() > 0">{{ summary() }}</span>
        </summary>
        <ul class="rows">
          @for (note of notes(); track sourceKey(note); let index = $index) {
            <li class="row">
              <p class="excerpt">{{ note.label }}</p>
              <div class="controls">
                <div class="control">
                  <span class="caption" aria-hidden="true">Подробность</span>
                  <app-mnema-select [controlId]="uid + '-effort-' + index" [label]="'Подробность для заметки «' + note.label + '»'"
                    [options]="effortChoices" [value]="effortValue(note)" [compact]="true"
                    (valueChange)="setEffort(note, $event)" />
                </div>
                <details class="media">
                  <summary>{{ mediaSummary(note) }}</summary>
                  <div class="media-controls">
                    <div class="control">
                      <span class="caption" aria-hidden="true">Изображения</span>
                      <app-mnema-select [controlId]="uid + '-image-' + index" [label]="'Изображения для заметки «' + note.label + '»'"
                        [options]="switchChoices" [value]="imageValue(note)" [compact]="true" [disabled]="!imageAvailable()"
                        (valueChange)="setImage(note, $event)" />
                    </div>
                    <div class="control">
                      <span class="caption" aria-hidden="true">Аудио</span>
                      <app-mnema-select [controlId]="uid + '-audio-' + index" [label]="'Аудио для заметки «' + note.label + '»'"
                        [options]="switchChoices" [value]="audioValue(note)" [compact]="true" [disabled]="!audioAvailable()"
                        (valueChange)="setAudio(note, $event)" />
                    </div>
                    @if (!imageAvailable() || !audioAvailable()) {
                      <p class="hint">Недоступное сейчас выключено: добавить его можно в редакторе.</p>
                    }
                  </div>
                </details>
                @if (customized(note)) {
                  <button type="button" class="reset" (click)="reset(note)"
                    [attr.aria-label]="'Сбросить настройки заметки «' + note.label + '»'">Сбросить</button>
                }
              </div>
            </li>
          }
        </ul>
      </details>
    `,
    styleUrl: './note-overrides.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NoteOverridesComponent {
    /** The `SOURCE` notes of the request, in order. */
    readonly notes = input.required<readonly ComposerSource[]>();
    readonly imageAvailable = input(false);
    readonly audioAvailable = input(false);
    /** Drafts by note id; a note without an entry follows the session settings. */
    readonly value = model<NoteOverrideMap>({});

    protected readonly uid = `mn-note-overrides-${nextOverrides++}`;
    protected readonly sourceKey = sourceKey;
    protected readonly effortChoices = EFFORT_CHOICES;
    protected readonly switchChoices = SWITCH_CHOICES;
    protected readonly count = computed(() => customizedCount(this.value(), this.notes().map(sourceKey)));
    protected readonly summary = computed(() => customizedSummary(this.count()));

    protected effortValue(note: ComposerSource): string { return this.draft(note).effort ?? AS_FOR_ALL; }
    protected imageValue(note: ComposerSource): string { return switchValue(this.draft(note).imageSearch); }
    protected audioValue(note: ComposerSource): string { return switchValue(this.draft(note).audio); }
    protected customized(note: ComposerSource): boolean { return isCustomized(this.value()[sourceKey(note)]); }

    protected mediaSummary(note: ComposerSource): string {
        const draft = this.draft(note);
        return draft.imageSearch === null && draft.audio === null ? 'Вложения: как для всех' : 'Вложения: свои';
    }

    protected setEffort(note: ComposerSource, value: string): void {
        this.patch(note, { effort: (EFFORTS as readonly string[]).includes(value) ? value as Effort : null });
    }

    protected setImage(note: ComposerSource, value: string): void { this.patch(note, { imageSearch: readSwitch(value) }); }
    protected setAudio(note: ComposerSource, value: string): void { this.patch(note, { audio: readSwitch(value) }); }

    protected reset(note: ComposerSource): void {
        const { [sourceKey(note)]: _removed, ...rest } = this.value();
        this.value.set(rest);
    }

    private draft(note: ComposerSource): NoteOverrideDraft { return this.value()[sourceKey(note)] ?? NO_OVERRIDE; }

    /** A draft that is back to «как для всех» everywhere is dropped, so the map holds only what is really set. */
    private patch(note: ComposerSource, change: Partial<NoteOverrideDraft>): void {
        const next = { ...this.draft(note), ...change };
        if (!isCustomized(next)) { this.reset(note); return; }
        this.value.set({ ...this.value(), [sourceKey(note)]: next });
    }
}
