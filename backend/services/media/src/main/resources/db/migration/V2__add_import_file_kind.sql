DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'media_kind') THEN
            RAISE EXCEPTION 'media_kind enum missing';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM pg_enum
            JOIN pg_type ON pg_enum.enumtypid = pg_type.oid
            WHERE pg_type.typname = 'media_kind' AND enumlabel = 'import_file'
        ) THEN
            ALTER TYPE media_kind ADD VALUE 'import_file';
        END IF;
    END
$$ LANGUAGE plpgsql;
