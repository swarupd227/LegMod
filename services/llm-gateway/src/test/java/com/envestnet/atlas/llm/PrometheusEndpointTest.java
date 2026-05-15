package com.envestnet.atlas.llm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Prometheus scrape contract for llm-gateway:
 *  • {@code /actuator/prometheus} is reachable and returns the Prometheus
 *    text-exposition payload;
 *  • the standard JVM + HTTP server metrics families are present;
 *  • exercising the LLM stub endpoint produces an
 *    {@code atlas_llm_invocation_seconds} timer reading.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.endpoints.web.exposure.include=health,info,prometheus",
            "management.endpoint.prometheus.enabled=true"
        })
// Spring Boot tests disable observability auto-config by default to keep
// runs fast and side-effect-free. Re-enable it so the prometheus
// scrape endpoint registers, mirroring the production wiring.
@AutoConfigureObservability
class PrometheusEndpointTest {

    @LocalServerPort int port;
    @Autowired       TestRestTemplate http;

    private String url(String path) { return "http://localhost:" + port + path; }

    @Test
    void prometheusEndpointIsReachableAndReturnsTextExposition() {
        ResponseEntity<String> probe = http.getForEntity(url("/actuator"), String.class);
        // Diagnostic: list discovered actuator endpoints if /prometheus 404s.
        ResponseEntity<String> res = http.getForEntity(url("/actuator/prometheus"), String.class);
        assertThat(res.getStatusCode().is2xxSuccessful())
                .as("expected 2xx; /actuator listing was: %s", probe.getBody())
                .isTrue();

        String body = res.getBody();
        assertThat(body).isNotBlank();
        // Sanity-check that the standard Spring Boot / JVM metrics show up.
        // If these aren't there, micrometer-registry-prometheus isn't on
        // the classpath or the actuator endpoint isn't exposed.
        assertThat(body).contains("jvm_memory_used_bytes");
        assertThat(body).contains("jvm_threads_states_threads");
    }

    @Test
    void atlasLlmTimerEmitsAfterInvokeStubPath() {
        // /invoke without an Anthropic API key falls into the stub path
        // and stops the timer with outcome=stub_no_key.
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> invoke = http.postForEntity(
                url("/api/v1/llm/invoke"),
                new HttpEntity<>("{\"prompt\":\"ping\",\"modelHint\":\"sonnet\"}", headers),
                String.class);
        assertThat(invoke.getStatusCode().is2xxSuccessful()).isTrue();

        // Now scrape and look for the timer.
        ResponseEntity<String> res = http.getForEntity(url("/actuator/prometheus"), String.class);
        String body = res.getBody();
        assertThat(body).contains("atlas_llm_invocation_seconds");
        // Tagged with the model + outcome we expect.
        assertThat(body).contains("model=\"claude-sonnet-4-6\"");
        assertThat(body).contains("outcome=\"stub_no_key\"");
        // Timer.builder(...).publishPercentiles(0.5, 0.95, 0.99) means
        // the body carries percentile readings as separate samples.
        assertThat(body).contains("quantile=\"0.5\"");
        assertThat(body).contains("quantile=\"0.95\"");
        assertThat(body).contains("quantile=\"0.99\"");
    }

    @Test
    void unauthenticatedActuatorScrapeDoesNotRequireAuth() {
        // The gateway is the auth boundary; downstream services trust the
        // network. /actuator/prometheus must be reachable without a JWT
        // so Prometheus can scrape directly inside the docker network.
        MultiValueMap<String, String> noHeaders = new LinkedMultiValueMap<>();
        ResponseEntity<String> res = http.exchange(
                url("/actuator/prometheus"),
                HttpMethod.GET,
                new HttpEntity<>(noHeaders),
                String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
    }
}
