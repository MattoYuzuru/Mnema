UPDATE auth.oauth2_registered_client
SET redirect_uris = (
    SELECT string_agg(uri, ',')
    FROM (
             SELECT unnest(NULLIF(string_to_array(COALESCE(redirect_uris, ''), ','), '{}')) AS uri
             UNION
             SELECT 'http://localhost:8084/api/user/swagger-ui/oauth2-redirect.html'
             UNION
             SELECT 'https://mnema.app/api/user/swagger-ui/oauth2-redirect.html'
         ) u
)
WHERE client_id = 'swagger-ui';
