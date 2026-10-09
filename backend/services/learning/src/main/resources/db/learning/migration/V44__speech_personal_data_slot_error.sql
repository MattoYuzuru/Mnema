-- A speech step refuses a text that holds personal data (an e-mail address, a telephone or a card number) instead of sending it to a provider
-- (owner decision: text leaves Russia only depersonalised). The slot of such a clip fails with the new code PERSONAL_DATA.
DO $$
DECLARE
    found record;
BEGIN
    FOR found IN
        SELECT conname
        FROM pg_constraint
        WHERE contype = 'c'
          AND conrelid = 'app_learning.generation_media_slot'::regclass
          AND pg_get_constraintdef(oid) LIKE '%VERIFICATION_REJECTED%'
    LOOP
        EXECUTE format('ALTER TABLE app_learning.generation_media_slot DROP CONSTRAINT %I', found.conname);
    END LOOP;
END
$$;
ALTER TABLE app_learning.generation_media_slot
    ADD CONSTRAINT generation_media_slot_error_code_check CHECK (error_code IS NULL OR error_code IN ('PROVIDER_UNAVAILABLE', 'NO_RESULT',
        'VERIFICATION_REJECTED', 'DEADLINE_EXCEEDED', 'USAGE_LIMIT', 'ESTIMATE_EXCEEDED', 'CANCELLED', 'PERSONAL_DATA'));
