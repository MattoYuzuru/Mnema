package app.mnema.learning.generation.mbm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code codes.json} is the machine-readable table; {@link MbmCode} must carry exactly the same codes and rules. */
class MbmCodesContractTest {

    @Test
    void enumMatchesCodesJsonInOrderSeverityAndRule() throws IOException {
        JsonNode codes = MbmFixtures.JSON.readTree(Files.readString(MbmFixtures.MBM.resolve("codes.json"))).path("codes");
        List<String> expected = new ArrayList<>();
        codes.forEach(code -> expected.add(code.path("code").stringValue() + "|" + code.path("severity").stringValue()
                + "|" + code.path("rule").stringValue()));
        List<String> actual = Arrays.stream(MbmCode.values())
                .map(code -> code.name() + "|" + code.severity() + "|" + code.rule()).toList();
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void everyFixtureErrorCodeIsKnownAndEveryErrorCodeExceptTheLargeDocumentHasAFixture() throws IOException {
        var covered = new java.util.TreeSet<String>();
        for (String name : MbmFixtures.cases("invalid")) {
            MbmFixtures.json("invalid", name, ".errors.json").forEach(error -> covered.add(error.path("code").stringValue()));
        }
        var errorCodes = Arrays.stream(MbmCode.values()).filter(code -> code.severity() == MbmCode.Severity.ERROR)
                .map(Enum::name).filter(name -> !name.equals("MBM_DOCUMENT_TOO_LARGE")).toList();
        assertThat(covered).containsAll(errorCodes);
    }

    @Test
    void findingValidatesItsFields() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MbmFinding(-1, null, MbmCode.MBM_EMPTY_DOCUMENT, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MbmFinding(1, 0, MbmCode.MBM_EMPTY_DOCUMENT, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new MbmFinding(1, 1, MbmCode.MBM_LINK_NOT_ALLOWED, null).severity()).isEqualTo(MbmCode.Severity.WARNING);
    }
}
