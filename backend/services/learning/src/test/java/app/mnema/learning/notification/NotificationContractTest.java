package app.mnema.learning.notification;

import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Executes {@code contracts/notifications/notifications.json}: every kind's example is published by the real publisher
 * and read back through the real controller, and the wire shapes are compared with the contract's own fixtures.
 */
@SpringBootTest
class NotificationContractTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode CONTRACT = contract();

    @Autowired private NotificationPublisher publisher;
    @Autowired private NotificationController controller;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcClient jdbc;

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void theKindEnumIsExactlyTheContractsKindTableWithItsSeverityRouteAndParams() {
        var contractKinds = new TreeSet<String>();
        CONTRACT.path("kinds").propertyNames().forEach(contractKinds::add);
        assertThat(new TreeSet<>(java.util.Arrays.stream(NotificationKind.values()).map(Enum::name).toList()))
                .isEqualTo(contractKinds);
        CONTRACT.path("kinds").properties().forEach(entry -> {
            NotificationKind kind = NotificationKind.valueOf(entry.getKey());
            JsonNode definition = entry.getValue();
            assertThat(kind.severity().name()).isEqualTo(definition.path("severity").stringValue(null));
            String route = definition.path("route").stringValue(null);
            Map<String, Object> noScope = new HashMap<>();
            if ("DYNAMIC".equals(route)) {
                assertThat(kind.route(noScope)).isEqualTo(NotificationRoute.NONE);
                noScope.put("deckId", UUID.randomUUID());
                assertThat(kind.route(noScope)).isEqualTo(NotificationRoute.DECK);
                noScope.put("sessionId", UUID.randomUUID());
                assertThat(kind.route(noScope)).isEqualTo(NotificationRoute.WORKSHOP);
            } else {
                assertThat(kind.route(noScope).name()).isEqualTo(route);
            }
            var declared = new TreeSet<String>();
            definition.path("params").propertyNames().forEach(declared::add);
            assertThat(new TreeSet<>(kind.params().stream().map(NotificationKind.Param::name).toList())).isEqualTo(declared);
            assertThat(definition.path("example").path("params").size()).isEqualTo(declared.size());
        });
    }

    @Test
    void everyContractExampleIsAcceptedAndComesBackWithTheSameParamsSeverityAndRoute() throws Exception {
        UUID owner = UUID.randomUUID();
        List<String> kinds = new ArrayList<>();
        for (var entry : CONTRACT.path("kinds").properties()) {
            NotificationKind kind = NotificationKind.valueOf(entry.getKey());
            JsonNode example = entry.getValue().path("example");
            Map<String, Object> params = toParams(kind, example.path("params"));
            NotificationRoute route = NotificationRoute.valueOf(example.path("route").stringValue(null));
            Boolean created = new TransactionTemplate(transactions).execute(status -> publisher.publish(owner, kind,
                    "contract:" + kind, params, route));
            assertThat(created).isTrue();
            kinds.add(kind.name());
        }
        JsonNode body = JSON.readTree(mvc(owner).perform(get("/notifications").queryParam("limit", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(5);
        assertThat(propertyNames(body)).isEqualTo(propertyNames(CONTRACT.path("listResponse")));
        assertThat(body.path("items")).hasSize(kinds.size());
        assertThat(body.path("nextCursor").isNull()).isTrue();
        assertThat(body.path("unreadCount").intValue()).isEqualTo(kinds.size());
        assertThat(body.path("readUpto").stringValue(null)).isEqualTo("0");
        assertThat(body.path("activeWork").intValue()).isZero();

        long previous = Long.MAX_VALUE;
        for (JsonNode item : body.path("items")) {
            JsonNode example = CONTRACT.path("kinds").path(item.path("kind").stringValue(null)).path("example");
            assertThat(propertyNames(item)).isEqualTo(propertyNames(CONTRACT.path("listResponse").path("items").get(0)));
            assertThat(item.path("params")).isEqualTo(example.path("params"));
            assertThat(item.path("severity")).isEqualTo(example.path("severity"));
            assertThat(item.path("route")).isEqualTo(example.path("route"));
            long seq = Long.parseLong(item.path("seq").stringValue(null));
            assertThat(seq).isLessThan(previous);
            previous = seq;
            assertThat(Instant.parse(item.path("expiresAt").stringValue(null)))
                    .isEqualTo(Instant.parse(item.path("createdAt").stringValue(null)).plus(30, ChronoUnit.DAYS));
            UuidPolicy.requireEntityId(UUID.fromString(item.path("notificationId").stringValue(null)), "id");
        }

        JsonNode catchUp = JSON.readTree(mvc(owner).perform(get("/notifications").queryParam("after", "6"))
                .andReturn().getResponse().getContentAsString());
        assertThat(catchUp.path("items")).extracting(item -> item.path("seq").stringValue(null)).containsExactly("7", "8");
        assertThat(propertyNames(catchUp)).isEqualTo(propertyNames(CONTRACT.path("listResponseAfter")).stream()
                .filter(name -> !name.equals("note")).collect(java.util.stream.Collectors.toCollection(TreeSet::new)));

        JsonNode read = JSON.readTree(mvc(owner).perform(put("/notifications/read-cursor")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"readUpto\":\"3\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(propertyNames(read)).isEqualTo(propertyNames(CONTRACT.path("readCursorResponse")));
        assertThat(read.path("readUpto").stringValue(null)).isEqualTo("3");
        assertThat(read.path("unreadCount").intValue()).isEqualTo(kinds.size() - 3);

        // the contract's own endpoint table is what the controller serves
        assertThat(CONTRACT.path("endpoints")).extracting(e -> e.path("method").stringValue(null) + " "
                + e.path("path").stringValue(null)).containsExactly("GET /api/notifications",
                "PUT /api/notifications/read-cursor", "DELETE /api/notifications/{notificationId}");
        assertThat(CONTRACT.path("notModifiedExample").path("headers").path("ETag").stringValue(null)).matches("\"[^\"]+\"");
    }

    private MockMvc mvc(UUID owner) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new app.mnema.learning.platform.api.ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    private static Map<String, Object> toParams(NotificationKind kind, JsonNode example) {
        Map<String, Object> params = new HashMap<>();
        for (NotificationKind.Param param : kind.params()) {
            JsonNode value = example.path(param.name());
            params.put(param.name(), value.isNull() ? null : switch (param.type()) {
                case UUID -> UUID.fromString(value.stringValue(null));
                case TIMESTAMP -> Instant.parse(value.stringValue(null));
                case COUNT -> value.intValue();
                case TOKEN -> value.stringValue(null);
            });
        }
        return params;
    }

    private static TreeSet<String> propertyNames(JsonNode node) {
        var names = new TreeSet<String>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static JsonNode contract() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/notifications/notifications.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository root");
        try {
            return JSON.readTree(Files.readString(root.resolve("contracts/notifications/notifications.json")));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
