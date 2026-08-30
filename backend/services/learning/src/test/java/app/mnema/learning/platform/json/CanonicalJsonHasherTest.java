package app.mnema.learning.platform.json;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalJsonHasherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CanonicalJsonHasher hasher = new CanonicalJsonHasher();

    @Test
    void normalizesObjectOrderNestedValuesAndNumberRepresentation() throws Exception {
        var first = objectMapper.readTree("""
                {"z":[true,null,{"b":2,"a":1.0}],"a":"text"}
                """);
        var second = objectMapper.readTree("""
                {"a":"text","z":[true,null,{"a":1,"b":2.00}]}
                """);

        assertThat(hasher.canonicalBytes(first))
                .isEqualTo("{\"a\":\"text\",\"z\":[true,null,{\"a\":1,\"b\":2}]}"
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(hasher.hash(first).sha256()).isEqualTo(hasher.hash(second).sha256());
    }

    @Test
    void preservesArrayOrderAndDefensivelyCopiesDigests() throws Exception {
        var first = hasher.hash(objectMapper.readTree("[1,2]"));
        var second = hasher.hash(objectMapper.readTree("[2,1]"));

        assertThat(first.sha256()).isNotEqualTo(second.sha256());
        byte[] returned = first.sha256();
        returned[0] ^= 1;
        assertThat(first.sha256()).isNotEqualTo(returned);
        assertThat(first.byteLength()).isEqualTo(5);
    }

    @Test
    void usesAConfigurationIndependentUtf8StringEncoding() throws Exception {
        var escapingMapper = JsonMapper.builder()
                .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .build();
        var payload = escapingMapper.readTree("{\"text\":\"é\\n\\u0001\"}");

        assertThat(escapingMapper.writeValueAsString(payload)).contains("\\u00E9");
        assertThat(hasher.canonicalBytes(payload))
                .isEqualTo("{\"text\":\"é\\n\\u0001\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsNullNonFiniteAndNonJsonNodes() {
        assertThatThrownBy(() -> hasher.canonicalBytes(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> hasher.canonicalBytes(DoubleNode.valueOf(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Non-finite");
        assertThatThrownBy(() -> hasher.canonicalBytes(BinaryNode.valueOf(new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported JSON node type");
        assertThatThrownBy(() -> new CanonicalJsonHasher.CanonicalPayload(new byte[31], 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalJsonHasher.CanonicalPayload(new byte[32], -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
