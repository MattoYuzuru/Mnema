import {
    DECK_COMMAND_MAX_BYTES,
    DECK_DESCRIPTION_MAX_BYTES,
    DECK_TITLE_MAX_BYTES,
    DECK_TITLE_MAX_CODE_POINTS,
    expectedEtag,
    isCanonicalCommandId,
    isCanonicalEntityId,
    isCanonicalVersion,
    validateDeckMetadata
} from './own-deck.models';

describe('own deck contract helpers', () => {
    it('checks title code points and UTF-8 bytes without trimming authored text', () => {
        const metadata = { title: `  ${'😀'.repeat(198)}`, description: '  перенос\nстроки  ' };
        const validation = validateDeckMetadata(metadata);

        expect(validation.valid).toBeTrue();
        expect(validation.titleCodePoints).toBe(DECK_TITLE_MAX_CODE_POINTS);
        expect(validation.titleBytes).toBe(2 + 198 * 4);
        expect(validation.titleBytes).toBeLessThanOrEqual(DECK_TITLE_MAX_BYTES);
        expect(metadata.description).toBe('  перенос\nстроки  ');
    });

    it('rejects blank, invalid Unicode, byte overflow, and JSON-envelope overflow', () => {
        expect(validateDeckMetadata({ title: ' \n\t', description: '' }).titleBlank).toBeTrue();
        expect(validateDeckMetadata({ title: '\u00a0', description: '' }).valid).toBeTrue();
        expect(validateDeckMetadata({ title: '\ud800', description: '' }).unicodeValid).toBeFalse();
        expect(validateDeckMetadata({ title: 'a', description: `a\0b` }).unicodeValid).toBeFalse();
        expect(validateDeckMetadata({ title: 'я'.repeat(401), description: '' }).titleBytes)
            .toBeGreaterThan(DECK_TITLE_MAX_BYTES);
        expect(validateDeckMetadata({ title: 'a', description: 'я'.repeat(2049) }).descriptionBytes)
            .toBeGreaterThan(DECK_DESCRIPTION_MAX_BYTES);
        const escaped = validateDeckMetadata({ title: 'a', description: '\u0001'.repeat(DECK_DESCRIPTION_MAX_BYTES) });
        expect(escaped.descriptionBytes).toBe(DECK_DESCRIPTION_MAX_BYTES);
        expect(escaped.requestBytes).toBeGreaterThan(DECK_COMMAND_MAX_BYTES);
        expect(escaped.valid).toBeFalse();
    });

    it('accepts only canonical entity, command, and decimal version boundaries', () => {
        expect(isCanonicalEntityId('11111111-1111-4111-8111-111111111111')).toBeTrue();
        expect(isCanonicalEntityId('AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA')).toBeTrue();
        expect(isCanonicalEntityId('00000000-0000-0000-0000-000000000000')).toBeFalse();
        expect(isCanonicalCommandId('11111111-1111-4111-8111-111111111111')).toBeTrue();
        expect(isCanonicalCommandId('11111111-1111-5111-8111-111111111111')).toBeFalse();
        expect(isCanonicalVersion('0')).toBeTrue();
        expect(isCanonicalVersion('9223372036854775806')).toBeTrue();
        expect(isCanonicalVersion('9223372036854775807')).toBeFalse();
        expect(isCanonicalVersion('01')).toBeFalse();
        expect(expectedEtag('42')).toBe('"42"');
    });
});
