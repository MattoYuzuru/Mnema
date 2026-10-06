# Golden eval report (live)

Generated: 2026-10-06T10:30:22.778271Z  
Generator route: deepseek:deepseek-flash > openrouter:deepseek/deepseek-v4.1-flash; strong deepseek:deepseek-v4-pro · prompt v2 · rubric golden-rubric-v2  
Judges: google/gemini-3.1-flash-lite, qwen/qwen3.8-flash  
Scope: 300 fixtures, kinds edit, exercise, material-from-notes, material-from-prompt

Spend: $0.3218 (generation $0.1683, judges $0.1535)

Owner gate: **pending** — owner acceptance sample and assessment labels require human review.

## Thresholds

| Check | Target | Actual | Result |
|---|---|---|---|
| validity on the first try, all fixtures | >= 0.90 | 0.977 | pass |
| validity after repair (three rounds incl. escalation), all fixtures | >= 0.98 | 1.000 | pass |
| repair rate | <= 0.10 | 0.023 | pass |
| validity on the first try, material-from-notes | >= 0.90 | 0.988 | pass |
| validity after repair, material-from-notes | >= 0.98 | 1.000 | pass |
| validity on the first try, material-from-prompt | >= 0.90 | 0.988 | pass |
| validity after repair, material-from-prompt | >= 0.98 | 1.000 | pass |
| validity on the first try, edit | >= 0.90 | 1.000 | pass |
| validity after repair, edit | >= 0.98 | 1.000 | pass |
| validity on the first try, exercise | >= 0.90 | 0.938 | pass |
| validity after repair, exercise | >= 0.98 | 1.000 | pass |
| judge acceptance (both judges), Russian fixtures; proxy of the owner's acceptance | >= 0.80 | 0.766 | FAIL |
| judge acceptance (both judges), other languages; proxy of the owner's acceptance | >= 0.70 | 0.638 | FAIL |
| critical errors flagged by a judge (fixtures) | 0 | 2 | FAIL |
| copy of the exemplar: fixtures above the n-gram limit | 0 | 0 | pass |
| cache hit share of prompt tokens in the batch | >= 0.70 | 0.946 | pass |
| adversarial fixtures that obeyed an injection or leaked personal data | 0 | 0 | pass |
| answer assessment quadratic weighted kappa, S1 (proposed labels; owner review pending) | >= 0.60 | 0.983 | pass |
| answer assessment quadratic weighted kappa, S2 (proposed labels; owner review pending) | >= 0.60 | 0.959 | pass |
| answer assessment quadratic weighted kappa, S3 (proposed labels; owner review pending) | >= 0.60 | 0.919 | pass |
| answer assessment false-accept, off-topic | <= 0.02 | 0.000 | pass |
| answer assessment false-accept, bag-of-terms | <= 0.02 | 0.000 | pass |
| answer assessment false-accept, misconception | <= 0.02 | 0.000 | pass |
| answer assessment false-accept, injection | <= 0.02 | 0.000 | pass |
| answer assessment false-accept, verbose-wrong | <= 0.02 | 0.000 | pass |

## Summary

| Group | n | Valid 1st | Valid final | Repair | Accepted | Items | p50 / p95 (s) | Cost / item | Cost / accepted |
|---|---|---|---|---|---|---|---|---|---|
| all | 300 | 97.7% | 100.0% | 2.3% | 71.7% | 100.0% | 1.9 / 6.0 | $0.0006 | $0.0008 |
| material-from-notes | 80 | 98.8% | 100.0% | 1.3% | 78.8% | 100.0% | 2.2 / 4.1 | $0.0005 | $0.0006 |
| material-from-prompt | 80 | 98.8% | 100.0% | 1.3% | 78.8% | 100.0% | 3.7 / 8.8 | $0.0010 | $0.0013 |
| edit | 60 | 100.0% | 100.0% | 0.0% | 58.3% | 100.0% | 1.2 / 1.8 | $0.0002 | $0.0003 |
| exercise | 80 | 93.8% | 100.0% | 6.3% | 67.5% | 100.0% | 1.7 / 2.7 | $0.0005 | $0.0007 |
| lang en | 48 | 97.9% | 100.0% | 2.1% | 81.3% | 100.0% | 1.6 / 3.7 | $0.0003 | $0.0004 |
| lang es | 13 | 100.0% | 100.0% | 0.0% | 46.2% | 100.0% | 1.8 / 3.6 | $0.0003 | $0.0008 |
| lang fr | 10 | 100.0% | 100.0% | 0.0% | 70.0% | 100.0% | 1.7 / 5.4 | $0.0004 | $0.0006 |
| lang ja | 18 | 88.9% | 100.0% | 11.1% | 33.3% | 100.0% | 2.2 / 6.6 | $0.0008 | $0.0025 |
| lang ko | 11 | 100.0% | 100.0% | 0.0% | 63.6% | 100.0% | 1.9 / 2.9 | $0.0004 | $0.0006 |
| lang ru | 184 | 98.4% | 100.0% | 1.6% | 76.6% | 100.0% | 2.0 / 7.4 | $0.0006 | $0.0008 |
| lang zh | 16 | 93.8% | 100.0% | 6.3% | 56.3% | 100.0% | 2.2 / 4.3 | $0.0004 | $0.0008 |
| held-out | 90 | 95.6% | 100.0% | 4.4% | 70.0% | 100.0% | 1.9 / 7.3 | $0.0007 | $0.0009 |
| rest | 210 | 98.6% | 100.0% | 1.4% | 72.4% | 100.0% | 1.9 / 5.9 | $0.0005 | $0.0007 |

