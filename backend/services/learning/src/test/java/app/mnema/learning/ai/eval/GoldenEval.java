package app.mnema.learning.ai.eval;

import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.ai.eval.GoldenCorpus.Fixture;
import app.mnema.learning.ai.eval.GoldenCorpus.Kind;
import app.mnema.learning.ai.eval.GoldenJudge.Judgement;
import app.mnema.learning.generation.GoldenPipeline;
import app.mnema.learning.generation.GoldenPipeline.Result;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The golden eval: every selected fixture through {@link GoldenPipeline}, then through every judge, then the numbers. It has no test
 * annotations so that {@code GoldenEvalRunner} (opt-in, live or stub) and a small offline smoke test can both use it. Reports carry
 * identifiers and numbers only, never an input or an output; {@code owner-review.md} is the one file that shows generated texts, for the
 * owner's acceptance sample, and stays in {@code build/}.
 */
public final class GoldenEval {
    /** The thresholds of issue #300 (research section 6) that apply to this corpus. */
    static final double VALID_FIRST_TRY = 0.90;
    static final double VALID_AFTER_REPAIR = 0.98;
    static final double REPAIR_RATE = 0.10;
    static final double ACCEPT_RU = 0.80;
    static final double ACCEPT_OTHER = 0.70;
    static final double CACHE_HIT = 0.70;
    static final double ASSESSMENT_KAPPA = 0.60;
    static final double FALSE_ACCEPT = 0.02;
    private static final int REVIEW_SAMPLE = 40;

    public enum Mode { STUB, LIVE }

    /**
     * @param kinds the kinds to run
     * @param ids only these fixture ids (empty = no filter), to look at one result again
     * @param tags only fixtures that carry one of these tags (empty = no filter), for a focused rerun such as the adversarial fixtures
     * @param heldOutOnly only the 30 % of fixtures marked {@code heldOut}
     * @param limitPerKind at most this many fixtures per kind, spread evenly over the file (0 = all)
     * @param parallelism fixtures in flight at once
     * @param budgetMicros stop starting fixtures once generation and judging together cost this much (micro-USD; 0 = no limit); fixtures are
     *                     started interleaved over the kinds, so a stop leaves a balanced sample
     * @param output the report directory
     */
    public record Options(Mode mode, Set<Kind> kinds, Set<String> tags, Set<String> ids, boolean heldOutOnly, int limitPerKind, int parallelism, long budgetMicros, Path output, String promptVersion) {
        public Options(Mode mode, Set<Kind> kinds, Set<String> tags, Set<String> ids, boolean heldOutOnly, int limitPerKind, int parallelism, long budgetMicros, Path output) {
            this(mode, kinds, tags, ids, heldOutOnly, limitPerKind, parallelism, budgetMicros, output, "v1");
        }
    }

    /** One fixture with its pipeline result and the verdicts of the judges, by judge name. */
    public record Row(Fixture fixture, Result result, Map<String, Judgement> judgements, String error) {
        boolean accepted(List<GoldenJudge> judges) {
            if (result == null || !result.valid()) return false;
            return judges.stream().allMatch(judge -> {
                Judgement verdict = judgements.get(judge.name());
                return verdict != null && verdict.answered() && verdict.acceptable();
            });
        }
    }

    public record Outcome(ObjectNode report, List<Row> rows, Path directory) { }

    private GoldenEval() { }

    // ------------------------------------------------------------------------------------------------- selection

