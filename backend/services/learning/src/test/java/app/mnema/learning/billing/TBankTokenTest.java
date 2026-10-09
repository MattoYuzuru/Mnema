package app.mnema.learning.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** The signature of {@code contracts/billing/README.md}: the two official vectors and every signed fixture of the contract. */
class TBankTokenTest {
    @Test
    void theOfficialVectorsProduceTheirTokens() {
        for (JsonNode vector : BillingFixtures.read("contracts/billing/tbank/token-vectors.json").path("vectors")) {
            ObjectNode fields = (ObjectNode) vector.path("fields").deepCopy();

            assertThat(TBankToken.compute(fields, vector.path("password").stringValue(null))).as(vector.path("name").stringValue(null))
                    .isEqualTo(vector.path("expectedToken").stringValue(null));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"init-request.json", "get-state-request.json", "notification-confirmed.json", "notification-authorized.json",
            "notification-refunded.json", "notification-rejected.json", "notification-amount-mismatch.json", "notification-foreign-terminal.json"})
    void everySignedFixtureVerifiesWithTheFixturePassword(String name) {
        assertThat(TBankToken.verify(BillingFixtures.tbank(name), BillingFixtures.PASSWORD)).as(name).isTrue();
    }

    @Test
    void theForgedFixtureAndAWrongPasswordFail() {
        assertThat(TBankToken.verify(BillingFixtures.tbank("notification-forged-token.json"), BillingFixtures.PASSWORD)).isFalse();
        assertThat(TBankToken.verify(BillingFixtures.tbank("notification-confirmed.json"), "another-password")).isFalse();
    }

    @Test
    void anyChangedSignedMemberBreaksTheToken() {
        for (String member : new String[] {"Amount", "OrderId", "Status", "PaymentId", "Success", "TerminalKey"}) {
            ObjectNode body = BillingFixtures.tbank("notification-confirmed.json");
            switch (member) {
                case "Amount" -> body.put(member, 100);
                case "PaymentId" -> body.put(member, 1L);
                case "Success" -> body.put(member, false);
                default -> body.put(member, "x" + body.path(member).stringValue(null));
            }
            assertThat(TBankToken.verify(body, BillingFixtures.PASSWORD)).as(member).isFalse();
        }
    }

    @Test
    void nestedMembersAndNullsDoNotTakePartButAddedScalarsDo() {
        ObjectNode body = BillingFixtures.tbank("notification-confirmed.json");
        body.putObject("Data").put("Source", "other");
        body.putArray("Receipt").add("x");
        body.putNull("RebillId");
        assertThat(TBankToken.verify(body, BillingFixtures.PASSWORD)).isTrue();

        body.put("Extra", "1");
        assertThat(TBankToken.verify(body, BillingFixtures.PASSWORD)).isFalse();
    }

    @Test
    void aMissingMalformedOrShortTokenNeverVerifies() {
        ObjectNode body = BillingFixtures.tbank("notification-confirmed.json");
        String token = body.path("Token").stringValue(null);
        body.remove("Token");
        assertThat(TBankToken.verify(body, BillingFixtures.PASSWORD)).isFalse();
        body.put("Token", 7);
        assertThat(TBankToken.verify(body, BillingFixtures.PASSWORD)).isFalse();
        body.put("Token", token.substring(1));
        assertThat(TBankToken.verify(body, BillingFixtures.PASSWORD)).isFalse();
        body.put("Token", token.toUpperCase(java.util.Locale.ROOT));
        assertThat(TBankToken.verify(body, BillingFixtures.PASSWORD)).isTrue();
    }

    @Test
    void numbersAreTheirExactTextAndSigningAddsTheToken() throws Exception {
        ObjectNode body = (ObjectNode) BillingFixtures.JSON.readTree("{\"A\":12.50,\"B\":1E+2,\"C\":9007199254740993,\"D\":true}");
        // 12.50 and 1E+2 keep their decimal value as plain text: "12.50" and "100"; the big integer is not rounded through a double.
        ObjectNode text = BillingFixtures.JSON.createObjectNode();
        text.put("A", "12.50").put("B", "100").put("C", "9007199254740993").put("D", "true");

        assertThat(TBankToken.compute(body, "p")).isEqualTo(TBankToken.compute(text, "p"));
        assertThat(TBankToken.sign(body, "p").path("Token").stringValue(null)).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(TBankToken.verify(body, "p")).isTrue();
    }
}
