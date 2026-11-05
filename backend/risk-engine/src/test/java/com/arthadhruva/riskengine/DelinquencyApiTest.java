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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The delinquency fields on the term-structure endpoint, through the real filter chain as the application's
 * non-owner database role: old requests unchanged, the fallback explained, impossible input refused naming its field,
 * the delinquency model and its intervals returned, the audit entry naming the model that ran, and the model listed in the
 * inventory with its card.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DelinquencyApiTest extends AbstractIntegrationTest {

    private static final String CSRF = "delinquency-test-csrf-token";

    @Autowired MockMvc mvc;
    @Autowired OrganizationService organizations;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private Organization org;
    private String analyst;

    @BeforeAll
    void account() {
        org = organizations.create("delinq-" + UUID.randomUUID().toString().substring(0, 8), "Delinquency Bank", false, null, null);
        TenantContext.set(org.getId());
        try {
            analyst = jwt.issueSession(users.save(new User(org, "dana", encoder.encode("Correct-Horse-9-Battery"), Role.ANALYST))).token();
        } finally {
            TenantContext.clear();
        }
    }

    private MvcResult call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.cookie(new Cookie(SessionCookie.NAME, analyst)).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static Map<String, Object> loan() {
        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("loanId", "dq-1");
        loan.put("creditScore", 720);
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
        loan.put("propertyState", "TX");
        loan.put("originationMonth", "2023-03");
        return loan;
    }

    private static Map<String, Object> history(int observed, int episodes, Integer cure, String worst) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("monthsObserved", observed);
        h.put("episodes30Plus", episodes);
        h.put("monthsSinceCure", cure);
        h.put("maxStatus12m", worst);
        return h;
    }

    private Map<String, Object> request(Integer dpd, Map<String, Object> delinquency, Map<String, Object> intervals) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("loan", loan());
        request.put("months", 12);
        if (dpd != null) {
            request.put("daysPastDue", dpd);
        }
        if (delinquency != null) {
            request.put("delinquency", delinquency);
        }
        if (intervals != null) {
            request.put("intervals", intervals);
        }
        return request;
    }

    @Test
    void aRequestWithoutTheNewFieldsBehavesAsBefore() throws Exception {
        MvcResult result = call(post("/v1/risk/term-structure"), request(null, null, null));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode b = body(result);
        assertTrue(b.get("modelUsed").asString().startsWith("survival@"));
        assertTrue(b.get("termStructure").get("modelVersion").asString().startsWith("survival@"));
        assertFalse(b.has("intervals"), "no interval unless asked for");
        assertEquals(DelinquencyRouterHolder.NO_DATA, b.get("fallbackReason").asString());
        // the existing response shape, untouched
        assertTrue(b.get("termStructure").has("summary") && b.get("termStructure").has("ecl") && b.get("warnings").isArray());
    }

    @Test
    void aStatusWithIncompleteHistoryUsesTheExistingEngineAndSaysWhy() throws Exception {
        Map<String, Object> incomplete = history(6, 1, null, null);
        MvcResult result = call(post("/v1/risk/term-structure"), request(45, incomplete, null));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode b = body(result);
        assertEquals("history incomplete, delinquency model not used", b.get("fallbackReason").asString());
        assertTrue(b.get("modelUsed").asString().startsWith("survival@"));
        assertEquals(2, b.get("termStructure").get("ecl").get("stage").asInt(), "staging still reflects the delinquency");
    }

    @Test
    void impossibleHistoryIsRefusedNamingTheField() throws Exception {
        MvcResult result = call(post("/v1/risk/term-structure"), request(40, history(3, 5, 1, "D30"), null));
        assertEquals(400, result.getResponse().getStatus());
        JsonNode b = body(result);
        assertEquals("history.episodes30Plus", b.get("field").asString());
        assertTrue(b.get("error").asString().contains("history.episodes30Plus"));
        assertEquals(400, call(post("/v1/risk/term-structure"), request(95, history(12, 1, null, "D60"), null)).getResponse().getStatus());
        assertEquals(400, call(post("/v1/risk/term-structure"), request(40, history(13, 1, 1, "D30"), null)).getResponse().getStatus(),
                "monthsObserved is at most 12");
    }

    @Test
    void theDelinquencyModelRunsWithRangesAndIsAudited() throws Exception {
        Map<String, Object> intervals = new LinkedHashMap<>();
        intervals.put("draws", 30);
        MvcResult result = call(post("/v1/risk/term-structure"), request(35, history(12, 2, 3, "D30"), intervals));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode b = body(result);
        assertTrue(b.get("modelUsed").asString().startsWith("delinquency@"));
        assertFalse(b.has("fallbackReason"));
        JsonNode ranges = b.get("intervals");
        assertEquals("regime uncertainty plus historical calibration error", ranges.get("method").asString());
        assertEquals(30, ranges.get("draws").asInt());
        assertTrue(ranges.get("bandVintages").asInt() >= 4);
        JsonNode pd = ranges.get("pdLifetime");
        assertTrue(pd.get("lower").asDouble() <= pd.get("point").asDouble() && pd.get("point").asDouble() <= pd.get("upper").asDouble());
        assertEquals(b.get("termStructure").get("summary").get("pdLifetime").asDouble(), pd.get("point").asDouble(), 0);

        Map<String, Object> audit = inTenant(() -> jdbc.queryForMap(
                "SELECT model_version, status_code FROM model_invocation_events WHERE tenant_id = ? "
                        + "AND path = '/v1/risk/term-structure' ORDER BY occurred_at DESC LIMIT 1", org.getId()));
        assertTrue(((String) audit.get("model_version")).startsWith("delinquency@"), String.valueOf(audit));
        assertEquals(200, audit.get("status_code"));
    }

    @Test
    void theSameRequestGivesTheSameRangeTwice() throws Exception {
        Map<String, Object> intervals = Map.of("draws", 25);
        JsonNode first = body(call(post("/v1/risk/term-structure"), request(35, history(12, 2, 3, "D30"), intervals))).get("intervals");
        JsonNode second = body(call(post("/v1/risk/term-structure"), request(35, history(12, 2, 3, "D30"), intervals))).get("intervals");
        assertEquals(first, second);
    }

    @Test
    void theModelIsInTheInventoryWithItsCardAndChecksum() throws Exception {
        MvcResult list = call(get("/v1/models"), null);
        assertEquals(200, list.getResponse().getStatus());
        JsonNode entry = null;
        for (JsonNode e : body(list)) {
            if (e.get("id").asString().equals("delinquency")) {
                entry = e;
            }
        }
        assertTrue(entry != null, "the delinquency model is listed");
        assertTrue(entry.get("validated").asBoolean());
        assertEquals(1, entry.get("tier").asInt());
        assertTrue(entry.get("checksumVerified").asBoolean());
        assertEquals(64, entry.get("sha256").asString().length());
        MvcResult card = call(get("/v1/models/delinquency"), null);
        assertEquals(200, card.getResponse().getStatus());
        JsonNode c = body(card);
        assertTrue(c.toString().contains("housing downturn"), "the downturn limitation is stated");
        assertTrue(c.toString().contains("Sparse deep-delinquency"), "the sparse-data limitation is stated");
        assertTrue(c.toString().contains("forbearance"), "the forbearance limitation is stated");
        assertTrue(c.toString().contains("agency-conforming"), "the product scope is stated");
    }

    private static Map<String, Object> statusRequest(Integer dpd, Map<String, Object> delinquency) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("loan", loan());
        if (dpd != null) {
            request.put("daysPastDue", dpd);
        }
        if (delinquency != null) {
            request.put("delinquency", delinquency);
        }
        return request;
    }

    @Test
    void theBareExpectedLossRequestIsUnchanged() throws Exception {
        MvcResult result = call(post("/v1/expected-loss"), loan());
        assertEquals(200, result.getResponse().getStatus());
        JsonNode b = body(result);
        assertFalse(b.has("modelUsed"), "the bare endpoint's response does not gain fields");
        assertFalse(b.has("fallbackReason"));
        assertTrue(b.get("modelVersion").asString().startsWith("survival@"));
    }

    @Test
    void expectedLossFromStatusUsesTheDelinquencyModelAndAgreesWithTheTermStructure() throws Exception {
        Map<String, Object> history = history(12, 2, 3, "D30");
        MvcResult result = call(post("/v1/expected-loss/from-status"), statusRequest(35, history));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode b = body(result);
        assertTrue(b.get("modelUsed").asString().startsWith("delinquency@"));
        assertEquals(b.get("modelUsed").asString(), b.get("modelVersion").asString());
        assertFalse(b.has("fallbackReason"));

        JsonNode t = body(call(post("/v1/risk/term-structure"), request(35, history, null))).get("termStructure");
        assertEquals(t.get("summary").get("pd12m").asDouble(), b.get("pd").asDouble(), 1e-12);
        assertEquals(t.get("summary").get("pdLifetime").asDouble(), b.get("pdLifetime").asDouble(), 1e-12);
        assertEquals(t.get("ecl").get("ecl12m").asDouble(), b.get("expectedLoss").asDouble(), 1e-9);
        assertEquals(t.get("ecl").get("eclLifetime").asDouble(), b.get("expectedLossLifetime").asDouble(), 1e-9);
        assertEquals(t.get("ecl").get("stage").asInt(), b.get("stage").asInt());

        Map<String, Object> audit = inTenant(() -> jdbc.queryForMap(
                "SELECT model_version FROM model_invocation_events WHERE tenant_id = ? "
                        + "AND path = '/v1/expected-loss/from-status' ORDER BY occurred_at DESC LIMIT 1", org.getId()));
        assertTrue(((String) audit.get("model_version")).startsWith("delinquency@"), String.valueOf(audit));
    }

    @Test
    void expectedLossFromStatusWithoutAStatusIsTheBareEndpointAndSaysWhy() throws Exception {
        JsonNode bare = body(call(post("/v1/expected-loss"), loan()));
        MvcResult result = call(post("/v1/expected-loss/from-status"), statusRequest(null, null));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode b = body(result);
        assertTrue(b.get("modelUsed").asString().startsWith("survival@"));
        assertEquals(DelinquencyRouterHolder.NO_DATA, b.get("fallbackReason").asString());
        assertEquals(bare.get("pd").asDouble(), b.get("pd").asDouble(), 0);
        assertEquals(bare.get("expectedLossLifetime").asDouble(), b.get("expectedLossLifetime").asDouble(), 0);
    }

    @Test
    void expectedLossFromStatusRefusesImpossibleInputNamingTheField() throws Exception {
        MvcResult result = call(post("/v1/expected-loss/from-status"), statusRequest(40, history(3, 5, 1, "D30")));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("history.episodes30Plus", body(result).get("field").asString());
        assertEquals(400, call(post("/v1/expected-loss/from-status"), statusRequest(95, history(12, 1, null, "D60"))).getResponse().getStatus());
        Map<String, Object> noLoan = new LinkedHashMap<>();
        noLoan.put("daysPastDue", 35);
        assertEquals(400, call(post("/v1/expected-loss/from-status"), noLoan).getResponse().getStatus(), "the loan is required");
    }

    private <T> T inTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(org.getId());
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** The router's messages, so this test does not restate them. */
    private static final class DelinquencyRouterHolder {
        static final String NO_DATA = com.arthadhruva.riskengine.survival.DelinquencyRouter.NO_DATA;
    }
}
