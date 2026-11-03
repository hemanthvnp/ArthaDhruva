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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The API's OpenAPI description, generated from the controllers and records, against the copy committed at
 * {@code frontend/src/api/openapi.json}. The frontend checks its hand-written types against that copy
 * (the {@code tsc -b} step of {@code npm run build}), so a change to a Java record that the frontend has not caught up with fails
 * one side or the other instead of reaching a browser.
 *
 * <p>After an intended API change: {@code ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true}, then
 * {@code npm run api:types} then {@code npx tsc -b} in {@code frontend/}, and commit the new copy with the change.
 */
class OpenApiContractTest extends AbstractIntegrationTest {

    static final Path COMMITTED = Path.of("..", "..", "frontend", "src", "api", "openapi.json");

    @Autowired MockMvc mvc;
    @Autowired OrganizationService organizations;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;

    private final ObjectMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Test
    void theCommittedDescriptionIsTheOneTheServiceGenerates() throws Exception {
        Organization org = organizations.create("openapi-" + UUID.randomUUID().toString().substring(0, 8), "OpenAPI Bank", false, null, null);
        String session;
        TenantContext.set(org.getId());
        try {
            session = jwt.issueSession(users.save(new User(org, "olga", encoder.encode("Correct-Horse-9-Battery"), Role.ANALYST))).token();
        } finally {
            TenantContext.clear();
        }
        MvcResult result = mvc.perform(get("/v1/api-docs").cookie(new Cookie(SessionCookie.NAME, session))).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/v1/api-docs")).andReturn().getResponse().getStatus(), "not for anyone signed out");

        JsonNode spec = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        ((ObjectNode) spec).remove("servers");   // the test server's address, not part of the API
        String generated = json.writeValueAsString(spec).replace("\r\n", "\n") + "\n";
        assertTrue(generated.contains("\"/v1/loans/{loanId}/case\""), "paths carry the /v1 prefix");

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(COMMITTED, generated, StandardCharsets.UTF_8);
            return;
        }
        assertTrue(Files.exists(COMMITTED), "no committed copy at " + COMMITTED.toAbsolutePath()
                + "; create it with -Dopenapi.update=true");
        String committed = Files.readString(COMMITTED, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(committed, generated, "The API changed but frontend/src/api/openapi.json did not. If the change is "
                + "intended, regenerate it with -Dopenapi.update=true, run `npm run api:types` and `npx tsc -b` in frontend/ and commit both.");
    }
}
