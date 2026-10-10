package app.mnema.identityaccount.admin;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.authorization.AuthorizationConfiguration;
import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.support.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"identity.admin.owner-id=01234567-89ab-4000-8000-0123456789ab","identity.admin-origin=https://admin.mnema.app"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class AdminDirectoryIntegrationTest extends PostgresIntegrationTest {
    private static final UUID OWNER=UUID.fromString("01234567-89ab-4000-8000-0123456789ab");
    private static final String PASSWORD="synthetic-console-password-42";
    @Autowired JdbcClient jdbc;
    @Autowired AdminDirectory directory;
    @Autowired AccountStore accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthorizationConfiguration authorization;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactions;

    @BeforeEach void ownerAccount() {
        // Another test class may have removed the reserved client while its own context started without an admin origin.
        app.mnema.identityaccount.authorization.AdminClientFixture.ensureRegistered(authorization,jdbcTemplate,transactions);
        jdbc.sql("INSERT INTO app_identity.account(account_id,email,profile_username,display_name) VALUES (:id,'console-owner@example.test','console-owner','Console owner') ON CONFLICT(account_id) DO NOTHING").param("id",OWNER).update();
        jdbc.sql("INSERT INTO app_identity.local_credential(account_id,login_name,password_hash) VALUES (:id,'console-owner',:hash) ON CONFLICT(account_id) DO NOTHING")
                .param("id",OWNER).param("hash",passwords.encode(PASSWORD)).update();
        jdbc.sql("UPDATE app_identity.account SET is_admin=false,admin_granted_by=NULL,admin_granted_at=NULL WHERE account_id=:id").param("id",OWNER).update();
    }

    @Test void ownerReadsLiteralBoundedDirectoryAndDetailsWithoutAnAdminGrant() throws Exception {
        String prefix="console-fixture-"+UUID.randomUUID();
        for(int n=0;n<55;n++) insert(prefix+"-"+n,UUID.randomUUID());
        var first=directory.page(prefix,null,null);
        assertThat(first.accounts()).hasSize(50); assertThat(first.next()).isNotNull();
        var last=directory.page(prefix,"ACTIVE",first.next());
        assertThat(last.accounts()).hasSize(5); assertThat(last.next()).isNull();
        assertThat(first.accounts().stream().map(AdminDirectory.Account::accountId).toList()).doesNotContainAnyElementsOf(last.accounts().stream().map(AdminDirectory.Account::accountId).toList());
        assertThat(directory.page("%",null,null).accounts()).isEmpty();
        assertThat(directory.account(first.accounts().getFirst().accountId()).email()).startsWith(prefix);
        assertThatThrownBy(() -> directory.account(UUID.randomUUID())).isInstanceOf(AccountFailure.class);
        String owner=bearer("mnema-admin-web");
        var response=mvc.perform(get("/api/accounts/admin/directory").secure(true).header("Authorization",owner).param("query",prefix))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        var body=json.readTree(response.getContentAsString()); assertThat(body.path("accounts")).hasSize(50);
        assertThat(response.getContentAsString()).doesNotContain("password","securityGeneration","providerSubject","token");
        mvc.perform(get("/api/accounts/admin/directory/"+first.accounts().getFirst().accountId()).secure(true).header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/admin/directory").secure(true).header("Authorization",owner).param("query",prefix,prefix)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts/admin/directory").secure(true).header("Authorization",owner).param("extra","x")).andExpect(status().isBadRequest());
        for(String query:new String[]{"", "a".repeat(101), "a\n"}) assertThatThrownBy(() -> directory.page(query,null,null)).isInstanceOf(AccountFailure.class);
        for(String cursor:new String[]{"bad", "a".repeat(201),""}) assertThatThrownBy(() -> directory.page(null,null,cursor)).isInstanceOf(AccountFailure.class);
        String extreme=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("+999999999-01-01T00:00:00Z|"+OWNER).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> directory.page(null,null,extreme)).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(() -> directory.page(null,"PURGED",null)).isInstanceOf(AccountFailure.class);
    }

    @Test void onlyTheOwnerThroughTheAdminClientReachesDirectoryJournalAndModeration() throws Exception {
        UUID target=UUID.randomUUID();insert("console-client-"+UUID.randomUUID(),target);
        jdbc.sql("UPDATE app_identity.account SET is_admin=true WHERE account_id=:id").param("id",OWNER).update();
        String web=bearer("mnema-web"),admin=bearer("mnema-admin-web");Cookie cookie=login();
        String moderation="/api/accounts/admin/accounts/"+target+"/ban";
        for(String route:new String[]{"/api/accounts/admin/directory","/api/accounts/admin/directory/"+target,"/api/accounts/admin/audit"}) {
            mvc.perform(get(route).secure(true).header("Authorization",web)).andExpect(status().isForbidden());
            mvc.perform(get(route).secure(true).cookie(cookie)).andExpect(status().isForbidden());
            mvc.perform(get(route).secure(true).header("Authorization",admin)).andExpect(status().isOk());
        }
        mvc.perform(post(moderation).secure(true).header("Authorization",web).contentType("application/json").content("{\"reason\":\"Web client\"}")).andExpect(status().isForbidden());
        mvc.perform(post(moderation).secure(true).cookie(cookie).with(csrf()).contentType("application/json").content("{\"reason\":\"Cookie\"}")).andExpect(status().isForbidden());
        assertThat(directory.account(target).status()).isEqualTo("ACTIVE");
        // An admin-client token of an account that is not the configured owner is refused as well.
        UUID stranger=UUID.randomUUID();String strangerName="console-stranger-"+UUID.randomUUID();insert(strangerName,stranger);
        jdbc.sql("INSERT INTO app_identity.local_credential(account_id,login_name,password_hash) VALUES (:id,:login,:hash)")
                .param("id",stranger).param("login","s"+stranger.toString().replace("-","").substring(0,16)).param("hash",passwords.encode(PASSWORD)).update();
        jdbc.sql("UPDATE app_identity.account SET is_admin=true WHERE account_id=:id").param("id",stranger).update();
        String foreign=bearerFor(strangerName+"@example.test","mnema-admin-web");
        String foreignRoute="/api/accounts/admin/directory";
        mvc.perform(get(foreignRoute).secure(true).header("Authorization",foreign)).andExpect(status().isForbidden());
        mvc.perform(post(moderation).secure(true).header("Authorization",foreign).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(moderation).secure(true).header("Authorization",admin).contentType("application/json").content("{\"reason\":\"Owner via the admin client\"}")).andExpect(status().isNoContent());
        assertThat(directory.account(target).status()).isEqualTo("BANNED");
        mvc.perform(post("/api/accounts/admin/accounts/"+target+"/unban").secure(true).header("Authorization",admin)).andExpect(status().isNoContent());
    }

    @Test void moderationNeedsActualAdminAndJournalsReasonOutcomeAndDeniedAttemptsAtomically() throws Exception {
        UUID target=UUID.randomUUID();insert("console-target-"+UUID.randomUUID(),target);
        String owner=bearer("mnema-admin-web");String route="/api/accounts/admin/accounts/"+target;
        mvc.perform(post(route+"/ban").secure(true).header("Authorization",owner).contentType("application/json").content("{\"reason\":\"Synthetic reason\"}")).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.admin_audit WHERE resource_id=:id").param("id",target).query(Long.class).single()).as("a non-admin leaves no journal row").isZero();
        jdbc.sql("UPDATE app_identity.account SET is_admin=true WHERE account_id=:id").param("id",OWNER).update();
        mvc.perform(post(route+"/ban").secure(true).header("Authorization",owner).contentType("application/json").content("{\"reason\":\"Synthetic reason\"}")).andExpect(status().isNoContent());
        assertThat(directory.account(target).status()).isEqualTo("BANNED");
        assertThat(directory.account(target).banReason()).isEqualTo("Synthetic reason");
        mvc.perform(post(route+"/ban").secure(true).header("Authorization",owner).contentType("application/json").content("{\"reason\":\"Again\"}")).andExpect(status().isForbidden());
        mvc.perform(post(route+"/unban").secure(true).header("Authorization",owner)).andExpect(status().isNoContent());
        assertThat(directory.account(target).status()).isEqualTo("ACTIVE");
        assertThat(directory.account(target).banReason()).isNull();
        var rows=jdbc.sql("SELECT action,outcome,reason FROM app_identity.admin_audit WHERE resource_id=:id ORDER BY audit_id").param("id",target)
                .query((rs,n)->rs.getString("action")+"/"+rs.getString("outcome")+"/"+rs.getString("reason")).list();
        assertThat(rows).containsExactly("BAN/SUCCESS/Synthetic reason","BAN/DENIED/null","UNBAN/SUCCESS/null");
        mvc.perform(post("/api/accounts/admin/accounts/"+OWNER+"/ban").secure(true).header("Authorization",owner).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        var audit=directory.audit(null).entries();assertThat(audit).isNotEmpty();
        assertThat(audit.stream().filter(entry->target.equals(entry.resourceId())).map(AdminDirectory.AuditEntry::reason).toList()).contains("Synthetic reason");
        var page=mvc.perform(get("/api/accounts/admin/audit").secure(true).header("Authorization",owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(page).path("entries").get(0).has("outcome")).isTrue();
        mvc.perform(get("/api/accounts/admin/audit").secure(true).header("Authorization",owner).param("before","bad")).andExpect(status().isBadRequest());
        for(String statement:new String[]{"UPDATE app_identity.admin_audit SET action='BAN' WHERE resource_id=:id","DELETE FROM app_identity.admin_audit WHERE resource_id=:id"})
            assertThatThrownBy(() -> jdbc.sql(statement).param("id",target).update()).as(statement).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE app_identity.admin_audit").update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.admin_audit WHERE resource_id=:id").param("id",target).query(Long.class).single()).isEqualTo(3);
    }

    @Test void emptyConfigurationAndOtherActorsFailClosedAndStaleGenerationIsDenied() throws Exception {
        var actor=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(OWNER.toString(),null,java.util.List.of());
        actor.setDetails("0");
        assertThatThrownBy(() -> new AdminOwnerAccess(accounts,"").requireConsole(actor)).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(() -> new AdminOwnerAccess(accounts,UUID.randomUUID().toString()).requireConsole(actor)).isInstanceOf(AccountFailure.class);
        var stale=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(OWNER.toString(),null,java.util.List.of());
        stale.setDetails("999");
        assertThatThrownBy(() -> new AdminOwnerAccess(accounts,OWNER.toString()).requireConsole(stale)).isInstanceOf(AccountFailure.class);
        for(String value:new String[]{"bad","00000000-0000-0000-0000-000000000000",OWNER.toString().toUpperCase(java.util.Locale.ROOT)})
            assertThatThrownBy(() -> new AdminOwnerAccess(accounts,value)).isInstanceOf(IllegalArgumentException.class);
        mvc.perform(get("/api/accounts/admin/directory").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts/admin/audit").secure(true)).andExpect(status().isUnauthorized());
    }

    private Cookie login() throws Exception { return login("console-owner@example.test"); }
    private Cookie login(String email) throws Exception {
        return mvc.perform(post("/api/accounts/login").secure(true).with(csrf()).contentType("application/json")
                .content("{\"login\":\""+email+"\",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
    }
    /** A real S256 authorization-code exchange for the owner, so the token carries the issuing client like production. */
    private String bearer(String client) throws Exception { return bearerFor("console-owner@example.test",client); }
    private String bearerFor(String email,String client) throws Exception {
        Cookie cookie=login(email);
        String verifier="synthetic-console-verifier-0123456789-abcdefghijklmnopqrstuvwxyz";
        String origin=client.equals("mnema-admin-web") ? "https://admin.mnema.app" : "https://mnema.app";
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var redirect=mvc.perform(get("/oauth2/authorize").secure(true).cookie(cookie).queryParam("response_type","code").queryParam("client_id",client)
                .queryParam("redirect_uri",origin+"/auth/callback").queryParam("scope","openid profile account.read account.write").queryParam("state","fixture")
                .queryParam("code_challenge",challenge).queryParam("code_challenge_method","S256")).andExpect(status().is3xxRedirection()).andReturn();
        String code=Arrays.stream(URI.create(redirect.getResponse().getRedirectedUrl()).getRawQuery().split("&")).filter(v->v.startsWith("code="))
                .map(v->URLDecoder.decode(v.substring(5),StandardCharsets.UTF_8)).findFirst().orElseThrow();
        var tokens=json.readTree(mvc.perform(post("/oauth2/token").param("grant_type","authorization_code").param("client_id",client)
                .param("redirect_uri",origin+"/auth/callback").param("code",code).param("code_verifier",verifier)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return "Bearer "+tokens.path("access_token").asString();
    }
    private void insert(String name,UUID id) {
        jdbc.sql("INSERT INTO app_identity.account(account_id,email,profile_username,display_name) VALUES (:id,:email,:username,'Synthetic')")
                .param("id",id).param("email",name+"@example.test").param("username",id.toString()).update();
    }
}
