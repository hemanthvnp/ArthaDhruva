package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CSRF cookie and the refusals as a real servlet container writes them. MockMvc renders the {@code SameSite}
 * attribute only for its own cookie type, so CsrfProtectionTest checks the cookie's attributes; this class starts
 * the application on a random port and reads the raw {@code Set-Cookie} header off the wire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CsrfLiveHeadersTest extends AbstractIntegrationTest {

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void theTokenCookieIsWrittenWithTheIntendedAttributes() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/v1/csrf")).GET());
        assertEquals(401, response.statusCode(), "no handler: the token rides on the 401, which idle-stop.sh does not count as a visitor");
        List<String> cookies = response.headers().allValues("set-cookie");
        String cookie = cookies.stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertTrue(cookie.contains("SameSite=Strict"), cookie);
        assertTrue(cookie.contains("Path=/"), cookie);
        assertFalse(cookie.contains("Path=/v1"), cookie);
        assertFalse(cookie.toLowerCase().contains("httponly"), "the page has to read it: " + cookie);
        assertFalse(cookie.contains("Secure"), "http test deployment: " + cookie);
    }

    @Test
    void aWriteIsRefusedWithoutTheTokenAndReachesTheHandlerWithIt() throws Exception {
        String body = "{\"orgSlug\":\"no-such-org\",\"username\":\"x\",\"password\":\"y\"}";
        HttpResponse<String> refused = send(HttpRequest.newBuilder(uri("/v1/login"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(403, refused.statusCode());

        String set = send(HttpRequest.newBuilder(uri("/v1/csrf")).GET()).headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        String token = set.substring("XSRF-TOKEN=".length(), set.indexOf(';'));
        HttpResponse<String> accepted = send(HttpRequest.newBuilder(uri("/v1/login"))
                .header("Content-Type", "application/json").header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(401, accepted.statusCode(), "with the token the request reaches the credential check");
    }
}
