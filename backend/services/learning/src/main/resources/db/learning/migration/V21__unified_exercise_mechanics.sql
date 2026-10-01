-- #266: five unified exercise mechanics (SELF_CHECK, FREE_RESPONSE, CLOZE, CHOICE, MATCH) over typed content
-- slots. Content, answer key and objective descriptor become separate persisted concerns.
--
-- The rewrite is greenfield: legacy mechanic names and answer contracts are not converted. Refuse to run
-- against old exercise data instead of deleting or reinterpreting it.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM app_learning.exercise_revision)
       OR EXISTS (SELECT 1 FROM app_learning.objective_revision)
       OR EXISTS (SELECT 1 FROM app_learning.study_presentation) THEN
        RAISE EXCEPTION 'Exercise data from the pre-#266 mechanics exists. A fresh local Learning database is required; see docs/deploy/selfhost-local.md (scripts/mnema-local-full-stack.sh reset --confirm-delete-local-data).'
            USING ERRCODE = '55000';
    END IF;
END
$$;

-- Objective: stable identity plus a human title. Answer keys belong to the exercise revision.
ALTER TABLE app_learning.objective_revision RENAME COLUMN answer_contract TO descriptor;
ALTER TABLE app_learning.objective_revision RENAME CONSTRAINT objective_revision_answer_contract_check
    TO objective_revision_descriptor_shape_check;
ALTER TABLE app_learning.objective_revision ADD CONSTRAINT objective_revision_descriptor_check
    CHECK (descriptor ->> 'schemaVersion' = '1'
        AND jsonb_typeof(descriptor -> 'title') = 'string'
        AND char_length(descriptor ->> 'title') BETWEEN 1 AND 160
        AND btrim(descriptor ->> 'title') <> ''
        AND descriptor - 'schemaVersion' - 'title' = '{}'::jsonb);

-- Exercise revision: content slots, answer key and evaluator policy are independent.
ALTER TABLE app_learning.exercise_revision DROP CONSTRAINT exercise_revision_exercise_type_check;
ALTER TABLE app_learning.exercise_revision ADD CONSTRAINT exercise_revision_exercise_type_check
    CHECK (exercise_type IN ('SELF_CHECK','FREE_RESPONSE','CLOZE','CHOICE','MATCH'));
ALTER TABLE app_learning.exercise_revision DROP CONSTRAINT exercise_revision_schema_version_check;
ALTER TABLE app_learning.exercise_revision ADD CONSTRAINT exercise_revision_schema_version_check
    CHECK (schema_version = 2);
ALTER TABLE app_learning.exercise_revision RENAME COLUMN prompt_spec TO content;
ALTER TABLE app_learning.exercise_revision RENAME CONSTRAINT exercise_revision_prompt_spec_check
    TO exercise_revision_content_check;
ALTER TABLE app_learning.exercise_revision
    ADD COLUMN answer_key JSONB NOT NULL CHECK (jsonb_typeof(answer_key) = 'object'
        AND answer_key ->> 'kind' IN ('SELF_REPORT','TEXT','CLOZE','CHOICE','MATCH'));

-- Server-derived bindings only: the ASSESSED subject and the CONTEXT materials quoted by MATERIAL blocks.
ALTER TABLE app_learning.exercise_content_binding DROP CONSTRAINT exercise_content_binding_role_check;
ALTER TABLE app_learning.exercise_content_binding ADD CONSTRAINT exercise_content_binding_role_check
    CHECK (role IN ('ASSESSED','CONTEXT'));
ALTER TABLE app_learning.exercise_content_binding DROP COLUMN display_spec;

-- Every media block of every slot is retained, with the kind the author declared.
ALTER TABLE app_learning.exercise_media_ref
    ADD COLUMN media_kind TEXT NOT NULL CHECK (media_kind IN ('image','audio','video'));
DROP FUNCTION app_learning.exercise_audio_ready(UUID, UUID, UUID, UUID);
-- One readiness predicate for candidate selection, pair checks and submission: every pinned asset is READY
-- and its verified source really is the declared kind. A text-only exercise is trivially ready.
CREATE FUNCTION app_learning.exercise_media_ready(p_owner UUID, p_deck UUID, p_exercise UUID,
    p_revision UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT COALESCE(bool_and(COALESCE(asset.state = 'READY' AND blob.mime_type LIKE ref.media_kind || '/%', FALSE)), TRUE)
      FROM app_learning.exercise_media_ref ref
      JOIN app_learning.media_asset asset ON asset.asset_id = ref.asset_id AND asset.owner_id = ref.owner_id
      LEFT JOIN app_learning.media_blob blob ON blob.blob_id = asset.source_blob_id
     WHERE ref.owner_id = p_owner AND ref.deck_id = p_deck AND ref.exercise_id = p_exercise
       AND ref.exercise_revision_id = p_revision;
$$;

-- Issued presentations: learner-visible content is resolved once at issue time and replayed verbatim.
-- The answer key stays private; `reveal` is shown only after the answer is final.
ALTER TABLE app_learning.study_presentation DROP CONSTRAINT study_presentation_exercise_type_check;
ALTER TABLE app_learning.study_presentation ADD CONSTRAINT study_presentation_exercise_type_check
    CHECK (exercise_type IN ('SELF_CHECK','FREE_RESPONSE','CLOZE','CHOICE','MATCH'));
ALTER TABLE app_learning.study_presentation DROP COLUMN options;
ALTER TABLE app_learning.study_presentation DROP COLUMN bindings;
ALTER TABLE app_learning.study_presentation RENAME COLUMN prompt TO content;
ALTER TABLE app_learning.study_presentation RENAME CONSTRAINT study_presentation_prompt_check
    TO study_presentation_content_check;
ALTER TABLE app_learning.study_presentation RENAME COLUMN answer_contract TO answer_key;
ALTER TABLE app_learning.study_presentation RENAME CONSTRAINT study_presentation_answer_contract_check
    TO study_presentation_answer_key_check;
ALTER TABLE app_learning.study_presentation
    ADD COLUMN reveal JSONB NOT NULL CHECK (jsonb_typeof(reveal) = 'object');

-- Pair interactions name the two sides of a MATCH, not a cue and an option.
ALTER TABLE app_learning.study_pair_interaction RENAME COLUMN cue_id TO left_id;
ALTER TABLE app_learning.study_pair_interaction RENAME COLUMN option_id TO right_id;

-- A transcript can belong to any audio or video block, so the accommodation is media-neutral.
ALTER TABLE app_learning.study_audio_accommodation RENAME TO study_transcript_accommodation;
ALTER TRIGGER study_audio_accommodation_immutable ON app_learning.study_transcript_accommodation
    RENAME TO study_transcript_accommodation_immutable;

-- A first-letter hint is recorded by the server; the client can no longer claim hint usage.
CREATE TABLE app_learning.study_hint_reveal (
    account_id UUID NOT NULL,
    session_id UUID NOT NULL,
    presentation_id UUID NOT NULL,
    blank_id UUID NOT NULL,
    revealed_at TIMESTAMPTZ NOT NULL CHECK (isfinite(revealed_at)),
    PRIMARY KEY (account_id, session_id, presentation_id, blank_id),
    FOREIGN KEY (account_id, session_id, presentation_id)
        REFERENCES app_learning.study_presentation(account_id, session_id, presentation_id)
);
CREATE TRIGGER study_hint_reveal_immutable BEFORE UPDATE OR DELETE ON app_learning.study_hint_reveal
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_immutable_guard();
