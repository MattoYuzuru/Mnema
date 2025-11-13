DO $$
    BEGIN
        UPDATE auth.oauth2_registered_client
        SET redirect_uris = 'http://127.0.0.1:3005/,https://mnema.app/'
        WHERE client_id = 'mnema-web';
    END $$;
