import { firstValueFrom } from 'rxjs';

import { DEMO_ASSETS, demoAwareResolver, isDemoAsset } from '../../content/exercise/demo/demo-media';
import { MECHANICS, authoringSlots } from '../../content/exercise/exercise-content.models';
import { parseExerciseSpec, parseLearnerContent } from '../../content/exercise/exercise-content.parse';
import { MECHANIC_CATALOG, catalogEntry, stepTitle } from '../../content/exercise/mechanic-catalog';
import { MediaPlaybackResolver } from '../study/media-playback-resolver';
import { removedNames } from '../study/study-test-data';
import { learnerContent } from './exercise-draft';

describe('mechanic catalog and demo fixtures', () => {
    const subject = { memberKey: '00000000-0000-4000-8000-000000000000', itemRevisionId: '00000000-0000-4000-8000-000000000000' };

    it('registers exactly the seven mechanics with human titles, ordered steps and no placeholder entries', () => {
        expect(MECHANIC_CATALOG.map(entry => entry.mechanic)).toEqual([...MECHANICS]);
        expect(MECHANIC_CATALOG.map(entry => entry.title)).toEqual(['Вспомнить и сверить', 'Ввести ответ', 'Заполнить пропуски',
            'Выбрать ответ', 'Сопоставить элементы', 'Восстановить порядок', 'Распределить по группам']);
        for (const entry of MECHANIC_CATALOG) {
            expect(entry.steps.at(-1)).toBe('finish');
            expect(entry.steps.length).toBe(entry.mechanic === 'CATEGORIZE' ? 4 : 3);
            expect(new Set(entry.steps).size).toBe(entry.steps.length);
            expect(entry.description.length).toBeGreaterThan(40);
            for (const step of entry.steps) expect(stepTitle(entry.mechanic, step)).not.toBe('');
            expect(entry.demo.exercise.type).toBe(entry.mechanic);
            expect(catalogEntry(entry.mechanic)).toBe(entry);
        }
        const json = JSON.stringify(MECHANIC_CATALOG);
        for (const removed of removedNames) expect(json).not.toContain(removed);
        expect(json).not.toContain('AI');
    });

    it('keeps the demo of every mechanic a valid exercise whose learner content passes the strict Study parser', () => {
        for (const entry of MECHANIC_CATALOG) {
            const spec = parseExerciseSpec({ ...entry.demo.exercise, enabled: true, subject });
            const learner = learnerContent(entry.demo.exercise, { context: null, revealed: false, placeholders: false });
            expect(() => parseLearnerContent(entry.mechanic, learner.content, false)).withContext(entry.mechanic).not.toThrow();
            // The demo never contains a transcript, a material pin or a real asset id.
            const json = JSON.stringify(spec);
            expect(json).not.toContain('MATERIAL');
            expect(json).not.toContain('aaaaaaaa');
            for (const block of authoringSlots(spec).flat()) {
                if (block.kind === 'IMAGE' || block.kind === 'AUDIO' || block.kind === 'VIDEO') expect(isDemoAsset(block.assetId)).toBeTrue();
            }
        }
    });

    it('gives CHOICE and MATCH a local audio and a local image and uses each demo asset as one media kind', () => {
        for (const mechanic of ['CHOICE', 'MATCH'] as const) {
            const spec = parseExerciseSpec({ ...catalogEntry(mechanic).demo.exercise, enabled: true, subject });
            const kinds = new Set(authoringSlots(spec).flat().map(block => block.kind));
            expect(kinds.has('AUDIO')).withContext(mechanic).toBeTrue();
            expect(kinds.has('IMAGE')).withContext(mechanic).toBeTrue();
        }
    });

    it('resolves demo assets from the bundle without the media API and leaves every other asset to the real resolver', async () => {
        const real = jasmine.createSpyObj<MediaPlaybackResolver>('real', ['resolve']);
        const resolver = demoAwareResolver(real);
        const tone = await firstValueFrom(resolver.resolve(DEMO_ASSETS.toneHigh));
        expect(tone?.url).toBe('/assets/demo/tone-high.mp3');
        expect(tone?.mimeType).toBe('audio/mpeg');
        expect((await firstValueFrom(resolver.resolve(DEMO_ASSETS.waveDense)))?.mimeType).toBe('image/svg+xml');
        expect(real.resolve).not.toHaveBeenCalled();
        real.resolve.and.returnValue(new (await import('rxjs')).BehaviorSubject(null));
        await firstValueFrom(resolver.resolve('aaaaaaaa-0000-4000-8000-000000000001'));
        expect(real.resolve).toHaveBeenCalledOnceWith('aaaaaaaa-0000-4000-8000-000000000001');
    });

    it('ships small original demo media that the test server can serve', async () => {
        for (const file of ['tone-low.mp3', 'tone-high.mp3', 'wave-sparse.svg', 'wave-dense.svg']) {
            const response = await fetch(`/assets/demo/${file}`);
            expect(response.ok).withContext(file).toBeTrue();
            expect((await response.arrayBuffer()).byteLength).withContext(file).toBeLessThan(50 * 1024);
        }
    });
});
