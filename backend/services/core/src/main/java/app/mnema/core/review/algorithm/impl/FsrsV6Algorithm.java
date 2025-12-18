package app.mnema.core.review.algorithm.impl;

import app.mnema.core.review.algorithm.CanonicalProgress;
import app.mnema.core.review.algorithm.SrsAlgorithm;
import app.mnema.core.review.domain.Rating;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class FsrsV6Algorithm implements SrsAlgorithm {

    private static final double[] FALLBACK_W = new double[]{
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666,
            0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
    };

    private final ObjectMapper om;

    public FsrsV6Algorithm(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public String id() {
        return "fsrs_v6";
    }

    @Override
    public JsonNode initialState(JsonNode effectiveConfig) {
        ObjectNode o = om.createObjectNode();
        o.put("phase", "learning");
        o.put("step", 0);
        o.put("s", 0.0);
        o.put("d", 0.0);
        return o;
    }

    @Override
    public ReviewComputation apply(ReviewInput input, Rating rating, Instant now, JsonNode effectiveConfig) {
        FsrsConfig cfg = FsrsConfig.from(effectiveConfig);

        FsrsState st = FsrsState.from(input.state());

        if ("learning".equals(st.phase)) {
            return handleLearning(now, rating, st, cfg);
        }
        if ("relearning".equals(st.phase)) {
            return handleRelearning(now, rating, input.lastReviewAt(), st, cfg);
        }

        return handleReview(now, rating, input.lastReviewAt(), st, cfg);
    }

    @Override
    public CanonicalProgress toCanonical(JsonNode state) {
        double d = state.path("d").asDouble(5.0);
        double s = state.path("s").asDouble(1.0);
        double difficulty01 = clamp01((d - 1.0) / 9.0);
        double stabilityDays = Math.max(0.1, s);
        return new CanonicalProgress(difficulty01, stabilityDays);
    }

    @Override
    public JsonNode fromCanonical(CanonicalProgress progress, JsonNode effectiveConfig) {
        ObjectNode o = om.createObjectNode();
        o.put("phase", "review");
        o.put("step", 0);

        double d = 1.0 + 9.0 * progress.difficulty01();
        o.put("d", clamp(d, 1.0, 10.0));
        o.put("s", Math.max(0.1, progress.stabilityDays()));

        return o;
    }

    private ReviewComputation handleLearning(Instant now, Rating rating,
                                             FsrsState st, FsrsConfig cfg) {
        List<Integer> steps = cfg.learningStepsMinutes;
        int step = st.step;

        if (steps.isEmpty()) {
            FsrsState toReview = new FsrsState("review", 0,
                    initialStability(cfg, grade(rating)),
                    initialDifficulty(cfg, grade(rating))
            );
            return graduate(now, toReview, cfg, rating);
        }

        if (rating == Rating.EASY) {
            FsrsState toReview = new FsrsState("review", 0,
                    initialStability(cfg, 4),
                    initialDifficulty(cfg, 4)
            );
            return graduate(now, toReview, cfg, rating);
        }

        if (rating == Rating.GOOD) step++;
        if (rating == Rating.AGAIN) step = 0;

        if (step >= steps.size()) {
            FsrsState toReview = new FsrsState("review", 0,
                    initialStability(cfg, 3),
                    initialDifficulty(cfg, 3)
            );
            return graduate(now, toReview, cfg, rating);
        }

        int minutes = Math.max(cfg.minimumIntervalMinutes, steps.get(step));
        FsrsState next = new FsrsState("learning", step, st.s, st.d);

        return new ReviewComputation(next.toJson(om), now.plus(Duration.ofMinutes(minutes)), now, 1);
    }

    private ReviewComputation handleRelearning(Instant now, Rating rating, Instant lastReviewAt,
                                               FsrsState st, FsrsConfig cfg) {
        List<Integer> steps = cfg.relearningStepsMinutes;
        int step = st.step;

        if (steps.isEmpty()) {
            return handleReview(now, rating, lastReviewAt, st, cfg);
        }

        if (rating == Rating.AGAIN) step = 0;
        if (rating == Rating.GOOD) step++;
        if (rating == Rating.EASY) step = steps.size();

        if (step >= steps.size()) {
            FsrsState toReview = new FsrsState("review", 0, st.s, st.d);
            return graduate(now, toReview, cfg, rating);
        }

        int minutes = Math.max(cfg.minimumIntervalMinutes, steps.get(step));
        FsrsState next = new FsrsState("relearning", step, st.s, st.d);

        return new ReviewComputation(next.toJson(om), now.plus(Duration.ofMinutes(minutes)), now, 1);
    }

    private ReviewComputation handleReview(Instant now, Rating rating, Instant lastReviewAt,
                                           FsrsState st, FsrsConfig cfg) {

        int G = grade(rating);

        if (lastReviewAt == null) {
            FsrsState init = new FsrsState("learning", 0,
                    initialStability(cfg, G),
                    initialDifficulty(cfg, G)
            );
            return handleLearning(now, rating, init, cfg);
        }

        double elapsedDays = Math.max(0.0, Duration.between(lastReviewAt, now).toSeconds() / 86400.0);
        double S = (st.s <= 0.0) ? initialStability(cfg, 3) : st.s;
        double D = (st.d <= 0.0) ? initialDifficulty(cfg, 3) : st.d;

        double R = retrievability(cfg, elapsedDays, S);
        double newD = updateDifficulty(cfg, D, G);

        if (G == 1) {
            double newS = stabilityAfterForgetting(cfg, newD, S, R);
            FsrsState next = new FsrsState("relearning", 0, newS, newD);

            int minutes = cfg.relearningStepsMinutes.isEmpty()
                    ? 10
                    : Math.max(cfg.minimumIntervalMinutes, cfg.relearningStepsMinutes.getFirst());

            return new ReviewComputation(next.toJson(om), now.plus(Duration.ofMinutes(minutes)), now, 1);
        }

        double newS;
        if (elapsedDays < 1.0) {
            newS = stabilitySameDay(cfg, S, G);
        } else {
            newS = stabilityAfterRecall(cfg, newD, S, R, G);
        }

        double intervalDays = intervalFromRetention(cfg, newS, cfg.requestRetention);
        intervalDays = clamp(intervalDays, 1.0 / 1440.0 * cfg.minimumIntervalMinutes, cfg.maximumIntervalDays);

        FsrsState next = new FsrsState("review", 0, newS, newD);
        Instant due = now.plus(Duration.ofSeconds((long) (intervalDays * 86400)));

        return new ReviewComputation(next.toJson(om), due, now, 1);
    }

    private ReviewComputation graduate(Instant now, FsrsState toReview, FsrsConfig cfg, Rating rating) {
        double intervalDays = (rating == Rating.EASY) ? cfg.easyIntervalDays : cfg.graduatingIntervalDays;
        Instant due = now.plus(Duration.ofSeconds((long) (intervalDays * 86400)));
        return new ReviewComputation(toReview.toJson(om), due, now, 1);
    }


    private double retrievability(FsrsConfig cfg, double tDays, double S) {
        double w20 = safeW20(cfg.w[20]);
        double factor = Math.pow(0.9, -1.0 / w20) - 1.0;
        return Math.pow(1.0 + factor * (tDays / S), -w20);
    }

    private double intervalFromRetention(FsrsConfig cfg, double S, double r) {
        double w20 = safeW20(cfg.w[20]);
        double factor = Math.pow(0.9, -1.0 / w20) - 1.0;
        return (S / factor) * (Math.pow(r, -1.0 / w20) - 1.0);
    }

    private double stabilitySameDay(FsrsConfig cfg, double S, int G) {
        double inc = Math.exp(cfg.w[17] * (G - 3.0 + cfg.w[18])) * Math.pow(S, -cfg.w[19]);
        if (G >= 3) inc = Math.max(1.0, inc);
        return Math.max(0.1, S * inc);
    }

    private double stabilityAfterRecall(FsrsConfig cfg, double D, double S, double R, int G) {
        double hardMul = (G == 2) ? cfg.w[15] : 1.0;
        double easyMul = (G == 4) ? cfg.w[16] : 1.0;

        double term = Math.exp(cfg.w[8])
                * (11.0 - D)
                * Math.pow(S, -cfg.w[9])
                * (Math.exp(cfg.w[10] * (1.0 - R)) - 1.0)
                * hardMul
                * easyMul;

        return Math.max(0.1, S * (term + 1.0));
    }

    private double stabilityAfterForgetting(FsrsConfig cfg, double D, double S, double R) {
        double out = cfg.w[11]
                * Math.pow(D, -cfg.w[12])
                * (Math.pow(S + 1.0, cfg.w[13]) - 1.0)
                * Math.exp(cfg.w[14] * (1.0 - R));
        return Math.max(0.1, out);
    }

    private double initialStability(FsrsConfig cfg, int G) {
        int idx = Math.max(0, Math.min(3, G - 1));
        return Math.max(0.1, cfg.w[idx]);
    }

    private double initialDifficulty(FsrsConfig cfg, int G) {
        double d = cfg.w[4] - Math.exp(cfg.w[5] * (G - 1.0)) + 1.0;
        return clamp(d, 1.0, 10.0);
    }

    private double updateDifficulty(FsrsConfig cfg, double D, int G) {
        double delta = -cfg.w[6] * (G - 3.0);
        double d1 = D + delta * (10.0 - D) / 9.0;

        double d0Easy = initialDifficulty(cfg, 4);
        double d2 = cfg.w[7] * d0Easy + (1.0 - cfg.w[7]) * d1;

        return clamp(d2, 1.0, 10.0);
    }

    private int grade(Rating r) {
        return switch (r) {
            case AGAIN -> 1;
            case HARD -> 2;
            case GOOD -> 3;
            case EASY -> 4;
        };
    }

    private static double safeW20(double w20) {
        return (w20 <= 0.0) ? 1.0 : w20;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private record FsrsConfig(
            double requestRetention,
            double maximumIntervalDays,
            double graduatingIntervalDays,
            double easyIntervalDays,
            int minimumIntervalMinutes,
            double[] w,
            List<Integer> learningStepsMinutes,
            List<Integer> relearningStepsMinutes
    ) {
        static FsrsConfig from(JsonNode cfg) {
            if (cfg == null || cfg.isNull())
                cfg = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();

            double rr = cfg.path("requestRetention").asDouble(0.9);
            double max = cfg.path("maximumIntervalDays").asDouble(36500);
            double grad = cfg.path("graduatingIntervalDays").asDouble(1);
            double easy = cfg.path("easyIntervalDays").asDouble(4);
            int minMin = cfg.path("minimumIntervalMinutes").asInt(1);

            double[] w = new double[21];
            JsonNode wj = cfg.path("weights");
            for (int i = 0; i < 21; i++) {
                if (wj != null && wj.isArray() && i < wj.size()) {
                    w[i] = wj.get(i).asDouble();
                } else {
                    w[i] = FALLBACK_W[i];
                }
            }

            return new FsrsConfig(
                    rr, max, grad, easy, minMin, w,
                    toIntList(cfg.path("learningStepsMinutes")),
                    toIntList(cfg.path("relearningStepsMinutes"))
            );
        }

        private static List<Integer> toIntList(JsonNode n) {
            if (n == null || !n.isArray()) return List.of();
            List<Integer> out = new ArrayList<>();
            for (JsonNode x : n) out.add(x.asInt());
            return out;
        }
    }

    private record FsrsState(String phase, int step, double s, double d) {
        static FsrsState from(JsonNode n) {
            if (n == null || n.isNull() || !n.isObject()) {
                return new FsrsState("learning", 0, 0.0, 0.0);
            }
            String phase = n.path("phase").asText("review");
            int step = n.path("step").asInt(0);
            double s = n.path("s").asDouble(0.0);
            double d = n.path("d").asDouble(5.0);
            return new FsrsState(phase, step, s, d);
        }

        JsonNode toJson(ObjectMapper om) {
            ObjectNode o = om.createObjectNode();
            o.put("phase", phase);
            o.put("step", step);
            o.put("s", s);
            o.put("d", d);
            return o;
        }
    }
}
