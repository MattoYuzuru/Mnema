package app.mnema.identityaccount.admin;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
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
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties="identity.admin.owner-id=01234567-89ab-4000-8000-0123456789ab")
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

    @BeforeEach void ownerAccount() {
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
        Cookie owner=login();
        var response=mvc.perform(get("/api/accounts/admin/directory").secure(true).cookie(owner).param("query",prefix))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        var body=json.readTree(response.getContentAsString()); assertThat(body.path("accounts")).hasSize(50);
        assertThat(response.getContentAsString()).doesNotContain("password","securityGeneration","providerSubject","token");
        mvc.perform(get("/api/accounts/admin/directory/"+first.accounts().getFirst().accountId()).secure(true).cookie(owner)).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/admin/directory").secure(true).cookie(owner).param("query",prefix,prefix)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts/admin/directory").secure(true).cookie(owner).param("extra","x")).andExpect(status().isBadRequest());
        for(String query:new String[]{"", "a".repeat(101), "a\n"}) assertThatThrownBy(() -> directory.page(query,null,null)).isInstanceOf(AccountFailure.class);
        for(String cursor:new String[]{"bad", "a".repeat(201),""}) assertThatThrownBy(() -> directory.page(null,null,cursor)).isInstanceOf(AccountFailure.class);
        String extreme=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("+999999999-01-01T00:00:00Z|"+OWNER).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> directory.page(null,null,extreme)).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(() -> directory.page(null,"PURGED",null)).isInstanceOf(AccountFailure.class);
    }

    @Test void moderationNeedsActualAdminAndJournalsAtomicSuccessfulActions() throws Exception {
        UUID target=UUID.randomUUID();insert("console-target-"+UUID.randomUUID(),target);
        Cookie owner=login();String route="/api/accounts/admin/accounts/"+target;
        mvc.perform(post(route+"/ban").secure(true).cookie(owner).with(csrf()).contentType("application/json").content("{\"reason\":\"Synthetic reason\"}")).andExpect(status().isForbidden());
        jdbc.sql("UPDATE app_identity.account SET is_admin=true WHERE account_id=:id").param("id",OWNER).update();
        mvc.perform(post(route+"/ban").secure(true).cookie(owner).with(csrf()).contentType("application/json").content("{\"reason\":\"Synthetic reason\"}")).andExpect(status().isNoContent());
        assertThat(directory.account(target).status()).isEqualTo("BANNED");
        assertThat(directory.account(target).banReason()).isEqualTo("Synthetic reason");
        mvc.perform(post(route+"/unban").secure(true).cookie(owner).with(csrf())).andExpect(status().isNoContent());
        assertThat(directory.account(target).status()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.admin_audit WHERE resource_id=:id").param("id",target).query(Long.class).single()).isEqualTo(2);
        mvc.perform(post("/api/accounts/admin/accounts/"+OWNER+"/ban").secure(true).cookie(owner).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        assertThat(directory.audit(null).entries()).isNotEmpty();
        mvc.perform(get("/api/accounts/admin/audit").secure(true).cookie(owner)).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/admin/audit").secure(true).cookie(owner).param("before","bad")).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_identity.admin_audit SET action='BAN' WHERE resource_id=:id").param("id",target).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void emptyConfigurationAndOtherActorsFailClosedAndStaleGenerationIsDenied() throws Exception {
        var actor=new AccountAccess(OWNER,0);
        assertThatThrownBy(() -> new AdminOwnerAccess(accounts,"").require(actor)).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(() -> new AdminOwnerAccess(accounts,UUID.randomUUID().toString()).require(actor)).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(() -> new AdminOwnerAccess(accounts,OWNER.toString()).require(new AccountAccess(OWNER,999))).isInstanceOf(AccountFailure.class);
        for(String value:new String[]{"bad","00000000-0000-0000-0000-000000000000",OWNER.toString().toUpperCase(java.util.Locale.ROOT)})
            assertThatThrownBy(() -> new AdminOwnerAccess(accounts,value)).isInstanceOf(IllegalArgumentException.class);
        mvc.perform(get("/api/accounts/admin/directory").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts/admin/audit").secure(true)).andExpect(status().isUnauthorized());
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/accounts/login").secure(true).with(csrf()).contentType("application/json")
                .content("{\"login\":\"console-owner@example.test\",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
    }
    private void insert(String name,UUID id) {
        jdbc.sql("INSERT INTO app_identity.account(account_id,email,profile_username,display_name) VALUES (:id,:email,:username,'Synthetic')")
                .param("id",id).param("email",name+"@example.test").param("username",id.toString()).update();
    }
}
