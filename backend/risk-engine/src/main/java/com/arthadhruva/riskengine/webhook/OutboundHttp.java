package com.arthadhruva.riskengine.webhook;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The one HTTP client for requests to tenant-supplied URLs (webhook deliveries, OIDC discovery, token
 * and JWKS endpoints). Its DNS resolver applies {@link UrlGuard}'s address policy to every address a
 * connection is about to use, so DNS rebinding cannot slip a private address past a check made earlier.
 * Redirects are never followed (a redirect could point anywhere), cookies are not kept, and every call
 * is bounded by connect (3s) and response (5s) timeouts.
 */
@Component
public class OutboundHttp implements DisposableBean {

    private final CloseableHttpClient client;
    private final RestClient restClient;

    public OutboundHttp(UrlGuard guard) {
        DnsResolver guarded = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] addresses = SystemDefaultDnsResolver.INSTANCE.resolve(host);
                for (InetAddress address : addresses) {
                    if (!guard.isAllowedTarget(address)) {
                        throw new UnknownHostException("Refusing to connect: " + host + " resolves to a non-public address");
                    }
                }
                return addresses;
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
            }
        };
        this.client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(guarded)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofSeconds(3))
                                .setSocketTimeout(Timeout.ofSeconds(5))
                                .build())
                        .setMaxConnTotal(50)
                        .setMaxConnPerRoute(10)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(5)).build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAutomaticRetries()
                .build();
        this.restClient = RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(client)).build();
    }

    public final ClientHttpRequestFactory requestFactory() {
        return new HttpComponentsClientHttpRequestFactory(client);
    }

    /** POSTs a JSON body; returns the HTTP status (the response body is read and discarded). */
    public int postJson(java.net.URI uri, java.util.Map<String, String> headers, String body) throws IOException {
        var post = new org.apache.hc.client5.http.classic.methods.HttpPost(uri);
        headers.forEach(post::setHeader);
        post.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(body,
                org.apache.hc.core5.http.ContentType.APPLICATION_JSON));
        return client.execute(post, response -> {
            org.apache.hc.core5.http.io.entity.EntityUtils.consume(response.getEntity());
            return response.getCode();
        });
    }

    public RestClient restClient() {
        return restClient;
    }

    @Override
    public void destroy() throws IOException {
        client.close();
    }
}
