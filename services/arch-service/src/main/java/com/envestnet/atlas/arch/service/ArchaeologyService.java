package com.envestnet.atlas.arch.service;

import com.envestnet.atlas.arch.agent.ArchaeologyAgent;
import com.envestnet.atlas.arch.analysis.SourceWalker;
import com.envestnet.atlas.arch.domain.*;
import com.envestnet.atlas.arch.graph.GraphPublisher;
import com.envestnet.atlas.arch.llm.LlmClient;
import com.envestnet.atlas.arch.prov.ProvenanceEmitter;
import com.envestnet.atlas.arch.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ArchaeologyService {
    private static final Logger log = LoggerFactory.getLogger(ArchaeologyService.class);

    private final RunRepository runs;
    private final OperationRepository operations;
    private final TypeMappingRepository typeMappings;
    private final AdapterRepository adapters;
    private final NarrativeRepository narratives;
    private final ArchaeologyAgent agent;
    private final GraphPublisher graphPublisher;
    private final ProvenanceEmitter prov;
    private final JdbcTemplate jdbc;

    public ArchaeologyService(RunRepository runs,
                              OperationRepository operations,
                              TypeMappingRepository typeMappings,
                              AdapterRepository adapters,
                              NarrativeRepository narratives,
                              ArchaeologyAgent agent,
                              GraphPublisher graphPublisher,
                              ProvenanceEmitter prov,
                              JdbcTemplate jdbc) {
        this.runs = runs;
        this.operations = operations;
        this.typeMappings = typeMappings;
        this.adapters = adapters;
        this.narratives = narratives;
        this.agent = agent;
        this.graphPublisher = graphPublisher;
        this.prov = prov;
        this.jdbc = jdbc;
    }

    @Transactional
    public RunRow run(UUID projectId, String sourcePath) throws Exception {
        log.info("Archaeology run starting · project={} source={}", projectId, sourcePath);

        // Wipe prior results so re-runs are clean.
        operations.deleteByProject(projectId);
        adapters.deleteByProject(projectId);

        OffsetDateTime now = OffsetDateTime.now();
        RunRow run = runs.save(new RunRow(null, projectId, now, null,
                "running", sourcePath, "{}"));

        SourceWalker.Result result;
        try {
            result = new SourceWalker().walk(Path.of(sourcePath));
        } catch (Exception e) {
            jdbc.update("UPDATE arch.run SET status='failed', finished_at=now(), summary=?::jsonb WHERE id=?",
                    "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}", run.id());
            throw e;
        }

        // Adapters first
        for (SourceWalker.Adapter a : result.adapters) {
            adapters.save(new AdapterRow(null, projectId, a.fqn, a.kind, a.pattern, a.sourceLines));
        }

        // Operations + per-operation narrative + per-operation type mappings
        int narrated = 0;
        boolean anyStub = false;
        for (SourceWalker.Operation op : result.operations) {
            OperationRow row = operations.save(new OperationRow(
                    null, projectId, run.id(),
                    op.namespace, op.name, op.soapStyle, op.soapUse,
                    op.sourceClass, op.sourceLines,
                    op.inputType, op.outputType,
                    op.faultTypes.toArray(new String[0]),
                    op.flags.toArray(new String[0]),
                    initialConfidence(op),
                    "pending",
                    null, null, OffsetDateTime.now()
            ));

            // Attach the type mappings whose Java type matches the input/output.
            // For Phase 1 we attach all class-registered mappings to every op;
            // a Phase-1.5 refinement is to only attach the mappings transitively
            // reachable from the op's parameter type graph.
            for (SourceWalker.TypeMapping m : result.typeMappings) {
                typeMappings.save(new TypeMappingRow(
                        null, row.id(), null,
                        m.javaType, m.qnameNamespace, m.qnameLocal,
                        m.adapterFqn, m.notes
                ));
            }

            // Narrative
            LlmClient.Response resp = agent.narrate(op, result.typeMappings, result.adapters);
            narratives.save(new NarrativeRow(
                    null, row.id(),
                    resp.text(),
                    resp.model,
                    resp.tokensIn(), resp.tokensOut(),
                    resp.latencyMs == null ? null : resp.latencyMs.intValue(),
                    resp.stub,
                    OffsetDateTime.now()
            ));
            if (resp.stub) anyStub = true;

            // Provenance — one entry per agent narrative.
            ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                    projectId, "agent", "code-archaeology", "agent_narrative");
            ev.prompt = Map.of(
                    "operation", op.name,
                    "namespace", op.namespace,
                    "flags", op.flags);
            ev.model = resp.model;
            ev.tokensIn = resp.tokensIn();
            ev.tokensOut = resp.tokensOut();
            ev.latencyMs = resp.latencyMs == null ? null : resp.latencyMs.intValue();
            ev.output = Map.of(
                    "text", resp.text(),
                    "stub", resp.stub);
            ev.links = List.of("operation:" + row.id());
            prov.emit(ev);
            narrated++;
        }

        String summary = String.format(
                "{\"operations\":%d,\"adapters\":%d,\"narrated\":%d,\"stub_llm\":%s}",
                result.operations.size(), result.adapters.size(), narrated, anyStub);

        jdbc.update("UPDATE arch.run SET status='completed', finished_at=now(), summary=?::jsonb WHERE id=?",
                summary, run.id());

        // Publish to the knowledge graph (best-effort; SQL writes already committed).
        graphPublisher.publish(projectId, result);

        // Run-level provenance event.
        ProvenanceEmitter.Event runEv = ProvenanceEmitter.Event.of(
                projectId, "system", "arch-service", "archaeology_run_completed");
        runEv.output = Map.of(
                "operations", result.operations.size(),
                "adapters", result.adapters.size(),
                "narrated", narrated,
                "agent_stub", anyStub);
        runEv.links = List.of("run:" + run.id());
        prov.emit(runEv);

        log.info("Archaeology run done · ops={} adapters={} narrated={} stub={}",
                result.operations.size(), result.adapters.size(), narrated, anyStub);
        return runs.findById(run.id()).orElse(run);
    }

    public List<Map<String, Object>> listOperations(UUID projectId) {
        List<OperationRow> rows = operations.findByProject(projectId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (OperationRow r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("namespace", r.namespace());
            m.put("name", r.name());
            m.put("inputType", r.inputType());
            m.put("outputType", r.outputType());
            m.put("sourceClass", r.sourceClass());
            m.put("sourceLines", r.sourceLines());
            m.put("flags", r.flags());
            m.put("faultTypes", r.faultTypes());
            m.put("confidence", r.confidence());
            m.put("decisionState", r.decisionState());
            m.put("typeMappings", typeMappings.findByOperation(r.id()).stream()
                    .map(this::mappingMap).toList());
            narratives.findLatestForOperation(r.id()).ifPresent(n -> {
                Map<String, Object> nMap = new LinkedHashMap<>();
                nMap.put("text", n.text());
                nMap.put("model", n.model());
                nMap.put("stub", n.stub());
                nMap.put("tokensIn", n.tokensIn());
                nMap.put("tokensOut", n.tokensOut());
                nMap.put("latencyMs", n.latencyMs());
                m.put("narrative", nMap);
            });
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> mappingMap(TypeMappingRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("javaType", r.javaType());
        m.put("qnameNamespace", r.qnameNamespace());
        m.put("qnameLocal", r.qnameLocal());
        m.put("adapterFqn", r.adapterFqn());
        m.put("notes", r.notes());
        return m;
    }

    private String initialConfidence(SourceWalker.Operation op) {
        if (op.flags.isEmpty()) return "high";
        if (op.flags.size() <= 2) return "medium";
        return "low";
    }
}
