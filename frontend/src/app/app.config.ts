export interface AppConfig {
    authServerUrl: string;
    apiBaseUrl: string;
    coreApiBaseUrl: string;
    clientId: string;
}

const isProd = window.location.hostname === 'mnema.app';

export const appConfig: AppConfig = {
    authServerUrl: isProd ? 'https://auth.mnema.app' : 'http://localhost:8083',
    apiBaseUrl: isProd ? '/api/user' : 'http://localhost:8084/api/user',
    coreApiBaseUrl: isProd ? '/api/core' : 'http://localhost:8085/api/core',
    clientId: 'mnema-web'
};
