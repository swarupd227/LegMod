package com.envestnet.atlas.arch.agent;

import com.envestnet.atlas.arch.analysis.SourceWalker;
import com.envestnet.atlas.arch.llm.LlmClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds a focused prompt for the Code Archaeology agent and runs it through
 * the LLM Gateway. Output is plain prose — UI displays it verbatim. The agent
 * is steered to be concise (≤4 sentences) and to flag wire-impacting patterns.
 */
@Component
public class ArchaeologyAgent {

    private static final String SYSTEM = """
            You are the Code Archaeology Agent inside Atlas Migrate, a tool
            that helps senior engineers migrate legacy SOAP services to JAX-WS.

            For the operation provided, you receive structured facts pulled
            from the legacy source (type mappings, adapters, flags, source
            class). Produce a SHORT narrative (3-4 sentences) that:
              1. Names what the operation does in plain language.
              2. Calls out any wire-impacting custom serialization (date,
                 enum, decimal precision) — these are the things that, if
                 the migration silently changes them, will break partner
                 integrations.
              3. States a confidence — high/medium/low — and the one
                 observation that drove it.

            Do not invent facts. Do not propose code. Do not list bullets.
            One paragraph, ≤4 sentences. Audience: a senior Java engineer.
            """;

    private final LlmClient llm;

    public ArchaeologyAgent(LlmClient llm) { this.llm = llm; }

    public LlmClient.Response narrate(SourceWalker.Operation op,
                                      List<SourceWalker.TypeMapping> mappings,
                                      List<SourceWalker.Adapter> adapters) {
        String prompt = """
                Operation: %s (namespace %s)
                Source: %s line %d
                Input type:  %s
                Output type: %s
                Faults: %s
                Flags: %s

                Type mappings touched by this operation:
                %s

                Custom adapters in the project:
                %s

                Write the narrative now.
                """.formatted(
                        op.name, op.namespace,
                        op.sourceClass, op.sourceLines[0],
                        op.inputType, op.outputType,
                        op.faultTypes.isEmpty() ? "none" : String.join(", ", op.faultTypes),
                        op.flags.isEmpty() ? "none" : String.join(", ", op.flags),
                        mappings.isEmpty()
                                ? "  (none)"
                                : mappings.stream()
                                        .limit(8)
                                        .map(m -> "  - " + m.javaType + "  →  {"
                                                + m.qnameNamespace + "}" + m.qnameLocal
                                                + (m.adapterFqn != null && !m.adapterFqn.isBlank()
                                                        ? " via " + m.adapterFqn : ""))
                                        .collect(Collectors.joining("\n")),
                        adapters.isEmpty()
                                ? "  (none)"
                                : adapters.stream()
                                        .map(a -> "  - " + a.fqn
                                                + " (" + a.kind
                                                + (a.pattern != null ? ", pattern: " + a.pattern : "")
                                                + ")")
                                        .collect(Collectors.joining("\n"))
                );

        return llm.invoke("code-archaeology", "narrate-operation",
                SYSTEM, prompt, "sonnet", 350);
    }
}
