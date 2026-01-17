DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'import_source_type') THEN
            CREATE TYPE import_source_type AS ENUM (
                'apkg',
                'mnema',
                'csv',
                'tsv',
                'txt'
            );
        ELSE
            IF NOT EXISTS (
                SELECT 1 FROM pg_enum
                JOIN pg_type ON pg_enum.enumtypid = pg_type.oid
                WHERE pg_type.typname = 'import_source_type' AND enumlabel = 'mnema'
            ) THEN
                ALTER TYPE import_source_type ADD VALUE 'mnema';
            END IF;
        END IF;
    END
$$ LANGUAGE plpgsql;
