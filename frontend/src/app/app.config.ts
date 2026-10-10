export interface AppConfig {
    authServerUrl: string;
    identityRedirectUri: string;
    learningApiBaseUrl: string;
    clientId: string;
    buildId: string;
    /** Public BotFather username, without @. Optional in runtime overrides and fixtures. */
    supportTelegramUsername?: string;
    features: AppFeatures;
}

export interface AppFeatures {
    showEmailVerificationWarning: boolean;
}

type AppConfigOverride = Partial<Omit<AppConfig, 'features'>> & {
    features?: Partial<AppFeatures>;
};

declare global {
    interface Window {
        MNEMA_APP_CONFIG?: AppConfigOverride;
    }
}

/** The production console host and its development twin (`admin.localhost` resolves to loopback). */
export const isAdminHostname = (host: string): boolean => host === 'admin.mnema.app' || host === 'admin.localhost';
export const isAdminHost = isAdminHostname(window.location.hostname);

/** Where the learner product lives for a console host: `https://mnema.app` or the local origin without `admin.`. */
export function learnerOrigin(host: string, origin: string): string {
    return host === 'admin.mnema.app' ? 'https://mnema.app' : host === 'admin.localhost' ? origin.replace('//admin.', '//') : origin;
}

/** Exact deployment hosts share one bundle; each public client retains its own callback. */
export function resolveAppConfig(host: string, origin: string, override: AppConfigOverride = {}): AppConfig {
    const admin = isAdminHostname(host);
    const production = host === 'mnema.app' || host === 'admin.mnema.app';
    const local = host === 'localhost' || host === '127.0.0.1' || host === 'admin.localhost';
    const defaults: AppConfig = {
        authServerUrl: production ? 'https://auth.mnema.app' : (local ? 'https://localhost:18081' : origin),
        identityRedirectUri: `${origin}/auth/callback`,
        learningApiBaseUrl: '/api',
        clientId: admin ? 'mnema-admin-web' : 'mnema-web',
        buildId: 'dev',
        supportTelegramUsername: 'Mnema_Support_Bot',
        features: { showEmailVerificationWarning: !local }
    };
    return {
        ...defaults,
        ...override,
        // The image's shared runtime file pins main-host settings; an exact admin host uses its registered client.
        ...(admin ? { identityRedirectUri: `${origin}/auth/callback`, clientId: 'mnema-admin-web' } : {}),
        features: { ...defaults.features, ...override.features }
    };
}

export const appConfig: AppConfig = resolveAppConfig(window.location.hostname, window.location.origin, window.MNEMA_APP_CONFIG);
