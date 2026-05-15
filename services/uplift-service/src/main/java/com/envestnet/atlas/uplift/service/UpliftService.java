package com.envestnet.atlas.uplift.service;

import com.envestnet.atlas.uplift.domain.FindingRow;
import com.envestnet.atlas.uplift.domain.ModuleRow;
import com.envestnet.atlas.uplift.domain.ScanRunRow;
import com.envestnet.atlas.uplift.prov.ProvenanceEmitter;
import com.envestnet.atlas.uplift.repo.FindingRepository;
import com.envestnet.atlas.uplift.repo.ModuleRepository;
import com.envestnet.atlas.uplift.repo.ScanRunRepository;
import com.envestnet.atlas.uplift.scan.HeatmapScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class UpliftService {
    private static final Logger log = LoggerFactory.getLogger(UpliftService.class);

    private final ScanRunRepository runs;
    private final ModuleRepository modules;
    private final FindingRepository findings;
    private final ProvenanceEmitter prov;
    private final JdbcTemplate jdbc;

    public UpliftService(ScanRunRepository runs,
                         ModuleRepository modules,
                         FindingRepository findings,
                         ProvenanceEmitter prov,
                         JdbcTemplate jdbc) {
        this.runs = runs;
        this.modules = modules;
        this.findings = findings;
        this.prov = prov;
        this.jdbc = jdbc;
    }

    @Transactional
    public ScanRunRow run(UUID projectId, String sourcePath) throws Exception {
        log.info("Inventory scan starting · project={} source={}", projectId, sourcePath);

        // Wipe prior runs/findings/modules so re-runs are clean.
        jdbc.update("DELETE FROM uplift.scan_run WHERE project_id = ?", projectId);

        ScanRunRow run = runs.save(new ScanRunRow(
                null, projectId, OffsetDateTime.now(), null,
                "running", sourcePath, "{}"
        ));

        HeatmapScanner.Result res;
        try {
            res = new HeatmapScanner().scan(Path.of(sourcePath));
        } catch (Exception e) {
            jdbc.update("UPDATE uplift.scan_run SET status='failed', finished_at=now(), summary=?::jsonb WHERE id=?",
                    "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}", run.id());
            throw e;
        }

        int totalLoc = 0;
        for (HeatmapScanner.Module m : res.modules) {
            ModuleRow mr = modules.save(new ModuleRow(
                    null, projectId, run.id(),
                    m.name, m.packageName,
                    m.fileCount, m.loc, m.difficulty, m.findings.size()
            ));
            totalLoc += m.loc;

            for (HeatmapScanner.Finding f : m.findings) {
                findings.save(new FindingRow(
                        null, projectId, run.id(), mr.id(),
                        f.ruleId, f.ruleLabel, f.severity,
                        f.filePath, f.lineStart, f.lineEnd, f.snippet,
                        f.suggestedRecipe
                ));
            }
        }

        String summary = String.format(
                "{\"modules\":%d,\"findings\":%d,\"loc\":%d}",
                res.modules.size(), res.totalFindings, totalLoc);
        jdbc.update("UPDATE uplift.scan_run SET status='completed', finished_at=now(), summary=?::jsonb WHERE id=?",
                summary, run.id());

        // Provenance — system event for the scan completion.
        ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                projectId, "system", "uplift-service", "inventory_scan_completed");
        ev.output = Map.of(
                "modules", res.modules.size(),
                "findings", res.totalFindings,
                "loc", totalLoc);
        ev.links = List.of("scan_run:" + run.id());
        prov.emit(ev);

        log.info("Inventory scan done · modules={} findings={} loc={}",
                res.modules.size(), res.totalFindings, totalLoc);
        return runs.findById(run.id()).orElse(run);
    }

    public Map<String, Object> status(UUID projectId) {
        Optional<ScanRunRow> r = runs.latest(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.isEmpty()) {
            out.put("run", null);
            out.put("modules", List.of());
            out.put("findings", List.of());
            out.put("ruleCounts", List.of());
            return out;
        }

        out.put("run", runMap(r.get()));
        out.put("modules", modules.findByRun(r.get().id()).stream()
                .map(this::moduleMap).toList());
        out.put("findings", findings.findByRun(r.get().id()).stream()
                .map(this::findingMap).toList());
        // Use raw JdbcTemplate to avoid Spring Data JDBC's record-projection
        // column-name matching, which folded our `ruleId` alias to lowercase
        // and produced null-component records.
        out.put("ruleCounts", jdbc.query(
            "SELECT rule_id, count(*) AS c FROM uplift.finding " +
            "WHERE run_id = ? GROUP BY rule_id ORDER BY c DESC",
            (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ruleId", rs.getString("rule_id"));
                m.put("count", rs.getLong("c"));
                return m;
            },
            r.get().id()));
        return out;
    }

    private Map<String, Object> runMap(ScanRunRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("startedAt", r.startedAt());
        m.put("finishedAt", r.finishedAt());
        m.put("status", r.status());
        m.put("sourcePath", r.sourcePath());
        return m;
    }

    private Map<String, Object> moduleMap(ModuleRow m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", m.id());
        r.put("name", m.name());
        r.put("packageName", m.packageName());
        r.put("fileCount", m.fileCount());
        r.put("loc", m.loc());
        r.put("difficulty", m.difficulty());
        r.put("findingCount", m.findingCount());
        return r;
    }

    private Map<String, Object> findingMap(FindingRow f) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", f.id());
        r.put("moduleId", f.moduleId());
        r.put("ruleId", f.ruleId());
        r.put("ruleLabel", f.ruleLabel());
        r.put("severity", f.severity());
        r.put("filePath", f.filePath());
        r.put("lineStart", f.lineStart());
        r.put("lineEnd", f.lineEnd());
        r.put("snippet", f.snippet());
        r.put("suggestedRecipe", f.suggestedRecipe());
        return r;
    }
}
