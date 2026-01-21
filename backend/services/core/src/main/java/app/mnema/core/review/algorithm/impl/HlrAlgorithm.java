package app.mnema.core.review.algorithm.impl;

import app.mnema.core.review.algorithm.CanonicalProgress;
import app.mnema.core.review.algorithm.ReviewContext;
import app.mnema.core.review.algorithm.SrsAlgorithm;
import app.mnema.core.review.domain.Rating;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class HlrAlgorithm implements SrsAlgorithm {

    private static final double LN2 = Math.log(2.0);

    private final ObjectMapper om;

    public HlrAlgorithm(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public String id() {
        return "hlr";
    }

    @Override
    public JsonNode initialState(JsonNode effectiveConfig) {
        HlrConfig cfg = HlrConfig.from(effectiveConfig);
        ObjectNode s = om.createObjectNode();
        s.put("phase", "learning");
        s.put("step", 0);
        s.put("h", cfg.initialHalfLifeDays);
        return s;
    }

    @Override
    public ReviewComputation apply(ReviewInput input, Rating rating, Instant now, JsonNode effectiveConfig) {
        return apply(input, rating, now, effectiveConfig, ReviewContext.EMPTY);
    }

    @Override
    public ReviewComputation apply(ReviewInput input,
                                   Rating rating,
                                   Instant now,
                                   JsonNode effectiveConfig,
                                   ReviewContext context) {
        HlrConfig cfg = HlrConfig.from(effectiveConfig);
        HlrState st = HlrState.from(input.state());
        double elapsedDays = elapsedDays(input.lastReviewAt(), now);
        double[] x = FeatureVector.from(context.features(), input, elapsedDays);
        double[] w = cfg.weightsFor(x.length);

        HlrResult result = compute(st, input, rating, now, elapsedDays, cfg, x, w);
        return result.computation;
    }

    @Override
    public ReviewOutcome review(ReviewInput input,
                                Rating rating,
                                Instant now,
                                JsonNode effectiveConfig,
                                ReviewContext context,
                                JsonNode deckConfig) {
        HlrConfig cfg = HlrConfig.from(effectiveConfig);
        HlrState st = HlrState.from(input.state());
        double elapsedDays = elapsedDays(input.lastReviewAt(), now);
        double[] x = FeatureVector.from(context.features(), input, elapsedDays);

        double[] w = cfg.weightsFor(x.length);
        HlrResult result = compute(st, input, rating, now, elapsedDays, cfg, x, w);

        ObjectNode updatedDeckConfig = mergeWeights(deckConfig, result.updatedWeights);
        return new ReviewOutcome(result.computation, updatedDeckConfig);
    }

    @Override
    public CanonicalProgress toCanonical(JsonNode state) {
        double h = state.path("h").asDouble(1.0);
        double stabilityDays = Math.max(0.1, h);
        return new CanonicalProgress(0.5, stabilityDays);
    }

    @Override
    public JsonNode fromCanonical(CanonicalProgress progress, JsonNode effectiveConfig) {
        ObjectNode s = om.createObjectNode();
        s.put("phase", "review");
        s.put("step", 0);
        s.put("h", Math.max(0.1, progress.stabilityDays()));
        return s;
    }

    private HlrResult compute(HlrState st,
                              ReviewInput input,
                              Rating rating,
                              Instant now,
                              double elapsedDays,
                              HlrConfig cfg,
                              double[] x,
                              double[] w) {
        double hPred = halfLifeDays(w, x, cfg);

        boolean correct = rating == Rating.GOOD || rating == Rating.EASY;
        updateWeights(w, x, elapsedDays, hPred, correct, cfg);
        double hUpdated = halfLifeDays(w, x, cfg);

        if ("learning".equals(st.phase)) {
            return handleLearning(st, rating, now, cfg, hUpdated, w);
        }
        if ("relearning".equals(st.phase)) {
            return handleRelearning(st, rating, now, cfg, hUpdated, w);
        }
        return handleReview(rating, now, cfg, hUpdated, w);
    }

    private HlrResult handleLearning(HlrState st,
                                     Rating rating,
                                     Instant now,
                                     HlrConfig cfg,
                                     double hUpdated,
                                     double[] w) {
        List<Integer> steps = cfg.learningStepsMinutes;
        int step = st.step;

        if (steps.isEmpty()) {
            return graduate(now, cfg, rating, hUpdated, w);
        }

        if (rating == Rating.EASY) {
            return graduate(now, cfg, rating, hUpdated, w);
        }

        if (rating == Rating.GOOD) step++;
        if (rating == Rating.AGAIN) step = 0;

        if (step >= steps.size()) {
            return graduate(now, cfg, rating, hUpdated, w);
        }

        int minutes = Math.max(cfg.minimumIntervalMinutes, steps.get(step));
        ObjectNode next = state("learning", step, hUpdated);
        Instant due = now.plus(Duration.ofMinutes(minutes));
        return new HlrResult(new ReviewComputation(next, due, now, 1), w);
    }

    private HlrResult handleRelearning(HlrState st,
                                       Rating rating,
                                       Instant now,
                                       HlrConfig cfg,
                                       double hUpdated,
                                       double[] w) {
        List<Integer> steps = cfg.relearningStepsMinutes;
        int step = st.step;

        if (steps.isEmpty()) {
            return handleReview(rating, now, cfg, hUpdated, w);
        }

        if (rating == Rating.AGAIN) step = 0;
        if (rating == Rating.GOOD) step++;
        if (rating == Rating.EASY) step = steps.size();

        if (step >= steps.size()) {
            return handleReview(rating, now, cfg, hUpdated, w);
        }

        int minutes = Math.max(cfg.minimumIntervalMinutes, steps.get(step));
        ObjectNode next = state("relearning", step, hUpdated);
        Instant due = now.plus(Duration.ofMinutes(minutes));
        return new HlrResult(new ReviewComputation(next, due, now, 1), w);
    }

    private HlrResult handleReview(Rating rating,
                                   Instant now,
                                   HlrConfig cfg,
                                   double hUpdated,
                                   double[] w) {
        double intervalDays = intervalFromRetention(hUpdated, cfg.requestRetention);
        intervalDays = clamp(intervalDays, cfg.minimumIntervalMinutes / 1440.0, cfg.maximumIntervalDays);
        if (rating == Rating.HARD) {
            intervalDays = Math.max(cfg.minimumIntervalMinutes / 1440.0, intervalDays * 0.8);
        }
        if (rating == Rating.EASY) {
            intervalDays = Math.min(cfg.maximumIntervalDays, intervalDays * 1.2);
        }

        ObjectNode next = state("review", 0, hUpdated);
        Instant due = now.plus(Duration.ofSeconds((long) (intervalDays * 86400)));
        return new HlrResult(new ReviewComputation(next, due, now, 1), w);
    }

    private HlrResult graduate(Instant now,
                               HlrConfig cfg,
                               Rating rating,
                               double hUpdated,
                               double[] w) {
        double intervalDays = (rating == Rating.EASY) ? cfg.easyIntervalDays : cfg.graduatingIntervalDays;
        ObjectNode next = state("review", 0, hUpdated);
        Instant due = now.plus(Duration.ofSeconds((long) (intervalDays * 86400)));
        return new HlrResult(new ReviewComputation(next, due, now, 1), w);
    }

    private static double elapsedDays(Instant lastReviewAt, Instant now) {
        if (lastReviewAt == null) return 0.0;
        double seconds = Math.max(0.0, Duration.between(lastReviewAt, now).toSeconds());
        return seconds / 86400.0;
    }

    private static double halfLifeDays(double[] w, double[] x, HlrConfig cfg) {
        double dot = 0.0;
        for (int i = 0; i < x.length; i++) {
            dot += w[i] * x[i];
        }
        double h = Math.pow(2.0, dot);
        return clamp(h, cfg.minHalfLifeDays, cfg.maxHalfLifeDays);
    }

    private static double intervalFromRetention(double hDays, double retention) {
        double r = clamp(retention, 0.01, 0.9999);
        return hDays * (Math.log(1.0 / r) / LN2);
    }

    private static void updateWeights(double[] w,
                                      double[] x,
                                      double elapsedDays,
                                      double hDays,
                                      boolean correct,
                                      HlrConfig cfg) {
        double t = Math.max(0.0, elapsedDays);
        double p = Math.pow(2.0, -t / hDays);
        double y = correct ? 1.0 : 0.0;
        double dpDa = p * LN2 * LN2 * t / hDays;
        double error = p - y;
        double scale = error * dpDa;

        for (int i = 0; i < w.length; i++) {
            double grad = scale * x[i] + cfg.l2 * w[i];
            w[i] -= cfg.learningRate * grad;
        }
    }

    private ObjectNode state(String phase, int step, double h) {
        ObjectNode s = om.createObjectNode();
        s.put("phase", phase);
        s.put("step", step);
        s.put("h", h);
        return s;
    }

    private static ObjectNode mergeWeights(JsonNode deckConfig, double[] weights) {
        ObjectNode out;
        if (deckConfig != null && deckConfig.isObject()) {
            out = ((ObjectNode) deckConfig).deepCopy();
        } else {
            out = JsonNodeFactory.instance.objectNode();
        }
        ArrayNode w = out.putArray("weights");
        for (double v : weights) {
            w.add(v);
        }
        out.put("featureSize", weights.length);
        return out;
    }

    private record HlrResult(ReviewComputation computation, double[] updatedWeights) {
    }

    private record HlrState(String phase, int step, double h) {
        static HlrState from(JsonNode state) {
            if (state == null || state.isNull() || !state.isObject()) {
                return new HlrState("learning", 0, 1.0);
            }
            String phase = state.path("phase").asText("review");
            int step = state.path("step").asInt(0);
            double h = state.path("h").asDouble(1.0);
            return new HlrState(phase, step, h);
        }
    }

    private record HlrConfig(
            double requestRetention,
            double maximumIntervalDays,
            double graduatingIntervalDays,
            double easyIntervalDays,
            int minimumIntervalMinutes,
            double initialHalfLifeDays,
            double minHalfLifeDays,
            double maxHalfLifeDays,
            double learningRate,
            double l2,
            double[] weights,
            List<Integer> learningStepsMinutes,
            List<Integer> relearningStepsMinutes
    ) {
        static HlrConfig from(JsonNode cfg) {
            if (cfg == null || cfg.isNull()) {
                cfg = JsonNodeFactory.instance.objectNode();
            }

            double rr = cfg.path("requestRetention").asDouble(0.9);
            double max = cfg.path("maximumIntervalDays").asDouble(36500);
            double grad = cfg.path("graduatingIntervalDays").asDouble(1);
            double easy = cfg.path("easyIntervalDays").asDouble(4);
            int minMin = cfg.path("minimumIntervalMinutes").asInt(1);
            double initH = cfg.path("initialHalfLifeDays").asDouble(1.0);
            double minH = cfg.path("minHalfLifeDays").asDouble(0.1);
            double maxH = cfg.path("maxHalfLifeDays").asDouble(36500);
            double lr = cfg.path("learningRate").asDouble(0.02);
            double l2 = cfg.path("l2").asDouble(0.0);

            double[] w = toWeights(cfg.path("weights"));

            return new HlrConfig(
                    rr, max, grad, easy, minMin,
                    initH, minH, maxH,
                    lr, l2, w,
                    toIntList(cfg.path("learningStepsMinutes")),
                    toIntList(cfg.path("relearningStepsMinutes"))
            );
        }

        double[] weightsFor(int featureCount) {
            if (weights == null) {
                return new double[featureCount];
            }
            if (weights.length == featureCount) {
                return weights.clone();
            }
            double[] out = new double[featureCount];
            System.arraycopy(weights, 0, out, 0, Math.min(weights.length, featureCount));
            return out;
        }

        private static List<Integer> toIntList(JsonNode n) {
            if (n == null || !n.isArray()) return List.of();
            List<Integer> out = new ArrayList<>();
            for (JsonNode x : n) out.add(x.asInt());
            return out;
        }

        private static double[] toWeights(JsonNode n) {
            if (n == null || !n.isArray()) return null;
            double[] out = new double[n.size()];
            for (int i = 0; i < n.size(); i++) {
                out[i] = n.get(i).asDouble();
            }
            return out;
        }
    }

    private static final class FeatureVector {
        private FeatureVector() {
        }

        static double[] from(JsonNode features, ReviewInput input, double elapsedDays) {
            if (features != null && !features.isNull()) {
                JsonNode direct = features.path("x");
                if (direct != null && direct.isArray() && direct.size() > 0) {
                    return toDoubleArray(direct);
                }
                JsonNode client = features.path("client").path("x");
                if (client != null && client.isArray() && client.size() > 0) {
                    return toDoubleArray(client);
                }
            }

            double reviewCount = Math.max(0, input.reviewCount());
            double isNew = input.lastReviewAt() == null ? 1.0 : 0.0;
            return new double[]{
                    1.0,
                    Math.log1p(reviewCount),
                    isNew,
                    Math.log1p(elapsedDays)
            };
        }

        private static double[] toDoubleArray(JsonNode n) {
            double[] out = new double[n.size()];
            for (int i = 0; i < n.size(); i++) {
                out[i] = n.get(i).asDouble();
            }
            return out;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
