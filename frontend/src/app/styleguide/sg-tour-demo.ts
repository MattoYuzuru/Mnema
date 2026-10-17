import { signal } from '@angular/core';

import { ActionOptions, JourneyAction } from '../core/onboarding/onboarding.models';
import { TourPersistence } from '../core/onboarding/tour-engine.service';
import { paragraphs, slides } from '../core/onboarding/tour-rich-text';
import { TourBody, TourDefinition } from '../core/onboarding/tour.models';

/** The texts are the approved ones of the sample deck tour (design/prototype/onboarding/texts.js); the keys are the registry's. */
export const DEMO_TOUR: TourDefinition = {
    id: 'styleguide-demo',
    branch: 'SAMPLE',
    steps: [
        {
            key: 'sample.hub', anchor: 'hub.header', title: 'Это ваша учебная колода', gate: 'next',
            body: paragraphs('Здесь название, описание и все <span class="key">материалы</span> колоды. Её можно менять и учить, как свою.')
        },
        {
            key: 'sample.material', anchor: 'hub.material', title: 'Материал — то, что вы учите', gate: 'next',
            body: slides(
                'Текст можно оформить: выделить <span class="key">жирным</span>, собрать в список, добавить цитату или ссылку.',
                'В материал можно добавить <span class="key">картинку</span> и <span class="key">аудио</span>.',
                'У каждого материала — свои упражнения. Их можно добавить позже.')
        },
        {
            key: 'sample.exercises', anchor: 'hub.exercises', title: 'Упражнения проверяют, что вы помните', gate: 'next',
            body: paragraphs('Одно и то же можно спросить по-разному: выбрать ответ, вписать слово или <span class="key">объяснить своими словами</span>. Объяснение проверит ИИ.')
        },
        {
            key: 'sample.study', anchor: 'hub.start', title: 'Пройдите первое занятие', placement: 'top',
            gate: { action: 'study.started', label: 'Начать занятие' },
            body: paragraphs('Короткое занятие — несколько упражнений. Дальше Mnema сама подскажет, что и когда повторить.')
        }
    ]
};

/** What the specimens show on a card; the same approved texts. */
export const DEMO_INFO_BODY: TourBody = DEMO_TOUR.steps[0].body;
export const DEMO_SLIDES_BODY: TourBody = DEMO_TOUR.steps[1].body;
export const DEMO_GATE_BODY: TourBody = DEMO_TOUR.steps[3].body;

/** Records what the engine would send to the server, in a list the page shows; nothing leaves the browser. */
export class DemoTourPersistence implements TourPersistence {
    readonly journal = signal<readonly string[]>([]);

    act(action: JourneyAction, options: ActionOptions = {}): Promise<unknown> {
        this.journal.update(list => [...list, options.stepKey === undefined ? action : `${action} ${options.stepKey}`]);
        return Promise.resolve(null);
    }
}
