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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Component
public class Sm2Algorithm implements SrsAlgorithm {

    private final ObjectMapper om;

    public Sm2Algorithm(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public String id() {
        return "sm2";
    }

    @Override
    public JsonNode initialState(JsonNode effectiveConfig) {
        Sm2Config cfg = Sm2Config.from(effectiveConfig);

        ObjectNode s = om.createObjectNode();
        s.put("phase", "learning");
        s.put("step", 0);
        s.put("ef", cfg.initialEaseFactor);
        s.put("intervalDays", 0.0);
        s.put("repetitions", 0);
        s.put("lapses", 0);
        return s;
    }

    @Override
    public ReviewComputation apply(ReviewInput input, Rating rating, Instant now, JsonNode effectiveConfig) {
        Sm2Config cfg = Sm2Config.from(effectiveConfig);
        Sm2State st = Sm2State.from(input.state());

        String phase = st.phase;

        if ("learning".equals(phase)) {
            return handleLearning(st, cfg, rating, now);
        }

        if ("relearning".equals(phase)) {
            return handleRelearning(st, cfg, rating, now);
        }

        return handleReview(st, cfg, rating, now);
    }

    @Override
    public CanonicalProgress toCanonical(JsonNode state) {
        double ef = state.path("ef").asDouble(2.5);
        double intervalDays = state.path("intervalDays").asDouble(1.0);

        double difficulty01 = clamp01((3.0 - ef) / 1.7);
        double stabilityDays = Math.max(0.1, intervalDays / 0.9);

        return new CanonicalProgress(difficulty01, stabilityDays);
    }

    @Override
    public JsonNode fromCanonical(CanonicalProgress progress, JsonNode effectiveConfig) {
        Sm2Config cfg = Sm2Config.from(effectiveConfig);

        double ef = 3.0 - (progress.difficulty01() * 1.7);
        ef = clamp(ef, cfg.minimumEaseFactor, 2.8);

        double intervalDays = Math.max(1.0, progress.stabilityDays() * 0.9);

        ObjectNode s = om.createObjectNode();
        s.put("phase", "review");
        s.put("step", 0);
        s.put("ef", ef);
        s.put("intervalDays", intervalDays);
        s.put("repetitions", 1);
        s.put("lapses", 0);
        return s;
    }

    private ReviewComputation handleLearning(Sm2State st, Sm2Config cfg, Rating rating, Instant now) {
        List<Integer> steps = cfg.learningStepsMinutes;
        int step = st.step;

        if (steps.isEmpty()) {
            ObjectNode n = st.toJson(om);
            n.put("phase", "review");
            n.put("intervalDays", (rating == Rating.EASY) ? cfg.easyIntervalDays : cfg.graduatingIntervalDays);
            Instant due = now.plus(Math.round(n.get("intervalDays").asDouble()), ChronoUnit.DAYS);
            return new ReviewComputation(n, due, now, 1);
        }

        if (rating == Rating.EASY) {
            ObjectNode n = st.toJson(om);
            n.put("phase", "review");
            n.put("step", 0);
            n.put("intervalDays", cfg.easyIntervalDays);
            Instant due = now.plus((long) cfg.easyIntervalDays, ChronoUnit.DAYS);
            return new ReviewComputation(n, due, now, 1);
        }

        if (rating == Rating.GOOD) step++;
        if (rating == Rating.AGAIN) step = 0;

        if (step >= steps.size()) {
            ObjectNode n = st.toJson(om);
            n.put("phase", "review");
            n.put("step", 0);
            n.put("intervalDays", cfg.graduatingIntervalDays);
            Instant due = now.plus((long) cfg.graduatingIntervalDays, ChronoUnit.DAYS);
            return new ReviewComputation(n, due, now, 1);
        }

        int minutes = Math.max(cfg.minimumIntervalMinutes, steps.get(step));
        ObjectNode n = st.toJson(om);
        n.put("phase", "learning");
        n.put("step", step);

        return new ReviewComputation(n, now.plus(Duration.ofMinutes(minutes)), now, 1);
    }

    private ReviewComputation handleRelearning(Sm2State st, Sm2Config cfg, Rating rating, Instant now) {
        List<Integer> steps = cfg.relearningStepsMinutes;
        int step = st.step;

        if (steps.isEmpty()) {
            return handleReview(st, cfg, rating, now);
        }

        if (rating == Rating.AGAIN) step = 0;
        if (rating == Rating.GOOD) step++;
        if (rating == Rating.EASY) step = steps.size();

        if (step >= steps.size()) {
            ObjectNode n = st.toJson(om);
            n.put("phase", "review");
            n.put("step", 0);

            double oldInterval = Math.max(1.0, n.path("intervalDays").asDouble(1.0));
            double newInterval = Math.max(1.0, Math.round(oldInterval * 0.5));
            n.put("intervalDays", newInterval);

            Instant due = now.plus((long) newInterval, ChronoUnit.DAYS);
            return new ReviewComputation(n, due, now, 1);
        }

        int minutes = Math.max(cfg.minimumIntervalMinutes, steps.get(step));
        ObjectNode n = st.toJson(om);
        n.put("phase", "relearning");
        n.put("step", step);

        return new ReviewComputation(n, now.plus(Duration.ofMinutes(minutes)), now, 1);
    }

    private ReviewComputation handleReview(Sm2State st, Sm2Config cfg, Rating rating, Instant now) {
        ObjectNode n = st.toJson(om);

        double ef = n.path("ef").asDouble(cfg.initialEaseFactor);
        int reps = n.path("repetitions").asInt(0);
        int lapses = n.path("lapses").asInt(0);
        double interval = n.path("intervalDays").asDouble(1.0);

        if (rating == Rating.AGAIN) {
            lapses++;
            ef = Math.max(cfg.minimumEaseFactor, ef - 0.2);

            n.put("ef", ef);
            n.put("lapses", lapses);
            n.put("phase", "relearning");
            n.put("step", 0);

            List<Integer> steps = cfg.relearningStepsMinutes;
            int minutes = steps.isEmpty() ? 10 : Math.max(cfg.minimumIntervalMinutes, steps.getFirst());

            return new ReviewComputation(n, now.plus(Duration.ofMinutes(minutes)), now, 1);
        }

        int q = switch (rating) {
            case HARD -> 3;
            case EASY -> 5;
            default -> 4;
        };

        ef = ef + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02));
        ef = Math.max(cfg.minimumEaseFactor, ef);