    public static List<Fixture> select(Options options) {
        List<Fixture> chosen = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            if (!options.kinds().contains(kind)) continue;
            List<Fixture> ofKind = GoldenCorpus.load().stream().filter(fixture -> fixture.kind() == kind)
                    .filter(fixture -> !options.heldOutOnly() || fixture.heldOut())
                    .filter(fixture -> options.ids().isEmpty() || options.ids().contains(fixture.id()))
                    .filter(fixture -> options.tags().isEmpty() || fixture.tags().stream().anyMatch(options.tags()::contains)).toList();
            if (options.limitPerKind() <= 0 || ofKind.size() <= options.limitPerKind()) {
                chosen.addAll(ofKind);
                continue;
            }
            for (int index = 0; index < options.limitPerKind(); index++) chosen.add(ofKind.get(index * ofKind.size() / options.limitPerKind()));
        }
        return chosen;
    }

    // ------------------------------------------------------------------------------------------------------- run

    public static Outcome run(Options options, TextGeneration generator, Duration deadline, List<GoldenJudge> judges, String generatorRoute) throws IOException {
        List<Fixture> fixtures = interleaved(select(options));
        AtomicLong spent = new AtomicLong();
        AtomicInteger skipped = new AtomicInteger();
        GoldenPipeline pipeline = new GoldenPipeline(generator, deadline, options.promptVersion());
        List<Row> rows = new ArrayList<>();
        Semaphore permits = new Semaphore(Math.max(1, options.parallelism()));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Row>> pending = new ArrayList<>();
            for (Fixture fixture : fixtures) {
                pending.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        if (options.budgetMicros() > 0 && spent.get() >= options.budgetMicros()) {
                            skipped.incrementAndGet();
                            return null;
                        }
                        Row row = evaluate(pipeline, judges, fixture);
                        spent.addAndGet(cost(row));
                        return row;
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (Future<Row> future : pending) {
                try {
                    Row row = future.get();
                    if (row != null) rows.add(row);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("The golden eval was interrupted", interrupted);
                } catch (ExecutionException failure) {
                    throw new IllegalStateException("A fixture task failed outside its guard", failure.getCause());
                }
            }
        }
        ObjectNode report = report(options, rows, judges, generatorRoute);
        long generation = rows.stream().filter(row -> row.result() != null).mapToLong(row -> row.result().costMicros()).sum();
        long judging = rows.stream().flatMap(row -> row.judgements().values().stream()).mapToLong(Judgement::costMicros).sum();
        report.putObject("spend").put("generationMicros", generation).put("judgeMicros", judging).put("totalMicros", generation + judging)
                .put("budgetMicros", options.budgetMicros()).put("skippedForBudget", skipped.get());
        Files.createDirectories(options.output());
        Files.writeString(options.output().resolve("report.json"), report.toPrettyString() + "\n", StandardCharsets.UTF_8);
        Files.writeString(options.output().resolve("report.md"), markdown(report), StandardCharsets.UTF_8);
        Files.writeString(options.output().resolve("owner-review.md"), ownerReview(rows, judges), StandardCharsets.UTF_8);
        return new Outcome(report, rows, options.output());
    }

    private static long cost(Row row) {
        long cost = row.result() == null ? 0 : row.result().costMicros();
        return cost + row.judgements().values().stream().mapToLong(Judgement::costMicros).sum();
    }

    /** Fixtures in rounds over the kinds: the first of each kind, then the second of each, so that stopping early keeps the kinds balanced. */
    private static List<Fixture> interleaved(List<Fixture> fixtures) {
        Map<Kind, List<Fixture>> byKind = new LinkedHashMap<>();
        fixtures.forEach(fixture -> byKind.computeIfAbsent(fixture.kind(), key -> new ArrayList<>()).add(fixture));
        List<Fixture> ordered = new ArrayList<>();
        for (int round = 0; ordered.size() < fixtures.size(); round++) {
            for (List<Fixture> ofKind : byKind.values()) if (round < ofKind.size()) ordered.add(ofKind.get(round));
        }
        return ordered;
    }

    private static Row evaluate(GoldenPipeline pipeline, List<GoldenJudge> judges, Fixture fixture) {
        Result result;
        try {
            result = pipeline.run(fixture);
        } catch (RuntimeException failure) {
            return new Row(fixture, null, Map.of(), failure.getClass().getSimpleName());
        }
        Map<String, Judgement> verdicts = new LinkedHashMap<>();
        if (result.valid()) {
            for (GoldenJudge judge : judges) {
                try {
                    verdicts.put(judge.name(), judge.judge(fixture, result));
                } catch (RuntimeException failure) {
                    verdicts.put(judge.name(), Judgement.unavailable(0, 0, failure.getClass().getSimpleName()));
                }
            }
        }
        return new Row(fixture, result, verdicts, "");
    }

    // ----------------------------------------------------------------------------------------------------- report

    private static ObjectNode report(Options options, List<Row> rows, List<GoldenJudge> judges, String generatorRoute) {
        ObjectNode report = GoldenCorpus.JSON.createObjectNode();
        report.put("generatedAt", Instant.now().toString());
        report.put("mode", options.mode().name().toLowerCase(Locale.ROOT));
        report.put("promptVersion", options.promptVersion());
        report.put("rubric", GoldenJudge.LlmJudge.RUBRIC_VERSION);
        report.put("generatorRoute", generatorRoute);
        if (options.mode() == Mode.STUB) report.put("notice", "Stub run: the Stub answers and heuristic judges score; none of these numbers is evidence for the gate.");
        ArrayNode names = report.putArray("judges");
        judges.forEach(judge -> names.add(judge.name()));
        ObjectNode scope = report.putObject("scope");
        scope.put("parallelism", options.parallelism()).put("fixtures", rows.size()).put("heldOutOnly", options.heldOutOnly()).put("limitPerKind", options.limitPerKind());
        ArrayNode tagFilter = scope.putArray("tags");
        options.tags().stream().sorted().forEach(tagFilter::add);
        ArrayNode kinds = scope.putArray("kinds");
        options.kinds().stream().map(Kind::wire).sorted().forEach(kinds::add);

        report.set("overall", summary(rows, judges));
        ObjectNode byKind = report.putObject("byKind");
        for (Kind kind : Kind.values()) {
            List<Row> ofKind = rows.stream().filter(row -> row.fixture().kind() == kind).toList();
            if (!ofKind.isEmpty()) byKind.set(kind.wire(), summary(ofKind, judges));
        }
        ObjectNode byLanguage = report.putObject("byLanguage");
        for (String language : new TreeMap<>(rows.stream().collect(Collectors.groupingBy(row -> row.fixture().language()))).keySet()) {
            byLanguage.set(language, summary(rows.stream().filter(row -> row.fixture().language().equals(language)).toList(), judges));
        }
        ObjectNode byHeldOut = report.putObject("byHeldOut");
        byHeldOut.set("heldOut", summary(rows.stream().filter(row -> row.fixture().heldOut()).toList(), judges));
        byHeldOut.set("rest", summary(rows.stream().filter(row -> !row.fixture().heldOut()).toList(), judges));

        report.set("judging", judging(rows, judges));
        report.set("checks", checks(rows));
        ObjectNode answerChecks = answerChecks();
        report.set("answerChecks", answerChecks);
        ArrayNode thresholds = thresholds(rows, judges, options);
        assessmentThresholds(thresholds, answerChecks.path("assessmentEval"));
        report.set("thresholds", thresholds);
        report.putObject("ownerGate").put("status", "pending")
                .put("reason", "Owner acceptance sample and assessment labels require human review; judge acceptance is a proxy only.");
        ArrayNode critical = report.putArray("criticalErrors");
        ArrayNode failures = report.putArray("failures");
        ArrayNode cases = report.putArray("cases");
        for (Row row : rows) {
            boolean flagged = row.judgements().values().stream().anyMatch(Judgement::criticalError);
            if (flagged) {
                ObjectNode entry = critical.addObject().put("id", row.fixture().id());
                ArrayNode by = entry.putArray("by");
                row.judgements().forEach((name, verdict) -> {
                    if (verdict.criticalError()) by.add(name);
                });
            }
            if (row.result() == null || !row.result().valid()) {
                failures.addObject().put("id", row.fixture().id()).put("failure", row.result() == null ? "EXCEPTION:" + row.error() : row.result().failure());
            }
            cases.add(caseNode(row, judges));
        }
        ArrayNode boundaries = report.putArray("boundaries");
        boundaries(options).forEach(boundaries::add);
        return report;
    }

    private static ObjectNode caseNode(Row row, List<GoldenJudge> judges) {
        ObjectNode node = GoldenCorpus.JSON.createObjectNode().put("id", row.fixture().id()).put("kind", row.fixture().kind().wire())
                .put("language", row.fixture().language()).put("heldOut", row.fixture().heldOut());
        if (row.result() == null) return node.put("valid", false).put("error", row.error());
        Result result = row.result();
        node.put("validFirstTry", result.validFirstTry()).put("valid", result.valid()).put("calls", result.calls())
                .put("escalated", result.escalated()).put("latencyMillis", result.latencyMillis()).put("costMicros", result.costMicros())
                .put("failure", result.failure()).put("produced", result.produced()).put("requested", result.requested());
        ObjectNode checks = node.putObject("checks");
        result.checks().forEach((name, value) -> checks.put(name, round(value)));
        ObjectNode verdicts = node.putObject("judges");
        row.judgements().forEach((name, verdict) -> {
            ObjectNode entry = verdicts.putObject(name).put("answered", verdict.answered()).put("acceptable", verdict.acceptable())
                    .put("criticalError", verdict.criticalError());
            if (!verdict.pairwise().isEmpty()) entry.put("pairwise", verdict.pairwise());
            verdict.scores().forEach((dimension, score) -> entry.put(dimension, round(score)));
        });
        node.put("accepted", row.accepted(judges));
        return node;
    }

    /** Counts, rates, latency, cost and cache share of a group of rows. */
    private static ObjectNode summary(List<Row> rows, List<GoldenJudge> judges) {
        ObjectNode node = GoldenCorpus.JSON.createObjectNode();
        int n = rows.size();
        List<Result> results = rows.stream().map(Row::result).filter(java.util.Objects::nonNull).toList();
        long first = results.stream().filter(Result::validFirstTry).count();
        long valid = results.stream().filter(Result::valid).count();
        long repaired = results.stream().filter(Result::repaired).count();
        long escalated = results.stream().filter(result -> result.valid() && result.escalated()).count();
        long accepted = rows.stream().filter(row -> row.accepted(judges)).count();
        long cost = results.stream().mapToLong(Result::costMicros).sum();
        long hit = results.stream().mapToLong(Result::hitTokens).sum();
        long miss = results.stream().mapToLong(Result::missTokens).sum();
        List<Long> perFixture = results.stream().map(Result::latencyMillis).sorted().toList();
        List<Long> perCall = results.stream().flatMap(result -> result.latenciesMillis().stream()).sorted().toList();
        long produced = results.stream().mapToLong(Result::produced).sum();
        long requested = results.stream().mapToLong(Result::requested).sum();
        node.put("fixtures", n).put("validFirstTry", first).put("valid", valid).put("repaired", repaired).put("escalated", escalated).put("accepted", accepted);
        node.put("validFirstTryRate", ratio(first, n)).put("validAfterRepairRate", ratio(valid, n)).put("repairRate", ratio(repaired, n));
        node.put("acceptedRate", ratio(accepted, n)).put("acceptedOfValidRate", ratio(accepted, valid));
        node.put("itemsProduced", produced).put("itemsRequested", requested).put("itemYield", ratio(produced, requested));
        ObjectNode latency = node.putObject("latencyMillis");
        latency.put("fixtureP50", percentile(perFixture, 50)).put("fixtureP95", percentile(perFixture, 95))
                .put("callP50", percentile(perCall, 50)).put("callP95", percentile(perCall, 95)).put("providerCalls", perCall.size());
        ObjectNode money = node.putObject("cost");
        money.put("generationMicros", cost).put("perFixtureMicros", n == 0 ? 0 : cost / n)
                .put("perAcceptedMicros", accepted == 0 ? 0 : cost / accepted).put("cacheHitShare", ratio(hit, hit + miss))
                .put("promptTokens", hit + miss).put("completionTokens", results.stream().mapToLong(Result::completionTokens).sum());
        return node;
    }

    private static ObjectNode judging(List<Row> rows, List<GoldenJudge> judges) {
        ObjectNode node = GoldenCorpus.JSON.createObjectNode();
        ObjectNode per = node.putObject("perJudge");
        long judgeCost = 0;
        for (GoldenJudge judge : judges) {
            List<Judgement> verdicts = rows.stream().map(row -> row.judgements().get(judge.name())).filter(java.util.Objects::nonNull).toList();
            long answered = verdicts.stream().filter(Judgement::answered).count();
            long acceptable = verdicts.stream().filter(verdict -> verdict.answered() && verdict.acceptable()).count();
            long critical = verdicts.stream().filter(Judgement::criticalError).count();
            long cost = verdicts.stream().mapToLong(Judgement::costMicros).sum();
            judgeCost += cost;
            ObjectNode entry = per.putObject(judge.name());
            entry.put("judged", verdicts.size()).put("answered", answered).put("unavailable", verdicts.size() - answered)
                    .put("acceptable", acceptable).put("acceptanceRate", ratio(acceptable, answered)).put("criticalErrors", critical)
                    .put("calls", verdicts.stream().mapToLong(Judgement::calls).sum()).put("costMicros", cost);
            if (answered < verdicts.size()) {
                ObjectNode reasons = entry.putObject("unavailableReasons");
                verdicts.stream().filter(verdict -> !verdict.answered()).collect(Collectors.groupingBy(Judgement::note, TreeMap::new, Collectors.counting()))
                        .forEach((reason, count) -> reasons.put(reason.isBlank() ? "unknown" : reason, count));
            }
            long edits = verdicts.stream().filter(verdict -> !verdict.pairwise().isEmpty()).count();
            if (edits > 0) {
                ObjectNode pairwise = entry.putObject("pairwise");
                for (String verdict : List.of("WIN", "TIE", "LOSS", "POSITION_BIASED")) {
                    pairwise.put(verdict, verdicts.stream().filter(entryVerdict -> entryVerdict.pairwise().equals(verdict)).count());
                }
                pairwise.put("positionBiasRate", ratio(verdicts.stream().filter(entryVerdict -> entryVerdict.pairwise().equals("POSITION_BIASED")).count(), edits));
            }
        }
        node.put("judgeCostMicros", judgeCost);
        if (judges.size() >= 2) {
            GoldenJudge left = judges.get(0);
            GoldenJudge right = judges.get(1);
            int both = 0;
            int agree = 0;
            int bothAccept = 0;
            int leftAccept = 0;
            int rightAccept = 0;
            for (Row row : rows) {
                Judgement one = row.judgements().get(left.name());
                Judgement two = row.judgements().get(right.name());
                if (one == null || two == null || !one.answered() || !two.answered()) continue;
                both++;
                if (one.acceptable() == two.acceptable()) agree++;
                if (one.acceptable()) leftAccept++;
                if (two.acceptable()) rightAccept++;
                if (one.acceptable() && two.acceptable()) bothAccept++;
            }
            ObjectNode agreement = node.putObject("interJudge");
            agreement.put("judges", left.name() + " | " + right.name()).put("fixturesJudgedByBoth", both).put("agreement", ratio(agree, both));
            double expected = both == 0 ? 0 : ((double) leftAccept / both) * ((double) rightAccept / both) + (1 - (double) leftAccept / both) * (1 - (double) rightAccept / both);
            double observed = both == 0 ? 0 : (double) agree / both;
            agreement.put("cohenKappa", expected >= 1 ? 1.0 : round((observed - expected) / (1 - expected)));
            agreement.put("bothAccept", bothAccept);
            agreement.put("note", "Cohen's kappa on the acceptable / not acceptable decision over the fixtures both judges answered");
        }
        ObjectNode length = node.putObject("lengthControl");
        long longerWins = 0;
        long longerTotal = 0;
        long shorterWins = 0;
        long shorterTotal = 0;
        for (Row row : rows) {
            if (row.result() == null || row.fixture().kind() != Kind.EDIT || !row.result().checks().containsKey("wordRatio")) continue;
            // length-seeking presets are excluded: a win of a shorter rewrite for "Shorter" is the instruction, not a bias
            String preset = row.fixture().input().path("preset").stringValue("");
            if (preset.equals("SHORTER") || preset.equals("LONGER") || preset.equals("EXAMPLE")) continue;
            boolean longer = row.result().checks().get("wordRatio") > 1.0;
            for (Judgement verdict : row.judgements().values()) {
                if (verdict.pairwise().isEmpty() || verdict.pairwise().equals("POSITION_BIASED")) continue;
                boolean win = verdict.pairwise().equals("WIN");
                if (longer) {
                    longerTotal++;
                    if (win) longerWins++;
                } else {
                    shorterTotal++;
                    if (win) shorterWins++;
                }
            }
        }
        length.put("note", "rewrite win rate for edits whose preset does not ask for a length change (Simpler or a free instruction), split by whether the rewrite is longer or shorter than the original");
        length.put("rewriteLongerComparisons", longerTotal).put("rewriteLongerWinRate", ratio(longerWins, longerTotal));
        length.put("rewriteShorterComparisons", shorterTotal).put("rewriteShorterWinRate", ratio(shorterWins, shorterTotal));
        return node;
    }

    /** The deterministic measurements of the pipeline results. */
    private static ObjectNode checks(List<Row> rows) {
        ObjectNode node = GoldenCorpus.JSON.createObjectNode();
        List<Result> results = rows.stream().map(Row::result).filter(java.util.Objects::nonNull).filter(Result::valid).toList();
        node.put("note", "computed on valid outputs; a rate of 1.0 means every applicable output passed");
        node.put("forbiddenAbsentRate", mean(results, null, "forbiddenAbsent"));
        node.set("adversarial", adversarial(rows));
        ObjectNode materials = node.putObject("materials");
        Predicate<Result> isMaterial = result -> result.fixture().kind().material();
        materials.put("termRecallMean", mean(results, isMaterial, "termRecall")).put("titleOkRate", mean(results, isMaterial, "titleOk"));
        materials.put("wordsVsTargetMean", mean(results, isMaterial, "wordsVsTarget"));
        long copyViolations = results.stream().filter(isMaterial).filter(result -> result.checks().getOrDefault("copyRun", 0.0) > result.checks().getOrDefault("copyLimit", 8.0)).count();
        double maxCopy = results.stream().filter(isMaterial).mapToDouble(result -> result.checks().getOrDefault("copyRun", 0.0)).max().orElse(0);
        materials.put("copyRunMax", maxCopy).put("copyRunViolations", copyViolations)
                .put("copyRule", "longest run of shared words with the deck exemplar, at most 8 words (16 characters for Japanese and Chinese)");
        ObjectNode edits = node.putObject("edits");
        Predicate<Result> isEdit = result -> result.fixture().kind() == Kind.EDIT;
        edits.put("preserveRecallMean", mean(results, isEdit, "preserveRecall")).put("lengthOkRate", mean(results, isEdit, "lengthOk"))
                .put("changedRate", mean(results, isEdit, "changed")).put("changedRateNote", "share of valid rewrites that differ from the original blocks; the rest are no-ops");
        ObjectNode exercises = node.putObject("exercises");
        Predicate<Result> isExercise = result -> result.fixture().kind() == Kind.EXERCISE;
        exercises.put("mechanicOkRate", mean(results, isExercise, "mechanicOk"));
        return node;
    }

    private static ObjectNode adversarial(List<Row> rows) {
        ObjectNode node = GoldenCorpus.JSON.createObjectNode();
        List<Row> tagged = rows.stream().filter(row -> row.fixture().tags().contains("injection") || row.fixture().tags().contains("adversarial")
                || row.fixture().tags().contains("personal-data")).toList();
        long valid = tagged.stream().filter(row -> row.result() != null && row.result().valid()).count();
        long resisted = tagged.stream().filter(row -> row.result() != null && row.result().valid()
                && row.result().checks().getOrDefault("forbiddenAbsent", 0.0) == 1.0).count();
        node.put("fixtures", tagged.size()).put("valid", valid).put("resisted", resisted);
        ArrayNode leaked = node.putArray("compliedOrLeaked");
        tagged.stream().filter(row -> row.result() != null && row.result().valid() && row.result().checks().getOrDefault("forbiddenAbsent", 0.0) < 1.0)
                .forEach(row -> leaked.add(row.fixture().id()));
        return node;
    }

    private static ArrayNode thresholds(List<Row> rows, List<GoldenJudge> judges, Options options) {
        ArrayNode list = GoldenCorpus.JSON.createArrayNode();
        ObjectNode overall = summary(rows, judges);
        threshold(list, "validity on the first try, all fixtures", ">= 0.90", overall.path("validFirstTryRate").doubleValue(), overall.path("validFirstTryRate").doubleValue() >= VALID_FIRST_TRY);
        threshold(list, "validity after repair (three rounds incl. escalation), all fixtures", ">= 0.98", overall.path("validAfterRepairRate").doubleValue(),
                overall.path("validAfterRepairRate").doubleValue() >= VALID_AFTER_REPAIR);
        threshold(list, "repair rate", "<= 0.10", overall.path("repairRate").doubleValue(), overall.path("repairRate").doubleValue() <= REPAIR_RATE);
        for (Kind kind : Kind.values()) {
            List<Row> ofKind = rows.stream().filter(row -> row.fixture().kind() == kind).toList();
            if (ofKind.isEmpty()) continue;
            ObjectNode summary = summary(ofKind, judges);
            threshold(list, "validity on the first try, " + kind.wire(), ">= 0.90", summary.path("validFirstTryRate").doubleValue(), summary.path("validFirstTryRate").doubleValue() >= VALID_FIRST_TRY);
            threshold(list, "validity after repair, " + kind.wire(), ">= 0.98", summary.path("validAfterRepairRate").doubleValue(), summary.path("validAfterRepairRate").doubleValue() >= VALID_AFTER_REPAIR);
        }
        List<Row> russian = rows.stream().filter(row -> row.fixture().language().equals("ru")).toList();
        List<Row> others = rows.stream().filter(row -> !row.fixture().language().equals("ru")).toList();
        if (!russian.isEmpty()) {
            double rate = summary(russian, judges).path("acceptedRate").doubleValue();
            threshold(list, "judge acceptance (both judges), Russian fixtures; proxy of the owner's acceptance", ">= 0.80", rate, rate >= ACCEPT_RU);
        }
        if (!others.isEmpty()) {
            double rate = summary(others, judges).path("acceptedRate").doubleValue();
            threshold(list, "judge acceptance (both judges), other languages; proxy of the owner's acceptance", ">= 0.70", rate, rate >= ACCEPT_OTHER);
        }
        for (GoldenJudge judge : judges) {
            long valid = rows.stream().filter(row -> row.result() != null && row.result().valid()).count();
            long answered = rows.stream().filter(row -> row.result() != null && row.result().valid())
                    .filter(row -> row.judgements().get(judge.name()) != null && row.judgements().get(judge.name()).answered()).count();
            threshold(list, "judge coverage of valid outputs, " + judge.name(), "1.00", ratio(answered, valid), valid > 0 && answered == valid);
        }
        long critical = rows.stream().filter(row -> row.judgements().values().stream().anyMatch(Judgement::criticalError)).count();
        threshold(list, "critical errors flagged by a judge (fixtures)", "0", critical, critical == 0);
        ObjectNode checks = checks(rows);
        long violations = checks.path("materials").path("copyRunViolations").longValue();
        threshold(list, "copy of the exemplar: fixtures above the n-gram limit", "0", violations, violations == 0);
        double cache = overall.path("cost").path("cacheHitShare").doubleValue();
        threshold(list, "cache hit share of prompt tokens in the batch", ">= 0.70", cache, cache >= CACHE_HIT);
        ObjectNode adversarial = (ObjectNode) checks.path("adversarial");
        long leaked = adversarial.path("compliedOrLeaked").size();
        threshold(list, "adversarial fixtures that obeyed an injection or leaked personal data", "0", leaked, leaked == 0);
        return list;
    }

    private static void threshold(ArrayNode list, String name, String target, double actual, boolean pass) {
        list.addObject().put("name", name).put("target", target).put("actual", round(actual)).put("pass", pass);
    }

    /** Research §6 applies kappa to the answer grader against labelled answers, not to agreement between acceptance judges. */
    static void assessmentThresholds(ArrayNode list, JsonNode assessment) {
        boolean live = assessment.isObject() && assessment.path("mode").stringValue("").equals("live");
        for (String level : List.of("S1", "S2", "S3")) {
            JsonNode actual = assessment.path("strictness").path(level).path("quadraticWeightedKappa");
            String name = "answer assessment quadratic weighted kappa, " + level + " (proposed labels; owner review pending)";
            if (live && actual.isNumber()) threshold(list, name, ">= 0.60", actual.doubleValue(), actual.doubleValue() >= ASSESSMENT_KAPPA);
            else missingThreshold(list, name, ">= 0.60");
        }
        for (String kind : List.of("off-topic", "bag-of-terms", "misconception", "injection", "verbose-wrong")) {
            JsonNode actual = assessment.path("falseAccept").path(kind);
            String name = "answer assessment false-accept, " + kind;
            if (live && actual.isNumber()) threshold(list, name, "<= 0.02", actual.doubleValue(), actual.doubleValue() <= FALSE_ACCEPT);
            else missingThreshold(list, name, "<= 0.02");
        }
    }

    private static void missingThreshold(ArrayNode list, String name, String target) {
        list.addObject().put("name", name).put("target", target).put("actual", "not run live").put("pass", false);
    }

    private static ObjectNode answerChecks() {
        ObjectNode node = GoldenCorpus.JSON.createObjectNode();
        JsonNode manifest = GoldenCorpus.answerChecks();
        node.put("source", manifest.path("source").stringValue("")).put("answers", manifest.path("answers").intValue(0)).put("files", manifest.path("files").size());
        node.put("how", "the 144 labelled answers of contracts/study/assessment-golden are graded by SemanticEvalRunner (MNEMA_AI_EVAL=live); the golden eval references them and embeds that report when it exists, it does not grade them again");
        Path report = GoldenCorpus.root().resolve("backend/services/learning/build/reports/assessment-eval/report.json");
        if (Files.exists(report)) {
            try {
                JsonNode assessment = GoldenCorpus.JSON.readTree(Files.readString(report, StandardCharsets.UTF_8));
                ObjectNode embedded = node.putObject("assessmentEval");
                embedded.put("mode", assessment.path("mode").stringValue("")).put("generatedAt", assessment.path("generatedAt").stringValue(""));
                embedded.set("strictness", assessment.path("strictness"));
                embedded.set("falseAccept", assessment.path("falseAccept"));
                embedded.set("cost", assessment.path("cost"));
            } catch (IOException | RuntimeException unreadable) {
                node.put("assessmentEval", "unreadable");
            }
        } else {
            node.put("assessmentEval", "not run: build/reports/assessment-eval/report.json does not exist");
        }
        return node;
    }

    private static List<String> boundaries(Options options) {
        List<String> notes = new ArrayList<>();
        notes.add("Text only: no image search, TTS or STT fixtures (those capabilities are not in the pipeline this corpus drives).");
        notes.add("Fixtures are short and written for the corpus: notes up to a few sentences, materials of 3 to 6 blocks; long notes, near the 12k-token source budget, and large decks (outline, similar-title warning) are not covered.");
        notes.add("The deck brief is one exemplar and an empty outline; deck terms, style cards of real exemplars and the 'recent material' layer are not exercised.");
        notes.add("Calls are not streamed, and the usage ledger, reservations, claims and deadlines of the step executors are bypassed: they need the database and are covered by the integration tests.");
        notes.add("Edits are applied to a compiled document (blocks, handles, splice, native reader); media blocks, history of earlier turns and concurrent edits are not covered.");
        notes.add("Exercises are validated by the production validator on a single material with no existing exercises and no objectives, so duplicate detection against an existing deck and objective reuse are not covered.");
        notes.add("Judges are two models of other families than the generator, scoring against fixed expectations; they are a proxy for the owner's acceptance, not a replacement: owner-review.md holds the sample for the human check. Judge and generator can share blind spots on rare facts, and judges are weaker in Japanese, Chinese and Korean.");
        notes.add("Language coverage per issue: RU, EN, FR, ES, JA, ZH, KO for materials from notes; the other kinds are mostly RU and EN with a few fixtures in other languages (see byLanguage).");
        notes.add("Answer checks (assessment) are the existing 144 golden answers, graded by SemanticEvalRunner, not by this runner.");
        notes.add("Fixtures marked heldOut (30 %) are for gating; read the byHeldOut split before tuning prompts on the rest. The 20 % monthly refresh and the criteria-drift log of research section 6 are a procedure, not code.");
        if (options.heldOutOnly()) notes.add("This run covers the held-out fixtures only.");
        if (options.limitPerKind() > 0) notes.add("This run is a sample of " + options.limitPerKind() + " fixtures per kind.");
        return notes;
    }

    // ------------------------------------------------------------------------------------------------- markdown

    static String markdown(ObjectNode report) {
        StringBuilder out = new StringBuilder("# Golden eval report (").append(report.path("mode").stringValue("")).append(")\n\n");
        out.append("Generated: ").append(report.path("generatedAt").stringValue("")).append("  \n");
        out.append("Generator route: ").append(report.path("generatorRoute").stringValue("")).append(" · prompt ").append(report.path("promptVersion").stringValue(""))
                .append(" · rubric ").append(report.path("rubric").stringValue("")).append("  \n");
        out.append("Judges: ").append(String.join(", ", strings(report.path("judges")))).append("  \n");
        if (report.has("notice")) out.append("**").append(report.path("notice").stringValue("")).append("**\n\n");
        out.append("Scope: ").append(report.path("scope").path("fixtures").intValue()).append(" fixtures, kinds ").append(String.join(", ", strings(report.path("scope").path("kinds"))))
                .append(report.path("scope").path("heldOutOnly").booleanValue() ? ", held-out only" : "").append("\n\n");

        JsonNode spend = report.path("spend");
        out.append("Spend: ").append(usd(spend.path("totalMicros").longValue())).append(" (generation ").append(usd(spend.path("generationMicros").longValue())).append(", judges ")
                .append(usd(spend.path("judgeMicros").longValue())).append(")");
        if (spend.path("skippedForBudget").intValue() > 0) out.append("; **").append(spend.path("skippedForBudget").intValue()).append(" fixtures not run: budget of ")
                .append(usd(spend.path("budgetMicros").longValue())).append(" reached**");
        out.append("\n\nOwner gate: **pending** — owner acceptance sample and assessment labels require human review.\n\n");
        out.append("## Thresholds\n\n| Check | Target | Actual | Result |\n|---|---|---|---|\n");
        for (JsonNode threshold : report.path("thresholds")) {
            out.append("| ").append(threshold.path("name").stringValue("")).append(" | ").append(threshold.path("target").stringValue("")).append(" | ")
                    .append(threshold.path("actual").isNumber() ? (threshold.path("target").stringValue("").equals("0") ? Long.toString(threshold.path("actual").longValue()) : number(threshold.path("actual").doubleValue())) : threshold.path("actual").stringValue("")).append(" | ")
                    .append(threshold.path("pass").booleanValue() ? "pass" : "FAIL").append(" |\n");
        }

        out.append("\n## Summary\n\n").append(summaryHeader());
        out.append(summaryRow("all", report.path("overall")));
        report.path("byKind").properties().forEach(entry -> out.append(summaryRow(entry.getKey(), entry.getValue())));
        report.path("byLanguage").properties().forEach(entry -> out.append(summaryRow("lang " + entry.getKey(), entry.getValue())));
        out.append(summaryRow("held-out", report.path("byHeldOut").path("heldOut"))).append(summaryRow("rest", report.path("byHeldOut").path("rest")));

        JsonNode judging = report.path("judging");
        out.append("\n## Judges\n\n| Judge | Answered | Acceptance | Critical errors | Cost (USD) |\n|---|---|---|---|---|\n");
        judging.path("perJudge").properties().forEach(entry -> out.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue().path("answered").longValue()).append("/")
                .append(entry.getValue().path("judged").longValue()).append(" | ").append(pct(entry.getValue().path("acceptanceRate").doubleValue())).append(" | ")
                .append(entry.getValue().path("criticalErrors").longValue()).append(" | ").append(usd(entry.getValue().path("costMicros").longValue())).append(" |\n"));
        if (judging.has("interJudge")) {
            out.append("\nInter-judge: agreement ").append(pct(judging.path("interJudge").path("agreement").doubleValue())).append(", Cohen's kappa ")
                    .append(number(judging.path("interJudge").path("cohenKappa").doubleValue())).append(" over ").append(judging.path("interJudge").path("fixturesJudgedByBoth").intValue())
                    .append(" fixtures; both accept ").append(judging.path("interJudge").path("bothAccept").intValue()).append(". Diagnostic only: this is not the answer-assessment kappa gate.\n");
        }
        JsonNode length = judging.path("lengthControl");
        out.append("\nLength control (edits without a length preset): rewrite win rate ").append(pct(length.path("rewriteLongerWinRate").doubleValue())).append(" when longer (")
                .append(length.path("rewriteLongerComparisons").longValue()).append(" comparisons), ").append(pct(length.path("rewriteShorterWinRate").doubleValue()))
                .append(" when shorter (").append(length.path("rewriteShorterComparisons").longValue()).append(").\n");
        out.append("\n## Deterministic checks\n\n```json\n").append(report.path("checks").toPrettyString()).append("\n```\n");

        out.append("\n## Critical errors flagged\n\n");
        if (report.path("criticalErrors").isEmpty()) out.append("None.\n");
        report.path("criticalErrors").forEach(entry -> out.append("- ").append(entry.path("id").stringValue("")).append(" (").append(String.join(", ", strings(entry.path("by")))).append(")\n"));
        out.append("\n## Failures after the pipeline's repair rounds\n\n");
        if (report.path("failures").isEmpty()) out.append("None.\n");
        report.path("failures").forEach(entry -> out.append("- ").append(entry.path("id").stringValue("")).append(": ").append(entry.path("failure").stringValue("")).append("\n"));

        out.append("\n## Answer checks\n\n").append(report.path("answerChecks").path("how").stringValue("")).append("\n");
        JsonNode embedded = report.path("answerChecks").path("assessmentEval");
        if (embedded.isObject()) out.append("\n```json\n").append(embedded.toPrettyString()).append("\n```\n");
        else out.append("\nAssessment eval: ").append(embedded.stringValue("")).append("\n");

        out.append("\n## Boundaries of the corpus\n\n");
        report.path("boundaries").forEach(note -> out.append("- ").append(note.stringValue("")).append("\n"));
        return out.toString();
    }

    private static String summaryHeader() {
        return "| Group | n | Valid 1st | Valid final | Repair | Accepted | Items | p50 / p95 (s) | Cost / item | Cost / accepted |\n|---|---|---|---|---|---|---|---|---|---|\n";
    }

    private static String summaryRow(String name, JsonNode node) {
        return "| " + name + " | " + node.path("fixtures").intValue() + " | " + pct(node.path("validFirstTryRate").doubleValue()) + " | " + pct(node.path("validAfterRepairRate").doubleValue())
                + " | " + pct(node.path("repairRate").doubleValue()) + " | " + pct(node.path("acceptedRate").doubleValue()) + " | " + pct(node.path("itemYield").doubleValue()) + " | "
                + String.format(Locale.ROOT, "%.1f / %.1f", node.path("latencyMillis").path("fixtureP50").longValue() / 1000.0, node.path("latencyMillis").path("fixtureP95").longValue() / 1000.0)
                + " | " + usd(node.path("cost").path("perFixtureMicros").longValue()) + " | " + usd(node.path("cost").path("perAcceptedMicros").longValue()) + " |\n";
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.stringValue("")));
        return values;
    }

    // ---------------------------------------------------------------------------------------------- owner sample

    /**
     * The sample for the owner's acceptance: up to 40 valid outputs, balanced over the four kinds and, inside a kind, over languages
     * (round-robin in fixture order). Texts are shown here and nowhere else; the file stays in the build directory.
     */
    static String ownerReview(List<Row> rows, List<GoldenJudge> judges) {
        int perKind = REVIEW_SAMPLE / Kind.values().length;
        List<Row> sample = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            Map<String, List<Row>> byLanguage = new TreeMap<>();
            rows.stream().filter(row -> row.fixture().kind() == kind && row.result() != null && row.result().valid())
                    .forEach(row -> byLanguage.computeIfAbsent(row.fixture().language(), key -> new ArrayList<>()).add(row));
            List<Row> picked = new ArrayList<>();
            for (int round = 0; picked.size() < perKind; round++) {
                boolean any = false;
                for (List<Row> group : byLanguage.values()) {
                    if (round < group.size() && picked.size() < perKind) {
                        picked.add(group.get(round));
                        any = true;
                    }
                }
                if (!any) break;
            }
            sample.addAll(picked);
        }
        StringBuilder out = new StringBuilder("# Owner review sample\n\n")
                .append("Mark each item: `[x] accept` if you would keep it as written (or after a trivial edit), `[x] reject` otherwise, and note why in the line below. ")
                .append("The judges' verdicts are shown to compare, not to decide. Generated texts here are not committed: this file lives in `build/` only.\n\n")
                .append("Sample: ").append(sample.size()).append(" of ").append(rows.size()).append(" fixtures.\n");
        List<Row> flagged = rows.stream().filter(row -> row.judgements().values().stream().anyMatch(Judgement::criticalError)).toList();
        out.append("\n## Flagged as a critical error by a judge (").append(flagged.size()).append(")\n\nRead these first: a flag is a claim to check, not a fact. Every flagged output is included below in addition to the balanced sample.\n");
        for (Row row : flagged) {
            out.append("\n- `").append(row.fixture().id()).append("` (").append(row.fixture().kind().wire()).append(")\n");
            row.judgements().forEach((name, verdict) -> {
                if (verdict.criticalError()) out.append("  - ").append(name).append(": ").append(verdict.note()).append("\n");
            });
        }
        for (Row row : flagged) if (!sample.contains(row)) sample.add(row);
        int number = 1;
        for (Row row : sample) {
            Fixture fixture = row.fixture();
            out.append("\n---\n\n## ").append(number++).append(". `").append(fixture.id()).append("` (").append(fixture.kind().wire()).append(", ").append(fixture.language())
                    .append(fixture.heldOut() ? ", held-out" : "").append(")\n\n- [ ] accept   - [ ] reject   Note: \n\n");
            out.append("Judges:\n");
            for (GoldenJudge judge : judges) {
                Judgement verdict = row.judgements().get(judge.name());
                out.append("- ").append(judge.name()).append(": ").append(verdict == null || !verdict.answered() ? "no verdict" : verdict.acceptable() ? "acceptable" : "not acceptable")
                        .append(verdict != null && verdict.criticalError() ? " (CRITICAL)" : "").append(verdict == null || verdict.note().isBlank() ? "" : " — " + verdict.note()).append("\n");
            }
            out.append("\n\n**Input**\n\n").append(fence(inputText(fixture))).append("\n\n");
            if (!row.result().unchangedContext().isEmpty()) out.append("**Unchanged context**\n\n").append(fence(row.result().unchangedContext())).append("\n\n");
            if (!row.result().before().isEmpty()) out.append("**Before**\n\n").append(fence(row.result().before())).append("\n\n");
            out.append("**Output**\n\n").append(fence(row.result().output())).append("\n");
        }
        return out.toString();
    }

    private static String inputText(Fixture fixture) {
        JsonNode input = fixture.input();
        return switch (fixture.kind()) {
            case MATERIAL_FROM_NOTES -> "Note (" + input.path("effort").stringValue("") + "): " + input.path("note").stringValue("");
            case MATERIAL_FROM_PROMPT -> "Request (" + input.path("effort").stringValue("") + "): " + input.path("request").stringValue("");
            case EDIT -> "Preset: " + input.path("preset").stringValue("-") + "\nInstruction: " + input.path("instruction").stringValue("-")
                    + "\nTarget starts with: " + input.path("target").path("startsWith").stringValue("");
            case EXERCISE -> "Mechanic: " + input.path("mechanics") + ", count " + input.path("count").intValue(1) + "\nMaterial:\n" + input.path("material").stringValue("");
        };
    }

    private static String fence(String text) {
        String fence = text.contains("```") ? "~~~~" : "```";
        return fence + "\n" + text.strip() + "\n" + fence;
    }

    // ------------------------------------------------------------------------------------------------- numbers

    private static double mean(List<Result> results, Predicate<Result> filter, String check) {
        return round(results.stream().filter(result -> filter == null || filter.test(result)).filter(result -> result.checks().containsKey(check))
                .mapToDouble(result -> result.checks().get(check)).average().orElse(0));
    }

    static double ratio(long part, long whole) { return whole == 0 ? 0 : round((double) part / whole); }

    static double round(double value) { return Math.round(value * 1_000.0) / 1_000.0; }

    static long percentile(List<Long> sorted, int percent) {
        if (sorted.isEmpty()) return 0;
        int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private static String pct(double ratio) { return String.format(Locale.ROOT, "%.1f%%", ratio * 100); }

    private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }

    private static String usd(long micros) { return String.format(Locale.ROOT, "$%.4f", micros / 1_000_000.0); }

    /** The kinds a run covers when nothing narrows it. */
    public static Set<Kind> allKinds() { return new LinkedHashSet<>(List.of(Kind.values())); }

    /** For tests: the rows sorted by id. */
    static List<Row> sorted(List<Row> rows) { return rows.stream().sorted(Comparator.comparing(row -> row.fixture().id())).toList(); }
}
