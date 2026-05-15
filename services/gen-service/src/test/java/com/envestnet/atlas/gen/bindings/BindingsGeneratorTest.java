package com.envestnet.atlas.gen.bindings;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BindingsGeneratorTest {

    private static final String ARCH_URL = "http://arch-service:8083";

    @Test
    void emitsHeaderAndGlobalBindingsAndPackageBinding() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(contains("/internal/archaeology/adapters/"), eq(Object[].class)))
                .thenReturn(new Object[0]);

        var gen = new BindingsGenerator(http, ARCH_URL);
        UUID pid = UUID.randomUUID();

        String xml = gen.generate(pid, "com.envestnet.broadridge");

        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(xml).contains("Atlas Migrate");
        assertThat(xml).contains("Project: " + pid);
        assertThat(xml).contains("xmlns:jaxb=\"https://jakarta.ee/xml/ns/jaxb\"");
        assertThat(xml).contains("<jaxb:globalBindings>");
        assertThat(xml).contains("<jaxb:javaType name=\"java.lang.String\" xmlType=\"xs:string\"/>");
        assertThat(xml).contains("<jaxb:package name=\"com.envestnet.broadridge\"/>");
        assertThat(xml).endsWith("</jaxb:bindings>\n");
    }

    @Test
    void noAdapterBindingsWhenAdapterListIsEmpty() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(contains("/internal/archaeology/adapters/"), eq(Object[].class)))
                .thenReturn(new Object[0]);

        var gen = new BindingsGenerator(http, ARCH_URL);
        String xml = gen.generate(UUID.randomUUID(), "com.envestnet.x");

        assertThat(xml).doesNotContain("date adapter detected");
        assertThat(xml).doesNotContain("DateAdapter.parse");
    }

    @Test
    void dateAdapterEmitsTradeDateAndSettlementDateBindings() {
        RestTemplate http = mock(RestTemplate.class);
        Object[] adapters = new Object[]{
                Map.of("fqn", "com.legacy.broadridge.DateAdapter", "kind", "date")
        };
        when(http.getForObject(contains("/internal/archaeology/adapters/"), eq(Object[].class)))
                .thenReturn(adapters);

        var gen = new BindingsGenerator(http, ARCH_URL);
        String xml = gen.generate(UUID.randomUUID(), "com.envestnet.broadridge");

        assertThat(xml).contains("date adapter detected in archaeology: com.legacy.broadridge.DateAdapter");
        assertThat(xml).contains("xs:element[@name='tradeDate']");
        assertThat(xml).contains("xs:element[@name='settlementDate']");
        assertThat(xml).contains("parseMethod=\"com.envestnet.broadridge.adapters.DateAdapter.parse\"");
        assertThat(xml).contains("printMethod=\"com.envestnet.broadridge.adapters.DateAdapter.print\"");
    }

    @Test
    void nonDateAdaptersAreIgnored() {
        RestTemplate http = mock(RestTemplate.class);
        Object[] adapters = new Object[]{
                Map.of("fqn", "com.legacy.MoneyAdapter", "kind", "money"),
                Map.of("fqn", "com.legacy.NameAdapter",  "kind", "string")
        };
        when(http.getForObject(contains("/internal/archaeology/adapters/"), eq(Object[].class)))
                .thenReturn(adapters);

        var gen = new BindingsGenerator(http, ARCH_URL);
        String xml = gen.generate(UUID.randomUUID(), "com.envestnet.x");

        assertThat(xml).doesNotContain("date adapter detected");
        assertThat(xml).doesNotContain("DateAdapter.parse");
        // Non-date adapters do not surface anywhere in the bindings XML.
        assertThat(xml).doesNotContain("MoneyAdapter");
        assertThat(xml).doesNotContain("NameAdapter");
    }

    @Test
    void multipleDateAdaptersEachEmitTheirOwnCommentBlock() {
        RestTemplate http = mock(RestTemplate.class);
        Object[] adapters = new Object[]{
                Map.of("fqn", "com.legacy.A", "kind", "date"),
                Map.of("fqn", "com.legacy.B", "kind", "date")
        };
        when(http.getForObject(contains("/internal/archaeology/adapters/"), eq(Object[].class)))
                .thenReturn(adapters);

        var gen = new BindingsGenerator(http, ARCH_URL);
        String xml = gen.generate(UUID.randomUUID(), "com.envestnet.x");

        assertThat(xml).contains("date adapter detected in archaeology: com.legacy.A");
        assertThat(xml).contains("date adapter detected in archaeology: com.legacy.B");
        // Each date adapter contributes a tradeDate binding block — count them.
        int tradeDateBlocks = xml.split("xs:element\\[@name='tradeDate'\\]", -1).length - 1;
        assertThat(tradeDateBlocks).isEqualTo(2);
    }

    @Test
    void archServiceFailureProducesValidBindingsWithNoAdapterCustomizations() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(contains("/internal/archaeology/adapters/"), eq(Object[].class)))
                .thenThrow(new RestClientException("arch-service down"));

        var gen = new BindingsGenerator(http, ARCH_URL);
        String xml = gen.generate(UUID.randomUUID(), "com.envestnet.x");

        // Failure mode is graceful — empty adapter list, no exception, valid wrapper.
        assertThat(xml).contains("<jaxb:globalBindings>");
        assertThat(xml).contains("<jaxb:package name=\"com.envestnet.x\"/>");
        assertThat(xml).doesNotContain("date adapter detected");
    }

    @Test
    void nullAdapterResponseIsTreatedAsEmptyList() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(contains("/internal/archaeology/adapters/"), eq(Object[].class)))
                .thenReturn(null);

        var gen = new BindingsGenerator(http, ARCH_URL);
        String xml = gen.generate(UUID.randomUUID(), "com.envestnet.x");

        assertThat(xml).doesNotContain("date adapter detected");
        // Wrapper still well-formed.
        assertThat(xml).contains("</jaxb:bindings>");
    }

    @Test
    void packageNameIsPropagatedIntoAdapterMethodReferences() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(contains("/internal/archaeology/adapters/"), eq(Object[].class)))
                .thenReturn(new Object[]{ Map.of("fqn", "x", "kind", "date") });

        var gen = new BindingsGenerator(http, ARCH_URL);
        String xml = gen.generate(UUID.randomUUID(), "com.acme.lpl");

        assertThat(xml).contains("com.acme.lpl.adapters.DateAdapter.parse");
        assertThat(xml).contains("com.acme.lpl.adapters.DateAdapter.print");
        assertThat(xml).contains("<jaxb:package name=\"com.acme.lpl\"/>");
    }

    @Test
    void nonMapEntriesInAdapterResponseAreSkippedSilently() {
        RestTemplate http = mock(RestTemplate.class);
        // Mix in a String — current loadAdapters() filters these out via instanceof Map.
        Object[] mixed = new Object[]{
                "not-a-map",
                Map.of("fqn", "com.legacy.D", "kind", "date")
        };
        when(http.getForObject(contains("/internal/archaeology/adapters/"), eq(Object[].class)))
                .thenReturn(mixed);

        var gen = new BindingsGenerator(http, ARCH_URL);
        String xml = gen.generate(UUID.randomUUID(), "com.envestnet.x");

        assertThat(xml).contains("date adapter detected in archaeology: com.legacy.D");
        // The malformed string entry never surfaces.
        assertThat(xml).doesNotContain("not-a-map");
    }

    @Test
    void adapterWithNullKindDoesNotEmitDateBindings() {
        // String.valueOf(null) returns the literal "null", which must NOT match "date".
        var withNullKind = new java.util.HashMap<String, Object>();
        withNullKind.put("fqn", "com.legacy.NoKind");
        withNullKind.put("kind", null);

        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(contains("/internal/archaeology/adapters/"), eq(Object[].class)))
                .thenReturn(new Object[]{ withNullKind });

        var gen = new BindingsGenerator(http, ARCH_URL);
        String xml = gen.generate(UUID.randomUUID(), "com.envestnet.x");

        assertThat(xml).doesNotContain("date adapter detected");
    }
}
