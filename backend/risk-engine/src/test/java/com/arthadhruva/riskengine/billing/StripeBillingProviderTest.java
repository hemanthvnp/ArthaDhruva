package com.arthadhruva.riskengine.billing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the real HTTP adapter against a local fake Stripe: request shape, auth, and failure isolation. */
class StripeBillingProviderTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " auth="
                    + exchange.getRequestHeaders().getFirst("Authorization") + " body=" + body);
            byte[] response = "{\"id\":\"cus_test123\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private StripeBillingProvider provider() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new StripeBillingProvider("sk_test_abc", base, new MockEnvironment().withProperty("billing.stripe.price.pro", "price_pro_1"));
    }

    @Test
    void createsCustomerWithBearerAuthAndFormBody() {
        var id = provider().createCustomer("Acme Co", "a@acme.test", "acme");
        assertEquals("cus_test123", id.orElseThrow());
        String req = requests.get(0);
        assertTrue(req.startsWith("POST /customers auth=Bearer sk_test_abc"), req);
        assertTrue(req.contains("name=Acme+Co") || req.contains("name=Acme%20Co"), req);
        assertTrue(req.contains("metadata%5Borg_slug%5D=acme") || req.contains("metadata[org_slug]=acme"), req);
    }

    @Test
    void providerErrorNeverPropagates() {
        status = 500;
        assertTrue(provider().createCustomer("Acme", "a@acme.test", "acme").isEmpty());
        assertFalse(provider().changePlan("cus_1", "PRO"));
    }

    @Test
    void changePlanUsesConfiguredPriceAndSkipsWhenUnconfigured() {
        assertTrue(provider().changePlan("cus_1", "PRO"));
        assertTrue(requests.get(0).contains("price_pro_1"), requests.get(0));
        assertFalse(provider().changePlan("cus_1", "STARTER"), "no price configured for STARTER");
    }
}
