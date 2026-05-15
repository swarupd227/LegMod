package com.envestnet.atlas.arch.web;

import com.envestnet.atlas.arch.analysis.SourceWalker;
import com.envestnet.atlas.arch.domain.RunRow;
import com.envestnet.atlas.arch.repo.AdapterRepository;
import com.envestnet.atlas.arch.repo.RunRepository;
import com.envestnet.atlas.arch.service.ArchaeologyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Internal API consumed by prj-service. The public path lives behind
 * /api/v1/projects/{id}/stages/archaeology/* on the gateway.
 */
@RestController
@RequestMapping("/internal/archaeology")
public class ArchController {

    private final ArchaeologyService svc;
    private final RunRepository runs;
    private final AdapterRepository adapters;

    public ArchController(ArchaeologyService svc,
                          RunRepository runs,
                          AdapterRepository adapters) {
        this.svc = svc;
        this.runs = runs;
        this.adapters = adapters;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody RunRequest req) throws Exception {
        RunRow row = svc.run(req.projectId(), req.sourcePath());
        return ResponseEntity.ok(Map.of(
                "runId", row.id(),
                "status", row.status(),
                "startedAt", row.startedAt(),
                "finishedAt", row.finishedAt()
        ));
    }

    @GetMapping("/operations/{projectId}")
    public List<Map<String, Object>> listOperations(@PathVariable UUID projectId) {
        return svc.listOperations(projectId);
    }

    @GetMapping("/runs/{projectId}")
    public List<RunRow> listRuns(@PathVariable UUID projectId) {
        return runs.findByProject(projectId);
    }

    @GetMapping("/adapters/{projectId}")
    public List<?> listAdapters(@PathVariable UUID projectId) {
        return adapters.findByProject(projectId);
    }

    /**
     * Lightweight structural probe of a project's source tree. Walks the
     * Java AST exactly like the full archaeology run does, but emits
     * counts + names only. No persistence. No LLM. Sub-second on the
     * WS-I sample. Used by the Migration Forecast feature in prj-service
     * to size a project *before* paying for the Stage A LLM round-trips.
     */
    @PostMapping("/peek")
    public ResponseEntity<Map<String, Object>> peek(@RequestBody RunRequest req) throws IOException {
        Path src = Path.of(req.sourcePath());
        if (!Files.isDirectory(src)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "sourcePath is not a directory",
                    "sourcePath", req.sourcePath()
            ));
        }

        long javaFileCount;
        try (Stream<Path> s = Files.walk(src)) {
            javaFileCount = s.filter(p -> p.toString().endsWith(".java")).count();
        }

        SourceWalker.Result result = new SourceWalker().walk(src);

        // Flatten the operations to lightweight summaries (name + i/o types)
        // — the LLM forecast prompt doesn't need source-line metadata.
        List<Map<String, String>> ops = result.operations.stream()
                .map(op -> Map.of(
                        "namespace", op.namespace,
                        "name", op.name,
                        "inputType", op.inputType,
                        "outputType", op.outputType,
                        "soapStyle", op.soapStyle,
                        "flags", String.join(",", op.flags)
                ))
                .toList();

        // Surface a few high-signal complexity flags the agent should weight.
        Set<String> flags = new LinkedHashSet<>();
        for (SourceWalker.Operation op : result.operations) {
            flags.addAll(op.flags);
            if ("RPC".equalsIgnoreCase(op.soapStyle)) flags.add("rpc-encoded");
            if (op.faultTypes.size() > 1) flags.add("multi-fault");
        }
        for (SourceWalker.Adapter a : result.adapters) {
            if (!"other".equals(a.kind)) flags.add("custom-" + a.kind + "-adapter");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", req.projectId());
        body.put("sourcePath", req.sourcePath());
        body.put("javaFileCount", javaFileCount);
        body.put("operationCount", result.operations.size());
        body.put("typeMappingCount", result.typeMappings.size());
        body.put("adapterCount", result.adapters.size());
        body.put("operations", ops);
        body.put("complexityFlags", new ArrayList<>(flags));
        return ResponseEntity.ok(body);
    }

    public record RunRequest(UUID projectId, String sourcePath) {}
}
