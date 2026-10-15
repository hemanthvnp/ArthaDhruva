package com.arthadhruva.riskengine;

import com.arthadhruva.riskengine.security.JwtService;
import com.arthadhruva.riskengine.security.Role;
import com.arthadhruva.riskengine.security.TotpSecretCipher;
import com.arthadhruva.riskengine.security.User;
import com.arthadhruva.riskengine.security.UserRepository;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The API end to end, through the real filter chain and against the real stores: authentication,
 * authorization, validation, the models, the asynchronous portfolio run, workflow rules, idempotency,
 * the audit trail and -- with the application connected as its unprivileged database role -- tenant
 * isolation enforced by Postgres itself.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Correct-Horse-9-Battery";
    private static final String CSRF_TEST_TOKEN = "integration-test-csrf-token";

    @Autowired MockMvc mvc;
    @Autowired OrganizationService organizations;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired TotpSecretCipher cipher;
    @Autowired com.arthadhruva.riskengine.score.LoanCatalogService catalog;

    private final ObjectMapper json = new ObjectMapper();
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    private Organization alpha, beta, gamma;
    private String ana, ben, adam, bea, gus;   // session tokens: two alpha analysts, the alpha admin, a beta analyst, the gamma admin

    @BeforeAll
    void accounts() {
        alpha = organizations.create("alpha-" + run, "Alpha Bank", false, null, null);
        beta = organizations.create("beta-" + run, "Beta Bank", false, null, null);
        gamma = organizations.create("gamma-" + run, "Gamma Bank", false, null, null);
        ana = token(alpha, "ana", Role.ANALYST);
        ben = token(alpha, "ben", Role.ANALYST);
        adam = token(alpha, "adam", Role.ADMIN);
        bea = token(beta, "bea", Role.ANALYST);
        gus = token(gamma, "gus", Role.ADMIN);
    }

    private String token(Organization org, String username, Role role) {
        return inTenant(org, () -> jwt.issueSession(users.save(new User(org, username, encoder.encode(PASSWORD), role))).token());
    }

    private <T> T inTenant(Organization org, Supplier<T> work) {
        TenantContext.set(org.getId());
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    private static jakarta.servlet.http.Cookie sessionCookie(String token) {
        return new jakarta.servlet.http.Cookie(com.arthadhruva.riskengine.security.SessionCookie.NAME, token);
    }

    /** The token itself travels only as the Set-Cookie response header now, never the JSON body. */
    private static String sessionTokenFrom(MvcResult result) {
        var cookie = result.getResponse().getCookie(com.arthadhruva.riskengine.security.SessionCookie.NAME);
        assertNotNull(cookie, "expected a Set-Cookie session cookie on the response");
        return cookie.getValue();
    }

    /**
     * Every unsafe request needs the CSRF token now. These tests exercise the endpoints, not the token, so each
     * write carries the real double-submit pair: the XSRF-TOKEN cookie and the same value in X-XSRF-TOKEN
     * (CsrfProtectionTest is the one that exercises the exchange and the refusals). Deliberately NOT Spring's
     * {@code csrf()} test helper: it swaps the shared CsrfFilter's repository for a session-based one and never
     * puts it back, which silently stops every later test class in this Spring context from receiving the cookie.
     */
    private static RequestPostProcessor csrfOnWrites() {
        return request -> {
            String method = request.getMethod();
            boolean safe = method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS") || method.equals("TRACE");
            if (!safe) {
                jakarta.servlet.http.Cookie[] held = request.getCookies() == null ? new jakarta.servlet.http.Cookie[0] : request.getCookies();
                jakarta.servlet.http.Cookie[] all = java.util.Arrays.copyOf(held, held.length + 1);
                all[held.length] = new jakarta.servlet.http.Cookie("XSRF-TOKEN", CSRF_TEST_TOKEN);
                request.setCookies(all);
                request.addHeader("X-XSRF-TOKEN", CSRF_TEST_TOKEN);
            }
            return request;
        };
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.with(csrfOnWrites());
        if (token != null) {
            request.cookie(sessionCookie(token));
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    /** The body of a streamed response: the controller returns before it is written, so the write has to be dispatched. */
    private String download(MockHttpServletRequestBuilder request, String token) throws Exception {
        MvcResult written = mvc.perform(asyncDispatch(call(request, token, null))).andReturn();
        assertEquals(200, status(written));
        return written.getResponse().getContentAsString();
    }

    private JsonNode finished(String runId, String token) throws Exception {
        JsonNode polled = null;
        for (int i = 0; i < 240; i++) {
            polled = body(call(get("/v1/risk/portfolio/runs/" + runId), token, null));
            if (!List.of("QUEUED", "RUNNING").contains(polled.get("status").asString())) {
                break;
            }
            Thread.sleep(250);
        }
        return polled;
    }

    private static Map<String, Object> loan(String loanId, int creditScore, String state, String originationMonth) {
        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("loanId", loanId);
        loan.put("creditScore", creditScore);
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
        loan.put("originationMonth", originationMonth);
        return loan;
    }

    // ---------------------------------------------------------------- authentication and authorization

    @Test
    void callsWithoutASessionOrWithoutTheRoleAreRefused() throws Exception {
        assertEquals(401, status(call(get("/v1/loans"), null, null)));
        assertEquals(401, status(call(get("/v1/loans"), "not-a-token", null)));
        assertEquals(403, status(call(get("/v1/admin/users"), ana, null)), "an analyst is not an admin");
        assertEquals(403, status(call(get("/v1/platform/organizations"), adam, null)), "a tenant admin is not the platform operator");
        assertEquals(200, status(call(get("/v1/admin/users"), adam, null)));
        assertEquals(200, status(call(get("/actuator/health"), null, null)));
    }

    @Test
    void passwordLoginNeedsAFreshSecondFactorAndLogoutEndsTheSession() throws Exception {
        String secret = "JBSWY3DPEHPK3PXP";
        inTenant(alpha, () -> {
            User tina = new User(alpha, "tina", encoder.encode(PASSWORD), Role.ANALYST);
            tina.setTotpSecret(cipher.encrypt(secret));
            tina.setTotpEnabled(true);
            return users.save(tina);
        });
        Map<String, Object> login = new LinkedHashMap<>(Map.of("orgSlug", alpha.getSlug(), "username", "tina", "password", PASSWORD));

        assertEquals(401, status(call(post("/v1/login"), null, login)), "a password alone is not enough");
        login.put("password", "wrong-password");
        login.put("totpCode", code(secret));
        assertEquals(401, status(call(post("/v1/login"), null, login)));

        login.put("password", PASSWORD);
        String code = code(secret);
        login.put("totpCode", code);
        MvcResult ok = call(post("/v1/login"), null, login);
        assertEquals(200, status(ok));
        String session = sessionTokenFrom(ok);
        assertEquals("ANALYST", body(ok).get("role").asString());
        assertNotNull(body(ok).get("sessionExpiresAt"));

        login.put("totpCode", code);
        assertEquals(401, status(call(post("/v1/login"), null, login)), "the same code cannot be used twice");

        assertEquals(200, status(call(get("/v1/loans?limit=1"), session, null)));
        MvcResult refreshed = call(post("/v1/account/session/refresh"), session, null);
        assertEquals(200, status(refreshed));
        String renewed = sessionTokenFrom(refreshed);
        assertEquals(200, status(call(get("/v1/loans?limit=1"), renewed, null)));

        assertEquals(200, status(call(post("/v1/account/logout"), renewed, null)));
        assertEquals(401, status(call(get("/v1/loans?limit=1"), renewed, null)), "logout revokes the session");
        assertEquals(401, status(call(get("/v1/loans?limit=1"), session, null)), "and every token issued before it");
    }

    private static String code(String secret) throws Exception {
        return new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6).generate(secret, System.currentTimeMillis() / 1000 / 30);
    }

    @Test
    void deactivatingAnAccountCutsOffItsLiveSession() throws Exception {
        String carl = token(alpha, "carl", Role.ANALYST);
        assertEquals(200, status(call(get("/v1/loans?limit=1"), carl, null)));
        assertEquals(200, status(call(post("/v1/admin/users/carl/deactivate"), adam, null)));
        assertEquals(401, status(call(get("/v1/loans?limit=1"), carl, null)));
    }

    // ---------------------------------------------------------------- scoring, validation, audit, metering

    @Test
    void aScoreIsExplainedRecordedAuditedAndMetered() throws Exception {
        String loanId = "IT-SCORE-" + run;
        MvcResult scored = call(post("/v1/score"), ana, loan(loanId, 640, "FL", null));
        assertEquals(200, status(scored));
        JsonNode score = body(scored);
        double pd = score.get("calibratedProbability").asDouble();
        assertTrue(pd > 0 && pd < 1);
        assertTrue(score.get("modelVersion").asString().startsWith("pd_24m@"));
        assertEquals(24, score.get("horizonMonths").asInt());
        assertFalse(score.get("explanation").isEmpty());
        assertFalse(score.get("reasonCodes").isEmpty(), "a 640 credit score raises risk above the typical loan");
        double explained = 0;
        for (JsonNode attribution : score.get("explanation")) {
            explained += attribution.get("contribution").asDouble();
        }
        assertEquals(pd - score.get("baselineProbability").asDouble(), explained, 1e-7, "the attribution is complete");
        assertNotNull(scored.getResponse().getHeader("X-RateLimit-Remaining"));

        JsonNode stored = body(call(get("/v1/score/" + loanId), ana, null));
        assertEquals(pd, stored.get("score").get("calibratedProbability").asDouble(), 1e-12);

        Map<String, Object> audit = inTenant(alpha, () -> jdbc.queryForMap(
                "SELECT actor, status_code, model_version, http_method, request_json FROM model_invocation_events "
                        + "WHERE tenant_id = ? AND path = '/v1/score' ORDER BY occurred_at DESC LIMIT 1", alpha.getId()));
        assertEquals("ana", audit.get("actor"));
        assertEquals(200, audit.get("status_code"));
        assertEquals("POST", audit.get("http_method"));
        assertTrue(((String) audit.get("model_version")).startsWith("pd_24m@"));
        assertTrue(((String) audit.get("request_json")).contains(loanId));

        Long metered = inTenant(alpha, () -> jdbc.queryForObject(
                "SELECT quantity FROM usage_record WHERE tenant_id = ? AND metric = 'SCORE_CALL'", Long.class, alpha.getId()));
        assertTrue(metered >= 1);

        // The export is streamed: its body is written after the controller has returned, on a second
        // (ASYNC) pass through the security chain that carries no token.
        String csv = download(get("/v1/loan-scores/export"), ana);
        assertTrue(csv.startsWith("loanId,rawProbability,calibratedProbability,computedAt,modelVersion\r\n"), csv);
        assertTrue(csv.contains(loanId + ","));
        assertFalse(download(get("/v1/loan-scores/export"), bea).contains(loanId), "another organization's export does not contain it");
    }

    /** The application's own database role cannot rewrite history, whatever the application code does. */
    @Test
    void theAuditTrailIsAppendOnlyForTheApplication() throws Exception {
        call(post("/v1/score"), ana, loan(null, 700, "CA", null));
        assertThrows(DataAccessException.class, () -> inTenant(alpha, () ->
                jdbc.update("UPDATE model_invocation_events SET actor = 'someone-else' WHERE tenant_id = ?", alpha.getId())));
        assertThrows(DataAccessException.class, () -> inTenant(alpha, () ->
                jdbc.update("DELETE FROM model_invocation_events WHERE tenant_id = ?", alpha.getId())));
    }

    @Test
    void loansTheModelCannotScoreAreRejectedWithTheReason() throws Exception {
        MvcResult unknownState = call(post("/v1/score"), ana, loan(null, 700, "ZZ", null));
        assertEquals(422, status(unknownState));
        assertTrue(body(unknownState).get("fields").has("property_state"));

        Map<String, Object> missing = loan(null, 700, "CA", null);
        missing.remove("creditScore");
        MvcResult invalid = call(post("/v1/score"), ana, missing);
        assertEquals(400, status(invalid));
        assertTrue(body(invalid).get("fields").has("creditScore"));

        assertEquals(400, status(call(post("/v1/score"), ana, "{ not json")));
        assertEquals(422, status(call(post("/v1/score"), ana, loan(null, 700, "CA", "1989-05"))), "before the market history");
    }

    // ---------------------------------------------------------------- tenant isolation

    @Test
    void oneOrganizationCannotSeeAnothersData() throws Exception {
        String loanId = "IT-ISOLATION-" + run;
        assertEquals(200, status(call(post("/v1/score"), ana, loan(loanId, 720, "TX", null))));
        assertEquals(200, status(call(get("/v1/score/" + loanId), ana, null)));

        assertEquals(404, status(call(get("/v1/score/" + loanId), bea, null)));
        JsonNode betaScores = body(call(get("/v1/loan-scores"), bea, null));
        for (JsonNode score : betaScores) {
            assertNotEquals(loanId, score.get("loanId").asString());
        }
        // Even a query with no tenant filter at all, run on beta's connection, returns nothing of alpha's:
        // the database enforces the boundary, not the application's WHERE clauses.
        Integer visible = inTenant(beta, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM loan_score WHERE loan_id = ?", Integer.class, loanId));
        assertEquals(0, visible);
        Integer own = inTenant(alpha, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM loan_score WHERE loan_id = ?", Integer.class, loanId));
        assertEquals(1, own);
    }

    // ---------------------------------------------------------------- lifetime risk

    @Test
    void termStructureCoversTheLoansLifeAndRespondsToScenarios() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("loan", loan(null, 700, "NV", "2024-11"));
        request.put("months", 24);
        MvcResult result = call(post("/v1/risk/term-structure"), ana, request);
        assertEquals(200, status(result));
        JsonNode t = body(result).get("termStructure");
        assertEquals("BASELINE", t.get("scenario").asString());
        assertEquals(24, t.get("months").size());
        assertEquals(t.get("monthsOnBook").asInt() + t.get("remainingMonths").asInt(), 360);
        JsonNode summary = t.get("summary");
        assertTrue(summary.get("pd12m").asDouble() <= summary.get("pd24m").asDouble());
        assertTrue(summary.get("pd24m").asDouble() <= summary.get("pdLifetime").asDouble());
        assertEquals(1.0, summary.get("pdLifetime").asDouble() + summary.get("prepayLifetime").asDouble()
                + summary.get("maturityProbability").asDouble(), 1e-9);
        assertTrue(t.get("modelVersion").asString().startsWith("survival@"));

        MvcResult compared = call(post("/v1/risk/term-structure/compare"), ana, Map.of("loan", loan(null, 700, "NV", "2024-11")));
        assertEquals(200, status(compared));
        JsonNode scenarios = body(compared).get("scenarios");
        assertEquals(5, scenarios.size());
        Map<String, Double> lifetimeEcl = new LinkedHashMap<>();
        scenarios.forEach(s -> lifetimeEcl.put(s.get("scenario").asString(), s.get("ecl").get("eclLifetime").asDouble()));
        assertTrue(lifetimeEcl.get("BASELINE") < lifetimeEcl.get("ADVERSE"));
        assertTrue(lifetimeEcl.get("ADVERSE") < lifetimeEcl.get("SEVERELY_ADVERSE"));
        assertEquals(120, body(compared).get("months").size());

        // Weights add one probability-weighted block, the weighted sum of the scenarios' own figures; without
        // weights there is no such block.
        assertFalse(body(compared).has("weighted"));
        Map<String, Object> weighting = Map.of("loan", loan(null, 700, "NV", "2024-11"),
                "scenarios", List.of("BASELINE", "ADVERSE", "SEVERELY_ADVERSE"),
                "weights", Map.of("SEVERELY_ADVERSE", 0.2, "baseline", 0.5, "ADVERSE", 0.3));
        JsonNode weighed = body(call(post("/v1/risk/term-structure/compare"), ana, weighting));
        JsonNode weighted = weighed.get("weighted");
        assertEquals("SCENARIO_WEIGHTED", weighted.get("method").asString());
        assertEquals("{\"BASELINE\":0.5,\"ADVERSE\":0.3,\"SEVERELY_ADVERSE\":0.2}", weighted.get("weights").toString());
        double[] share = {0.5, 0.3, 0.2};
        for (String field : List.of("ecl12m", "eclLifetime", "eclIfrs9", "eclCecl", "pd12m", "pdLifetime")) {
            String block = field.startsWith("pd") ? "summary" : "ecl";
            double expected = 0;
            for (int i = 0; i < 3; i++) {
                expected += share[i] * weighed.get("scenarios").get(i).get(block).get(field).asDouble();
            }
            assertEquals(expected, weighted.get(field).asDouble(), 1e-12 * Math.max(1.0, expected), field);
        }
        double stageProbability = 0;
        for (JsonNode p : weighted.get("stageProbability")) {
            stageProbability += p.asDouble();
        }
        assertEquals(1.0, stageProbability, 1e-12);
        assertEquals(lifetimeEcl.get("BASELINE"), weighed.get("scenarios").get(0).get("ecl").get("eclLifetime").asDouble(), 0.0,
                "weighting does not change a scenario's own figures");
        Map<String, Object> shortfall = Map.of("loan", loan(null, 700, "NV", "2024-11"), "scenarios", List.of("BASELINE", "ADVERSE"),
                "weights", Map.of("BASELINE", 0.5, "ADVERSE", 0.3));
        MvcResult badSum = call(post("/v1/risk/term-structure/compare"), ana, shortfall);
        assertEquals(422, status(badSum));
        assertTrue(body(badSum).get("error").asString().contains("must sum to 1"));
        Map<String, Object> elsewhere = Map.of("loan", loan(null, 700, "NV", "2024-11"), "scenarios", List.of("BASELINE", "ADVERSE"),
                "weights", Map.of("BASELINE", 0.5, "RATES_UP_200", 0.5));
        MvcResult notCompared = call(post("/v1/risk/term-structure/compare"), ana, elsewhere);
        assertEquals(422, status(notCompared));
        assertTrue(body(notCompared).get("error").asString().contains("RATES_UP_200"));

        request.put("scenario", "DOOMSDAY");
        assertEquals(422, status(call(post("/v1/risk/term-structure"), ana, request)));
        request.put("scenario", "ADVERSE");
        request.put("monthsOnBook", 12);
        assertEquals(422, status(call(post("/v1/risk/term-structure"), ana, request)), "origination month and age both given");
        assertEquals(5, body(call(get("/v1/risk/scenarios"), ana, null)).size());

        MvcResult expectedLoss = call(post("/v1/expected-loss"), ana, loan(null, 700, "NV", "2024-11"));
        assertEquals(200, status(expectedLoss));
        assertEquals(summary.get("pd12m").asDouble(), body(expectedLoss).get("pd").asDouble(), 1e-12,
                "the one-number endpoint and the term structure agree");
    }

    @Test
    void aPortfolioRunIsStartedPolledAndStored() throws Exception {
        List<Map<String, Object>> loans = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            loans.add(loan("G-" + i, 640 + 15 * i, i % 2 == 0 ? "CA" : "TX", i % 3 == 0 ? null : "2022-0" + (1 + i % 9)));
        }
        loans.add(loan("G-BAD", 700, "ZZ", null));
        JsonNode upload = body(call(post("/v1/admin/portfolio"), gus, Map.of("loans", loans)));
        assertEquals(10, upload.get("accepted").asInt());
        assertEquals(1, upload.get("rejected").asInt());
        assertTrue(upload.get("results").get(10).get("error").asString().contains("property_state"));
        assertEquals(10, body(call(get("/v1/admin/portfolio"), gus, null)).get("loanCount").asInt());

        MvcResult started = call(post("/v1/risk/portfolio/runs"), gus, null);
        assertEquals(202, status(started));
        String runId = body(started).get("id").asString();
        assertEquals("/v1/risk/portfolio/runs/" + runId, started.getResponse().getHeader("Location"));

        JsonNode polled = finished(runId, gus);
        assertEquals("COMPLETED", polled.get("status").asString(), polled.toString());
        assertEquals(10, polled.get("loansDone").asInt());

        JsonNode view = body(call(get("/v1/risk/portfolio"), gus, null));
        assertEquals(3, view.get("snapshots").size());
        assertEquals(10, view.get("portfolioLoans").asInt());
        Map<String, Double> ecl = new LinkedHashMap<>();
        JsonNode baseline = null;
        for (JsonNode snapshot : view.get("snapshots")) {
            assertEquals(10, snapshot.get("loans").asInt());
            assertEquals(120, snapshot.get("detail").get("series").get("expectedLoss").size());
            ecl.put(snapshot.get("scenario").asString(), snapshot.get("eclLifetime").asDouble());
            if ("BASELINE".equals(snapshot.get("scenario").asString())) {
                baseline = snapshot;
            } else {
                assertTrue(snapshot.get("detail").get("lossDistribution").isNull(), "only the baseline carries a loss distribution");
            }
        }
        assertTrue(ecl.get("BASELINE") < ecl.get("SEVERELY_ADVERSE"));
        assertEquals(1, body(call(get("/v1/risk/portfolio/history?scenario=BASELINE"), gus, null)).size());

        // Expected and unexpected loss describe the same loss: the stored distribution is centred on the 12-month ECL.
        JsonNode stored = baseline.get("detail").get("lossDistribution");
        assertEquals(baseline.get("detail").get("ecl12m").asDouble(), stored.get("expectedLoss").asDouble(), 1e-6);
        assertEquals(0.999, stored.get("confidenceLevel").asDouble(), 0.0);
        assertTrue(stored.get("valueAtRisk").asDouble() > stored.get("expectedLoss").asDouble());

        // The stored scenarios weighted into one allowance, without projecting anything again.
        Map<String, Double> probability = Map.of("BASELINE", 0.5, "ADVERSE", 0.3, "SEVERELY_ADVERSE", 0.2);
        JsonNode weightedRun = body(call(post("/v1/risk/portfolio/weighted"), gus, Map.of("weights", probability)));
        double expectedIfrs9 = 0, expectedStage2 = 0;
        for (JsonNode snapshot : view.get("snapshots")) {
            double p = probability.get(snapshot.get("scenario").asString());
            expectedIfrs9 += p * snapshot.get("eclIfrs9").asDouble();
            expectedStage2 += p * snapshot.get("stage2").asDouble();
        }
        assertEquals("SCENARIO_WEIGHTED", weightedRun.get("method").asString());
        assertEquals(expectedIfrs9, weightedRun.get("eclIfrs9").asDouble(), 1e-12 * Math.max(1.0, expectedIfrs9));
        assertEquals(expectedStage2, weightedRun.get("stageLoans").get(1).asDouble(), 1e-12);
        assertEquals(baseline.get("asOf").asString(), weightedRun.get("asOf").asString());
        assertEquals(10, weightedRun.get("loans").asInt());
        assertFalse(weightedRun.get("eclCecl").isNull());
        assertEquals(422, status(call(post("/v1/risk/portfolio/weighted"), gus, Map.of("weights", Map.of("BASELINE", 0.5)))));
        assertEquals(400, status(call(post("/v1/risk/portfolio/weighted"), gus, Map.of())));

        // The listing behind the totals: paged, ordered, filtered by stage, and adding up to the snapshot.
        String gil = token(gamma, "gil", Role.ANALYST);
        JsonNode page = body(call(get("/v1/risk/portfolio/loans?limit=4"), gil, null));
        assertEquals(10, page.get("total").asInt());
        assertEquals(4, page.get("loans").size());
        assertEquals(baseline.get("asOf").asString(), page.get("asOf").asString());
        for (int i = 1; i < 4; i++) {
            assertTrue(page.get("loans").get(i - 1).get("eclLifetime").asDouble() >= page.get("loans").get(i).get("eclLifetime").asDouble());
        }
        JsonNode all = body(call(get("/v1/risk/portfolio/loans?sort=loanId&limit=500"), gil, null));
        assertEquals("G-0", all.get("loans").get(0).get("loanId").asString());
        double listedEcl = 0;
        for (JsonNode loan : all.get("loans")) {
            listedEcl += loan.get("eclLifetime").asDouble();
            assertEquals(1.0, loan.get("weight").asDouble(), 0.0);
        }
        assertEquals(ecl.get("BASELINE"), listedEcl, 0.01);
        int staged = 0;
        for (int stage = 1; stage <= 3; stage++) {
            int inStage = body(call(get("/v1/risk/portfolio/loans?stage=" + stage), gil, null)).get("total").asInt();
            assertEquals(baseline.get("stage" + stage).asInt(), inStage);
            staged += inStage;
        }
        assertEquals(10, staged);
        assertEquals(400, status(call(get("/v1/risk/portfolio/loans?sort=tenant_id"), gil, null)), "the order is chosen from a fixed list");
        assertEquals(400, status(call(get("/v1/risk/portfolio/loans?stage=4"), gil, null)));

        String csv = download(get("/v1/risk/portfolio/loans/export"), gil);
        String[] lines = csv.split("\r\n");
        assertEquals("loanId,state,exposure,pd12m,pdLifetime,lgd,ecl12m,eclLifetime,eclIfrs9,stage,stageReason,weight", lines[0]);
        assertEquals(11, lines.length);
        assertTrue(lines[1].startsWith("G-0,CA,"));

        // The loss distribution is simulated from those loans, at the caller's parameters, reproducibly.
        Map<String, Object> lossRequest = Map.of("confidenceLevel", 0.99, "numScenarios", 5000, "seed", 7);
        JsonNode loss = body(call(post("/v1/risk/portfolio/loss-distribution"), gil, lossRequest));
        assertEquals(10, loss.get("loans").asInt());
        assertFalse(loss.get("sampled").asBoolean());
        assertEquals(stored.get("expectedLoss").asDouble(), loss.get("result").get("expectedLoss").asDouble(), 1e-6);
        assertEquals(loss.get("result").get("valueAtRisk").asDouble(),
                body(call(post("/v1/risk/portfolio/loss-distribution"), gil, lossRequest)).get("result").get("valueAtRisk").asDouble(), 0.0);
        assertTrue(loss.get("result").get("valueAtRisk").asDouble() <= stored.get("valueAtRisk").asDouble(),
                "a one-in-a-hundred loss is no worse than a one-in-a-thousand one");
        assertEquals(400, status(call(post("/v1/risk/portfolio/loss-distribution"), gil, Map.of("confidenceLevel", 1.5))));

        // Another organization can see neither the run nor its results, and has nothing to simulate.
        assertEquals(404, status(call(get("/v1/risk/portfolio/runs/" + runId), bea, null)));
        assertTrue(body(call(get("/v1/risk/portfolio"), bea, null)).get("snapshots").isEmpty());
        assertEquals(0, body(call(get("/v1/risk/portfolio/loans"), bea, null)).get("total").asInt());
        assertEquals(422, status(call(post("/v1/risk/portfolio/loss-distribution"), bea, null)));
        assertEquals(404, status(call(post("/v1/risk/portfolio/weighted"), bea, Map.of("weights", Map.of("BASELINE", 1.0)))));

        // One active run per organization: a second request is answered with the run in progress...
        UUID active = UUID.randomUUID();
        inTenant(gamma, () -> jdbc.update("INSERT INTO portfolio_risk_run (id, tenant_id, requested_by, status, scenarios, loans_total) "
                + "VALUES (?, ?, 'gus', 'RUNNING', 'BASELINE', 10)", active, gamma.getId()));
        MvcResult conflict = call(post("/v1/risk/portfolio/runs"), gus, null);
        assertEquals(409, status(conflict));
        assertEquals(active.toString(), body(conflict).get("id").asString());
        // ...unless its worker died: a stale heartbeat no longer blocks, and reads as failed.
        inTenant(gamma, () -> jdbc.update("UPDATE portfolio_risk_run SET heartbeat_at = now() - interval '10 minutes' WHERE id = ?", active));
        assertEquals("FAILED", body(call(get("/v1/risk/portfolio/runs/" + active), gus, null)).get("status").asString());

        // A run always includes the baseline, and replaces everything stored for its date: the severely
        // adverse snapshot of the first run does not linger beside the figures of the second.
        MvcResult rerun = call(post("/v1/risk/portfolio/runs"), gus, Map.of("scenarios", List.of("ADVERSE")));
        assertEquals(202, status(rerun));
        assertEquals("[\"BASELINE\",\"ADVERSE\"]", body(rerun).get("scenarios").toString());
        assertEquals("COMPLETED", finished(body(rerun).get("id").asString(), gus).get("status").asString());
        JsonNode after = body(call(get("/v1/risk/portfolio"), gus, null));
        assertEquals(2, after.get("snapshots").size());
        MvcResult uncovered = call(post("/v1/risk/portfolio/weighted"), gus, Map.of("weights", Map.of("BASELINE", 0.8, "SEVERELY_ADVERSE", 0.2)));
        assertEquals(422, status(uncovered), "the second run did not cover the severely adverse scenario");
        assertTrue(body(uncovered).get("error").asString().contains("SEVERELY_ADVERSE"));
        assertEquals(1, body(call(get("/v1/risk/portfolio/history?scenario=BASELINE"), gus, null)).size(), "same date, one snapshot");
        assertEquals(0, body(call(get("/v1/risk/portfolio/history?scenario=SEVERELY_ADVERSE"), gus, null)).size());
        assertEquals(10, body(call(get("/v1/risk/portfolio/loans"), gus, null)).get("total").asInt());
    }

    // ---------------------------------------------------------------- attachment download links (valet key)

    @Test
    void aDownloadLinkGivesExactlyOneAttachmentForAShortTimeWithoutASession() throws Exception {
        String loanId = catalog.demoLoans().get(0).loanId();
        byte[] content = "Reviewed: income verified.".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MvcResult uploaded = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/v1/loans/" + loanId + "/attachments")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "review.txt", "text/plain", content))
                .with(csrfOnWrites()).cookie(sessionCookie(ana))).andReturn();
        assertEquals(201, status(uploaded));
        long attachmentId = body(uploaded).get("id").asLong();
        String linkPath = "/v1/loans/" + loanId + "/attachments/" + attachmentId + "/download-link";
        try {
            // Asking for a link is subject to the same access rule and tenant boundary as reading the file.
            assertEquals(401, status(call(post(linkPath), null, null)));
            assertEquals(404, status(call(post(linkPath), bea, null)), "another organization's analyst has no such attachment");
            JsonNode link = body(call(post(linkPath), ana, null));
            assertEquals(60, link.get("expiresInSeconds").asInt());
            String token = link.get("downloadToken").asString();

            // The token alone fetches the file: no session cookie on this request.
            MvcResult fetched = mvc.perform(get(link.get("path").asString()).param("token", token)).andReturn();
            assertEquals(200, status(fetched));
            assertEquals("Reviewed: income verified.", fetched.getResponse().getContentAsString());
            assertTrue(fetched.getResponse().getHeader("Content-Disposition").startsWith("attachment"));
            assertEquals("no-store", fetched.getResponse().getHeader("Cache-Control"));

            // It is a credential for that file only: not a session, and nothing else is accepted in its place.
            assertEquals(401, status(call(get("/v1/loans"), token, null)), "a link token is never a session");
            assertEquals(403, status(mvc.perform(get("/v1/attachment-downloads").param("token", ana)).andReturn()),
                    "a session token is not a download link");
            assertEquals(403, status(mvc.perform(get("/v1/attachment-downloads").param("token", token + "x")).andReturn()));
            String expired = jwt.issueClaimsToken("attachment-download", java.time.Duration.ofSeconds(-5),
                    Map.of("org", alpha.getId(), "loan", loanId, "att", attachmentId));
            assertEquals(403, status(mvc.perform(get("/v1/attachment-downloads").param("token", expired)).andReturn()));

            // The audit trail records who asked for the link, never the link itself.
            String audited = inTenant(alpha, () -> jdbc.queryForObject("SELECT response_json FROM model_invocation_events "
                    + "WHERE tenant_id = ? AND endpoint = 'LoanAttachmentController.downloadLink' AND actor = 'ana' "
                    + "ORDER BY occurred_at DESC LIMIT 1", String.class, alpha.getId()));
            assertFalse(audited.contains(token));
        } finally {
            assertEquals(204, status(call(delete("/v1/admin/loans/" + loanId + "/attachments/" + attachmentId), adam, null)));
        }
        // Once the attachment is gone, a link issued for it leads nowhere.
        String stale = jwt.issueClaimsToken("attachment-download", java.time.Duration.ofSeconds(60),
                Map.of("org", alpha.getId(), "loan", loanId, "att", attachmentId));
        assertEquals(404, status(mvc.perform(get("/v1/attachment-downloads").param("token", stale)).andReturn()));
    }

    // ---------------------------------------------------------------- region analysis (rough-set three-way decision)

    private long decisionMembersUsed(Organization org) {
        return inTenant(org, () -> jdbc.query("SELECT quantity FROM usage_record WHERE tenant_id = ? AND metric = 'DECISION_MEMBER'",
                rs -> rs.next() ? rs.getLong(1) : 0L, org.getId()));
    }

    @Test
    void aDecisionIsOptionalReproducibleLabelledAndMetered() throws Exception {
        Organization eta = organizations.create("eta-" + run, "Eta Bank", false, null, null);
        String eva = token(eta, "eva", Role.ANALYST);
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("loan", loan(null, 700, "NV", "2024-11"));
        plain.put("scenario", "ADVERSE");
        plain.put("months", 6);

        MvcResult without = call(post("/v1/risk/term-structure"), eva, plain);
        assertEquals(200, status(without));
        assertFalse(body(without).has("decision"), "a request without a decision block gets the response it always did");
        assertEquals(0L, decisionMembersUsed(eta));

        double pd = body(without).get("termStructure").get("summary").get("pdLifetime").asDouble();
        Map<String, Object> withDecision = new LinkedHashMap<>(plain);
        withDecision.put("decision", Map.of("metric", "PD_LIFETIME", "threshold", pd,
                "granule", Map.of("members", 8)));
        MvcResult first = call(post("/v1/risk/term-structure"), eva, withDecision);
        assertEquals(200, status(first));
        JsonNode d = body(first).get("decision");
        assertEquals(8, d.get("members").asInt());
        assertEquals(0.0, d.get("tolerance").asDouble(), 0.0, "defaults are applied and echoed");
        assertEquals(0.2, d.get("shockBand").asDouble(), 0.0);
        assertEquals(3, d.get("timingBandMonths").asInt());
        assertEquals("PERTURBATION", d.get("granuleKind").asString());
        assertTrue(d.get("method").asString().contains("not a probability"));
        assertEquals(pd, d.get("baseMetric").asDouble(), 1e-12, "member 0 is the unperturbed projection");
        assertTrue(d.get("min").asDouble() <= d.get("median").asDouble() && d.get("median").asDouble() <= d.get("max").asDouble());
        assertEquals(d.get("max").asDouble() - d.get("min").asDouble(), d.get("width").asDouble(), 1e-12);
        assertEquals("BOUNDARY", d.get("region").asString(), "a threshold at the unperturbed figure sits inside the variants");
        assertEquals(8L, decisionMembersUsed(eta));

        // The same request gives the same decision, seed included.
        JsonNode again = body(call(post("/v1/risk/term-structure"), eva, withDecision)).get("decision");
        assertEquals(d, again);
        assertEquals(16L, decisionMembersUsed(eta));

        // Compare: each scenario is judged over variants of itself.
        Map<String, Object> compare = new LinkedHashMap<>();
        compare.put("loan", loan(null, 700, "NV", "2024-11"));
        compare.put("scenarios", List.of("ADVERSE", "SEVERELY_ADVERSE"));
        compare.put("months", 6);
        compare.put("decision", Map.of("metric", "PD_12M", "threshold", 0.0, "granule", Map.of("members", 4)));
        JsonNode compared = body(call(post("/v1/risk/term-structure/compare"), eva, compare));
        assertEquals(2, compared.get("scenarios").size());
        for (JsonNode curve : compared.get("scenarios")) {
            assertEquals("CERTAIN_HIGH", curve.get("decision").get("region").asString(), "a zero threshold is met by every variant");
            assertEquals(4, curve.get("decision").get("members").asInt());
        }
        assertFalse(compared.has("decision"), "per-scenario decisions only for a perturbation granule");

        // A scenario set is judged once, across the listed scenarios.
        compare.put("decision", Map.of("metric", "PD_LIFETIME", "threshold", 0.0,
                "granule", Map.of("kind", "SCENARIO_SET", "scenarios", List.of("ADVERSE", "SEVERELY_ADVERSE"))));
        JsonNode set = body(call(post("/v1/risk/term-structure/compare"), eva, compare));
        assertEquals("CERTAIN_HIGH", set.get("decision").get("region").asString());
        assertEquals(2, set.get("decision").get("members").asInt());
        assertFalse(set.get("scenarios").get(0).has("decision"));
    }

    @Test
    void invalidDecisionOptionsAreRefusedBeforeAnythingRuns() throws Exception {
        Organization theta = organizations.create("theta-" + run, "Theta Bank", false, null, null);
        String tia = token(theta, "tia", Role.ANALYST);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("loan", loan(null, 700, "NV", "2024-11"));
        request.put("months", 6);
        Map<String, Map<String, Object>> bad = new LinkedHashMap<>();
        bad.put("tolerance", Map.of("metric", "PD_12M", "threshold", 0.05, "tolerance", 0.5));
        bad.put("threshold", Map.of("metric", "PD_12M", "threshold", 1.5));
        bad.put("metric", Map.of("metric", "NOPE", "threshold", 0.05));
        bad.put("members", Map.of("metric", "PD_12M", "threshold", 0.05, "granule", Map.of("members", 129)));
        bad.put("shockBand", Map.of("metric", "PD_12M", "threshold", 0.05, "granule", Map.of("shockBand", 0.9)));
        bad.put("regimeDraws", Map.of("metric", "PD_12M", "threshold", 0.05, "granule", Map.of("regimeDraws", true)));
        for (Map.Entry<String, Map<String, Object>> option : bad.entrySet()) {
            request.put("decision", option.getValue());
            MvcResult refused = call(post("/v1/risk/term-structure"), tia, request);
            assertEquals(422, status(refused), option.getKey());
            assertTrue(body(refused).get("error").asString().contains(option.getKey()), body(refused).toString());
        }
        assertEquals(0L, decisionMembersUsed(theta), "refused requests are not metered");
    }

    @Test
    void aPortfolioRunCanJudgeEveryLoanAndListTheBoundary() throws Exception {
        Organization zeta = organizations.create("zeta-" + run, "Zeta Bank", false, null, null);
        String zed = token(zeta, "zed", Role.ADMIN);
        List<Map<String, Object>> loans = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            loans.add(loan("Z-" + i, 620 + 25 * i, i % 2 == 0 ? "CA" : "FL", "2022-0" + (1 + i)));
        }
        assertEquals(6, body(call(post("/v1/admin/portfolio"), zed, Map.of("loans", loans))).get("accepted").asInt());

        // A threshold at one loan's adverse lifetime PD puts that loan on the boundary of its own variants.
        Map<String, Object> single = new LinkedHashMap<>();
        single.put("loan", loans.get(3));
        single.put("scenario", "ADVERSE");
        single.put("months", 1);
        double pd = body(call(post("/v1/risk/term-structure"), zed, single)).get("termStructure").get("summary").get("pdLifetime").asDouble();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("scenarios", List.of("ADVERSE"));
        request.put("decisionScenario", "ADVERSE");
        request.put("decision", Map.of("metric", "PD_LIFETIME", "threshold", pd, "granule", Map.of("members", 8)));
        MvcResult started = call(post("/v1/risk/portfolio/runs"), zed, request);
        assertEquals(202, status(started));
        JsonNode polled = finished(body(started).get("id").asString(), zed);
        assertEquals("COMPLETED", polled.get("status").asString(), polled.toString());
        assertEquals(6L * 8L, decisionMembersUsed(zeta), "one unit per projection the analysis costs");

        JsonNode view = body(call(get("/v1/risk/portfolio"), zed, null));
        JsonNode adverse = null;
        for (JsonNode snapshot : view.get("snapshots")) {
            if ("ADVERSE".equals(snapshot.get("scenario").asString())) {
                adverse = snapshot;
            } else {
                assertFalse(snapshot.has("decision"), "only the judged scenario carries the analysis");
            }
        }
        JsonNode summary = adverse.get("decision");
        assertEquals("ADVERSE", summary.get("scenario").asString());
        int loansInRegions = 0;
        double exposureInRegions = 0;
        int boundaryLoans = 0;
        for (JsonNode region : summary.get("regions")) {
            loansInRegions += region.get("loans").asInt();
            exposureInRegions += region.get("exposure").asDouble();
            if ("BOUNDARY".equals(region.get("region").asString())) {
                boundaryLoans = region.get("loans").asInt();
            }
        }
        assertEquals(summary.get("loans").asInt(), loansInRegions, "the three regions partition the book");
        assertEquals(summary.get("exposure").asDouble(), exposureInRegions, 1e-6);
        assertTrue(boundaryLoans >= 1, summary.toString());
        assertTrue(summary.get("eclLower").asDouble() <= summary.get("eclHighBase").asDouble() + 1e-6);
        assertTrue(summary.get("eclHighBase").asDouble() <= summary.get("eclUpper").asDouble() + 1e-6);
        assertTrue(summary.get("method").asString().contains("not a probability"));

        JsonNode listing = body(call(get("/v1/risk/portfolio/loans?sort=loanId&limit=50"), zed, null));
        assertEquals(6, listing.get("regions").size(), "every listed loan carries its region");
        JsonNode onBoundary = body(call(get("/v1/risk/portfolio/loans?region=BOUNDARY&limit=50"), zed, null));
        assertEquals(boundaryLoans, onBoundary.get("total").asInt());
        assertTrue(onBoundary.get("regions").has("Z-3"), "the loan whose threshold was set at its own figure is on the boundary");
        onBoundary.get("regions").forEach(info -> assertEquals("BOUNDARY", info.get("region").asString()));
        assertEquals(400, status(call(get("/v1/risk/portfolio/loans?region=NOPE"), zed, null)));

        String csv = download(get("/v1/risk/portfolio/loans/export"), zed);
        String[] lines = csv.split("\r\n");
        assertTrue(lines[0].endsWith(",region,variantsHigh,variants,metricMin,metricMax,eclLifetimeMin,eclLifetimeMax,extrapolated"), lines[0]);
        assertEquals(7, lines.length);

        // Another organization sees none of it.
        String bia = token(organizations.create("iota-" + run, "Iota Bank", false, null, null), "bia", Role.ANALYST);
        assertEquals(0, body(call(get("/v1/risk/portfolio/loans?region=BOUNDARY"), bia, null)).get("total").asInt());
        assertTrue(body(call(get("/v1/risk/portfolio"), bia, null)).get("snapshots").isEmpty());

        // The same run twice gives the same regions: variants are seeded from the loans, not from the schedule.
        MvcResult again = call(post("/v1/risk/portfolio/runs"), zed, request);
        assertEquals(202, status(again));
        assertEquals("COMPLETED", finished(body(again).get("id").asString(), zed).get("status").asString());
        JsonNode repeated = body(call(get("/v1/risk/portfolio/loans?sort=loanId&limit=50"), zed, null));
        assertEquals(listing.get("regions"), repeated.get("regions"));

        // A run that does not ask for regions leaves none behind.
        MvcResult plain = call(post("/v1/risk/portfolio/runs"), zed, Map.of("scenarios", List.of("ADVERSE")));
        assertEquals(202, status(plain));
        assertEquals("COMPLETED", finished(body(plain).get("id").asString(), zed).get("status").asString());
        assertTrue(body(call(get("/v1/risk/portfolio/loans?limit=50"), zed, null)).get("regions").isEmpty());
        for (JsonNode snapshot : body(call(get("/v1/risk/portfolio"), zed, null)).get("snapshots")) {
            assertFalse(snapshot.has("decision"));
        }
        assertEquals(0, body(call(get("/v1/risk/portfolio/loans?region=BOUNDARY"), zed, null)).get("total").asInt());
    }

    @Test
    void aPortfolioRegionRunIsRefusedWhenItsOptionsOrSizeAreWrong() throws Exception {
        Organization kappa = organizations.create("kappa-" + run, "Kappa Bank", false, null, null);
        String kai = token(kappa, "kai", Role.ADMIN);
        List<Map<String, Object>> loans = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            loans.add(loan("K-" + i, 650 + 20 * i, "TX", "2022-0" + (1 + i)));
        }
        assertEquals(3, body(call(post("/v1/admin/portfolio"), kai, Map.of("loans", loans))).get("accepted").asInt());

        Map<String, Object> tooManyMembers = Map.of("decision", Map.of("metric", "PD_12M", "threshold", 0.05, "granule", Map.of("members", 33)));
        MvcResult cap = call(post("/v1/risk/portfolio/runs"), kai, tooManyMembers);
        assertEquals(422, status(cap));
        assertTrue(body(cap).get("error").asString().contains("members"));

        Map<String, Object> elsewhere = Map.of("scenarios", List.of("ADVERSE"), "decisionScenario", "SEVERELY_ADVERSE",
                "decision", Map.of("metric", "PD_12M", "threshold", 0.05));
        MvcResult notInRun = call(post("/v1/risk/portfolio/runs"), kai, elsewhere);
        assertEquals(422, status(notInRun));
        assertTrue(body(notInRun).get("error").asString().contains("decisionScenario"));

        assertEquals(0L, decisionMembersUsed(kappa), "refused runs are not metered");
        assertTrue(body(call(get("/v1/risk/portfolio"), kai, null)).get("snapshots").isEmpty(), "and nothing ran");
    }

    /**
     * Starting a portfolio run costs twenty tokens, so a caller who fires three at once runs out, while a
     * colleague in the same organization, drawing on a bucket of their own, is not affected.
     */
    @Test
    void heavyRequestsSpendTheCallersAllowanceNotTheirColleagues() throws Exception {
        Organization delta = organizations.create("delta-" + run, "Delta Bank", false, null, null);
        String dana = token(delta, "dana", Role.ANALYST);
        String dev = token(delta, "dev", Role.ANALYST);
        Map<String, Object> unknownScenario = Map.of("scenarios", List.of("NOPE"));   // rejected cheaply, but charged in full

        assertEquals(422, status(call(post("/v1/risk/portfolio/runs"), dana, unknownScenario)));
        assertEquals(422, status(call(post("/v1/risk/portfolio/runs"), dana, unknownScenario)));
        MvcResult throttled = call(post("/v1/risk/portfolio/runs"), dana, unknownScenario);
        assertEquals(429, status(throttled), "three runs at once exceed one caller's share");
        assertNotNull(throttled.getResponse().getHeader("Retry-After"));

        assertEquals(200, status(call(get("/v1/loans?limit=1"), dev, null)));
        assertEquals(200, status(call(get("/v1/loans?limit=1"), dana, null)), "a cheap request still fits what is left");
    }

    @Test
    void aSimulationIsReproducibleFromItsSeed() throws Exception {
        List<Map<String, Object>> loans = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            loans.add(Map.of("loanId", "C" + i, "pd", 0.005 + 0.0002 * i, "lgd", 0.35, "ead", 100000 + 500.0 * i));
        }
        Map<String, Object> request = new LinkedHashMap<>(Map.of("loans", loans, "confidenceLevel", 0.999, "numScenarios", 5000, "seed", 7));
        JsonNode first = body(call(post("/v1/cvar"), ana, request));
        JsonNode second = body(call(post("/v1/cvar"), ana, request));
        assertEquals(first.get("valueAtRisk").asDouble(), second.get("valueAtRisk").asDouble(), 0.0);
        assertEquals(first.get("conditionalValueAtRisk").asDouble(), second.get("conditionalValueAtRisk").asDouble(), 0.0);
        assertTrue(first.get("conditionalValueAtRisk").asDouble() >= first.get("valueAtRisk").asDouble());
        assertTrue(first.get("valueAtRisk").asDouble() > first.get("expectedLoss").asDouble());
        assertEquals(7, first.get("seed").asLong());
        assertEquals(10, first.get("topContributions").size());

        request.put("confidenceLevel", 1.5);
        assertEquals(400, status(call(post("/v1/cvar"), ana, request)));
    }

    // ---------------------------------------------------------------- case workflow

    @Test
    void caseWorkflowEnforcesReasonsFourEyesAndVersions() throws Exception {
        String loanId = body(call(get("/v1/loans?limit=1&offset=7"), ana, null)).get(0).get("loanId").asString();
        String path = "/v1/loans/" + loanId + "/case";

        assertEquals(422, status(call(post(path), ana, Map.of("status", "ESCALATED", "flagged", true))), "escalating needs a reason");
        MvcResult escalated = call(post(path), ana, Map.of("status", "ESCALATED", "flagged", true, "reason", "DTI looks misstated"));
        assertEquals(200, status(escalated));
        long version = body(escalated).get("version").asLong();
        assertEquals("ana", body(escalated).get("escalatedBy").asString());

        MvcResult ownClear = call(post(path), ana, Map.of("status", "CLEARED", "flagged", false, "reason", "looks fine to me"));
        assertEquals(422, status(ownClear), "the person who escalated cannot clear");
        assertTrue(body(ownClear).get("error").asString().contains("Four-eyes"));

        MvcResult stale = call(post(path), ben, Map.of("status", "CLEARED", "flagged", false, "reason", "verified income", "expectedVersion", version - 1));
        assertEquals(409, status(stale));
        assertEquals("ESCALATED", body(stale).get("current").get("status").asString());

        MvcResult cleared = call(post(path), ben, Map.of("status", "CLEARED", "flagged", false, "reason", "verified income", "expectedVersion", version));
        assertEquals(200, status(cleared));
        assertEquals(422, status(call(post(path), ben, Map.of("status", "ESCALATED", "flagged", false, "reason", "x"))), "a cleared case must be reopened first");
        assertEquals(422, status(call(post(path), ben, Map.of("status", "REVIEWED", "flagged", false, "assignedTo", "nobody-here", "reason", "reopen"))));

        JsonNode history = body(call(get(path + "/history"), ana, null));
        assertEquals(2, history.size());
        assertEquals("ben", history.get(0).get("actor").asString());
        assertEquals("verified income", history.get(0).get("reason").asString());
        assertTrue(download(get("/v1/loan-cases/export"), ana).contains(loanId + ",CLEARED,"));

        assertEquals(404, status(call(post("/v1/loans/NO-SUCH-LOAN/case"), ana, Map.of("status", "REVIEWED", "flagged", false))));
    }

    @Test
    void anIdempotentRetryReplaysTheFirstResponseInsteadOfActingAgain() throws Exception {
        String loanId = body(call(get("/v1/loans?limit=1&offset=11"), ana, null)).get(0).get("loanId").asString();
        String key = "note-" + run;
        Map<String, Object> note = Map.of("text", "Called the borrower; payment promised for Friday.");

        MvcResult first = mvc.perform(post("/v1/loans/" + loanId + "/notes").cookie(sessionCookie(ana)).with(csrfOnWrites())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(note))).andReturn();
        assertEquals(200, status(first));
        MvcResult retry = mvc.perform(post("/v1/loans/" + loanId + "/notes").cookie(sessionCookie(ana)).with(csrfOnWrites())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(note))).andReturn();
        assertEquals(200, status(retry));
        assertEquals("true", retry.getResponse().getHeader("Idempotent-Replay"));
        assertEquals(first.getResponse().getContentAsString(), retry.getResponse().getContentAsString());
        assertEquals(1, body(call(get("/v1/loans/" + loanId + "/case"), ana, null)).get("notes").size(), "the note exists once");

        MvcResult different = mvc.perform(post("/v1/loans/" + loanId + "/notes").cookie(sessionCookie(ana)).with(csrfOnWrites())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("text", "another note")))).andReturn();
        assertEquals(422, status(different), "the same key with a different request is an error, not a replay");

        // A key belongs to its caller: a colleague using the same value is making a new request.
        MvcResult colleague = mvc.perform(post("/v1/loans/" + loanId + "/notes").cookie(sessionCookie(ben)).with(csrfOnWrites())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(note))).andReturn();
        assertEquals(200, status(colleague));
        assertEquals(null, colleague.getResponse().getHeader("Idempotent-Replay"));
    }

    // ---------------------------------------------------------------- governance and the rest

    @Test
    void theModelInventoryCardsBacktestAndDriftAreServed() throws Exception {
        JsonNode inventory = body(call(get("/v1/models"), ana, null));
        assertEquals(7, inventory.size());
        for (JsonNode model : inventory) {
            boolean validated = List.of("pd_24m", "survival", "trajectory_lstm").contains(model.get("id").asString());
            assertEquals(validated, model.get("validated").asBoolean(), model.get("id").asString());
            if (validated) {
                assertTrue(model.get("checksumVerified").asBoolean());
                assertEquals(64, model.get("sha256").asString().length());
            } else {
                assertFalse(model.get("gaps").isEmpty(), "an unvalidated model says what is missing");
            }
        }
        JsonNode card = body(call(get("/v1/models/pd_24m"), ana, null));
        assertEquals(inventory.get(0).get("sha256").asString(), card.get("artifact_sha256").asString());
        assertFalse(body(call(get("/v1/models/survival"), ana, null)).get("limitations").isEmpty());
        assertEquals(404, status(call(get("/v1/models/unknown"), ana, null)));

        JsonNode backtest = body(call(get("/v1/models/survival/backtest"), ana, null));
        assertTrue(backtest.get("monthly").size() > 60);
        assertEquals(11, backtest.get("vintages").size());  // 2006-2008 and 2017-2024

        JsonNode drift = body(call(get("/v1/models/pd_24m/drift"), ana, null));
        assertEquals(400, drift.get("loans").asInt());
        assertEquals(16, drift.get("features").size());
    }

    @Test
    void listsArePagedAndForecastsBounded() throws Exception {
        MvcResult page = call(get("/v1/loans?limit=5"), ana, null);
        assertEquals(5, body(page).size());
        assertEquals("400", page.getResponse().getHeader("X-Total-Count"));
        String someId = body(page).get(2).get("loanId").asString();
        MvcResult found = call(get("/v1/loans?q=" + someId.substring(2).toLowerCase()), ana, null);
        assertEquals(someId, body(found).get(0).get("loanId").asString());
        assertEquals(400, status(call(get("/v1/loans?limit=100000"), ana, null)));

        JsonNode forecast = body(call(get("/v1/regime-forecast?monthsAhead=12"), ana, null));
        assertEquals(12, forecast.get("path").size());
        assertEquals(400, status(call(get("/v1/regime-forecast?monthsAhead=5000"), ana, null)));
    }

    /**
     * The graph query is checked against a breadth-first search done here on the edges the API itself
     * returns: same states, same shortest distances, for every hop limit.
     */
    @Test
    void segmentNeighboursAreShortestPathsOnTheGraphTheApiServes() throws Exception {
        JsonNode graph = body(call(get("/v1/segments/graph"), ana, null));
        Map<String, List<String>> adjacent = new LinkedHashMap<>();
        for (JsonNode state : graph.get("states")) {
            adjacent.put(state.asString(), new ArrayList<>());
        }
        assertTrue(adjacent.size() >= 10, "the correlation graph is loaded");
        assertFalse(graph.get("edges").isEmpty());
        for (JsonNode edge : graph.get("edges")) {
            String a = edge.get(0).asString(), b = edge.get(1).asString();
            assertTrue(a.compareTo(b) < 0, "each edge once, smaller code first");
            adjacent.get(a).add(b);
            adjacent.get(b).add(a);
        }
        String source = graph.get("edges").get(0).get(0).asString();
        Map<String, Integer> distance = new LinkedHashMap<>();
        distance.put(source, 0);
        List<String> frontier = List.of(source);
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String state : frontier) {
                for (String neighbour : adjacent.get(state)) {
                    if (distance.putIfAbsent(neighbour, distance.get(state) + 1) == null) {
                        next.add(neighbour);
                    }
                }
            }
            frontier = next;
        }
        for (int maxHops = 1; maxHops <= 5; maxHops++) {
            JsonNode neighbours = body(call(get("/v1/segments/" + source + "/neighbors?maxHops=" + maxHops), ana, null));
            Map<String, Integer> served = new LinkedHashMap<>();
            for (JsonNode n : neighbours) {
                served.put(n.get("state").asString(), n.get("hops").asInt());
            }
            Map<String, Integer> expected = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : distance.entrySet()) {
                if (e.getValue() >= 1 && e.getValue() <= maxHops) {
                    expected.put(e.getKey(), e.getValue());
                }
            }
            assertEquals(expected, served, "within " + maxHops + " hops of " + source);
        }
        assertEquals(400, status(call(get("/v1/segments/" + source + "/neighbors?maxHops=9"), ana, null)));
    }

    @Test
    void webhooksCannotBePointedAtInternalAddresses() throws Exception {
        for (String url : List.of("https://169.254.169.254/latest/meta-data", "https://127.0.0.1/hook", "http://example.com/hook")) {
            assertEquals(400, status(call(post("/v1/admin/webhooks"), adam, Map.of("url", url, "events", List.of("LOAN_SCORED")))), url);
        }
    }

    @Test
    void theAssistantDegradesCleanlyWhenNoModelProviderIsReachable() throws Exception {
        MvcResult answer = call(post("/v1/assistant/chat"), ana, Map.of("question", "Summarise this loan's risk."));
        assertEquals(200, status(answer));
        assertTrue(body(answer).get("answer").asString().contains("isn't available"));
        assertEquals(400, status(call(post("/v1/assistant/chat"), ana, Map.of("question", "x".repeat(5000)))));
    }

    @Test
    void uploadedPortfoliosCanBeRemoved() throws Exception {
        // gamma's portfolio (uploaded in the run test, or absent if that test has not run) is removable by its admin only
        assertEquals(403, status(call(delete("/v1/admin/portfolio"), ana, null)));
        assertEquals(200, status(call(delete("/v1/admin/portfolio"), adam, null)));
    }

    // ---------------------------------------------------------------- borrower portal

    private String borrower(Organization org, String username, String... loanIds) {
        return inTenant(org, () -> {
            User user = new User(org, username, encoder.encode(PASSWORD), Role.CLIENT);
            user.setLoanIds(new java.util.HashSet<>(List.of(loanIds)));
            return jwt.issueSession(users.save(user)).token();
        });
    }

    private static boolean absent(JsonNode node, String field) {
        return node.path(field).isNull() || node.path(field).isMissingNode();
    }

    private MvcResult uploadAs(String path, String token, String filename, String content) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(path)
                .file(new org.springframework.mock.web.MockMultipartFile("file", filename, "text/plain",
                        content.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .with(csrfOnWrites()).cookie(sessionCookie(token))).andReturn();
    }

    @Test
    void aBorrowerSeesABandTheirCaseAndAContactButNeverTheScoreOrTheNotes() throws Exception {
        JsonNode page = body(call(get("/v1/loans?limit=2&offset=31"), ana, null));
        String scored = page.get(0).get("loanId").asString();
        String quiet = page.get(1).get("loanId").asString();
        String rk = borrower(alpha, "rk", scored, quiet);

        assertEquals(200, status(call(post("/v1/score"), ana, loan(scored, 640, "FL", null))));
        assertEquals(200, status(call(post("/v1/loans/" + scored + "/case"), ana,
                Map.of("status", "REVIEWED", "flagged", true, "assignedTo", "ana"))));
        assertEquals(200, status(call(post("/v1/loans/" + scored + "/notes"), ana, Map.of("text", "Suspect the income is overstated"))));
        assertEquals(200, status(call(put("/v1/admin/borrower-contact"), adam, Map.of("name", "Loan Care team", "email", "care@alpha.test"))));
        assertEquals(403, status(call(put("/v1/admin/borrower-contact"), ana, Map.of("name", "x"))), "setting it is an admin action");

        MvcResult mine = call(get("/v1/my/loans"), rk, null);
        assertEquals(200, status(mine));
        String raw = mine.getResponse().getContentAsString();
        for (String hidden : List.of("calibratedProbability", "rawProbability", "modelVersion", "explanation", "reasonCodes",
                "Suspect the income", "flagged")) {
            assertFalse(raw.contains(hidden), "a borrower's response never carries " + hidden + ": " + raw);
        }
        JsonNode loans = body(mine);
        assertEquals(2, loans.size());
        JsonNode first = loans.get(0).get("loanId").asString().equals(scored) ? loans.get(0) : loans.get(1);
        JsonNode second = first == loans.get(0) ? loans.get(1) : loans.get(0);

        assertTrue(List.of("LOW", "MEDIUM", "HIGH").contains(first.get("status").get("band").asString()));
        assertTrue(first.get("status").get("trend").asString().startsWith("Unchanged since "));
        assertEquals("Under review", first.get("openCase").get("status").asString());
        assertFalse(absent(first.get("openCase"), "openedAt"));
        assertEquals("ana", first.get("contact").get("name").asString(), "the assigned analyst is the contact");
        assertTrue(absent(second, "status"), "an unscored loan has no status, and no error");
        assertTrue(absent(second, "openCase"), "nobody has acted on it: no open items");
        assertEquals("Loan Care team", second.get("contact").get("name").asString(), "the organization's default contact");

        // None of the analyst surfaces open up for a borrower, even on their own loan.
        assertEquals(403, status(call(get("/v1/score/" + scored), rk, null)));
        assertEquals(403, status(call(get("/v1/loans/" + scored + "/case"), rk, null)));
        assertEquals(403, status(call(get("/v1/loans/" + scored + "/attachments"), rk, null)));
        assertEquals(403, status(call(post("/v1/assistant/chat"), rk, Map.of("question", "What do the notes say?"))));
        assertEquals(204, status(call(delete("/v1/admin/borrower-contact"), adam, null)));
    }

    @Test
    void aBorrowerReadsAndAddsDocumentsOnlyOnTheirOwnLoans() throws Exception {
        JsonNode page = body(call(get("/v1/loans?limit=2&offset=35"), ana, null));
        String own = page.get(0).get("loanId").asString();
        String notMine = page.get(1).get("loanId").asString();
        String lee = borrower(alpha, "lee", own);
        String bob = borrower(beta, "bob", own);   // another organization's borrower, linked to the same loan id there

        MvcResult byStaff = uploadAs("/v1/loans/" + own + "/attachments", ana, "escrow-notice.txt", "Escrow analysis 2026.");
        assertEquals(201, status(byStaff));
        MvcResult byBorrower = uploadAs("/v1/my/loans/" + own + "/documents", lee, "payslip.txt", "September payslip.");
        assertEquals(201, status(byBorrower));
        assertTrue(body(byBorrower).get("uploadedByYou").asBoolean());
        long staffDoc = body(byStaff).get("id").asLong();
        long borrowerDoc = body(byBorrower).get("id").asLong();
        try {
            MvcResult listed = call(get("/v1/my/loans/" + own + "/documents"), lee, null);
            assertEquals(200, status(listed));
            assertEquals(2, body(listed).size());
            assertFalse(listed.getResponse().getContentAsString().contains("\"ana\""), "staff are 'your lender', not a username");
            MvcResult fetched = call(get("/v1/my/loans/" + own + "/documents/" + staffDoc + "/download"), lee, null);
            assertEquals(200, status(fetched));
            assertEquals("Escrow analysis 2026.", fetched.getResponse().getContentAsString());
            assertTrue(fetched.getResponse().getHeader("Content-Disposition").startsWith("attachment"));

            // Another loan is refused before anything is read or stored, exactly as if it did not exist.
            assertEquals(404, status(call(get("/v1/my/loans/" + notMine + "/documents"), lee, null)));
            assertEquals(404, status(uploadAs("/v1/my/loans/" + notMine + "/documents", lee, "x.txt", "x")));
            assertEquals(0, inTenant(alpha, () -> jdbc.queryForObject(
                    "SELECT count(*) FROM loan_attachment WHERE tenant_id = ? AND loan_id = ?", Integer.class, alpha.getId(), notMine)));
            assertEquals(404, status(call(get("/v1/my/loans/" + notMine + "/documents/" + staffDoc + "/download"), lee, null)));
            assertEquals(404, status(call(get("/v1/my/loans/NO-SUCH-LOAN/documents"), lee, null)));

            // The same loan id in another organization shows that organization's documents: none.
            MvcResult elsewhere = call(get("/v1/my/loans/" + own + "/documents"), bob, null);
            assertEquals(200, status(elsewhere));
            assertEquals(0, body(elsewhere).size());
            assertEquals(404, status(call(get("/v1/my/loans/" + own + "/documents/" + staffDoc + "/download"), bob, null)));

            // Staff see the borrower's upload through their own endpoint, with its uploader.
            JsonNode staffView = body(call(get("/v1/loans/" + own + "/attachments"), ana, null));
            boolean seen = false;
            for (JsonNode a : staffView) {
                seen |= a.get("id").asLong() == borrowerDoc && a.get("uploadedBy").asString().equals("lee");
            }
            assertTrue(seen);

            // The borrower is told a document was added by someone else (notifications are delivered after commit).
            boolean notified = false;
            for (int i = 0; i < 40 && !notified; i++) {
                for (JsonNode n : body(call(get("/v1/notifications"), lee, null))) {
                    notified |= n.get("message").asString().equals("A document was added to your loan " + own);
                }
                if (!notified) {
                    Thread.sleep(100);
                }
            }
            assertTrue(notified);
            assertEquals(1, body(call(get("/v1/notifications"), lee, null)).size(), "not for their own upload");
        } finally {
            assertEquals(204, status(call(delete("/v1/admin/loans/" + own + "/attachments/" + staffDoc), adam, null)));
            assertEquals(204, status(call(delete("/v1/admin/loans/" + own + "/attachments/" + borrowerDoc), adam, null)));
        }
    }

    @Test
    void theBorrowerAssistantIgnoresALoanIdAndIsAuditedAndMeteredApart() throws Exception {
        JsonNode page = body(call(get("/v1/loans?limit=2&offset=39"), ana, null));
        String own = page.get(0).get("loanId").asString();
        String other = page.get(1).get("loanId").asString();
        String sam = borrower(alpha, "sam", own);

        MvcResult answer = call(post("/v1/my/assistant"), sam,
                Map.of("question", "I got a text saying pay by gift card today. Is it real?", "loanId", other));
        assertEquals(200, status(answer), "a supplied loan id is ignored, not refused");
        assertTrue(body(answer).get("answer").asString().contains("isn't available"));
        assertTrue(body(answer).has("contact"), "the escalation contact is always part of the answer");
        assertEquals(400, status(call(post("/v1/my/assistant"), sam, Map.of("question", "x".repeat(2001)))));
        assertEquals(400, status(call(post("/v1/my/assistant"), sam, Map.of("question", " "))));

        String endpoint = inTenant(alpha, () -> jdbc.queryForObject("SELECT endpoint FROM model_invocation_events "
                + "WHERE tenant_id = ? AND actor = 'sam' AND path = '/v1/my/assistant' AND status_code = 200 "
                + "ORDER BY occurred_at DESC LIMIT 1", String.class, alpha.getId()));
        assertEquals("BorrowerAssistantController.ask", endpoint);
        Long metered = inTenant(alpha, () -> jdbc.queryForObject(
                "SELECT quantity FROM usage_record WHERE tenant_id = ? AND metric = 'BORROWER_ASSISTANT_CALL'", Long.class, alpha.getId()));
        assertTrue(metered >= 1);

        // The analyst assistant is untouched: still staff only, still answering staff.
        assertEquals(403, status(call(post("/v1/assistant/chat"), sam, Map.of("question", "x"))));
        assertEquals(200, status(call(post("/v1/assistant/chat"), ana, Map.of("question", "Summarise this loan."))));
    }
}
