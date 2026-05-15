package com.envestnet.atlas.prj.reliability;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side idempotency for mutating requests (POST / PUT / DELETE).
 *
 * <p>When a request carries an {@code Idempotency-Key} header, the
 * filter:
 * <ol>
 *   <li>Looks the key up in an in-memory TTL cache.</li>
 *   <li>On hit, replays the cached status code + body without invoking
 *       the controller. The {@code Idempotent-Replay} response header
 *       is set so callers (and operators reading logs) can tell.</li>
 *   <li>On miss, lets the request through, caches the response if it's
 *       a 2xx (the only outcome we want to replay — 4xx are
 *       deterministic client errors, 5xx are likely transient).</li>
 * </ol>
 *
 * <p>The cache is in-memory and per-instance — adequate for single-pod
 * Atlas deployments and the local-Docker demo. Multi-pod production
 * needs a shared store (Redis); a swappable {@link IdempotencyCache}
 * interface lives below for that purpose.</p>
 *
 * <p>Idempotency keys are scoped to the request URI + HTTP method, so
 * the same key reused on a different endpoint doesn't accidentally
 * replay an unrelated response.</p>
 *
 * <p>The filter only kicks in on POST/PUT/DELETE — GET/HEAD/OPTIONS are
 * already idempotent by HTTP semantics.</p>
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HDR_KEY    = "Idempotency-Key";
    public static final String HDR_REPLAY = "Idempotent-Replay";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Duration TTL = Duration.ofMinutes(10);

    private final IdempotencyCache cache;

    public IdempotencyFilter(IdempotencyCache cache) { this.cache = cache; }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                     HttpServletResponse res,
                                     FilterChain chain)
            throws ServletException, IOException {

        if (!isMutating(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }

        String key = req.getHeader(HDR_KEY);
        if (key == null || key.isBlank()) {
            // No idempotency key — let the request through normally.
            // Callers that want at-most-once semantics MUST send the header.
            chain.doFilter(req, res);
            return;
        }

        // Key is scoped to method + URI so the same UUID re-used across
        // unrelated endpoints can't accidentally short-circuit.
        String scopedKey = req.getMethod() + " " + req.getRequestURI() + " " + key;

        // 1. Cache hit → replay.
        IdempotencyCache.Entry hit = cache.get(scopedKey);
        if (hit != null) {
            log.debug("idempotency replay for key={} method={} uri={}",
                    key, req.getMethod(), req.getRequestURI());
            res.setStatus(hit.status());
            res.setHeader(HDR_REPLAY, "true");
            hit.headers().forEach(res::setHeader);
            res.getOutputStream().write(hit.body());
            res.flushBuffer();
            return;
        }

        // 2. Cache miss → execute, capture, cache if successful.
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(res);
        chain.doFilter(req, wrapped);

        int status = wrapped.getStatus();
        if (status >= 200 && status < 300) {
            String contentType = wrapped.getContentType();
            cache.put(scopedKey, new IdempotencyCache.Entry(
                    status,
                    wrapped.getContentAsByteArray(),
                    contentType == null
                            ? Map.of()
                            : Map.of("Content-Type", contentType),
                    Instant.now().plus(TTL)));
        }
        // Always copy buffered body back to the real response.
        wrapped.copyBodyToResponse();
    }

    private static boolean isMutating(String method) {
        if (method == null) return false;
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    /**
     * Storage abstraction. The default implementation
     * ({@link InMemoryIdempotencyCache}) is fine for local-Docker and
     * single-pod deployments. Customers running multi-pod prj-service
     * should replace this bean with a Redis-backed implementation so
     * idempotency holds across replicas.
     */
    public interface IdempotencyCache {
        @Nullable Entry get(String scopedKey);
        void put(String scopedKey, Entry entry);

        /**
         * @param expiresAt absolute timestamp; entries past this are
         *                  swept on the next access.
         */
        record Entry(int status, byte[] body, Map<String, String> headers,
                     @JsonIgnore Instant expiresAt) {}
    }

    @Component
    public static class InMemoryIdempotencyCache implements IdempotencyCache {
        private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

        @Override
        public Entry get(String scopedKey) {
            Entry e = map.get(scopedKey);
            if (e == null) return null;
            if (Instant.now().isAfter(e.expiresAt())) {
                map.remove(scopedKey, e);
                return null;
            }
            return e;
        }

        @Override
        public void put(String scopedKey, Entry entry) {
            map.put(scopedKey, entry);
            // Cheap opportunistic sweep so the map doesn't grow without bound.
            // Bounded to ~256 entries removed per put to keep latency steady.
            if (map.size() > 4096) {
                int swept = 0;
                Instant now = Instant.now();
                for (var it = map.entrySet().iterator(); it.hasNext() && swept < 256; ) {
                    var e = it.next();
                    if (now.isAfter(e.getValue().expiresAt())) { it.remove(); swept++; }
                }
            }
        }
    }

    /** Allow the cache.Entry to carry @Nullable annotations cleanly. */
    @java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD,
            java.lang.annotation.ElementType.PARAMETER})
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    private @interface Nullable {}
}
