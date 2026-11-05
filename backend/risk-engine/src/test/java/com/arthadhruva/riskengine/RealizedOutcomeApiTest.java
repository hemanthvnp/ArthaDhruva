package com.arthadhruva.riskengine;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Recording outcomes and asking for a comparison are for administrators; reading the latest comparison is for analysts too. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealizedOutcomeApiTest extends AbstractIntegrationTest {

    private static final String CSRF = "outcomes-test-csrf-token";

    @Autowired MockMvc mvc;
    @Autowired OrganizationService organizations;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;

    private final ObjectMapper json = new ObjectMapper();
    private String admin, analyst;

    @BeforeAll
    void accounts() {
        Organization org = organizations.create("outcomes-" + UUID.randomUUID().toString().substring(0, 8), "Outcomes Bank", false, null, null);
        TenantContext.set(org.getId());
        try {
            admin = jwt.issueSession(users.save(new User(org, "ada", encoder.encode("Correct-Horse-9-Battery"), Role.ADMIN))).token();
            analyst = jwt.issueSession(users.save(new User(org, "ana", encoder.encode("Correct-Horse-9-Battery"), Role.ANALYST))).token();
        } finally {
            TenantContext.clear();
        }
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.cookie(new Cookie(SessionCookie.NAME, token)).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static Map<String, Object> batch(Object... entries) {
        return Map.of("outcomes", List.of(entries));
    }

    private static Map<String, Object> entry(String loanId, String outcome, LocalDate on) {
        return Map.of("loanId", loanId, "outcome", outcome, "observedOn", on.toString());
    }

    @Test
    void onlyAdministratorsRecordOutcomesAndAnalystsReadTheReport() throws Exception {
        LocalDate recently = LocalDate.now(ZoneOffset.UTC).minusDays(10);
        Map<String, Object> outcomes = batch(entry("API-1", "DEFAULTED", recently), entry("API-2", "ACTIVE", recently));
        assertEquals(403, call(post("/v1/admin/outcomes"), analyst, outcomes).getResponse().getStatus());
        MvcResult recorded = call(post("/v1/admin/outcomes"), admin, outcomes);
        assertEquals(200, recorded.getResponse().getStatus());
        assertEquals(2, body(recorded).get("recorded").asInt());
        assertEquals(2, body(recorded).get("total").asInt());

        MvcResult overview = call(get("/v1/models/pd_24m/realized"), analyst, null);
        assertEquals(200, overview.getResponse().getStatus());
        assertEquals(2, body(overview).get("recorded").asInt());
        assertTrue(body(overview).get("latest").isNull(), "no comparison has been asked for yet");

        assertEquals(403, call(post("/v1/admin/models/pd_24m/realized"), analyst, null).getResponse().getStatus());
        MvcResult queued = call(post("/v1/admin/models/pd_24m/realized"), admin, null);
        assertEquals(202, queued.getResponse().getStatus());
        assertNotNull(body(queued).get("id"));
        assertEquals("ada", body(queued).get("requestedBy").asString());
    }

    @Test
    void invalidOutcomesAreRefusedNamingTheProblem() throws Exception {
        LocalDate future = LocalDate.now(ZoneOffset.UTC).plusDays(5);
        assertEquals(400, call(post("/v1/admin/outcomes"), admin, batch(entry("F-1", "DEFAULTED", future))).getResponse().getStatus());
        assertEquals(400, call(post("/v1/admin/outcomes"), admin, batch(entry("F-2", "FORECLOSED", LocalDate.now(ZoneOffset.UTC)))).getResponse().getStatus());
        assertEquals(400, call(post("/v1/admin/outcomes"), admin, Map.of("outcomes", List.of())).getResponse().getStatus());
    }
}
