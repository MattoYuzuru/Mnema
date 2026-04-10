import { AiPreflightPanelComponent } from './ai-preflight-panel.component';

describe('AiPreflightPanelComponent', () => {
    let component: AiPreflightPanelComponent;

    beforeEach(() => {
        component = new AiPreflightPanelComponent();
    });

    it('formats eta in minutes and hours', () => {
        expect(component.formatEta(45)).toBe('45s');
        expect(component.formatEta(120)).toBe('2 min');
        expect(component.formatEta(3660)).toBe('1 h 1 min');
    });

    it('formats cost and token snapshots', () => {
        expect(component.formatCost(0.123456, 'USD')).toBe('0.1235 USD');
        expect(component.formatTokens(1200, 3400)).toContain('in 1.2K');
        expect(component.formatTokens(1200, 3400)).toContain('out 3.4K');
    });
});
