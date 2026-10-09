import { resolveAppConfig } from './app.config';

describe('exact public host Identity configuration', () => {
    it('serves the admin client despite the shared main-host runtime callback', () => {
        const config = resolveAppConfig('admin.mnema.app', 'https://admin.mnema.app', {
            authServerUrl: 'https://auth.mnema.app', identityRedirectUri: 'https://mnema.app/auth/callback',
            clientId: 'mnema-web', buildId: 'release-fixture'
        });
        expect(config.clientId).toBe('mnema-admin-web');
        expect(config.identityRedirectUri).toBe('https://admin.mnema.app/auth/callback');
        expect(config.authServerUrl).toBe('https://auth.mnema.app');
        expect(config.learningApiBaseUrl).toBe('/api');
        expect(config.buildId).toBe('release-fixture');
    });

    it('does not recognize an arbitrary subdomain as an approved deployment', () => {
        const config = resolveAppConfig('evil.mnema.app', 'https://evil.mnema.app');
        expect(config.clientId).toBe('mnema-web');
        expect(config.authServerUrl).toBe('https://evil.mnema.app');
    });

    it('preserves explicit local Identity configuration and feature defaults', () => {
        const config = resolveAppConfig('localhost', 'https://localhost:3443', {
            authServerUrl: 'https://localhost:18081', features: { showEmailVerificationWarning: true }
        });
        expect(config.identityRedirectUri).toBe('https://localhost:3443/auth/callback');
        expect(config.clientId).toBe('mnema-web');
        expect(config.features.showEmailVerificationWarning).toBe(true);
    });
});
