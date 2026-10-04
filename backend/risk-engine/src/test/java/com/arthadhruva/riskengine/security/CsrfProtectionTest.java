package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.AbstractIntegrationTest;
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
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The double-submit CSRF token on top of the SameSite=Strict session cookie. These tests do the real exchange --
 * fetch the {@code XSRF-TOKEN} cookie, echo it in {@code X-XSRF-TOKEN} -- rather than the test-support shortcut
 * the other integration tests use, and cover each way it can be refused.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CsrfProtectionTest extends AbstractIntegrationTest {

    private static final String COOKIE = "XSRF-TOKEN";
    private static final String HEADER = "X-XSRF-TOKEN";

    @Autowired MockMvc mvc;
    @Autowired OrganizationService organizations;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;

    private final ObjectMapper json = new ObjectMapper();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private Organization org;

    @BeforeAll
    void organization() {
        org = organizations.create("csrf-" + run, "Csrf Bank", false, null, null);
    }

    /** A fresh signed-in session; each test that writes uses its own because sign-out revokes it. */
    private String session() {
        TenantContext.set(org.getId());
        try {
            return jwt.issueSession(users.save(new User(org, "u" + UUID.randomUUID().toString().substring(0, 8),
                    encoder.encode("Correct-Horse-9-Battery"), Role.ANALYST))).token();
        } finally {
            TenantContext.clear();
        }
    }

    private static Cookie sessionCookie(String token) {
        return new Cookie(SessionCookie.NAME, token);
    }

    /**
     * What a browser does first: ask for a token and keep the cookie it is given. GET /v1/csrf has no handler, so
     * unauthenticated it answers 401, and the cookie rides on that response (see CsrfCookieFilter).
     */
    private String fetchToken() throws Exception {
        MvcResult result = mvc.perform(get("/v1/csrf")).andReturn();
        assertEquals(401, result.getResponse().getStatus());
        Cookie cookie = result.getResponse().getCookie(COOKIE);
        assertNotNull(cookie, "GET /v1/csrf must set the " + COOKIE + " cookie");
        assertFalse(cookie.getValue().isBlank());
        return cookie.getValue();
    }

    private int logout(String sessionToken, String cookieToken, String headerToken) throws Exception {
        var request = post("/v1/account/logout").cookie(sessionCookie(sessionToken));
        if (cookieToken != null) {
            request.cookie(new Cookie(COOKIE, cookieToken));
        }
        if (headerToken != null) {
            request.header(HEADER, headerToken);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void aWriteWithTheSessionCookieAloneIsRefused() throws Exception {
        assertEquals(403, logout(session(), null, null), "the session cookie by itself must not be enough to write");
    }

    @Test
    void aWriteWithTheMatchingCookieAndHeaderIsAccepted() throws Exception {
        String token = fetchToken();
        int status = logout(session(), token, token);
        assertTrue(status >= 200 && status < 300, "expected success with a matching token, got " + status);
    }

    @Test
    void aHeaderThatDoesNotMatchTheCookieIsRefused() throws Exception {
        String token = fetchToken();
        assertEquals(403, logout(session(), token, token + "x"));
        assertEquals(403, logout(session(), token, fetchToken()), "a token from another browser is not this one's");
    }

    @Test
    void theCookieWithoutTheHeaderIsRefused() throws Exception {
        // This is what a forged cross-site or same-site request looks like: the browser attaches the cookies by
        // itself, but the attacker's page cannot read the token to put it in a header.
        assertEquals(403, logout(session(), fetchToken(), null));
    }

    @Test
    void theHeaderWithoutTheCookieIsRefused() throws Exception {
        assertEquals(403, logout(session(), null, fetchToken()));
    }

    @Test
    void readsNeedNoToken() throws Exception {
        MvcResult result = mvc.perform(get("/v1/notifications/unread-count").cookie(sessionCookie(session()))).andReturn();
        assertEquals(200, result.getResponse().getStatus());
    }

    @Test
    void theTokenCookieIsReadableByScriptSameSiteStrictAndSitewide() throws Exception {
        // MockMvc renders SameSite into the header string only for its own MockCookie type, so the cookie's
        // attributes are asserted directly; the header as a real server writes it is checked against a running app.
        Cookie cookie = mvc.perform(get("/v1/csrf")).andReturn().getResponse().getCookie(COOKIE);
        assertNotNull(cookie);
        assertEquals("Strict", cookie.getAttribute("SameSite"));
        assertEquals("/", cookie.getPath(), "a /v1 path would hide it from the app's pages");
        assertFalse(cookie.isHttpOnly(), "the frontend has to read it");
        assertFalse(cookie.getSecure(), "the test deployment is http, like SessionCookie's");
    }

    @Test
    void anyResponseCarriesTheCookieNotJustTheBootstrapEndpoint() throws Exception {
        MvcResult unauthenticated = mvc.perform(get("/v1/loans")).andReturn();
        assertEquals(401, unauthenticated.getResponse().getStatus());
        assertNotNull(unauthenticated.getResponse().getCookie(COOKIE), "even a 401 should hand out the token");
    }

    /**
     * idle-stop.sh keeps the server awake for any 2xx /v1/ response, on the reasoning that an anonymous API call
     * answers 401. An anonymous 2xx GET (a public token endpoint, say) would let a crawler hold the VM up and billing
     * all month, so the token bootstrap must never be one.
     */
    @Test
    void theTokenBootstrapIsNeverASuccessResponseSoItCannotKeepTheServerAwake() throws Exception {
        int anonymous = mvc.perform(get("/v1/csrf")).andReturn().getResponse().getStatus();
        assertFalse(anonymous >= 200 && anonymous < 300, "anonymous GET /v1/csrf answered " + anonymous);
        int signedIn = mvc.perform(get("/v1/csrf").cookie(sessionCookie(session()))).andReturn().getResponse().getStatus();
        assertFalse(signedIn >= 200 && signedIn < 300, "signed-in GET /v1/csrf answered " + signedIn);
    }

    @Test
    void aBrowserThatAlreadyHasTheTokenIsNotGivenANewOne() throws Exception {
        MvcResult again = mvc.perform(get("/v1/csrf").cookie(new Cookie(COOKIE, "already-held"))).andReturn();
        assertEquals(401, again.getResponse().getStatus());
        assertEquals(null, again.getResponse().getCookie(COOKIE), "rotating it on every response would break open tabs");
    }

    @Test
    void theLoginFormNeedsTheTokenToo() throws Exception {
        String body = json.writeValueAsString(Map.of("orgSlug", "no-such-org", "username", "x", "password", "y"));
        MvcResult forged = mvc.perform(post("/v1/login").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertEquals(403, forged.getResponse().getStatus(), "a login with no token is refused before it is evaluated");

        String token = fetchToken();
        MvcResult real = mvc.perform(post("/v1/login").contentType(MediaType.APPLICATION_JSON).content(body)
                .cookie(new Cookie(COOKIE, token)).header(HEADER, token)).andReturn();
        assertEquals(401, real.getResponse().getStatus(), "with the token it reaches the credential check, which says no");
    }

    @Test
    void anApiKeyCallIsNotAskedForAToken() throws Exception {
        // No cookie, no token: a machine client. The bogus key is rejected by authentication (401), not by CSRF (403).
        MvcResult result = mvc.perform(post("/v1/ingest/loans").header("X-API-Key", "ak_not-a-real-key")
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andReturn();
        assertEquals(401, result.getResponse().getStatus());

        MvcResult withoutKey = mvc.perform(post("/v1/ingest/loans").contentType(MediaType.APPLICATION_JSON).content("[]")).andReturn();
        assertEquals(403, withoutKey.getResponse().getStatus(), "without the header credential the exemption does not apply");
    }

    @Test
    void theBearerSetupCallsAreExemptOnlyWhenTheyCarryTheBearerHeader() throws Exception {
        MvcResult bearer = mvc.perform(post("/v1/account/2fa/setup").header("Authorization", "Bearer not-a-token")).andReturn();
        assertEquals(401, bearer.getResponse().getStatus(), "reaches authentication, which refuses the bogus token");

        MvcResult cookieOnly = mvc.perform(post("/v1/account/2fa/setup").cookie(sessionCookie(session()))).andReturn();
        assertEquals(403, cookieOnly.getResponse().getStatus(), "a session-cookie request to the same path still needs the token");
    }
}