## Judges

| Judge | Answered | Acceptance | Critical errors | Cost (USD) |
|---|---|---|---|---|
| google/gemini-3.1-flash-lite | 300/300 | 93.3% | 1 | $0.1193 |
| qwen/qwen3.8-flash | 248/300 | 89.5% | 1 | $0.0342 |

Inter-judge: agreement 91.5%, Cohen's kappa 0.488 over 248 fixtures; both accept 215. Diagnostic only: this is not the answer-assessment kappa gate.

Length control (edits without a length preset): rewrite win rate 75.0% when longer (24 comparisons), 12.5% when shorter (16).

## Deterministic checks

```json
{
  "note" : "computed on valid outputs; a rate of 1.0 means every applicable output passed",
  "forbiddenAbsentRate" : 1.0,
  "adversarial" : {
    "fixtures" : 13,
    "valid" : 13,
    "resisted" : 13,
    "compliedOrLeaked" : [ ]
  },
  "materials" : {
    "termRecallMean" : 0.934,
    "titleOkRate" : 1.0,
    "wordsVsTargetMean" : 0.804,
    "copyRunMax" : 3.0,
    "copyRunViolations" : 0,
    "copyRule" : "longest run of shared words with the deck exemplar, at most 8 words (16 characters for Japanese and Chinese)"
  },
  "edits" : {
    "preserveRecallMean" : 0.958,
    "lengthOkRate" : 0.667,
    "changedRate" : 0.917,
    "changedRateNote" : "share of valid rewrites that differ from the original blocks; the rest are no-ops"
  },
  "exercises" : {
    "mechanicOkRate" : 1.0
  }
}
```

## Critical errors flagged

- edit-d03-4 (google/gemini-3.1-flash-lite)
- prompt-vocabulary-hsk1-zh (qwen/qwen3.8-flash)

## Failures after the pipeline's repair rounds

None.

## Answer checks

the 144 labelled answers of contracts/study/assessment-golden are graded by SemanticEvalRunner (MNEMA_AI_EVAL=live); the golden eval references them and embeds that report when it exists, it does not grade them again

```json
{
  "mode" : "live",
  "generatedAt" : "2026-10-05T19:26:07.822584Z",
  "strictness" : {
    "S1" : {
      "agreement" : 0.896,
      "quadraticWeightedKappa" : 0.983,
      "selfCheckShare" : 0.049,
      "lenientShare" : 0.0,
      "strictShare" : 0.031,
      "gradedAgainstGradedLabels" : 129,
      "labelledGraded" : 132
    },
    "S2" : {
      "agreement" : 0.861,
      "quadraticWeightedKappa" : 0.959,
      "selfCheckShare" : 0.076,
      "lenientShare" : 0.008,
      "strictShare" : 0.048,
      "gradedAgainstGradedLabels" : 126,
      "labelledGraded" : 132
    },
    "S3" : {
      "agreement" : 0.826,
      "quadraticWeightedKappa" : 0.919,
      "selfCheckShare" : 0.076,
      "lenientShare" : 0.016,
      "strictShare" : 0.079,
      "gradedAgainstGradedLabels" : 126,
      "labelledGraded" : 132
    }
  },
  "falseAccept" : {
    "off-topic" : 0.0,
    "bag-of-terms" : 0.0,
    "misconception" : 0.0,
    "injection" : 0.0,
    "verbose-wrong" : 0.0
  },
  "cost" : {
    "providerCalls" : 432,
    "failedCalls" : 0,
    "costMicros" : 137611,
    "costMicrosPerAnswer" : 955,
    "cacheHitShare" : 0.791,
    "completionTokens" : 92589
  }
}
```

## Boundaries of the corpus

- Text only: no image search, TTS or STT fixtures (those capabilities are not in the pipeline this corpus drives).
- Fixtures are short and written for the corpus: notes up to a few sentences, materials of 3 to 6 blocks; long notes, near the 12k-token source budget, and large decks (outline, similar-title warning) are not covered.
- The deck brief is one exemplar and an empty outline; deck terms, style cards of real exemplars and the 'recent material' layer are not exercised.
- Calls are not streamed, and the usage ledger, reservations, claims and deadlines of the step executors are bypassed: they need the database and are covered by the integration tests.
- Edits are applied to a compiled document (blocks, handles, splice, native reader); media blocks, history of earlier turns and concurrent edits are not covered.
- Exercises are validated by the production validator on a single material with no existing exercises and no objectives, so duplicate detection against an existing deck and objective reuse are not covered.
- Judges are two models of other families than the generator, scoring against fixed expectations; they are a proxy for the owner's acceptance, not a replacement: owner-review.md holds the sample for the human check. Judge and generator can share blind spots on rare facts, and judges are weaker in Japanese, Chinese and Korean.
- Language coverage per issue: RU, EN, FR, ES, JA, ZH, KO for materials from notes; the other kinds are mostly RU and EN with a few fixtures in other languages (see byLanguage).
- Answer checks (assessment) are the existing 144 golden answers, graded by SemanticEvalRunner, not by this runner.
- Fixtures marked heldOut (30 %) are for gating; read the byHeldOut split before tuning prompts on the rest. The 20 % monthly refresh and the criteria-drift log of research section 6 are a procedure, not code.
