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
        assertEquals(1, body(call(get("/v1/risk/portfolio/history?scenario=BASELINE"), gus, null)).size(), "same date, one snapshot");
        assertEquals(0, body(call(get("/v1/risk/portfolio/history?scenario=SEVERELY_ADVERSE"), gus, null)).size());
        assertEquals(10, body(call(get("/v1/risk/portfolio/loans"), gus, null)).get("total").asInt());
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
            boolean validated = List.of("pd_24m", "survival").contains(model.get("id").asString());
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
        assertEquals(8, backtest.get("vintages").size());

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
}
