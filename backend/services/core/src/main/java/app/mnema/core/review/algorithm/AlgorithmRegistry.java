package app.mnema.core.review.algorithm;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AlgorithmRegistry {

    private final Map<String, SrsAlgorithm> algorithms;

    public AlgorithmRegistry(List<SrsAlgorithm> list) {
        Map<String, SrsAlgorithm> map = new HashMap<>();
        for (var a : list) map.put(a.id(), a);
        this.algorithms = Map.copyOf(map);
    }

    public SrsAlgorithm require(String id) {
        var a = algorithms.get(id);
        if (a == null) throw new IllegalArgumentException("Unsupported algorithm: " + id);
        return a;
    }
}
