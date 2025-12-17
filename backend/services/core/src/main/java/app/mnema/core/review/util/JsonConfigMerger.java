package app.mnema.core.review.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class JsonConfigMerger {

    public JsonNode merge(JsonNode base, JsonNode override) {
        if (override == null || override.isNull()) {
            return (base == null) ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : base;
        }
        if (base == null || base.isNull()) {
            return override;
        }
        if (!base.isObject() || !override.isObject()) {
            return override;
        }

        ObjectNode out = base.deepCopy();
        override.properties().forEach(e -> {
            JsonNode baseChild = out.get(e.getKey());
            JsonNode overrideChild = e.getValue();
            out.set(e.getKey(), merge(baseChild, overrideChild));
        });
        return out;
    }
}
