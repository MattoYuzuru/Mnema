package app.mnema.learning.platform.json;

import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.BinaryNode;
import tools.jackson.databind.node.DoubleNode;
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

    /**
     * Command receipts persist these digests, so the canonical bytes are a storage contract that
     * must survive library upgrades. The expectation was derived independently of the hasher.
     * Supplementary characters (the emoji below) are persisted as escaped surrogate pairs by the
     * generator that produced every existing receipt; that quirk is part of the contract.
     */
    @Test
    void goldenDigestAndBytesStayStableForAFixedPayload() {
        var payload = new ContentJsonReader(4_096, 16, 512).read(("""
                {"z":[true,null,{"b":2,"a":1.0}],"a":"text","unicode":"é日本🎓",\
                "esc":"line\\n\\\"q\\\"\\\\\\u0001",\
                "num":{"big":9007199254740991,"neg":-12,"dec":0.1234567890123456,"exp":1e-7,"zero":-0.0,"trail":2.50},\
                "empty":{},"arr":[]}
                """).getBytes(StandardCharsets.UTF_8));
        String expected = "{\"a\":\"text\",\"arr\":[],\"empty\":{},"
                + "\"esc\":\"line\\n\\\"q\\\"\\\\\\u0001\","
                + "\"num\":{\"big\":9007199254740991,\"dec\":0.1234567890123456,\"exp\":0.0000001,"
                + "\"neg\":-12,\"trail\":2.5,\"zero\":0},"
                + "\"unicode\":\"é日本\\uD83C\\uDF93\",\"z\":[true,null,{\"a\":1,\"b\":2}]}";

        assertThat(hasher.canonicalBytes(payload)).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        var digest = hasher.hash(payload);
        assertThat(digest.byteLength()).isEqualTo(226);
        assertThat(java.util.HexFormat.of().formatHex(digest.sha256()))
                .isEqualTo("c2af06c8fe21a65877bbe882d92c7f271fd8867fcb049f5569e4dfa797c01631");
    }

    /** Escape rules are part of the stored-digest contract: only these characters are ever escaped. */
    @Test
    void escapesOnlyQuoteBackslashControlsAndSurrogatesInStringsAndKeys() {
        var factory = tools.jackson.databind.node.JsonNodeFactory.instance;
        String value = "\"\\\b\t\n\f\r\u0000\u001f\u007f\u0080\u2028\uffff/\ud83c\udf93\ud800";
        var node = factory.objectNode().put("k" + value, value);

        String escaped = "\\\"\\\\\\b\\t\\n\\f\\r\\u0000\\u001F\u007f\u0080\u2028\uffff/\\uD83C\\uDF93\\uD800";
        assertThat(new String(hasher.canonicalBytes(node), StandardCharsets.UTF_8))
                .isEqualTo("{\"k" + escaped + "\":\"" + escaped + "\"}");
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
