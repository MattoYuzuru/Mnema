/**
 * The single source of the operator's public requisites. The privacy policy, the terms and the footer render from it.
 * The policy and the terms must show the contact e-mail as text (the law requires a visible contact); the footer shows
 * only an action and assembles the `mailto:` address from these parts when the visitor activates it.
 * `legal-operator.spec.ts` fails if a `__OPERATOR_` placeholder is ever reintroduced.
 */
export interface LegalOperator {
    /** Full name as it appears in the register of individual entrepreneurs. */
    readonly name: string;
    /** Short form for running text. */
    readonly shortName: string;
    readonly inn: string;
    readonly ogrnip: string;
    /** Address that receives requests about personal data and the agreement. */
    readonly email: string;
}

const EMAIL_PARTS = ['matvei.riabushkin', 'yandex.ru'] as const;

export const LEGAL_OPERATOR: LegalOperator = {
    name: 'Индивидуальный предприниматель Рябушкин Матвей Игоревич',
    shortName: 'ИП Рябушкин Матвей Игоревич',
    inn: '771573834080',
    ogrnip: '326774600705952',
    email: EMAIL_PARTS.join('@')
};

/** Effective date of the current legal documents; both pages show it and the version below. */
export const LEGAL_EFFECTIVE_DATE = '10 октября 2026';
export const LEGAL_DOCUMENT_VERSION = '1.2';

export const LEGAL_SITE = 'https://mnema.app';

/** The `mailto:` address, assembled only when a visitor activates the footer action so it is not in any rendered attribute or text. */
export function operatorMailto(): string {
    return `mailto:${EMAIL_PARTS.join('@')}`;
}
