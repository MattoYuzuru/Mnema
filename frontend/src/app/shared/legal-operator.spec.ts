import { LEGAL_OPERATOR } from './legal-operator';

describe('LEGAL_OPERATOR', () => {
    it('names the individual entrepreneur and carries valid register numbers', () => {
        expect(LEGAL_OPERATOR.name).toBe('Индивидуальный предприниматель Рябушкин Матвей Игоревич');
        expect(LEGAL_OPERATOR.inn).toMatch(/^\d{12}$/);
        expect(LEGAL_OPERATOR.ogrnip).toMatch(/^\d{15}$/);
    });

    it('has no __OPERATOR_ placeholder left in any requisite', () => {
        for (const [field, value] of Object.entries(LEGAL_OPERATOR)) {
            expect(value, field).not.toContain('__OPERATOR_');
        }
    });

    it('uses the owner-approved contact e-mail', () => {
        expect(LEGAL_OPERATOR.email).toBe('matvei.riabushkin@yandex.ru');
        expect(LEGAL_OPERATOR.email).toMatch(/^[^@\s]+@[^@\s]+\.[^@\s]+$/);
    });
});
