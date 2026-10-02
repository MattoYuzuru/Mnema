import { DECK_COMMAND_MAX_BYTES, DECK_DESCRIPTION_MAX_BYTES, DECK_TITLE_MAX_BYTES, DECK_TITLE_MAX_CODE_POINTS, expectedEtag, isCanonicalCommandId, isCanonicalEntityId, isCanonicalVersion, validateDeckMetadata } from './own-deck.models';

describe('own deck contract helpers', () => {
    it('checks title code points and UTF-8 bytes without trimming authored text', () => {
        const metadata = { title: `  ${'😀'.repeat(198)}`, description: '  перенос\nстроки  ' };
        const validation = validateDeckMetadata(metadata);

        expect(validation.valid).toBe(true);
        expect(validation.titleCodePoints).toBe(DECK_TITLE_MAX_CODE_POINTS);
        expect(validation.titleBytes).toBe(2 + 198 * 4);
        expect(validation.titleBytes).toBeLessThanOrEqual(DECK_TITLE_MAX_BYTES);
        expect(metadata.description).toBe('  перенос\nстроки  ');
    });

    it('rejects blank, invalid Unicode, byte overflow, and JSON-envelope overflow', () => {
        expect(validateDeckMetadata({ title: ' \n\t', description: '' }).titleBlank).toBe(true);
        expect(validateDeckMetadata({ title: '\u00a0', description: '' }).valid).toBe(true);
        expect(validateDeckMetadata({ title: '\ud800', description: '' }).unicodeValid).toBe(false);
        expect(validateDeckMetadata({ title: 'a', description: `a\0b` }).unicodeValid).toBe(false);
        expect(validateDeckMetadata({ title: 'я'.repeat(401), description: '' }).titleBytes)
            .toBeGreaterThan(DECK_TITLE_MAX_BYTES);
        expect(validateDeckMetadata({ title: 'a', description: 'я'.repeat(2049) }).descriptionBytes)
            .toBeGreaterThan(DECK_DESCRIPTION_MAX_BYTES);
        const escaped = validateDeckMetadata({ title: 'a', description: '\u0001'.repeat(DECK_DESCRIPTION_MAX_BYTES) });
        expect(escaped.descriptionBytes).toBe(DECK_DESCRIPTION_MAX_BYTES);
        expect(escaped.requestBytes).toBeGreaterThan(DECK_COMMAND_MAX_BYTES);
        expect(escaped.valid).toBe(false);
    });

    it('accepts only canonical entity, command, and decimal version boundaries', () => {
        expect(isCanonicalEntityId('11111111-1111-4111-8111-111111111111')).toBe(true);
        expect(isCanonicalEntityId('AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA')).toBe(true);
        expect(isCanonicalEntityId('00000000-0000-0000-0000-000000000000')).toBe(false);
        expect(isCanonicalCommandId('11111111-1111-4111-8111-111111111111')).toBe(true);
        expect(isCanonicalCommandId('11111111-1111-5111-8111-111111111111')).toBe(false);
        expect(isCanonicalVersion('0')).toBe(true);
        expect(isCanonicalVersion('9223372036854775806')).toBe(true);
        expect(isCanonicalVersion('9223372036854775807')).toBe(false);
        expect(isCanonicalVersion('01')).toBe(false);
        expect(expectedEtag('42')).toBe('"42"');
    });
});
