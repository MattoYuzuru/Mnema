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

export const isAdminHost = window.location.hostname === 'admin.mnema.app';

/** Exact deployment hosts share one bundle; each public client retains its own callback. */
export function resolveAppConfig(host: string, origin: string, override: AppConfigOverride = {}): AppConfig {
    const admin = host === 'admin.mnema.app';
    const production = host === 'mnema.app' || admin;
    const local = host === 'localhost' || host === '127.0.0.1';
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
