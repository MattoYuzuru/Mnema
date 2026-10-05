package app.mnema.learning.experiment;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The experiments of {@code learning.experiments.<key>.variants=control:50,other:50} (weights add up to 100) and {@code .enabled}. A disabled
 * experiment gives nobody a variant. Invalid definitions fail at startup: an experiment whose split is wrong would skew a result silently.
 */
@Component
final class ExperimentSettings {
    private static final String PREFIX = "learning.experiments";
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{1,39}");
    private static final Pattern VARIANT = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    static final String CONTROL = "control";

    /** One enabled experiment: its variants with their weights, in bucket order. */
    record Experiment(String key, List<Variant> variants) { }

    record Variant(String name, int weight) { }

    /** Bound from {@code learning.experiments.<key>.*}. */
    public static final class Definition {
        private String variants = "";
        private boolean enabled;

        public String getVariants() { return variants; }

        public void setVariants(String variants) { this.variants = variants; }

        public boolean isEnabled() { return enabled; }

        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    private final Map<String, Experiment> enabled = new LinkedHashMap<>();

    ExperimentSettings(Environment environment) {
        Map<String, Definition> definitions = Binder.get(environment)
                .bind(PREFIX, Bindable.mapOf(String.class, Definition.class)).orElse(Map.of());
        definitions.forEach((key, definition) -> {
            if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid experiment settings: key");
            if (definition.isEnabled()) enabled.put(key, new Experiment(key, parse(definition.getVariants())));
        });
    }

    private static List<Variant> parse(String text) {
        List<Variant> variants = new ArrayList<>();
        int total = 0;
        for (String part : text.split(",")) {
            String[] pair = part.strip().split(":", -1);
            if (pair.length != 2 || !VARIANT.matcher(pair[0]).matches() || !pair[1].matches("[0-9]{1,3}")) {
                throw new IllegalArgumentException("Invalid experiment settings: variants");
            }
            int weight = Integer.parseInt(pair[1]);
            if (weight < 1 || variants.stream().anyMatch(variant -> variant.name().equals(pair[0]))) {
                throw new IllegalArgumentException("Invalid experiment settings: variants");
            }
            variants.add(new Variant(pair[0], weight));
            total += weight;
        }
        if (total != 100 || variants.stream().noneMatch(variant -> variant.name().equals(CONTROL))) {
            throw new IllegalArgumentException("Invalid experiment settings: variants must add up to 100 and include control");
        }
        return List.copyOf(variants);
    }

    /** The enabled experiments by key, in configuration order. */
    Map<String, Experiment> enabled() {
        return Map.copyOf(enabled);
    }

    boolean isEnabled(String key) {
        return enabled.containsKey(key);
    }
}
