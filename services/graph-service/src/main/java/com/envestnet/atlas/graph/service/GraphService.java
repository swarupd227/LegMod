package com.envestnet.atlas.graph.service;

import jakarta.annotation.PostConstruct;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Wraps the Atlas knowledge graph in a semantic API. Other services don't talk
 * to Neo4j directly; they go through this service so traversal logic + label
 * conventions stay centralized.
 */
@Service
public class GraphService {
    private static final Logger log = LoggerFactory.getLogger(GraphService.class);

    private final Driver driver;

    public GraphService(Driver driver) { this.driver = driver; }

    /** Idempotent: ensures constraints + indexes match the schema. */
    @PostConstruct
    public void ensureSchema() {
        try (Session s = driver.session()) {
            s.run("CREATE CONSTRAINT project_id IF NOT EXISTS " +
                  "FOR (p:Project) REQUIRE p.id IS UNIQUE");
            s.run("CREATE CONSTRAINT operation_key IF NOT EXISTS " +
                  "FOR (o:Operation) REQUIRE (o.project_id, o.namespace, o.name) IS UNIQUE");
            s.run("CREATE CONSTRAINT type_key IF NOT EXISTS " +
                  "FOR (t:Type) REQUIRE (t.project_id, t.namespace, t.name) IS UNIQUE");
            s.run("CREATE CONSTRAINT class_fqn IF NOT EXISTS " +
                  "FOR (c:Class) REQUIRE (c.project_id, c.fqn) IS UNIQUE");
            s.run("CREATE CONSTRAINT adapter_fqn IF NOT EXISTS " +
                  "FOR (a:Adapter) REQUIRE (a.project_id, a.fqn) IS UNIQUE");
            log.info("graph schema ensured");
        } catch (Exception e) {
            log.warn("schema bootstrap failed (will retry on first write): {}", e.getMessage());
        }
    }

    public void resetProject(String projectId) {
        try (Session s = driver.session()) {
            s.run("""
                MATCH (n {project_id: $pid})
                DETACH DELETE n
                """, Values.parameters("pid", projectId));
            // (re)create the project root
            s.run("""
                MERGE (p:Project {id: $pid})
                SET p.project_id = $pid
                """, Values.parameters("pid", projectId));
        }
    }

    /** Bulk-write the result of a Stage A archaeology run. */
    public Map<String, Object> publishArchaeology(ArchaeologyPayload p) {
        try (Session s = driver.session()) {
            // Adapters first — operations may reference them.
            for (AdapterDto a : p.adapters) {
                s.run("""
                    MERGE (ad:Adapter {project_id: $pid, fqn: $fqn})
                    SET ad.kind = $kind, ad.pattern = $pattern
                    """,
                    Values.parameters(
                        "pid", p.projectId, "fqn", a.fqn,
                        "kind", a.kind, "pattern", a.pattern == null ? "" : a.pattern));
            }

            int operationCount = 0;
            int typeCount = 0;
            for (OperationDto op : p.operations) {
                operationCount++;

                s.run("""
                    MERGE (cls:Class {project_id: $pid, fqn: $cls})
                    """, Values.parameters("pid", p.projectId, "cls", op.sourceClass));

                s.run("""
                    MERGE (o:Operation {project_id: $pid, namespace: $ns, name: $name})
                    SET o.input_type = $in, o.output_type = $out,
                        o.flags = $flags, o.confidence = $conf
                    WITH o
                    MATCH (cls:Class {project_id: $pid, fqn: $cls})
                    MERGE (cls)-[:DECLARES]->(o)
                    """,
                    Values.parameters(
                        "pid", p.projectId, "ns", op.namespace, "name", op.name,
                        "in", op.inputType, "out", op.outputType,
                        "flags", op.flags, "conf", op.confidence,
                        "cls", op.sourceClass));

                // Per-op type mappings -> Type nodes
                for (TypeMappingDto m : op.typeMappings) {
                    typeCount++;
                    s.run("""
                        MERGE (t:Type {project_id: $pid, namespace: $ns, name: $local})
                        SET t.java_type = $jt
                        WITH t
                        MATCH (o:Operation {project_id: $pid, namespace: $ons, name: $oname})
                        MERGE (o)-[:USES_TYPE]->(t)
                        """,
                        Values.parameters(
                            "pid", p.projectId,
                            "ns", m.qnameNamespace, "local", m.qnameLocal, "jt", m.javaType,
                            "ons", op.namespace, "oname", op.name));

                    if (m.adapterFqn != null && !m.adapterFqn.isBlank()) {
                        s.run("""
                            MATCH (t:Type {project_id: $pid, namespace: $ns, name: $local})
                            MATCH (ad:Adapter {project_id: $pid, fqn: $afqn})
                            MERGE (t)-[:USES_ADAPTER]->(ad)
                            """,
                            Values.parameters(
                                "pid", p.projectId,
                                "ns", m.qnameNamespace, "local", m.qnameLocal,
                                "afqn", m.adapterFqn));
                    }
                }
            }

            return Map.of(
                "ok", true,
                "operations", operationCount,
                "types", typeCount,
                "adapters", p.adapters.size()
            );
        }
    }

    public Map<String, Object> projectSummary(String projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Session s = driver.session()) {
            for (String label : List.of("Operation", "Type", "Adapter", "Class")) {
                Result r = s.run(
                        "MATCH (n:" + label + " {project_id: $pid}) RETURN count(n) AS c",
                        Values.parameters("pid", projectId));
                Record rec = r.single();
                out.put(label.toLowerCase() + "s", rec.get("c").asLong());
            }
        }
        return out;
    }

    public List<Map<String, Object>> dependentsOfAdapter(String projectId, String adapterFqn) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Session s = driver.session()) {
            Result r = s.run("""
                MATCH (ad:Adapter {project_id: $pid, fqn: $fqn})
                MATCH (t:Type)-[:USES_ADAPTER]->(ad)
                MATCH (o:Operation)-[:USES_TYPE]->(t)
                RETURN DISTINCT o.namespace AS ns, o.name AS name
                """, Values.parameters("pid", projectId, "fqn", adapterFqn));
            while (r.hasNext()) {
                Record rec = r.next();
                out.add(Map.of(
                    "namespace", rec.get("ns").asString(),
                    "name", rec.get("name").asString()));
            }
        }
        return out;
    }

    /* ---------- DTOs (also used as request bodies) ---------- */

    public static class ArchaeologyPayload {
        public String projectId;
        public List<OperationDto> operations = new ArrayList<>();
        public List<AdapterDto> adapters = new ArrayList<>();
    }
    public static class OperationDto {
        public String namespace;
        public String name;
        public String inputType;
        public String outputType;
        public String sourceClass;
        public String confidence;
        public List<String> flags = new ArrayList<>();
        public List<TypeMappingDto> typeMappings = new ArrayList<>();
    }
    public static class TypeMappingDto {
        public String javaType;
        public String qnameNamespace;
        public String qnameLocal;
        public String adapterFqn;
    }
    public static class AdapterDto {
        public String fqn;
        public String kind;
        public String pattern;
    }
}
