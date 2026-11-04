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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Where a stored loan stands now: uploaded beside its origination features, checked by the rule the single-loan API uses,
 * read back by a portfolio run, which projects such a loan with the delinquency model and says how many loans ran on each
 * model. Through the real filter chain, as the application's non-owner database role.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortfolioStatusApiTest extends AbstractIntegrationTest {

    private static final String CSRF = "portfolio-status-csrf-token";

    @Autowired MockMvc mvc;
    @Autowired OrganizationService organizations;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;

    private final ObjectMapper json = new ObjectMapper();
    private String admin;

    @BeforeAll
    void account() {
        Organization org = organizations.create("status-" + UUID.randomUUID().toString().substring(0, 8), "Status Bank", false, null, null);
        TenantContext.set(org.getId());
        try {
            admin = jwt.issueSession(users.save(new User(org, "ada", encoder.encode("Correct-Horse-9-Battery"), Role.ADMIN))).token();
        } finally {
            TenantContext.clear();
        }
    }

    private MvcResult call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.cookie(new Cookie(SessionCookie.NAME, admin)).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static Map<String, Object> loan(String id, int score, String state) {
        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("loanId", id);
        loan.put("creditScore", score);
        loan.put("originalDti", 36.0);
        loan.put("originalUpb", 300000.0);
        loan.put("originalCltv", 80.0);
        loan.put("originalLtv", 80.0);
        loan.put("originalInterestRate", 6.5);
        loan.put("originalLoanTerm", 360);
        loan.put("numberOfBorrowers", 2);
        loan.put("numberOfUnits", 1);
        loan.put("miPercent", 0.0);
        loan.put("occupancyStatus", "P");
        loan.put("propertyType", "SF");
        loan.put("loanPurpose", "P");
        loan.put("channel", "R");
        loan.put("firstTimeHomebuyerFlag", "N");
        loan.put("propertyState", state);
        loan.put("originationMonth", "2023-03");
        return loan;
    }

    private static Map<String, Object> status(Integer dpd, int observed, int episodes, Integer cure, String worst) {
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("monthsObserved", observed);
        history.put("episodes30Plus", episodes);
        history.put("monthsSinceCure", cure);
        history.put("maxStatus12m", worst);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("daysPastDue", dpd);
        status.put("history", history);
        return status;
    }

    private JsonNode finished(String runId) throws Exception {
        JsonNode polled = null;
        for (int i = 0; i < 480; i++) {
            polled = body(call(get("/v1/risk/portfolio/runs/" + runId), null));
            if (!List.of("QUEUED", "RUNNING").contains(polled.get("status").asString())) {
                break;
            }
            Thread.sleep(250);
        }
        return polled;
    }

    private JsonNode baselineDetail() throws Exception {
        for (JsonNode snapshot : body(call(get("/v1/risk/portfolio"), null)).get("snapshots")) {
            if ("BASELINE".equals(snapshot.get("scenario").asString())) {
                return snapshot.get("detail");
            }
        }
        throw new AssertionError("no baseline snapshot");
    }

    private JsonNode runToCompletion() throws Exception {
        MvcResult started = call(post("/v1/risk/portfolio/runs"), null);
        assertEquals(202, started.getResponse().getStatus(), started.getResponse().getContentAsString());
        JsonNode polled = finished(body(started).get("id").asString());
        assertEquals("COMPLETED", polled.get("status").asString(), polled.toString());
        return baselineDetail();
    }

    @Test
    void aLoansStatusIsCheckedStoredAndUsedByTheNextRunUntilItIsReplaced() throws Exception {
        List<Map<String, Object>> loans = new ArrayList<>(List.of(loan("S-30", 700, "TX"), loan("S-60", 680, "CA"),
                loan("S-CURED", 720, "FL"), loan("S-PLAIN", 740, "TX"), loan("S-DEFAULTED", 650, "AZ"), loan("S-INCONSISTENT", 690, "NV")));
        Map<String, Object> statuses = new LinkedHashMap<>();
        statuses.put("S-30", status(35, 12, 1, null, "D30"));
        statuses.put("S-60", status(65, 12, 1, null, "D60"));
        statuses.put("S-CURED", status(0, 12, 1, 3, "D30"));
        statuses.put("S-DEFAULTED", status(100, 12, 1, null, "D60"));          // 90 days or more has defaulted
        statuses.put("S-INCONSISTENT", status(35, 12, 0, null, "CURRENT"));    // late now, yet no spell in the history

        JsonNode upload = body(call(post("/v1/admin/portfolio"), Map.of("loans", loans, "statuses", statuses)));
        assertEquals(4, upload.get("accepted").asInt(), upload.toString());
        assertEquals(2, upload.get("rejected").asInt());
        String defaulted = upload.get("results").get(4).get("error").asString();
        assertTrue(defaulted.startsWith("Invalid status: ") && defaulted.contains("daysPastDue"), defaulted);
        String inconsistent = upload.get("results").get(5).get("error").asString();
        assertTrue(inconsistent.contains("episodes30Plus"), inconsistent);

        JsonNode detail = runToCompletion();
        JsonNode models = detail.get("modelLoans");
        assertEquals(3, loansOn(models, "delinquency@"), models.toString());
        assertEquals(1, loansOn(models, "survival@"), models.toString());
        assertEquals(1, detail.get("fallbackReasons").get("no delinquency data supplied").asInt(), detail.get("fallbackReasons").toString());
        assertEquals(4, detail.get("loans").asInt());

        // the same loan uploaded again without a status: the old one described the loan as it was, so it is cleared
        assertEquals(1, body(call(post("/v1/admin/portfolio"), Map.of("loans", List.of(loan("S-30", 700, "TX")))))
                .get("accepted").asInt());
        JsonNode after = runToCompletion().get("modelLoans");
        assertEquals(2, loansOn(after, "delinquency@"), after.toString());
        assertEquals(2, loansOn(after, "survival@"), after.toString());
    }

    /** Loans a result says ran on the models whose id and version start with {@code idPrefix}. */
    private static int loansOn(JsonNode modelLoans, String idPrefix) {
        return modelLoans.properties().stream().filter(e -> e.getKey().startsWith(idPrefix)).mapToInt(e -> e.getValue().asInt()).sum();
    }

    @Test
    void aStatusForALoanThatIsNotInTheRequestIsRefusedAsAWhole() throws Exception {
        int before = body(call(get("/v1/admin/portfolio"), null)).get("loanCount").asInt();
        MvcResult result = call(post("/v1/admin/portfolio"), Map.of("loans", List.of(loan("T-1", 700, "TX")),
                "statuses", Map.of("T-OTHER", status(35, 12, 1, null, "D30"))));
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(body(result).get("error").asString().contains("T-OTHER"), result.getResponse().getContentAsString());
        // nothing of the batch was stored
        assertEquals(before, body(call(get("/v1/admin/portfolio"), null)).get("loanCount").asInt());
    }
}
