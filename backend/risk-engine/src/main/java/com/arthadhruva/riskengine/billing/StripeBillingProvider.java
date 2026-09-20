package com.arthadhruva.riskengine.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Stripe over its plain REST API (form-encoded POSTs, bearer secret key), enabled only when
 * {@code billing.stripe.secret-key} is set. Never lets a Stripe outage break signup or a plan
 * change: failures are logged and reported as "unavailable". Price ids per plan come from
 * {@code billing.stripe.price.<plan code>} (created in the Stripe dashboard).
 */
@Component
@ConditionalOnExpression("'${billing.stripe.secret-key:}' != ''")
class StripeBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(StripeBillingProvider.class);

    private final RestClient client;
    private final org.springframework.core.env.Environment env;
    private final ObjectMapper mapper = new ObjectMapper();

    StripeBillingProvider(@Value("${billing.stripe.secret-key}") String secretKey,
                          @Value("${billing.stripe.base-url:https://api.stripe.com/v1}") String baseUrl,
                          org.springframework.core.env.Environment env) {
        this.env = env;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + secretKey).build();
    }

    @Override
    public Optional<String> createCustomer(String organizationName, String email, String organizationSlug) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("name", organizationName);
            if (email != null && !email.isBlank()) {
                form.add("email", email);
            }
            form.add("metadata[org_slug]", organizationSlug);
            String body = client.post().uri("/customers").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(String.class);
            JsonNode json = mapper.readTree(body);
            return Optional.ofNullable(json.get("id")).map(JsonNode::asString);
        } catch (Exception e) {
            log.warn("Stripe customer creation failed for '{}': {}", organizationSlug, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public boolean changePlan(String customerId, String planCode) {
        String price = env.getProperty("billing.stripe.price." + planCode.toLowerCase());
        if (customerId == null || price == null) {
            log.warn("Stripe plan change skipped: missing customer or price id for plan {}", planCode);
            return false;
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("customer", customerId);
            form.add("items[0][price]", price);
            client.post().uri("/subscriptions").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Stripe subscription change failed for {}: {}", customerId, e.toString());
            return false;
        }
    }
}
