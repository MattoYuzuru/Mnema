-- Three versioned listening mechanics retain logical assets through immutable exercise revisions.
ALTER TABLE app_learning.exercise_revision DROP CONSTRAINT exercise_revision_exercise_type_check;
ALTER TABLE app_learning.exercise_revision ADD CONSTRAINT exercise_revision_exercise_type_check
    CHECK (exercise_type IN ('SELF_CHECK','TYPED','CLOZE_SINGLE','SINGLE_CHOICE',
                            'LISTEN_CHOICE','AUDIO_TEXT_MATCH','LISTEN_TYPE'));
ALTER TABLE app_learning.study_presentation DROP CONSTRAINT study_presentation_exercise_type_check;
ALTER TABLE app_learning.study_presentation ADD CONSTRAINT study_presentation_exercise_type_check
    CHECK (exercise_type IN ('SELF_CHECK','TYPED','CLOZE_SINGLE','SINGLE_CHOICE',
                            'LISTEN_CHOICE','AUDIO_TEXT_MATCH','LISTEN_TYPE'));

ALTER TABLE app_learning.exercise_definition ADD CONSTRAINT exercise_definition_media_owner
    UNIQUE (deck_id, exercise_id, owner_id);
CREATE TABLE app_learning.exercise_media_ref (
    deck_id UUID NOT NULL,
    exercise_id UUID NOT NULL,
    exercise_revision_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    PRIMARY KEY (deck_id, exercise_id, exercise_revision_id, asset_id),
    FOREIGN KEY (deck_id, exercise_id, owner_id)
        REFERENCES app_learning.exercise_definition(deck_id, exercise_id, owner_id),
    FOREIGN KEY (deck_id, exercise_id, exercise_revision_id)
        REFERENCES app_learning.exercise_revision(deck_id, exercise_id, revision_id),
    FOREIGN KEY (asset_id, owner_id)
        REFERENCES app_learning.media_asset(asset_id, owner_id)
);
CREATE INDEX exercise_media_ref_asset ON app_learning.exercise_media_ref(asset_id);
CREATE FUNCTION app_learning.exercise_media_ref_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Immutable published exercise media reference' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER exercise_media_ref_guard BEFORE UPDATE ON app_learning.exercise_media_ref
    FOR EACH ROW EXECUTE FUNCTION app_learning.exercise_media_ref_guard();

-- One canonical readiness predicate is used by selection and attempt submission. The verified
-- source must really be audio; a READY image asset must never become a listening cue.
CREATE FUNCTION app_learning.exercise_audio_ready(p_owner UUID, p_deck UUID, p_exercise UUID,
    p_revision UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT count(*) > 0 AND COALESCE(bool_and(asset.state = 'READY'
        AND blob.mime_type LIKE 'audio/%'), FALSE)
      FROM app_learning.exercise_media_ref ref
      JOIN app_learning.media_asset asset ON asset.asset_id = ref.asset_id AND asset.owner_id = ref.owner_id
      LEFT JOIN app_learning.media_blob blob ON blob.blob_id = asset.source_blob_id
     WHERE ref.owner_id = p_owner AND ref.deck_id = p_deck AND ref.exercise_id = p_exercise
       AND ref.exercise_revision_id = p_revision;
$$;

-- A transcript is an explicit accessibility accommodation; it is never part of an ordinary
-- presentation response. This append-only row is serialized with attempt submission.
CREATE TABLE app_learning.study_audio_accommodation (
    account_id UUID NOT NULL,
    session_id UUID NOT NULL,
    presentation_id UUID NOT NULL,
    revealed_at TIMESTAMPTZ NOT NULL CHECK (isfinite(revealed_at)),
    PRIMARY KEY (account_id, session_id, presentation_id),
    FOREIGN KEY (account_id, session_id, presentation_id)
        REFERENCES app_learning.study_presentation(account_id, session_id, presentation_id)
);
CREATE TRIGGER study_audio_accommodation_immutable BEFORE UPDATE ON app_learning.study_audio_accommodation
    FOR EACH ROW EXECUTE FUNCTION app_learning.study_snapshot_immutable_guard();
