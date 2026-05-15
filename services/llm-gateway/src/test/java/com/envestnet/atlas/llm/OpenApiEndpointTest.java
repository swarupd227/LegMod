package com.envestnet.atlas.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the OpenAPI contract surface for llm-gateway:
 *   • {@code /v3/api-docs} returns a valid OpenAPI 3.x JSON document;
 *   • the {@code info} block carries the metadata wired in
 *     {@link com.envestnet.atlas.llm.openapi.OpenApiConfig};
 *   • the {@code paths} section enumerates the controller routes;
 *   • {@code /swagger-ui.html} serves the interactive UI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiEndpointTest {

    @LocalServerPort int port;
    @Autowired       TestRestTemplate http;

    private final ObjectMapper json = new ObjectMapper();

    private String url(String path) { return "http://localhost:" + port + path; }

    @Test
    void apiDocsReturnsValidOpenApiSpec() throws Exception {
        ResponseEntity<String> res = http.getForEntity(url("/v3/api-docs"), String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode doc = json.readTree(res.getBody());
        // OpenAPI 3.x version stamp.
        assertThat(doc.get("openapi").asText()).startsWith("3.");
        // Info block carries our wiring.
        assertThat(doc.path("info").path("title").asText())
                .contains("Atlas Migrate")
                .contains("llm-gateway");
        assertThat(doc.path("info").path("version").asText()).isNotBlank();
    }

    @Test
    void specEnumeratesEveryControllerRoute() throws Exception {
        ResponseEntity<String> res = http.getForEntity(url("/v3/api-docs"), String.class);
        JsonNode paths = json.readTree(res.getBody()).path("paths");

        // The two LlmController endpoints must appear.
        assertThat(paths.has("/api/v1/llm/invoke")).as("invoke path").isTrue();
        assertThat(paths.has("/api/v1/llm/health")).as("health path").isTrue();

        // The /invoke endpoint advertises POST.
        assertThat(paths.get("/api/v1/llm/invoke").has("post")).isTrue();
        // /health advertises GET.
        assertThat(paths.get("/api/v1/llm/health").has("get")).isTrue();
    }

    @Test
    void specCarriesGlobalBearerSecurityScheme() throws Exception {
        ResponseEntity<String> res = http.getForEntity(url("/v3/api-docs"), String.class);
        JsonNode doc = json.readTree(res.getBody());

        // Security scheme is registered as a component.
        JsonNode schemes = doc.path("components").path("securitySchemes");
        assertThat(schemes.has("bearer-jwt")).isTrue();
        assertThat(schemes.path("bearer-jwt").path("scheme").asText()).isEqualTo("bearer");
        assertThat(schemes.path("bearer-jwt").path("bearerFormat").asText()).isEqualTo("JWT");

        // And applied globally.
        assertThat(doc.path("security").isArray()).isTrue();
        boolean appliesBearer = false;
        for (JsonNode entry : doc.path("security")) {
            if (entry.has("bearer-jwt")) { appliesBearer = true; break; }
        }
        assertThat(appliesBearer).as("global security applies bearer-jwt").isTrue();
    }

    @Test
    void swaggerUiIsServed() {
        ResponseEntity<String> res = http.getForEntity(url("/swagger-ui/index.html"), String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        // Token check that we got the actual UI HTML, not a 404 page.
        assertThat(res.getBody()).contains("Swagger UI");
    }
}
