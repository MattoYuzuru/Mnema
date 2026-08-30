package app.mnema.learning.platform.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;

@Repository
class CommandReceiptRepository {

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    CommandReceiptRepository(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    void lock(UUID commandId) {
        long lockKey = commandId.getMostSignificantBits() ^ commandId.getLeastSignificantBits();
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, lockKey);
                statement.execute();
            }
            return null;
        });
    }

    Optional<Receipt> find(UUID commandId) {
        return jdbcClient.sql("""
                        SELECT command_id, actor_id, command_scope, command_type, payload_hash, result::text
                        FROM app_learning.command_receipt
                        WHERE command_id = :commandId
                        """)
                .param("commandId", commandId)
                .query((resultSet, rowNumber) -> new Receipt(
                        resultSet.getObject("command_id", UUID.class),
                        resultSet.getObject("actor_id", UUID.class),
                        resultSet.getString("command_scope"),
                        resultSet.getString("command_type"),
                        resultSet.getBytes("payload_hash"),
                        readJson(resultSet.getString("result"))
                ))
                .optional();
    }

    JsonNode insert(CommandIdentity identity, byte[] payloadHash, JsonNode result) {
        String resultJson = writeJson(result);
        String storedResult = jdbcClient.sql("""
                        INSERT INTO app_learning.command_receipt (
                            command_id, actor_id, command_scope, command_type, payload_hash, result
                        ) VALUES (
                            :commandId, :actorId, :scope, :type, :payloadHash, CAST(:result AS jsonb)
                        )
                        RETURNING result::text
                        """)
                .param("commandId", identity.commandId())
                .param("actorId", identity.actorId())
                .param("scope", identity.scope())
                .param("type", identity.type())
                .param("payloadHash", payloadHash)
                .param("result", resultJson)
                .query(String.class)
                .single();
        return readJson(storedResult);
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Command result cannot be serialized", exception);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new DataRetrievalFailureException("Stored command result is not valid JSON", exception);
        }
    }

    record Receipt(
            UUID commandId,
            UUID actorId,
            String scope,
            String type,
            byte[] payloadHash,
            JsonNode result
    ) {

        Receipt {
            payloadHash = payloadHash.clone();
        }

        @Override
        public byte[] payloadHash() {
            return payloadHash.clone();
        }

        boolean matches(CommandIdentity identity, byte[] expectedPayloadHash) {
            return commandId.equals(identity.commandId())
                    && actorId.equals(identity.actorId())
                    && scope.equals(identity.scope())
                    && type.equals(identity.type())
                    && MessageDigest.isEqual(payloadHash, expectedPayloadHash);
        }
    }
}
