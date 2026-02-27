DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'language_tag') THEN
            CREATE TYPE language_tag AS ENUM (
                'ru',
                'en',
                'jp',
                'sp',
                'zh',
                'hi',
                'ar',
                'fr',
                'bn',
                'pt',
                'id'
            );
        ELSE
            IF NOT EXISTS (
                SELECT 1
                FROM pg_enum
                JOIN pg_type ON pg_enum.enumtypid = pg_type.oid
                WHERE pg_type.typname = 'language_tag'
                  AND enumlabel = 'zh'
            ) THEN
                ALTER TYPE language_tag ADD VALUE 'zh';
            END IF;

            IF NOT EXISTS (
                SELECT 1
                FROM pg_enum
                JOIN pg_type ON pg_enum.enumtypid = pg_type.oid
                WHERE pg_type.typname = 'language_tag'
                  AND enumlabel = 'hi'
            ) THEN
                ALTER TYPE language_tag ADD VALUE 'hi';
            END IF;

            IF NOT EXISTS (
                SELECT 1
                FROM pg_enum
                JOIN pg_type ON pg_enum.enumtypid = pg_type.oid
                WHERE pg_type.typname = 'language_tag'
                  AND enumlabel = 'ar'
            ) THEN
                ALTER TYPE language_tag ADD VALUE 'ar';
            END IF;

            IF NOT EXISTS (
                SELECT 1
                FROM pg_enum
                JOIN pg_type ON pg_enum.enumtypid = pg_type.oid
                WHERE pg_type.typname = 'language_tag'
                  AND enumlabel = 'fr'
            ) THEN
                ALTER TYPE language_tag ADD VALUE 'fr';
            END IF;

            IF NOT EXISTS (
                SELECT 1
                FROM pg_enum
                JOIN pg_type ON pg_enum.enumtypid = pg_type.oid
                WHERE pg_type.typname = 'language_tag'
                  AND enumlabel = 'bn'
            ) THEN
                ALTER TYPE language_tag ADD VALUE 'bn';
            END IF;

            IF NOT EXISTS (
                SELECT 1
                FROM pg_enum
                JOIN pg_type ON pg_enum.enumtypid = pg_type.oid
                WHERE pg_type.typname = 'language_tag'
                  AND enumlabel = 'pt'
            ) THEN
                ALTER TYPE language_tag ADD VALUE 'pt';
            END IF;

            IF NOT EXISTS (
                SELECT 1
                FROM pg_enum
                JOIN pg_type ON pg_enum.enumtypid = pg_type.oid
                WHERE pg_type.typname = 'language_tag'
                  AND enumlabel = 'id'
            ) THEN
                ALTER TYPE language_tag ADD VALUE 'id';
            END IF;
        END IF;
    END
$$ LANGUAGE plpgsql;
