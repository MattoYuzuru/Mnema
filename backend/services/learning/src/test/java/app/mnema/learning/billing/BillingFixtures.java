package app.mnema.learning.billing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** The recorded T-Bank exchanges of {@code contracts/billing/tbank} and the fixture terminal they are signed with. No other credential is ever used. */
final class BillingFixtures {
    static final JsonMapper JSON = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    static final String TERMINAL = "1700000000000DEMO";
    static final String PASSWORD = "fixture$Pa55word";
    static final String PASSWORD_BASE64 = Base64.getEncoder().encodeToString(PASSWORD.getBytes(StandardCharsets.UTF_8));
    static final String ORDER_ID = "0199c7a2-3b4e-7c1d-9a2b-5e6f7a8b9c0d";
    static final String PAYMENT_ID = "9407493286";

    private BillingFixtures() { }

    static ObjectNode tbank(String name) {
        return (ObjectNode) read("contracts/billing/tbank/" + name);
    }

    static JsonNode read(String relative) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(relative))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository root");
        try {
            return JSON.readTree(Files.readString(root.resolve(relative)));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** A fixture notification for another order and payment, signed again with the fixture terminal. */
    static ObjectNode notification(String fixture, Object orderId, String paymentId, String status, long amount) {
        ObjectNode body = tbank(fixture);
        body.put("OrderId", orderId.toString());
        body.put("PaymentId", Long.parseLong(paymentId));
        body.put("Status", status);
        body.put("Amount", amount);
        return TBankToken.sign(body, PASSWORD);
    }
}
