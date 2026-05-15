package com.envestnet.atlas.gateway;

import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Stamps every routed request with an {@code X-Atlas-Request-Id} header
 * — using the client's value if present, generating fresh otherwise —
 * and echoes it back on the response. Downstream services pick the
 * header up into their SLF4J MDC via {@code RequestContextFilter}, so
 * every log line emitted while servicing the request carries the same
 * correlation id.
 *
 * <p>Runs early in the gateway filter chain so the header is set
 * before identity forwarding and routing both see it.</p>
 */
@Component
public class RequestIdFilter implements GlobalFilter, Ordered {

    public static final String HDR_REQUEST_ID = "X-Atlas-Request-Id";
    public static final String HDR_TRACE_ID   = "X-Atlas-Trace-Id";

    /**
     * Optional — Micrometer's {@link Tracer} is auto-configured when
     * the tracing-bridge-otel dependency is on the classpath. We mark
     * it nullable so this filter still works in tests that don't bring
     * the tracing autoconfig up.
     */
    private final @Nullable Tracer tracer;

    public RequestIdFilter(@Autowired(required = false) @Nullable Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String existing = exchange.getRequest().getHeaders().getFirst(HDR_REQUEST_ID);
        String reqId = (existing == null || existing.isBlank())
                ? UUID.randomUUID().toString()
                : existing;

        // Replace the header on the outbound request so downstream services
        // see the same value (whether we generated it or not). We use a
        // ServerHttpRequestDecorator with a fresh (writable) HttpHeaders
        // because `request.mutate().header(...)` can run into a
        // ReadOnlyHttpHeaders depending on where in the filter chain
        // we're invoked.
        ServerHttpRequest stamped = withRequestId(exchange.getRequest(), reqId);

        // And echo it on the response so the browser / curl / tracing
        // infra can correlate.
        exchange.getResponse().getHeaders().set(HDR_REQUEST_ID, reqId);

        // Echo the trace id too, when a span exists. The SPA reads this
        // to deep-link to Jaeger from the audit browser.
        if (tracer != null) {
            var span = tracer.currentSpan();
            if (span != null) {
                exchange.getResponse().getHeaders().set(HDR_TRACE_ID,
                        span.context().traceId());
            }
        }

        return chain.filter(exchange.mutate().request(stamped).build());
    }

    private static ServerHttpRequest withRequestId(ServerHttpRequest original, String reqId) {
        return new ServerHttpRequestDecorator(original) {
            private HttpHeaders cached;
            @Override
            public HttpHeaders getHeaders() {
                if (cached == null) {
                    HttpHeaders out = new HttpHeaders();
                    super.getHeaders().forEach((name, values) -> {
                        if (!HDR_REQUEST_ID.equalsIgnoreCase(name)) {
                            out.addAll(name, values);
                        }
                    });
                    out.set(HDR_REQUEST_ID, reqId);
                    cached = HttpHeaders.readOnlyHttpHeaders(out);
                }
                return cached;
            }
        };
    }

    @Override
    public int getOrder() {
        // Run before IdentityForwardingFilter so identity propagation can
        // log the request id alongside the user. Use HIGHEST_PRECEDENCE
        // plus a small offset to keep room for any future filter that
        // needs to run before us.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
