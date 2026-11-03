package com.arthadhruva.riskengine.config;

import com.arthadhruva.riskengine.AbstractIntegrationTest;
import com.arthadhruva.riskengine.score.LoanCatalogService;
import com.arthadhruva.riskengine.security.JwtService;
import com.arthadhruva.riskengine.security.Role;
import com.arthadhruva.riskengine.security.SessionCookie;
import com.arthadhruva.riskengine.security.User;
import com.arthadhruva.riskengine.security.UserRepository;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Conditional requests: 304 on an unchanged poll, and If-Match as the HTTP form of a case's expected version. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConditionalRequestsTest extends AbstractIntegrationTest {

    private static final String CSRF = "conditional-requests-test-token";

    @Autowired MockMvc mvc;
    @Autowired OrganizationService organizations;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    @Autowired LoanCatalogService catalog;

    private String session;

    @BeforeAll
    void account() {
        Organization org = organizations.create("cond-" + UUID.randomUUID().toString().substring(0, 8), "Cond Bank", false, null, null);
        TenantContext.set(org.getId());
        try {
            session = jwt.issueSession(users.save(new User(org, "cora", encoder.encode("Correct-Horse-9-Battery"), Role.ANALYST))).token();
        } finally {
            TenantContext.clear();
        }
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        request.cookie(new Cookie(SessionCookie.NAME, session), new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
        return mvc.perform(request).andReturn();
    }

    private MvcResult update(String loanId, String ifMatch, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/v1/loans/" + loanId + "/case").contentType(MediaType.APPLICATION_JSON).content(body);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return call(request);
    }

    @Test
    void anUnchangedPollIsAnsweredWith304AndNoBody() throws Exception {
        MvcResult first = call(get("/v1/models"));
        assertEquals(200, first.getResponse().getStatus());
        String etag = first.getResponse().getHeader("ETag");
        assertNotNull(etag);
        assertTrue(etag.startsWith("W/\""), etag);

        MvcResult again = call(get("/v1/models").header("If-None-Match", etag));
        assertEquals(304, again.getResponse().getStatus());
        assertEquals(0, again.getResponse().getContentAsByteArray().length);

        assertEquals(200, call(get("/v1/models").header("If-None-Match", "W/\"something-else\"")).getResponse().getStatus());
        // Not for a client that is not signed in: that is refused before anything is compared.
        assertEquals(401, mvc.perform(get("/v1/models").header("If-None-Match", etag)).andReturn().getResponse().getStatus());
    }

    @Test
    void ifMatchIsTheCasesVersionAndAStaleOneIs412() throws Exception {
        String loanId = catalog.demoLoans().get(1).loanId();
        // A case is created by its first change (and starts at version 0, as the unsaved one reads).
        assertEquals(200, update(loanId, null, "{\"status\":\"REVIEWED\",\"flagged\":false}").getResponse().getStatus());
        MvcResult read = call(get("/v1/loans/" + loanId + "/case"));
        assertEquals(200, read.getResponse().getStatus());
        String etag = read.getResponse().getHeader("ETag");
        assertNotNull(etag);

        MvcResult updated = update(loanId, etag, "{\"status\":\"REVIEWED\",\"assignedTo\":\"cora\",\"flagged\":false}");
        assertEquals(200, updated.getResponse().getStatus(), updated.getResponse().getContentAsString());
        String newer = updated.getResponse().getHeader("ETag");
        assertTrue(!etag.equals(newer), "the version moved on");

        MvcResult stale = update(loanId, etag, "{\"status\":\"REVIEWED\",\"assignedTo\":null,\"flagged\":false}");
        assertEquals(412, stale.getResponse().getStatus());
        assertEquals(newer, stale.getResponse().getHeader("ETag"), "a refusal names the version to retry against");
        assertTrue(stale.getResponse().getContentAsString().contains("\"current\""));

        assertEquals(400, update(loanId, "not-an-etag", "{\"status\":\"REVIEWED\",\"flagged\":false}").getResponse().getStatus());
        assertEquals(400, update(loanId, newer, "{\"status\":\"REVIEWED\",\"flagged\":false,\"expectedVersion\":999}").getResponse().getStatus());
        // The body field keeps working as before, and its conflict is still 409.
        assertEquals(409, update(loanId, null, "{\"status\":\"REVIEWED\",\"flagged\":false,\"expectedVersion\":0}").getResponse().getStatus());
        assertEquals(200, update(loanId, "*", "{\"status\":\"REVIEWED\",\"flagged\":false}").getResponse().getStatus());
    }
}
