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
    federatedAuthEnabled: boolean;
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

// Build-time public prerender has no browser or runtime secrets/configuration.
const browserWindow = typeof window === 'undefined' ? undefined : window;
const host = browserWindow?.location.hostname ?? 'mnema.app';
const isMnemaProd = host === 'mnema.app';
const isLocalHost = host === 'localhost' || host === '127.0.0.1';
const isLocalSelfHost = isLocalHost;

const defaultConfig: AppConfig = {
    authServerUrl: isMnemaProd
        ? 'https://auth.mnema.app'
        : (isLocalHost ? 'https://localhost:18081' : browserWindow!.location.origin),
    identityRedirectUri: `${browserWindow?.location.origin ?? 'https://mnema.app'}/auth/callback`,
    learningApiBaseUrl: '/api',
    clientId: 'mnema-web',
    buildId: 'dev',
    supportTelegramUsername: 'Mnema_Support_Bot',
    features: {
        federatedAuthEnabled: true,
        showEmailVerificationWarning: !isLocalSelfHost
    }
};

const override = browserWindow?.MNEMA_APP_CONFIG ?? {};
const overrideFeatures = override.features ?? {};

export const appConfig: AppConfig = {
    ...defaultConfig,
    ...override,
    features: {
        ...defaultConfig.features,
        ...overrideFeatures
    }
};
