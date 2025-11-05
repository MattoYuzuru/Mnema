DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM auth.oauth2_registered_client WHERE client_id = 'mnema-web') THEN
            INSERT INTO auth.oauth2_registered_client (
                id, client_id, client_id_issued_at, client_secret, client_name,
                client_authentication_methods, authorization_grant_types, redirect_uris,
                scopes, client_settings, token_settings
            )
            VALUES (
                       gen_random_uuid()::text,
                       'mnema-web',
                       now(),
                       NULL, -- public client
                       'mnema-web',
                       'none',
                       'authorization_code,refresh_token',
                       'http://localhost:3005/,https://mnema.app/',
                       'openid,profile,email,user.read,user.write',
                       '{"requireProofKey":true,"requireAuthorizationConsent":false}'::jsonb::text,
                       '{"accessTokenTimeToLive":"PT1H","refreshTokenTimeToLive":"P30D","reuseRefreshTokens":false}'::jsonb::text
                   );
        END IF;
    END $$;
