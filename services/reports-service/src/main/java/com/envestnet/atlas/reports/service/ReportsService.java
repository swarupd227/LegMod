package com.envestnet.atlas.reports.service;

import com.envestnet.atlas.reports.agent.ClosureDocAgent;
import com.envestnet.atlas.reports.bundle.BundleAssembler;
import com.envestnet.atlas.reports.domain.BundleRow;
import com.envestnet.atlas.reports.gather.ProjectFacts;
import com.envestnet.atlas.reports.prov.ProvenanceEmitter;
import com.envestnet.atlas.reports.repo.BundleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ReportsService {
    private static final Logger log = LoggerFactory.getLogger(ReportsService.class);

    private final BundleRepository bundles;
    private final ProjectFacts facts;
    private final ClosureDocAgent closureAgent;
    private final BundleAssembler assembler;
    private final ProvenanceEmitter prov;
    private final JdbcTemplate jdbc;

    public ReportsService(BundleRepository bundles,
                          ProjectFacts facts,
                          ClosureDocAgent closureAgent,
                          BundleAssembler assembler,
                          ProvenanceEmitter prov,
                          JdbcTemplate jdbc) {
        this.bundles = bundles;
        this.facts = facts;
        this.closureAgent = closureAgent;
        this.assembler = assembler;
        this.prov = prov;
        this.jdbc = jdbc;
    }

    public BundleRow build(UUID projectId) throws Exception {
        log.info("Building migration package · project={}", projectId);

        BundleRow row = bundles.save(new BundleRow(
                null, projectId, OffsetDateTime.now(),
                "building", null, null, null, null, null, false, "{}"
        ));

        try {
            ProjectFacts.Snapshot snap = facts.gather(projectId);
            ClosureDocAgent.Result closure = closureAgent.generate(snap);
            BundleAssembler.Built built = assembler.build(projectId, snap,
                    closure.markdown(), closure.stub());

            // Compact summary the UI can render without a fresh facts gather.
            String summaryJson = String.format(
                    "{\"operations\":%d,\"adapters\":%d,\"envelopes\":%s,\"decisions_total\":%s,\"files\":%s,\"diff_red\":%s,\"diff_amber\":%s,\"closure_model\":\"%s\"}",
                    snap.operations.size(),
                    snap.adapters.size(),
                    String.valueOf(snap.captureStatus.getOrDefault("totalEnvelopes", 0)),
                    String.valueOf(nested(snap.reconStatus, "counts", "total")),
                    String.valueOf(nested(snap.genStatus, "run", "fileCount")),
                    String.valueOf(nested(snap.diffStatus, "run", "redCount")),
                    String.valueOf(nested(snap.diffStatus, "run", "amberCount")),
                    closure.model() == null ? "" : closure.model()
            );

            jdbc.update("""
                UPDATE reports.bundle SET status='completed', built_at=now(),
                  bundle_uri=?, closure_uri=?, size_bytes=?, file_count=?,
                  closure_text=?, closure_stub=?, summary=?::jsonb
                WHERE id=?
                """,
                built.bundleUri(), built.closureUri(), built.sizeBytes(), built.fileCount(),
                closure.markdown(), closure.stub(), summaryJson, row.id());

            // Closure-doc agent event.
            ProvenanceEmitter.Event closureEv = ProvenanceEmitter.Event.of(
                    projectId, "agent", "closure-doc", "closure_doc_authored");
            closureEv.model = closure.model();
            closureEv.output = Map.of(
                    "stub", closure.stub(),
                    "length", closure.markdown() == null ? 0 : closure.markdown().length());
            closureEv.links = List.of("bundle:" + row.id(), built.closureUri());
            prov.emit(closureEv);

            // Bundle-built event.
            ProvenanceEmitter.Event bundleEv = ProvenanceEmitter.Event.of(
                    projectId, "system", "reports-service", "bundle_built");
            bundleEv.output = Map.of(
                    "files", built.fileCount(),
                    "bytes", built.sizeBytes(),
                    "closure_stub", closure.stub());
            bundleEv.links = List.of("bundle:" + row.id(), built.bundleUri());
            prov.emit(bundleEv);

            log.info("bundle done · project={} bytes={} files={}",
                    projectId, built.sizeBytes(), built.fileCount());
            return bundles.findById(row.id()).orElse(row);
        } catch (Exception e) {
            jdbc.update("UPDATE reports.bundle SET status='failed' WHERE id=?", row.id());
            throw e;
        }
    }

    public Map<String, Object> status(UUID projectId) {
        Optional<BundleRow> b = bundles.latest(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bundle", b.map(this::bundleMap).orElse(null));
        return out;
    }

    public byte[] download(UUID projectId) {
        return assembler.downloadBundle(projectId);
    }

    public String closure(UUID projectId) {
        return assembler.fetchClosure(projectId);
    }

    private Map<String, Object> bundleMap(BundleRow b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("builtAt", b.builtAt());
        m.put("status", b.status());
        m.put("bundleUri", b.bundleUri());
        m.put("closureUri", b.closureUri());
        m.put("sizeBytes", b.sizeBytes());
        m.put("fileCount", b.fileCount());
        m.put("closureStub", b.closureStub());
        return m;
    }

    private Object nested(Map<String, Object> m, String... keys) {
        Object o = m;
        for (String k : keys) {
            if (o instanceof Map<?, ?> mm) o = mm.get(k);
            else return null;
        }
        return o;
    }
}
