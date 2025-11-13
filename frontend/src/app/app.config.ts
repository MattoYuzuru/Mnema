// src/app/app.config.ts
export interface AppConfig {
    authServerUrl: string;
    apiBaseUrl: string;
    clientId: string;
}

const isProd = window.location.hostname === 'mnema.app';

export const appConfig: AppConfig = {
    authServerUrl: isProd ? 'https://auth.mnema.app' : 'http://localhost:8083',
    apiBaseUrl: isProd ? '/api/user' : 'http://localhost:8084/api/user',
    clientId: 'mnema-web'
};
