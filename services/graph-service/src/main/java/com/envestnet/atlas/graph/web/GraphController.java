package com.envestnet.atlas.graph.web;

import com.envestnet.atlas.graph.service.GraphService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/graph")
public class GraphController {

    private final GraphService graph;

    public GraphController(GraphService graph) { this.graph = graph; }

    @PostMapping("/projects/{pid}/reset")
    public Map<String, Object> reset(@PathVariable String pid) {
        graph.resetProject(pid);
        return Map.of("ok", true, "project_id", pid);
    }

    @PostMapping("/projects/{pid}/archaeology")
    public Map<String, Object> publish(@PathVariable String pid,
                                       @RequestBody GraphService.ArchaeologyPayload payload) {
        payload.projectId = pid;
        return graph.publishArchaeology(payload);
    }

    @GetMapping("/projects/{pid}/summary")
    public Map<String, Object> summary(@PathVariable String pid) {
        return graph.projectSummary(pid);
    }

    @GetMapping("/projects/{pid}/adapters/{fqn}/dependents")
    public List<Map<String, Object>> dependents(@PathVariable String pid,
                                                @PathVariable String fqn) {
        return graph.dependentsOfAdapter(pid, fqn);
    }
}
