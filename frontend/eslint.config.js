// @ts-check
const eslint = require("@eslint/js");
const tseslint = require("typescript-eslint");
const angular = require("angular-eslint");

module.exports = tseslint.config(
  {
    files: ["src/**/*.ts"],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      ...angular.configs.tsRecommended
    ],
    processor: angular.processInlineTemplates,
    rules: {
      "@typescript-eslint/no-explicit-any": "off",
      "@typescript-eslint/no-empty-function": "off",
      "@typescript-eslint/no-inferrable-types": "off",
      "@typescript-eslint/no-unused-vars": "off",
      "@typescript-eslint/array-type": "off",
      "@typescript-eslint/consistent-generic-constructors": "off",
      "@typescript-eslint/consistent-indexed-object-style": "off",
      "@typescript-eslint/consistent-type-definitions": "off",
      "@typescript-eslint/prefer-for-of": "off",
      "no-empty": "off",
      "no-extra-boolean-cast": "off",
      "no-useless-escape": "off",
      "@angular-eslint/directive-selector": [
        "error",
        {
          type: "attribute",
          prefix: "app",
          style: "camelCase"
        }
      ],
      "@angular-eslint/component-selector": [
        "error",
        {
          type: "element",
          prefix: "app",
          style: "kebab-case"
        }
      ]
    }
  },
  {
    files: ["src/**/*.html"],
    extends: [...angular.configs.templateRecommended]
  },
  {
    files: [
      "src/app/app.component.ts",
      "src/app/core/layout/app-shell.component.ts",
      "src/app/features/admin/admin-panel.component.ts",
      "src/app/features/decks/add-cards-modal.component.ts",
      "src/app/features/decks/ai-add-cards-modal.component.ts",
      "src/app/features/decks/ai-enhance-card-modal.component.ts",
      "src/app/features/decks/ai-enhance-deck-modal.component.ts",
      "src/app/features/decks/ai-import-modal.component.ts",
      "src/app/features/decks/card-browser.component.ts",
      "src/app/features/decks/deck-profile.component.ts",
      "src/app/features/decks/decks-list.component.ts",
      "src/app/features/decks/duplicate-review-page.component.ts",
      "src/app/features/decks/review-session.component.ts",
      "src/app/features/import/import-deck-modal.component.ts",
      "src/app/features/my-study/my-study.component.ts",
      "src/app/features/public-decks/public-card-browser.component.ts",
      "src/app/features/public-decks/public-decks-catalog.component.ts",
      "src/app/features/settings/settings.component.ts",
      "src/app/features/templates/public-templates.component.ts",
      "src/app/features/templates/template-profile.component.ts",
      "src/app/features/templates/templates-list.component.ts",
      "src/app/features/wizard/deck-wizard.component.ts",
      "src/app/features/wizard/steps/deck-metadata-step.component.ts",
      "src/app/features/wizard/steps/initial-content-step.component.ts",
      "src/app/features/wizard/steps/review-step.component.ts",
      "src/app/features/wizard/steps/template-selection-step.component.ts",
      "src/app/features/wizard/template-creator-modal.component.ts",
      "src/app/features/wizard/visual-template-builder.component.ts",
      "src/app/home-page.component.ts",
      "src/app/login-page.component.ts",
      "src/app/privacy-page.component.ts",
      "src/app/profile-page.component.ts",
      "src/app/shared/components/ai-preflight-panel.component.ts",
      "src/app/shared/components/button.component.ts",
      "src/app/shared/components/confirmation-dialog.component.ts",
      "src/app/shared/components/deck-card.component.ts",
      "src/app/shared/components/empty-state.component.ts",
      "src/app/shared/components/flashcard-view.component.ts",
      "src/app/shared/components/input.component.ts",
      "src/app/shared/components/media-upload.component.ts",
      "src/app/shared/components/memory-tip-loader.component.ts",
      "src/app/shared/components/report-content-modal.component.ts",
      "src/app/shared/components/review-stats-panel.component.ts",
      "src/app/shared/components/tag-chip.component.ts",
      "src/app/shared/components/template-card.component.ts",
      "src/app/shared/components/textarea.component.ts",
      "src/app/shared/components/toast-stack.component.ts",
      "src/app/shared/components/wizard-stepper.component.ts",
      "src/app/terms-page.component.ts"
    ],
    rules: {
      // TODO(#146): Remove this compatibility exception with the remaining legacy
      // components when their runtime/build wiring is deleted; replacement components
      // already use Signals/OnPush.
      "@angular-eslint/prefer-on-push-component-change-detection": "off"
    }
  }
);
