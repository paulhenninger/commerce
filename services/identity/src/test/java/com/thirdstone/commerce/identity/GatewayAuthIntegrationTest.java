package com.thirdstone.commerce.identity;

import com.thirdstone.commerce.identity.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The edge's auth check: what the other services are told about a caller, and when they are told nothing. */
class GatewayAuthIntegrationTest extends IntegrationTest {

    private static final String EMAIL = "shopper@example.com";

    @Test
    void validTokenIsAnsweredWithWhoTheCallerIs() throws Exception {
        Session session = newAccount(EMAIL);
        mvc.perform(as(session, get("/internal/authz")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Auth-User-Id", String.valueOf(session.userId())))
                .andExpect(header().string("X-Auth-Email", EMAIL))
                .andExpect(header().string("X-Auth-Role", "USER"))
                .andExpect(header().string("X-Auth-Strong", "false"));
    }

    @Test
    void noTokenIsUnauthenticatedAndNamesNobody() throws Exception {
        mvc.perform(get("/internal/authz"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("X-Auth-User-Id"));
    }

    @Test
    void garbageTokenIsUnauthenticated() throws Exception {
        mvc.perform(get("/internal/authz").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callerSuppliedIdentityHeadersAreNotEchoed() throws Exception {
        Session session = newAccount(EMAIL);
        mvc.perform(as(session, get("/internal/authz")).header("X-Auth-User-Id", "999").header("X-Auth-Role", "ADMIN"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Auth-User-Id", String.valueOf(session.userId())))
                .andExpect(header().string("X-Auth-Role", "USER"));
    }

    @Test
    void signedOutSessionIsRefusedOnTheNextCheck() throws Exception {
        Session first = newAccount(EMAIL);
        Session second = signIn(EMAIL, PASSWORD);
        mvc.perform(as(second, post("/api/v1/users/me/sessions/revoke-others"))).andExpect(status().isOk());
        mvc.perform(as(first, get("/internal/authz"))).andExpect(status().isUnauthorized());
        mvc.perform(as(second, get("/internal/authz"))).andExpect(status().isNoContent());
    }

    @Test
    void lockedUserIsRefusedOnTheNextCheck() throws Exception {
        Session admin = strongAdmin("admin@example.com");
        Session user = newAccount(EMAIL);
        mvc.perform(as(user, get("/internal/authz"))).andExpect(status().isNoContent());
        mvc.perform(as(admin, put("/api/v1/admin/users/" + user.userId() + "/locked")
                .contentType("application/json").content("{\"locked\":true}"))).andExpect(status().isOk());
        mvc.perform(as(user, get("/internal/authz"))).andExpect(status().isUnauthorized());
    }

    @Test
    void adminRoutesRefuseAnOrdinaryUser() throws Exception {
        Session session = newAccount(EMAIL);
        mvc.perform(as(session, get("/internal/authz").param("require", "admin")))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Auth-Error", "FORBIDDEN"))
                .andExpect(header().doesNotExist("X-Auth-User-Id"));
    }

    @Test
    void adminRoutesRefuseAnAdministratorWithoutASecondFactor() throws Exception {
        Session admin = newAccount("admin@example.com");
        makeAdmin(admin.userId());
        mvc.perform(as(admin, get("/internal/authz").param("require", "admin")))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Auth-Error", "ADMIN_MFA_REQUIRED"))
                .andExpect(header().doesNotExist("X-Auth-User-Id"));
        // The same administrator is still an ordinary signed-in customer.
        mvc.perform(as(admin, get("/internal/authz")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Auth-Role", "ADMIN"));
    }

    @Test
    void adminRoutesAcceptAnAdministratorWithASecondFactor() throws Exception {
        Session admin = strongAdmin("admin@example.com");
        mvc.perform(as(admin, get("/internal/authz").param("require", "admin")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Auth-Role", "ADMIN"))
                .andExpect(header().string("X-Auth-Strong", "true"));
    }

    @Test
    void unknownRequirementFailsClosed() throws Exception {
        Session admin = strongAdmin("admin@example.com");
        mvc.perform(as(admin, get("/internal/authz").param("require", "superuser")))
                .andExpect(status().isForbidden());
    }

    private Session strongAdmin(String address) throws Exception {
        Session admin = newAccount(address);
        makeAdmin(admin.userId());
        jdbc.update("update user_sessions set strong_auth = true where user_id = ?", admin.userId());
        return admin;
    }
}
