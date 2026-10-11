package app.mnema.identityaccount.profile;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Per-field, revocable consent to a public profile (152-FZ art. 10.1) and the author cards it gates.
 * A card exists only for an ACTIVE account with an enabled consent and a profile username; every other state is
 * indistinguishable from an unknown account.
 */
@Service
public class PublicProfiles {
    /** Date of the approved consent text. A client that shows another text is refused with 409. */
    public static final String TEXT_VERSION = "2026-10-10";
    public static final int MAX_BATCH = 50;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,50}");

    public record Consent(boolean enabled, boolean showDisplayName, boolean showAvatar, boolean showBio,
                          String textVersion, boolean publishReady, java.time.Instant updatedAt) {
    }

    public record Update(boolean enabled, boolean showDisplayName, boolean showAvatar, boolean showBio,
                         String textVersion) {
    }

    public record Card(UUID accountId, String profileUsername, String displayName, String bio,
                       boolean avatarPresent) {
    }

    private record Stored(boolean enabled, boolean showDisplayName, boolean showAvatar, boolean showBio,
                          String textVersion, OffsetDateTime updatedAt) {
    }

    /** The card gate over aliases {@code c} (consent) and {@code a} (account); shared with the avatar read. */
    public static final String CARD_GATE =
            "c.enabled AND a.status='ACTIVE' AND a.deletion_state='ACTIVE' AND a.profile_username IS NOT NULL";

    private static final String CARD_SELECT = """
            SELECT a.account_id,a.profile_username,
                   CASE WHEN c.show_display_name THEN a.display_name END AS display_name,
                   CASE WHEN c.show_bio THEN a.bio END AS bio,
                   (c.show_avatar AND EXISTS(SELECT 1 FROM app_identity.account_avatar v
                                             WHERE v.account_id=a.account_id)) AS avatar_present
            FROM app_identity.public_profile_consent c
            JOIN app_identity.account a ON a.account_id=c.account_id
            WHERE\s""" + CARD_GATE + "\n";

    private final AccountStore accounts;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactions;

    public PublicProfiles(AccountStore accounts, JdbcClient jdbcClient, TransactionTemplate transactions) {
        this.accounts = accounts;
        this.jdbcClient = jdbcClient;
        this.transactions = transactions;
    }

    public Consent consent(AccountAccess access) {
        var account = accounts.require(access, false);
        return view(stored(access.accountId()), account.profileUsername() != null);
    }

    /**
     * Replaces the consent. Withdrawal clears every field flag. An unchanged state writes nothing; every effective
     * change appends one journal row in the same transaction.
     */
    public Consent update(AccountAccess access, Update request) {
        return transactions.execute(status -> {
            // The account row lock serializes concurrent consent changes, profile edits and moderation.
            var account = accounts.require(access, true);
            // Withdrawal is as easy as granting: it never depends on which text the client showed. Only a grant
            // must name the current text, and the journal always records the current version.
            if (request.enabled()) {
                if (!TEXT_VERSION.equals(request.textVersion())) throw new AccountFailure(409, "consent_text_outdated");
                if (account.profileUsername() == null) throw new AccountFailure(409, "profile_username_required");
            }
            boolean enabled = request.enabled();
            boolean name = enabled && request.showDisplayName();
            boolean avatar = enabled && request.showAvatar();
            boolean bio = enabled && request.showBio();
            var current = stored(access.accountId());
            boolean wasEnabled = current.isPresent() && current.get().enabled();
            boolean unchanged = enabled ? wasEnabled && current.get().showDisplayName() == name &&
                    current.get().showAvatar() == avatar && current.get().showBio() == bio &&
                    current.get().textVersion().equals(TEXT_VERSION) : !wasEnabled;
            if (!unchanged) {
                jdbcClient.sql("""
                                INSERT INTO app_identity.public_profile_consent(account_id,enabled,show_display_name,
                                    show_avatar,show_bio,text_version,updated_at)
                                VALUES(:id,:enabled,:name,:avatar,:bio,:version,statement_timestamp())
                                ON CONFLICT(account_id) DO UPDATE SET enabled=:enabled,show_display_name=:name,
                                    show_avatar=:avatar,show_bio=:bio,text_version=:version,
                                    updated_at=statement_timestamp()
                                """)
                        .param("id", access.accountId()).param("enabled", enabled).param("name", name)
                        .param("avatar", avatar).param("bio", bio).param("version", TEXT_VERSION).update();
                jdbcClient.sql("""
                                INSERT INTO app_identity.public_profile_consent_event(account_id,action,enabled,
                                    show_display_name,show_avatar,show_bio,text_version)
                                VALUES(:id,:action,:enabled,:name,:avatar,:bio,:version)
                                """)
                        .param("id", access.accountId())
                        .param("action", !enabled ? "WITHDRAW" : wasEnabled ? "CHANGE" : "GRANT")
                        .param("enabled", enabled).param("name", name).param("avatar", avatar).param("bio", bio)
                        .param("version", TEXT_VERSION).update();
            }
            return view(stored(access.accountId()), account.profileUsername() != null);
        });
    }

    /**
     * Whether the account may publish a public deck: the consent is on and the account is active with a login (the card gate). It is the
     * {@code mnema_public_profile} claim of {@code /userinfo}.
     */
    public boolean publishReady(UUID accountId) {
        return jdbcClient.sql("""
                        SELECT EXISTS(SELECT 1 FROM app_identity.public_profile_consent c JOIN app_identity.account a ON a.account_id=c.account_id
                                      WHERE c.account_id=:id AND\s""" + CARD_GATE + ")").param("id", accountId).query(Boolean.class).single();
    }

    public Optional<Card> card(UUID id) {
        return jdbcClient.sql(CARD_SELECT + " AND c.account_id=:id").param("id", id).query(PublicProfiles::card)
                .optional();
    }

    /** One query for the whole batch; the result keeps the request order and omits non-public ids. */
    public List<Card> cards(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        var found = new HashMap<UUID, Card>();
        jdbcClient.sql(CARD_SELECT + " AND c.account_id=ANY(string_to_array(:ids,',')::uuid[])")
                .param("ids", String.join(",", ids.stream().map(UUID::toString).toList()))
                .query(PublicProfiles::card).list().forEach(card -> found.put(card.accountId(), card));
        return ids.stream().map(found::get).filter(java.util.Objects::nonNull).toList();
    }

    /** Matches the case-insensitive uniqueness of profile usernames; a malformed name cannot exist. */
    public Optional<Card> byUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) return Optional.empty();
        return jdbcClient.sql(CARD_SELECT + " AND a.normalized_profile_username=lower(btrim(:username))")
                .param("username", username).query(PublicProfiles::card).optional();
    }

    private Optional<Stored> stored(UUID id) {
        return jdbcClient.sql("""
                        SELECT enabled,show_display_name,show_avatar,show_bio,text_version,updated_at
                        FROM app_identity.public_profile_consent WHERE account_id=:id
                        """).param("id", id)
                .query((row, number) -> new Stored(row.getBoolean("enabled"), row.getBoolean("show_display_name"),
                        row.getBoolean("show_avatar"), row.getBoolean("show_bio"), row.getString("text_version"),
                        row.getObject("updated_at", OffsetDateTime.class))).optional();
    }

    private static Consent view(Optional<Stored> stored, boolean hasUsername) {
        if (stored.isEmpty()) return new Consent(false, false, false, false, TEXT_VERSION, false, null);
        var s = stored.get();
        return new Consent(s.enabled(), s.showDisplayName(), s.showAvatar(), s.showBio(), s.textVersion(),
                s.enabled() && hasUsername, s.updatedAt().toInstant());
    }

    private static Card card(ResultSet row, int number) throws SQLException {
        return new Card(row.getObject("account_id", UUID.class), row.getString("profile_username"),
                blankToNull(row.getString("display_name")), blankToNull(row.getString("bio")),
                row.getBoolean("avatar_present"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
