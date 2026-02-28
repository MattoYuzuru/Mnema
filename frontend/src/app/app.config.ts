export interface AppConfig {
    authServerUrl: string;
    apiBaseUrl: string;
    coreApiBaseUrl: string;
    mediaApiBaseUrl: string;
    importApiBaseUrl: string;
    aiApiBaseUrl: string;
    clientId: string;
    features: AppFeatures;
}

export interface AppFeatures {
    federatedAuthEnabled: boolean;
    showEmailVerificationWarning: boolean;
    aiSystemProviderEnabled: boolean;
    aiSystemProviderName: string;
}

type AppConfigOverride = Partial<Omit<AppConfig, 'features'>> & {
    features?: Partial<AppFeatures>;
};

declare global {
    interface Window {
        MNEMA_APP_CONFIG?: AppConfigOverride;
    }
}

const host = window.location.hostname;
const isMnemaProd = host === 'mnema.app';
const isLocalHost = host === 'localhost' || host === '127.0.0.1';
const isLocalSelfHost = isLocalHost;

const defaultConfig: AppConfig = {
    authServerUrl: isMnemaProd
        ? 'https://auth.mnema.app'
        : (isLocalHost ? 'http://localhost:8083' : window.location.origin),
    apiBaseUrl: isMnemaProd
        ? '/api/user'
        : (isLocalHost ? 'http://localhost:8084/api/user' : '/api/user'),
    coreApiBaseUrl: isMnemaProd
        ? '/api/core'
        : (isLocalHost ? 'http://localhost:8085/api/core' : '/api/core'),
    mediaApiBaseUrl: isMnemaProd
        ? '/api/media'
        : (isLocalHost ? 'http://localhost:8086/api/media' : '/api/media'),
    importApiBaseUrl: isMnemaProd
        ? '/api/import'
        : (isLocalHost ? 'http://localhost:8087/api/import' : '/api/import'),
    aiApiBaseUrl: isMnemaProd
        ? '/api/ai'
        : (isLocalHost ? 'http://localhost:8088/api/ai' : '/api/ai'),
    clientId: 'mnema-web',
    features: {
        federatedAuthEnabled: !isLocalSelfHost,
        showEmailVerificationWarning: !isLocalSelfHost,
        aiSystemProviderEnabled: isLocalSelfHost,
        aiSystemProviderName: 'ollama'
    }
};

const override = window.MNEMA_APP_CONFIG ?? {};
const overrideFeatures = override.features ?? {};

export const appConfig: AppConfig = {
    ...defaultConfig,
    ...override,
    features: {
        ...defaultConfig.features,
        ...overrideFeatures
    }
};
