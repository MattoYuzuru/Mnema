import { AuthoringProtocolError } from '../authoring/authoring.models';
import {
    AUDIO_REDO_HINT, AUDIO_RUNNING, audioFailureReason, editOutcomeNote, synthesizedCaption, turnFailureReason, voiceLabel, voiceRedoneText
} from './generation-view';
import {
    AUDIO_ERROR_CODES, RequestValidationError, parseArtifactDetail, parseTurn, serializeEdit
} from './generation.models';
import { clone, examples, ids, statesContract } from './generation-test-data';

const audio = '00000000-0000-4000-8000-000000000006';
const command = ids.command;
const base = { expectedRevisionId: ids.revision, action: 'AUDIO_REGENERATE' as const, nodeIds: [audio] };

describe('speech on the wire (contracts/generation, AI-09 #297)', () => {
    it('reads the voice and the language of the audio of the contract, and nothing of them for an image', () => {
        const [slot] = parseArtifactDetail(examples['artifactDetailItem']).mediaSlots;
        expect(slot).toMatchObject({ kind: 'AUDIO', voice: 'female', lang: 'ja', mode: null });
        const image = clone(examples['artifactDetailItem']);
        image['mediaSlots'] = [clone(examples['mediaSlotImageSearch'])];
        expect(parseArtifactDetail(image).mediaSlots[0]).toMatchObject({ kind: 'IMAGE', voice: null, lang: null });
    });

    it('refuses a voice that is neither female nor male and a language that is not text, and tolerates their absence', () => {
        const detail = (change: (slot: any) => void) => { const held = clone(examples['artifactDetailItem']); change(held.mediaSlots[0]); return held; };
        expect(() => parseArtifactDetail(detail(slot => { slot.voice = 'robot'; }))).toThrow(AuthoringProtocolError);
        expect(() => parseArtifactDetail(detail(slot => { slot.lang = 5; }))).toThrow(AuthoringProtocolError);
        expect(() => parseArtifactDetail(detail(slot => { slot.lang = ''; }))).toThrow(AuthoringProtocolError);
        expect(parseArtifactDetail(detail(slot => { delete slot.voice; delete slot.lang; })).mediaSlots[0]).toMatchObject({ voice: null, lang: null });
    });

    it('reads a refused audio slot with its PERSONAL_DATA code and knows it as a media slot code of states.json', () => {
        const held = clone(examples['artifactDetailItem']);
        Object.assign(held.mediaSlots[0], { state: 'FAILED', errorCode: 'PERSONAL_DATA' });
        expect(parseArtifactDetail(held).mediaSlots[0]).toMatchObject({ state: 'FAILED', errorCode: 'PERSONAL_DATA' });
        expect(Object.keys(statesContract['artifact'].mediaSlotErrorCodes)).toContain('PERSONAL_DATA');
    });

    it('reads turnAudioRegenerateApplied, and the codes an audio turn fails with', () => {
        expect(parseTurn(examples['turnAudioRegenerateApplied'])).toMatchObject({ status: 'APPLIED', action: 'AUDIO_REGENERATE', voice: 'male',
            targetNodeIds: [audio], resultRevisionId: '4e700000-0000-4000-8000-000000000004' });
        const failed = { ...clone(examples['turnAudioRegenerateApplied']), status: 'FAILED', resultRevisionId: null };
        for (const code of ['PROVIDER_UNAVAILABLE', 'VERIFICATION_REJECTED', 'DEADLINE_EXCEEDED', 'PERSONAL_DATA']) expect(parseTurn({ ...failed, errorCode: code }).errorCode).toBe(code);
        expect(parseTurn({ ...failed, errorCode: 'SOMETHING_NEW' }).errorCode).toBeNull();
    });

    it('knows exactly the audio error codes of states.json', () => {
        expect([...AUDIO_ERROR_CODES].sort()).toEqual(Object.keys(statesContract['turn'].audioErrorCodes).sort());
    });

    describe('the redo of the audio of a material', () => {
        it('names one audio and carries a voice only when there is one', () => {
            expect(serializeEdit(base, command)).toEqual({ commandId: command, expectedRevisionId: ids.revision, action: 'AUDIO_REGENERATE', target: { nodeIds: [audio] } });
            expect(serializeEdit({ ...base, voice: null }, command)).not.toHaveProperty('voice');
            expect(serializeEdit({ ...base, voice: 'male' }, command)).toEqual({ commandId: command, expectedRevisionId: ids.revision, action: 'AUDIO_REGENERATE',
                target: { nodeIds: [audio] }, voice: 'male' });
        });

        it('refuses no audio, two audios, a preset, an instruction, an unknown voice, and a voice on another action', () => {
            expect(() => serializeEdit({ ...base, nodeIds: [] }, command)).toThrow(RequestValidationError);
            expect(() => serializeEdit({ ...base, nodeIds: [audio, ids.first] }, command)).toThrow(RequestValidationError);
            expect(() => serializeEdit({ ...base, preset: 'SIMPLER' }, command)).toThrow(RequestValidationError);
            expect(() => serializeEdit({ ...base, instruction: 'громче' }, command)).toThrow(RequestValidationError);
            expect(() => serializeEdit({ ...base, voice: 'robot' as never }, command)).toThrow(AuthoringProtocolError);
            expect(() => serializeEdit({ ...base, action: 'REMOVE_MEDIA', voice: 'male' }, command)).toThrow(RequestValidationError);
        });
    });

    describe('the words', () => {
        it('has the caption, the hint, the status and the chips', () => {
            expect(synthesizedCaption('female')).toBe('Синтезированная речь · женский голос');
            expect(synthesizedCaption('male')).toBe('Синтезированная речь · мужской голос');
            expect(synthesizedCaption(null)).toBe('Синтезированная речь');
            expect(voiceLabel('male')).toBe('мужской голос');
            expect(AUDIO_RUNNING).toBe('Озвучиваю…');
            expect(AUDIO_REDO_HINT).toBe('Тем же голосом — новая запись (до 10 кредитов). Другим голосом — бесплатно, если такой текст уже озвучивали.');
            expect(voiceRedoneText('female')).toBe('Озвучено заново: женский');
        });

        it('says why a clip is missing, in the words of the issue', () => {
            expect(audioFailureReason('PROVIDER_UNAVAILABLE')).toBe('Озвучка сейчас недоступна.');
            expect(audioFailureReason('VERIFICATION_REJECTED')).toBe('Запись не прошла проверку.');
            expect(audioFailureReason('DEADLINE_EXCEEDED')).toBe('Озвучка заняла слишком долго.');
            expect(audioFailureReason('USAGE_LIMIT')).toBe('Не хватает лимита на озвучку.');
            expect(audioFailureReason('PERSONAL_DATA')).toContain('не отправляется на озвучку');
            expect(turnFailureReason('PERSONAL_DATA')).toBe(audioFailureReason('PERSONAL_DATA'));
            expect(audioFailureReason(null)).toBe('Не удалось озвучить.');
        });

        it('has no word about a Stub or about a synthesis that is not connected yet', () => {
            for (const status of ['APPLIED', 'FAILED', 'CANCELLED'] as const) {
                for (const exercise of [true, false]) expect(editOutcomeNote(status, 'AUDIO_REGENERATE', exercise)).not.toMatch(/подключим|Голос записан/u);
            }
            expect(editOutcomeNote('APPLIED', 'AUDIO_REGENERATE')).toBe('Озвучено заново.');
        });
    });
});
