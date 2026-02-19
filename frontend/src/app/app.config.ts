export interface AppConfig {
    authServerUrl: string;
    apiBaseUrl: string;
    coreApiBaseUrl: string;
    mediaApiBaseUrl: string;
    importApiBaseUrl: string;
    aiApiBaseUrl: string;
    clientId: string;
}

const isProd = window.location.hostname === 'mnema.app';

export const appConfig: AppConfig = {
    authServerUrl: isProd ? 'https://auth.mnema.app' : 'http://localhost:8083',
    apiBaseUrl: isProd ? '/api/user' : 'http://localhost:8084/api/user',
    coreApiBaseUrl: isProd ? '/api/core' : 'http://localhost:8085/api/core',
    mediaApiBaseUrl: isProd ? '/api/media' : 'http://localhost:8086/api/media',
    importApiBaseUrl: isProd ? '/api/import' : 'http://localhost:8087/api/import',
    aiApiBaseUrl: isProd ? 'https://ai.mnema.app/api/ai' : 'http://localhost:8088/api/ai',
    clientId: 'mnema-web'
};
