-- Share/8 (#430): explicit publication, publication metadata, the topic directory and the outbox of publication events
-- (community decks architecture sections 3, 6, 8 and 12).
--
-- deck_publication gains the metadata of the publication (still one O(1) row per deck; no row means private):
--   topic_id          a leaf of the two-level directory (the application enforces "leaf"; the FK enforces "exists")
--   content_language  language of the deck's text, target_language the language being learned: the BCP 47 primary subtag, lowercase
--   level             NULL or one closed list: CEFR A1..C2 for languages and exams, BEGINNER|INTERMEDIATE|ADVANCED for the rest. One list because
--                     the catalog filters by a single optional level facet; the application does not tie a level to a topic.
--   tags              at most 5, each 1..32 characters, trimmed, unique (the application also lowercases and NFKC-normalizes them)
--   release_note      the "what is new" of the last publication, 1..500 characters
--   requests_enabled  the author's switch for access requests (Share/13), on by default
-- Publishing writes this row and never a deck revision, so deck.row_version (and an open editor) is untouched.
SET LOCAL lock_timeout = '5s';

-- topic / topic_alias: the directory with synonyms. Plain data seeded below (names in Russian and English, synonyms in both and in the
-- languages' own names); the owner may edit it later without a deploy. alias_norm is what the Java alias normalizer produces (lowercase, NFKC,
-- e-diaeresis to e, marks stripped from Latin, Greek and Arabic letters, spaces collapsed), so the suggestion matches normalized text with an equality
-- lookup, no unaccent extension involved.
CREATE TABLE app_learning.topic (
    topic_id TEXT PRIMARY KEY CHECK (topic_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND char_length(topic_id) <= 40),
    parent_id TEXT REFERENCES app_learning.topic(topic_id),
    name_ru TEXT NOT NULL CHECK (char_length(name_ru) BETWEEN 1 AND 100),
    name_en TEXT NOT NULL CHECK (char_length(name_en) BETWEEN 1 AND 100),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    CHECK (parent_id IS NULL OR parent_id <> topic_id),
    CONSTRAINT topic_sibling_ordinal UNIQUE NULLS NOT DISTINCT (parent_id, ordinal)
);

-- Two levels only: the parent of a topic is a top-level topic.
CREATE FUNCTION app_learning.topic_two_levels_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL AND EXISTS (SELECT 1 FROM app_learning.topic p WHERE p.topic_id = NEW.parent_id AND p.parent_id IS NOT NULL) THEN
        RAISE EXCEPTION 'The topic directory has two levels' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.parent_id IS NOT NULL AND EXISTS (SELECT 1 FROM app_learning.topic c WHERE c.parent_id = NEW.topic_id) THEN
        RAISE EXCEPTION 'The topic directory has two levels' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER topic_two_levels_guard BEFORE INSERT OR UPDATE ON app_learning.topic
    FOR EACH ROW EXECUTE FUNCTION app_learning.topic_two_levels_guard();

CREATE TABLE app_learning.topic_alias (
    alias_norm TEXT NOT NULL CHECK (char_length(alias_norm) BETWEEN 1 AND 64 AND alias_norm = btrim(alias_norm)),
    topic_id TEXT NOT NULL REFERENCES app_learning.topic(topic_id),
    PRIMARY KEY (alias_norm, topic_id)
);
CREATE INDEX topic_alias_topic ON app_learning.topic_alias(topic_id);

INSERT INTO app_learning.topic(topic_id, parent_id, name_ru, name_en, ordinal) VALUES
    ('languages', NULL, 'Языки', 'Languages', 10),
    ('exams', NULL, 'Экзамены', 'Exams', 20),
    ('school', NULL, 'Школа', 'School subjects', 30),
    ('history-culture', NULL, 'История и культура', 'History and culture', 40),
    ('science', NULL, 'Наука', 'Science', 50),
    ('medicine', NULL, 'Медицина', 'Medicine', 60),
    ('it', NULL, 'IT и программирование', 'IT and programming', 70),
    ('professional', NULL, 'Профессии', 'Professional', 80),
    ('everyday', NULL, 'Жизнь', 'Everyday', 90),
    ('other', NULL, 'Другое', 'Other', 100),
    ('english', 'languages', 'Английский', 'English', 10),
    ('spanish', 'languages', 'Испанский', 'Spanish', 20),
    ('german', 'languages', 'Немецкий', 'German', 30),
    ('french', 'languages', 'Французский', 'French', 40),
    ('italian', 'languages', 'Итальянский', 'Italian', 50),
    ('portuguese', 'languages', 'Португальский', 'Portuguese', 60),
    ('chinese', 'languages', 'Китайский', 'Chinese', 70),
    ('japanese', 'languages', 'Японский', 'Japanese', 80),
    ('korean', 'languages', 'Корейский', 'Korean', 90),
    ('turkish', 'languages', 'Турецкий', 'Turkish', 100),
    ('arabic', 'languages', 'Арабский', 'Arabic', 110),
    ('russian', 'languages', 'Русский', 'Russian', 120),
    ('latin', 'languages', 'Латынь', 'Latin', 130),
    ('other-languages', 'languages', 'Другие языки', 'Other languages', 140),
    ('oge', 'exams', 'ОГЭ', 'OGE', 10),
    ('ege', 'exams', 'ЕГЭ', 'EGE (Unified State Exam)', 20),
    ('ielts', 'exams', 'IELTS', 'IELTS', 30),
    ('toefl', 'exams', 'TOEFL', 'TOEFL', 40),
    ('other-exams', 'exams', 'Другие экзамены', 'Other exams', 50),
    ('math', 'school', 'Математика', 'Mathematics', 10),
    ('physics', 'school', 'Физика', 'Physics', 20),
    ('chemistry', 'school', 'Химия', 'Chemistry', 30),
    ('biology', 'school', 'Биология', 'Biology', 40),
    ('geography', 'school', 'География', 'Geography', 50),
    ('literature', 'school', 'Литература', 'Literature', 60),
    ('russian-language-school', 'school', 'Русский язык (школа)', 'Russian language (school)', 70),
    ('social-studies', 'school', 'Обществознание', 'Social studies', 80),
    ('history', 'history-culture', 'История', 'History', 10),
    ('art', 'history-culture', 'Искусство', 'Art', 20),
    ('music', 'history-culture', 'Музыка', 'Music', 30),
    ('philosophy', 'history-culture', 'Философия', 'Philosophy', 40),
    ('religion', 'history-culture', 'Религии', 'Religions', 50),
    ('astronomy', 'science', 'Астрономия', 'Astronomy', 10),
    ('psychology', 'science', 'Психология', 'Psychology', 20),
    ('economics', 'science', 'Экономика', 'Economics', 30),
    ('law', 'science', 'Право', 'Law', 40),
    ('anatomy', 'medicine', 'Анатомия', 'Anatomy', 10),
    ('pharmacology', 'medicine', 'Фармакология', 'Pharmacology', 20),
    ('medicine-general', 'medicine', 'Медицина', 'Medicine', 30),
    ('programming', 'it', 'Программирование', 'Programming', 10),
    ('python', 'it', 'Python', 'Python', 20),
    ('javascript', 'it', 'JavaScript', 'JavaScript', 30),
    ('sql-data', 'it', 'SQL и данные', 'SQL and data', 40),
    ('algorithms', 'it', 'Алгоритмы', 'Algorithms', 50),
    ('devops', 'it', 'DevOps', 'DevOps', 60),
    ('business', 'professional', 'Бизнес', 'Business', 10),
    ('finance', 'professional', 'Финансы', 'Finance', 20),
    ('marketing', 'professional', 'Маркетинг', 'Marketing', 30),
    ('design', 'professional', 'Дизайн', 'Design', 40),
    ('geography-trivia', 'everyday', 'Страны и столицы', 'Countries and capitals', 10),
    ('cooking', 'everyday', 'Кулинария', 'Cooking', 20),
    ('driving', 'everyday', 'ПДД', 'Driving rules', 30),
    ('general-knowledge', 'everyday', 'Эрудиция', 'General knowledge', 40);

-- alias_norm = the alias normalizer (lowercase, NFKC, ё to е, marks stripped from Latin/Greek/Arabic letters, spaces collapsed) applied to each name and synonym
INSERT INTO app_learning.topic_alias(alias_norm, topic_id) VALUES
    ('языки', 'languages'),
    ('languages', 'languages'),
    ('английский', 'english'),
    ('english', 'english'),
    ('англ', 'english'),
    ('английский язык', 'english'),
    ('english language', 'english'),
    ('eng', 'english'),
    ('испанский', 'spanish'),
    ('spanish', 'spanish'),
    ('испанский язык', 'spanish'),
    ('espanol', 'spanish'),
    ('немецкий', 'german'),
    ('german', 'german'),
    ('немецкий язык', 'german'),
    ('deutsch', 'german'),
    ('французский', 'french'),
    ('french', 'french'),
    ('французский язык', 'french'),
    ('francais', 'french'),
    ('итальянский', 'italian'),
    ('italian', 'italian'),
    ('italiano', 'italian'),
    ('итальянский язык', 'italian'),
    ('португальский', 'portuguese'),
    ('portuguese', 'portuguese'),
    ('portugues', 'portuguese'),
    ('португальский язык', 'portuguese'),
    ('китайский', 'chinese'),
    ('chinese', 'chinese'),
    ('китайский язык', 'chinese'),
    ('中文', 'chinese'),
    ('mandarin', 'chinese'),
    ('путунхуа', 'chinese'),
    ('иероглифы', 'chinese'),
    ('японский', 'japanese'),
    ('japanese', 'japanese'),
    ('японский язык', 'japanese'),
    ('日本語', 'japanese'),
    ('nihongo', 'japanese'),
    ('кандзи', 'japanese'),
    ('кана', 'japanese'),
    ('корейский', 'korean'),
    ('korean', 'korean'),
    ('корейский язык', 'korean'),
    ('한국어', 'korean'),
    ('хангыль', 'korean'),
    ('hangul', 'korean'),
    ('турецкий', 'turkish'),
    ('turkish', 'turkish'),
    ('turkce', 'turkish'),
    ('турецкий язык', 'turkish'),
    ('арабский', 'arabic'),
    ('arabic', 'arabic'),
    ('العربية', 'arabic'),
    ('арабский язык', 'arabic'),
    ('русский', 'russian'),
    ('russian', 'russian'),
    ('русский язык', 'russian'),
    ('rki', 'russian'),
    ('ркн', 'russian'),
    ('рки', 'russian'),
    ('русский как иностранный', 'russian'),
    ('латынь', 'latin'),
    ('latin', 'latin'),
    ('латинский', 'latin'),
    ('lingua latina', 'latin'),
    ('латинский язык', 'latin'),
    ('другие языки', 'other-languages'),
    ('other languages', 'other-languages'),
    ('экзамены', 'exams'),
    ('exams', 'exams'),
    ('огэ', 'oge'),
    ('oge', 'oge'),
    ('егэ', 'ege'),
    ('ege (unified state exam)', 'ege'),
    ('ege', 'ege'),
    ('unified state exam', 'ege'),
    ('ielts', 'ielts'),
    ('айлтс', 'ielts'),
    ('айэлтс', 'ielts'),
    ('toefl', 'toefl'),
    ('тоефл', 'toefl'),
    ('другие экзамены', 'other-exams'),
    ('other exams', 'other-exams'),
    ('школа', 'school'),
    ('school subjects', 'school'),
    ('математика', 'math'),
    ('mathematics', 'math'),
    ('алгебра', 'math'),
    ('геометрия', 'math'),
    ('math', 'math'),
    ('maths', 'math'),
    ('физика', 'physics'),
    ('physics', 'physics'),
    ('химия', 'chemistry'),
    ('chemistry', 'chemistry'),
    ('биология', 'biology'),
    ('biology', 'biology'),
    ('география', 'geography'),
    ('geography', 'geography'),
    ('литература', 'literature'),
    ('literature', 'literature'),
    ('русский язык (школа)', 'russian-language-school'),
    ('russian language (school)', 'russian-language-school'),
    ('обществознание', 'social-studies'),
    ('social studies', 'social-studies'),
    ('история и культура', 'history-culture'),
    ('history and culture', 'history-culture'),
    ('история', 'history'),
    ('history', 'history'),
    ('искусство', 'art'),
    ('art', 'art'),
    ('живопись', 'art'),
    ('painting', 'art'),
    ('музыка', 'music'),
    ('music', 'music'),
    ('ноты', 'music'),
    ('music theory', 'music'),
    ('теория музыки', 'music'),
    ('философия', 'philosophy'),
    ('philosophy', 'philosophy'),
    ('религии', 'religion'),
    ('religions', 'religion'),
    ('религия', 'religion'),
    ('religion', 'religion'),
    ('наука', 'science'),
    ('science', 'science'),
    ('астрономия', 'astronomy'),
    ('astronomy', 'astronomy'),
    ('психология', 'psychology'),
    ('psychology', 'psychology'),
    ('экономика', 'economics'),
    ('economics', 'economics'),
    ('право', 'law'),
    ('law', 'law'),
    ('юриспруденция', 'law'),
    ('медицина', 'medicine'),
    ('medicine', 'medicine'),
    ('анатомия', 'anatomy'),
    ('anatomy', 'anatomy'),
    ('фармакология', 'pharmacology'),
    ('pharmacology', 'pharmacology'),
    ('медицина', 'medicine-general'),
    ('medicine', 'medicine-general'),
    ('мед', 'medicine-general'),
    ('медицинский', 'medicine-general'),
    ('it и программирование', 'it'),
    ('it and programming', 'it'),
    ('программирование', 'programming'),
    ('programming', 'programming'),
    ('coding', 'programming'),
    ('код', 'programming'),
    ('python', 'python'),
    ('питон', 'python'),
    ('javascript', 'javascript'),
    ('js', 'javascript'),
    ('джаваскрипт', 'javascript'),
    ('sql и данные', 'sql-data'),
    ('sql and data', 'sql-data'),
    ('базы данных', 'sql-data'),
    ('data', 'sql-data'),
    ('sql', 'sql-data'),
    ('алгоритмы', 'algorithms'),
    ('algorithms', 'algorithms'),
    ('алгоритмы и структуры данных', 'algorithms'),
    ('devops', 'devops'),
    ('linux', 'devops'),
    ('docker', 'devops'),
    ('профессии', 'professional'),
    ('professional', 'professional'),
    ('бизнес', 'business'),
    ('business', 'business'),
    ('менеджмент', 'business'),
    ('management', 'business'),
    ('финансы', 'finance'),
    ('finance', 'finance'),
    ('бухгалтерия', 'finance'),
    ('accounting', 'finance'),
    ('маркетинг', 'marketing'),
    ('marketing', 'marketing'),
    ('дизайн', 'design'),
    ('design', 'design'),
    ('жизнь', 'everyday'),
    ('everyday', 'everyday'),
    ('страны и столицы', 'geography-trivia'),
    ('countries and capitals', 'geography-trivia'),
    ('флаги', 'geography-trivia'),
    ('столицы', 'geography-trivia'),
    ('кулинария', 'cooking'),
    ('cooking', 'cooking'),
    ('пдд', 'driving'),
    ('driving rules', 'driving'),
    ('правила дорожного движения', 'driving'),
    ('вождение', 'driving'),
    ('эрудиция', 'general-knowledge'),
    ('general knowledge', 'general-knowledge'),
    ('викторина', 'general-knowledge'),
    ('trivia', 'general-knowledge'),
    ('другое', 'other'),
    ('other', 'other');

CREATE FUNCTION app_learning.publication_tags_valid(tags TEXT[]) RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $$
    SELECT cardinality(tags) <= 5 AND (cardinality(tags) = 0 OR array_ndims(tags) = 1)
       AND NOT EXISTS (SELECT 1 FROM unnest(tags) AS tag WHERE tag IS NULL OR char_length(tag) NOT BETWEEN 1 AND 32 OR tag <> btrim(tag))
       AND cardinality(tags) = (SELECT count(DISTINCT tag) FROM unnest(tags) AS tag);
$$;

ALTER TABLE app_learning.deck_publication
    ADD COLUMN topic_id TEXT REFERENCES app_learning.topic(topic_id),
    ADD COLUMN content_language TEXT CONSTRAINT deck_publication_content_language_check CHECK (content_language IS NULL OR content_language ~ '^[a-z]{2,3}$'),
    ADD COLUMN target_language TEXT CONSTRAINT deck_publication_target_language_check CHECK (target_language IS NULL OR target_language ~ '^[a-z]{2,3}$'),
    ADD COLUMN level TEXT CONSTRAINT deck_publication_level_check
        CHECK (level IS NULL OR level IN ('A1', 'A2', 'B1', 'B2', 'C1', 'C2', 'BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}' CONSTRAINT deck_publication_tags_check CHECK (app_learning.publication_tags_valid(tags)),
    ADD COLUMN release_note TEXT CONSTRAINT deck_publication_release_note_check CHECK (release_note IS NULL OR char_length(release_note) BETWEEN 1 AND 500),
    ADD COLUMN requests_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- deck_publication_event: the append-only outbox of the catalog (epic 3 projects public_deck from it; nothing consumes it yet). One row per effective
-- publication or level change, written by the same transaction as the change. event_id is a uuidv7, so the order of ids is the order of events.
CREATE TABLE app_learning.deck_publication_event (
    event_id UUID PRIMARY KEY DEFAULT uuidv7(),
    deck_id UUID NOT NULL REFERENCES app_learning.deck(deck_id),
    published_revision_id UUID NOT NULL,
    visibility TEXT NOT NULL CHECK (visibility IN ('PRIVATE', 'LINK', 'INVITE', 'PUBLIC')),
    occurred_at TIMESTAMPTZ NOT NULL CHECK (isfinite(occurred_at)),
    FOREIGN KEY (deck_id, published_revision_id) REFERENCES app_learning.deck_revision(deck_id, revision_id)
);
CREATE FUNCTION app_learning.deck_publication_event_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Append-only deck publication event' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER deck_publication_event_guard BEFORE UPDATE OR DELETE ON app_learning.deck_publication_event
    FOR EACH ROW EXECUTE FUNCTION app_learning.deck_publication_event_guard();

-- "Changes not published (N)": the journal of a deck after the sequence of its published revision is a range of these indexes.
CREATE INDEX deck_item_change_sequence ON app_learning.deck_item_change(deck_id, deck_sequence);
CREATE INDEX deck_exercise_change_sequence ON app_learning.deck_exercise_change(deck_id, deck_sequence);

-- The non-commercial stock media check (architecture section 8) starts from the owner's provenance rows that name such a license, so a deck
-- without any is one empty index probe. The predicate (case-insensitive) is repeated literally by the query. Provenance is keyed by owner: re-key by asset when copies exist.
CREATE INDEX generation_provenance_nc ON app_learning.generation_provenance(owner_id)
    WHERE jsonb_path_exists(media, '$[*] ? (@.license like_regex "(^|[^A-Za-z])nc([^A-Za-z]|$)|non[- ]?commercial" flag "i")');
