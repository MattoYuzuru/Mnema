import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';

import { MnemaSelectComponent } from '../../core/controls/mnema-select.component';
import { SegmentedChoiceComponent, SegmentedOption } from '../../shared/segmented-choice.component';
import { ToggletipComponent } from '../../shared/toggletip.component';
import { Effort, NotesMode } from './generation.models';
import { AUDIO_LANGUAGES, EFFORT_OPTIONS } from './generation-view';

export type VoiceChoice = 'any' | 'female' | 'male';

/**
 * What the composer lets the user tune for a Materials session. `notesMode` has no control yet: the note chips, the
 * grouping choice and per-note overrides arrive with AI-08 (#290) and extend this value without changing the composer.
 */
export interface GenerationSettingsValue {
    readonly effort: Effort;
    readonly notesMode: NotesMode;
    readonly imageSearch: boolean;
    readonly audio: boolean;
    readonly audioLang: string;
    readonly audioVoice: VoiceChoice;
    readonly similarToDeck: boolean;
}

export const DEFAULT_SETTINGS: GenerationSettingsValue = {
    effort: 'AUTO', notesMode: 'ONE_PER_NOTE', imageSearch: false, audio: false, audioLang: 'ru', audioVoice: 'any',
    similarToDeck: true
};

const VOICES: readonly SegmentedOption<VoiceChoice>[] = [
    { value: 'any', label: 'Любой' }, { value: 'female', label: 'Женский' }, { value: 'male', label: 'Мужской' }
];

let nextSettings = 0;

/**
 * The settings of one generation request, with progressive disclosure: «Подробность» is always visible with its live
 * explanation under the group; attachments (with the nested audio parameters), «Похоже на» and «Сначала показать план» sit
 * behind «Ещё настройки». The plan-first control is a disabled stub until the planner exists (AI-14): the request never
 * asks for a plan.
 */
@Component({
    selector: 'app-generation-settings',
    imports: [SegmentedChoiceComponent, ToggletipComponent, MnemaSelectComponent],
    template: `
      <app-segmented-choice legend="Подробность" [options]="effortOptions" [name]="uid + '-effort'"
        [value]="value().effort" (valueChange)="patch({ effort: $event ?? 'AUTO' })">
        <app-toggletip topic="подробность">
          <p>Чем подробнее материал, тем больше он занимает в лимите ИИ. Оценка рядом с кнопкой пересчитывается сама.</p>
        </app-toggletip>
      </app-segmented-choice>

      <details class="more">
        <summary>Ещё настройки</summary>

        <fieldset class="group" [attr.aria-describedby]="uid + '-attachments-hint'">
          <legend>Вложения</legend>
          <div class="chips">
            <label class="chip" [class.is-off]="!imageAvailable()">
              <input type="checkbox" [checked]="value().imageSearch && imageAvailable()" [disabled]="!imageAvailable()"
                (change)="patch({ imageSearch: $any($event.target).checked })" />
              <span>Изображения</span>
            </label>
            <label class="chip" [class.is-off]="!audioAvailable()">
              <input type="checkbox" [checked]="value().audio && audioAvailable()" [disabled]="!audioAvailable()"
                [attr.aria-controls]="uid + '-audio'" (change)="patch({ audio: $any($event.target).checked })" />
              <span>Аудио</span>
            </label>
          </div>
          <p class="hint" [id]="uid + '-attachments-hint'">{{ attachmentsHint() }}</p>

          @if (value().audio && audioAvailable()) {
            <fieldset class="nested" [id]="uid + '-audio'">
              <legend>Параметры аудио</legend>
              <app-mnema-select [controlId]="uid + '-audio-lang'" label="Язык озвучки" [options]="languages"
                [value]="value().audioLang" [compact]="true" (valueChange)="patch({ audioLang: $event })" />
              <app-segmented-choice legend="Голос" [options]="voices" [name]="uid + '-voice'" [value]="value().audioVoice"
                (valueChange)="patch({ audioVoice: $event ?? 'any' })" />
            </fieldset>
          }
        </fieldset>

        <div class="group">
          <label class="check">
            <input type="checkbox" [checked]="value().similarToDeck" [attr.aria-describedby]="uid + '-similar-hint'"
              (change)="patch({ similarToDeck: $any($event.target).checked })" />
            <span>Похоже на: как в колоде</span>
          </label>
          <p class="hint" [id]="uid + '-similar-hint'">Мнема возьмёт образцы стиля из материалов, отмеченных «Эталон», и из свежих материалов колоды.</p>
        </div>

        <div class="group">
          <label class="check is-stub">
            <input type="checkbox" disabled [attr.aria-describedby]="uid + '-plan-hint'" />
            <span>Сначала показать план</span>
          </label>
          <p class="hint" [id]="uid + '-plan-hint'">Появится позже. Пока Мнема сразу пишет материал, а вы решаете, что оставить.</p>
        </div>
      </details>
    `,
    styleUrl: './generation-settings.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class GenerationSettingsComponent {
    readonly value = model.required<GenerationSettingsValue>();
    /** Image search and speech are separate server capabilities; a switched-off one is shown disabled with the reason. */
    readonly imageAvailable = input(false);
    readonly audioAvailable = input(false);

    protected readonly uid = `mn-generation-settings-${nextSettings++}`;
    protected readonly effortOptions = EFFORT_OPTIONS;
    protected readonly voices = VOICES;
    protected readonly languages = AUDIO_LANGUAGES;
    protected readonly attachmentsHint = computed(() => {
        if (!this.imageAvailable() && !this.audioAvailable()) return 'Изображения и аудио сейчас недоступны: добавить их можно в редакторе.';
        if (!this.imageAvailable()) return 'Поиск изображений сейчас недоступен.';
        if (!this.audioAvailable()) return 'Озвучка сейчас недоступна.';
        return 'Мнема добавит их, если выбрано: это расходует лимит отдельно от текста.';
    });

    protected patch(change: Partial<GenerationSettingsValue>): void {
        this.value.set({ ...this.value(), ...change });
    }
}
