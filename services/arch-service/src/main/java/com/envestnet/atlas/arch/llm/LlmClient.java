package com.envestnet.atlas.arch.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin client over the Atlas LLM Gateway. Returns the gateway's structured
 * response untouched so callers can surface stub flag, token counts, etc.
 */
@Component
public class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final RestTemplate http;
    private final String llmUrl;

    public LlmClient(RestTemplate http,
                     @Value("${LLM_GATEWAY_URL:http://llm-gateway:8082}") String llmUrl) {
        this.http = http;
        this.llmUrl = llmUrl;
    }

    public Response invoke(String agent, String task, String system, String prompt,
                           String modelHint, Integer maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent", agent);
        body.put("task", task);
        body.put("system", system);
        body.put("prompt", prompt);
        body.put("modelHint", modelHint);
        if (maxTokens != null) body.put("maxTokens", maxTokens);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        try {
            return http.postForObject(llmUrl + "/api/v1/llm/invoke",
                    new HttpEntity<>(body, h), Response.class);
        } catch (Exception e) {
            log.warn("LLM gateway call failed: {}", e.toString());
            Response stub = new Response();
            stub.model = "error";
            stub.stub = true;
            stub.output = Map.of("text", "[error] " + e.getMessage());
            stub.tokens = Map.of("in", 0, "out", 0);
            return stub;
        }
    }

    public static class Response {
        public String model;
        public boolean stub;
        @JsonProperty("output") public Map<String, Object> output;
        @JsonProperty("tokens") public Map<String, Object> tokens;
        @JsonProperty("cost_usd") public Double costUsd;
        @JsonProperty("latency_ms") public Long latencyMs;

        public String text() {
            if (output == null) return "";
            Object t = output.get("text");
            return t == null ? "" : t.toString();
        }

        public Integer tokensIn() { return num("in"); }
        public Integer tokensOut() { return num("out"); }

        private Integer num(String key) {
            if (tokens == null) return 0;
            Object v = tokens.get(key);
            if (v == null) return 0;
            if (v instanceof Number n) return n.intValue();
            try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
        }
    }
}
