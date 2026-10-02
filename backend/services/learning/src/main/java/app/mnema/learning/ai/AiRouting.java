package app.mnema.learning.ai;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The server-owned route table: for every {@link AiRoute} an ordered list of provider and model, resolved against the
 * adapters that exist. Entries of a disabled provider or one without credentials are skipped at call time, so a route
 * lists the preferred order and the environment decides what is usable. With {@code learning.ai.provider=stub} every
 * route is the Stub (and route entries are ignored); a {@code stub} entry in a route is a configuration error.
 */
final class AiRouting {
    private static final Set<String> KNOWN = Set.of("deepseek", "gigachat", "openrouter");

    /** A provider adapter paired with the model to request from it. */
    record Candidate(TextAdapter adapter, String model) {
        String provider() { return adapter.provider(); }

        String key() { return adapter.provider() + ":" + model; }
    }

    private final Map<AiRoute, List<Entry>> entries = new EnumMap<>(AiRoute.class);
    private final Map<String, TextAdapter> adapters;

    private record Entry(String provider, String model) { }

    AiRouting(AiProperties properties, Map<String, TextAdapter> adapters) {
        this.adapters = Map.copyOf(adapters);
        boolean stub = AiProperties.STUB.equals(properties.provider());
        for (AiRoute route : AiRoute.values()) {
            List<Entry> parsed = new ArrayList<>();
            if (stub) {
                parsed.add(new Entry(StubTextAdapter.PROVIDER, StubTextAdapter.PROVIDER));
            } else {
                for (String raw : properties.routes().of(route)) parsed.add(parse(raw, properties));
            }
            entries.put(route, List.copyOf(parsed));
        }
    }

    private static Entry parse(String raw, AiProperties properties) {
        int colon = raw.indexOf(':');
        if (colon < 1 || colon == raw.length() - 1) throw new IllegalArgumentException("A route entry is provider:model");
        String provider = raw.substring(0, colon).strip();
        String model = raw.substring(colon + 1).strip();
        // The Stub is selected only by learning.ai.provider=stub, never through a route entry: a production route cannot
        // be pointed at it by accident.
        if (!KNOWN.contains(provider)) throw new IllegalArgumentException("Unknown provider in a route");
        if (properties.models().stream()
                .noneMatch(price -> price.provider().equals(provider) && price.id().equals(model))) {
            throw new IllegalArgumentException("A route names a model that has no price entry");
        }
        return new Entry(provider, model);
    }

    /** The usable candidates of {@code route}, in order. */
    List<Candidate> candidates(AiRoute route) {
        List<Candidate> out = new ArrayList<>();
        for (Entry entry : entries.get(route)) {
            TextAdapter adapter = adapters.get(entry.provider());
            if (adapter != null && adapter.configured()) out.add(new Candidate(adapter, entry.model()));
        }
        return out;
    }
}