        if (reps == 0) {
            interval = 1;
        } else if (reps == 1) {
            interval = 6;
        } else {
            interval = interval * ef * cfg.intervalModifier;
        }

        if (rating == Rating.HARD) {
            interval = interval / cfg.hardFactor;
        } else if (rating == Rating.EASY) {
            interval = interval * cfg.easyBonus;
        }

        interval = clamp(interval, 1.0 / 1440.0 * cfg.minimumIntervalMinutes, cfg.maximumIntervalDays);

        reps++;

        n.put("phase", "review");
        n.put("step", 0);
        n.put("ef", ef);
        n.put("repetitions", reps);
        n.put("intervalDays", interval);

        Instant due = now.plus(Math.round(interval), ChronoUnit.DAYS);
        return new ReviewComputation(n, due, now, 1);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private record Sm2Config(
            List<Integer> learningStepsMinutes,
            List<Integer> relearningStepsMinutes,
            double graduatingIntervalDays,
            double easyIntervalDays,
            double initialEaseFactor,
            double minimumEaseFactor,
            double easyBonus,
            double hardFactor,
            double intervalModifier,
            double maximumIntervalDays,
            int minimumIntervalMinutes
    ) {
        static Sm2Config from(JsonNode cfg) {
            if (cfg == null || cfg.isNull()) cfg = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();

            return new Sm2Config(
                    toIntList(cfg.path("learningStepsMinutes")),
                    toIntList(cfg.path("relearningStepsMinutes")),
                    cfg.path("graduatingIntervalDays").asDouble(1),
                    cfg.path("easyIntervalDays").asDouble(4),
                    cfg.path("initialEaseFactor").asDouble(2.5),
                    cfg.path("minimumEaseFactor").asDouble(1.3),
                    cfg.path("easyBonus").asDouble(1.3),
                    cfg.path("hardFactor").asDouble(1.2),
                    cfg.path("intervalModifier").asDouble(1.0),
                    cfg.path("maximumIntervalDays").asDouble(36500),
                    cfg.path("minimumIntervalMinutes").asInt(1)
            );
        }

        private static List<Integer> toIntList(JsonNode n) {
            if (n == null || !n.isArray()) return List.of();
            List<Integer> out = new ArrayList<>();
            for (JsonNode x : n) out.add(x.asInt());
            return out;
        }
    }

    private record Sm2State(String phase, int step) {
        static Sm2State from(JsonNode state) {
            if (state == null || state.isNull() || !state.isObject()) {
                return new Sm2State("learning", 0);
            }
            String p = state.path("phase").asText("learning");
            int s = state.path("step").asInt(0);
            return new Sm2State(p, s);
        }

        ObjectNode toJson(ObjectMapper om) {
            ObjectNode n = (stateToObjectNode(om));
            n.put("phase", phase);
            n.put("step", step);
            return n;
        }

        private ObjectNode stateToObjectNode(ObjectMapper om) {
            return om.createObjectNode();
        }
    }
}
