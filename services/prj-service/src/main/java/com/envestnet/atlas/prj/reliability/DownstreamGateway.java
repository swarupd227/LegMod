package com.envestnet.atlas.prj.reliability;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

/**
 * Resilience4j-decorated wrapper around the project-wide
 * {@link RestTemplate}. Every public method is annotated with
 * {@code @CircuitBreaker("downstream")} and {@code @Retry("downstream")}
 * so the call gets the policies declared in {@code application.yml}:
 *
 * <ul>
 *   <li>Retry up to 3 attempts with 200ms exponential backoff on
 *       transient failures (HTTP 5xx, connect/read errors).</li>
 *   <li>Trip the circuit breaker if 50% of the last 20 calls fail;
 *       fail fast for 10s before half-opening.</li>
 *   <li>4xx responses are NOT retried — those are deterministic client
 *       errors, not transient.</li>
 * </ul>
 *
 * <p>Usage from controllers / services:
 * <pre>
 *   {@literal @}Autowired DownstreamGateway downstream;
 *   ...
 *   var status = downstream.getForObject(uri, ProjectStatus.class);
 * </pre>
 *
 * <p>Wrap any new fan-out call in this class before merging — the
 * {@code @CircuitBreaker} / {@code @Retry} AOP only applies to public
 * methods called through a Spring proxy, so calling
 * {@code restTemplate.getForObject} directly bypasses the policy.</p>
 */
@Component
public class DownstreamGateway {

    private static final String INSTANCE = "downstream";

    private final RestTemplate http;

    public DownstreamGateway(RestTemplate http) { this.http = http; }

    @CircuitBreaker(name = INSTANCE)
    @Retry(name = INSTANCE)
    public <T> T getForObject(String url, Class<T> type) {
        return http.getForObject(url, type);
    }

    @CircuitBreaker(name = INSTANCE)
    @Retry(name = INSTANCE)
    public <T> T getForObject(URI uri, Class<T> type) {
        return http.getForObject(uri, type);
    }

    @CircuitBreaker(name = INSTANCE)
    @Retry(name = INSTANCE)
    public <T> T postForObject(String url, @Nullable Object body, Class<T> type) {
        return http.postForObject(url, body, type);
    }

    @CircuitBreaker(name = INSTANCE)
    @Retry(name = INSTANCE)
    public <T> ResponseEntity<T> exchange(URI uri, HttpMethod method,
                                           HttpEntity<?> entity, Class<T> type) {
        return http.exchange(uri, method, entity, type);
    }

    /** Direct access for the rare case a call site needs raw RestTemplate. */
    public RestTemplate raw() { return http; }
}
