package app.mnema.identityaccount.admin;

import app.mnema.identityaccount.support.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without MNEMA_ADMIN_OWNER_ACCOUNT_ID the console is closed and the earlier administrator moderation behaves as before. */
@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class AdminConsoleDisabledIntegrationTest extends PostgresIntegrationTest {
    private static final String PASSWORD="synthetic-disabled-console-password-42";
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired MockMvc mvc;

    @Test void directoryAndJournalStayClosedWhileAnExistingAdministratorKeepsModerating() throws Exception {
        UUID admin=UUID.randomUUID(),target=UUID.randomUUID();
        account(admin,"disabled-admin-"+admin,true);account(target,"disabled-target-"+target,false);
        Cookie cookie=mvc.perform(post("/api/accounts/login").secure(true).with(csrf()).contentType("application/json")
                .content("{\"login\":\"disabled-admin-"+admin+"@example.test\",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
        mvc.perform(get("/api/accounts/admin/directory").secure(true).cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/api/accounts/admin/audit").secure(true).cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post("/api/accounts/admin/accounts/"+target+"/ban").secure(true).cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"reason\":\"Pre-console moderation\"}")).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT status FROM app_identity.account WHERE account_id=:id").param("id",target).query(String.class).single()).isEqualTo("BANNED");
        mvc.perform(post("/api/accounts/admin/accounts/"+target+"/unban").secure(true).cookie(cookie).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT status FROM app_identity.account WHERE account_id=:id").param("id",target).query(String.class).single()).isEqualTo("ACTIVE");
    }

    private void account(UUID id,String name,boolean admin) {
        jdbc.sql("INSERT INTO app_identity.account(account_id,email,profile_username,display_name,is_admin) VALUES (:id,:email,:username,'Synthetic',:admin)")
                .param("id",id).param("email",name+"@example.test").param("username",id.toString()).param("admin",admin).update();
        jdbc.sql("INSERT INTO app_identity.local_credential(account_id,login_name,password_hash) VALUES (:id,:login,:hash)")
                .param("id",id).param("login","d"+id.toString().replace("-","").substring(0,16)).param("hash",passwords.encode(PASSWORD)).update();
    }
}
