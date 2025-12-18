DO
$$
    BEGIN
        ALTER TYPE card_field_type ADD VALUE 'markdown';
    EXCEPTION
        WHEN duplicate_object THEN
            NULL;
    END
$$;
