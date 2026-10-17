import { isCredentialTarget } from '../../auth.interceptor';

interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

/**
 * Every route of the onboarding contract is private: the interceptor must send the bearer to it. A route missing from its
 * allowlist goes out anonymously and answers 401, which unit specs of the pages cannot see (it happened twice: S1 and S2).
 */
describe('onboarding routes get the bearer', () => {
    it('covers every /api route of contracts/onboarding/README.md', async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        const readme = readFileSync(`${root}/../contracts/onboarding/README.md`, 'utf8');
        const routes = [...new Set([...readme.matchAll(/`(?:GET|PUT|POST) (\/api\/[a-z/-]+)/gu)].map(match => match[1]))];
        expect(routes).toEqual(expect.arrayContaining(['/api/onboarding', '/api/onboarding/journey', '/api/onboarding/preferences',
            '/api/onboarding/sample-deck', '/api/admin/onboarding/experiment']));
        for (const route of routes) expect(isCredentialTarget(route, 'https://identity.example.test', '/api', window.location.origin), route).toBe(true);
    });
});
