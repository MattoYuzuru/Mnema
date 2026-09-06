package app.mnema.learning.platform.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentJsonReaderTest {

    private final ContentJsonReader reader = new ContentJsonReader(1_048_576, 64, 100_000);

    @Test
    void preservesUnknownSemanticContentAndExactNumbers() {
        var tree = read("""
                {"type":"future","attrs":{"text":"Русский 日本語 العربية 🎓",
                "precise":0.1234567890123456,
                "integer":9007199254740991,"null":null,"bool":true},"content":[]}
                """);

        assertThat(tree.path("attrs").path("text").textValue()).isEqualTo("Русский 日本語 العربية 🎓");
        assertThat(tree.path("attrs").path("precise").decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.1234567890123456"));
        assertThat(tree.path("attrs").path("integer").bigIntegerValue().toString()).isEqualTo("9007199254740991");
        var canonical = new CanonicalJsonHasher().canonicalBytes(tree);
        assertThat(reader.read(canonical)).isEqualTo(tree);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{} {}", "", " ", "null", "[]", "true", "1", "{\"x\":1,\"x\":2}",
            "{\"nested\":{\"x\":1,\"\\u0078\":2}}", "{\"private-field\":NaN}",
            "{\"private-field\":1e999999999}", "{\"n\":1e-129}", "{\"n\":1e129}",
            "{\"n\":1e-999999999}", "{\"s\":\"\\u0000\"}",
            "{\"\\u0000\":1}", "{\"s\":\"\\ud800\"}", "{\"s\":\"\\udc00\"}",
            "{\"s\":\"\\ud800x\"}", "{\"\\ud800\":1}", "{/*comment*/\"x\":1}",
            "{\"n\":9007199254740992}", "{\"n\":-9007199254740992}",
            "{\"n\":9007199254740993}", "{\"n\":9.007199254740993e15}",
            "{\"n\":0.12345678901234567890123456789}", "{\"n\":1.00000000000000001}"
    })
    void rejectsAmbiguityUnsupportedScalarsAndInvalidUnicodeWithoutLeakingInput(String input) {
        assertThatThrownBy(() -> read(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid content JSON").hasNoCause();
    }

    @Test
    void enforcesUtf8BytesDepthTokensAndNumberLimits() {
        byte[] exact = "{\"s\":\"я\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(new ContentJsonReader(exact.length, 4, 20).read(exact).path("s").textValue()).isEqualTo("я");
        assertThatThrownBy(() -> new ContentJsonReader(exact.length - 1, 4, 20).read(exact))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContentJsonReader(1024, 2, 100).read(bytes("{\"a\":[{}]}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContentJsonReader(1024, 64, 4).read(bytes("{\"a\":[1,2,3]}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> read("{\"n\":" + "9".repeat(257) + "}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}'}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBoundaryNumbersAndSurrogatePairs() {
        var tree = read("{\"a\":9007199254740991,\"b\":1e-128,\"c\":\"\\ud83c\\udf93\",\"d\":0.1}");
        assertThat(tree.path("c").textValue()).isEqualTo("🎓");
        assertThat(new CanonicalJsonHasher().canonicalBytes(tree).length).isLessThan(300);
        assertThat(new CanonicalJsonHasher().canonicalBytes(read("{\"zero\":-0.0}")))
                .isEqualTo(bytes("{\"zero\":0}"));
    }

    @Test
    void rejectsInvalidConfigurationAndMissingInput() {
        assertThatThrownBy(() -> new ContentJsonReader(0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContentJsonReader(1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContentJsonReader(1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(null)).isInstanceOf(NullPointerException.class);
    }

    private com.fasterxml.jackson.databind.JsonNode read(String input) {
        return reader.read(bytes(input));
    }

    private static byte[] bytes(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }
}
