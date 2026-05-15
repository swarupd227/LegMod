package com.envestnet.atlas.reports.agent;

import com.envestnet.atlas.reports.gather.ProjectFacts;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClosureDocAgentTest {

    private static final String LLM_URL = "http://llm-gateway:8082";

    @Test
    void deterministicNarrativeContainsAllRequiredHeadings() {
        ProjectFacts.Snapshot s = snapshot();

        var agent = new ClosureDocAgent(mock(RestTemplate.class), LLM_URL);
        String md = agent.deterministic(s);

        assertThat(md).contains("## Scope and target");
        assertThat(md).contains("## Approach");
        assertThat(md).contains("## Notable decisions");
        assertThat(md).contains("## Test coverage");
        assertThat(md).contains("## Residual risks");
        assertThat(md).contains("## Recommended pre-production gates");
        assertThat(md).contains("## Maintenance notes");
    }

    @Test
    void deterministicNarrativeFoldsInProjectMetadata() {
        ProjectFacts.Snapshot s = snapshot();
        s.project = Map.of(
                "name", "BR Order Mgmt Migration",
                "mode", "SOAP",
                "sourceFramework", "Axis 1.4",
                "targetFramework", "Spring Boot 3.3 + JAX-WS RI",
                "vendorPartner", "Broadridge",
                "currentStage", "F"
        );

        String md = new ClosureDocAgent(mock(RestTemplate.class), LLM_URL).deterministic(s);

        assertThat(md).contains("BR Order Mgmt Migration");
        assertThat(md).contains("SOAP");
        assertThat(md).contains("Broadridge");
        assertThat(md).contains("Spring Boot 3.3 + JAX-WS RI");
    }

    @Test
    void deterministicNarrativeSummarisesStageCounts() {
        ProjectFacts.Snapshot s = snapshot();
        s.operations = List.of(Map.of("name", "submitOrder"), Map.of("name", "cancelOrder"),
                                Map.of("name", "getStatus"));      // 3
        s.adapters   = List.of(Map.of("fqn", "DateAdapter", "kind", "date"),
                                Map.of("fqn", "MoneyAdapter", "kind", "money")); // 2
        s.captureStatus = Map.of("totalEnvelopes", 4250);
        s.reconStatus = Map.of("counts", Map.of(
                "total", 12, "resolved", 10, "pending", 2));
        s.genStatus = Map.of("run", Map.of("fileCount", 87));
        s.diffStatus = Map.of("run", Map.of(
                "passCount", 4000, "benignCount", 200, "amberCount", 30, "redCount", 0));

        String md = new ClosureDocAgent(mock(RestTemplate.class), LLM_URL).deterministic(s);

        assertThat(md).contains("3 operations");
        assertThat(md).contains("2 adapters");
        assertThat(md).contains("10 of 12 divergences");
        assertThat(md).contains("87"); // file count
        // Diff bucket line: pass · benign · amber · red, in that order.
        assertThat(md).contains("byte-equivalent");
        // No red diffs ⇒ green-light residual-risks blurb.
        assertThat(md).contains("No red-bucket divergences remain.");
    }

    @Test
    void deterministicNarrativeSurfacesRedDivergencesInResidualRisks() {
        ProjectFacts.Snapshot s = snapshot();
        s.diffStatus = Map.of("run", Map.of(
                "passCount", 100, "benignCount", 5,
                "amberCount", 2, "redCount", 7));

        String md = new ClosureDocAgent(mock(RestTemplate.class), LLM_URL).deterministic(s);

        assertThat(md).contains("7 red-bucket divergence(s) remain unresolved");
        assertThat(md).doesNotContain("No red-bucket divergences remain.");
    }

    @Test
    void deterministicNarrativeIsRobustWhenStageStatusesAreMissing() {
        // Empty snapshot — every counter falls through to 0; agent must not throw.
        ProjectFacts.Snapshot s = new ProjectFacts.Snapshot();
        s.projectId = UUID.randomUUID();

        String md = new ClosureDocAgent(mock(RestTemplate.class), LLM_URL).deterministic(s);

        assertThat(md).contains("## Scope and target");
        assertThat(md).contains("0 operations");
        assertThat(md).contains("0 adapters");
        assertThat(md).contains("(unnamed)"); // default project name
    }

    @Test
    void generateUsesLlmTextWhenGatewayReturnsRealResponse() {
        ProjectFacts.Snapshot s = snapshot();
        RestTemplate http = mock(RestTemplate.class);

        var resp = new ClosureDocAgent.LlmResponse();
        resp.model = "claude-opus-4-7";
        resp.stub  = false;
        resp.output = Map.of("text", "## Scope and target\n\nReal LLM narrative.\n");
        when(http.postForObject(contains("/api/v1/llm/invoke"), any(), eq(ClosureDocAgent.LlmResponse.class)))
                .thenReturn(resp);

        var result = new ClosureDocAgent(http, LLM_URL).generate(s);

        assertThat(result.stub()).isFalse();
        assertThat(result.model()).isEqualTo("claude-opus-4-7");
        assertThat(result.markdown()).isEqualTo("## Scope and target\n\nReal LLM narrative.");
    }

    @Test
    void generateFallsBackToDeterministicWhenGatewayMarksResponseAsStub() {
        ProjectFacts.Snapshot s = snapshot();
        RestTemplate http = mock(RestTemplate.class);

        var resp = new ClosureDocAgent.LlmResponse();
        resp.model = "stub";
        resp.stub  = true;
        resp.output = Map.of("text", "[stub] llm-gateway has no upstream key configured");
        when(http.postForObject(contains("/api/v1/llm/invoke"), any(), eq(ClosureDocAgent.LlmResponse.class)))
                .thenReturn(resp);

        var result = new ClosureDocAgent(http, LLM_URL).generate(s);

        assertThat(result.stub()).isTrue();
        // Deterministic narrative is still embedded — the H2 sections are present.
        assertThat(result.markdown()).contains("## Scope and target");
        // The stub suffix exposes the upstream message for diagnostics.
        assertThat(result.markdown()).contains("stub fallback used");
        assertThat(result.markdown()).contains("llm-gateway has no upstream key");
    }

    @Test
    void generateHandlesNullResponseAsStubFallback() {
        ProjectFacts.Snapshot s = snapshot();
        RestTemplate http = mock(RestTemplate.class);
        when(http.postForObject(contains("/api/v1/llm/invoke"), any(), eq(ClosureDocAgent.LlmResponse.class)))
                .thenReturn(null);

        var result = new ClosureDocAgent(http, LLM_URL).generate(s);

        assertThat(result.stub()).isTrue();
        assertThat(result.model()).isNull();
        assertThat(result.markdown()).contains("## Scope and target");
        assertThat(result.markdown()).contains("[stub: no response]");
    }

    @Test
    void generateHandlesTransportExceptionAsStubFallback() {
        ProjectFacts.Snapshot s = snapshot();
        RestTemplate http = mock(RestTemplate.class);
        when(http.postForObject(contains("/api/v1/llm/invoke"), any(), eq(ClosureDocAgent.LlmResponse.class)))
                .thenThrow(new RestClientException("connection refused"));

        var result = new ClosureDocAgent(http, LLM_URL).generate(s);

        assertThat(result.stub()).isTrue();
        assertThat(result.markdown()).contains("## Scope and target");
        assertThat(result.markdown()).contains("[stub: transport: connection refused]");
    }

    @Test
    void llmResponseTextHandlesMissingOutputMap() {
        var r = new ClosureDocAgent.LlmResponse();
        // output left null
        assertThat(r.text()).isEmpty();

        r.output = Map.of(); // missing "text"
        assertThat(r.text()).isEmpty();

        r.output = Map.of("text", "hello");
        assertThat(r.text()).isEqualTo("hello");
    }

    /* ---------------- helpers ---------------- */

    private static ProjectFacts.Snapshot snapshot() {
        ProjectFacts.Snapshot s = new ProjectFacts.Snapshot();
        s.projectId = UUID.randomUUID();
        s.project = new java.util.LinkedHashMap<>(Map.of(
                "name", "Demo",
                "mode", "SOAP",
                "vendorPartner", "VendorX",
                "targetFramework", "Spring Boot 3.3"
        ));
        s.operations = List.of();
        s.adapters   = List.of();
        s.captureStatus = Map.of();
        s.reconStatus   = Map.of();
        s.genStatus     = Map.of();
        s.diffStatus    = Map.of();
        return s;
    }
}
