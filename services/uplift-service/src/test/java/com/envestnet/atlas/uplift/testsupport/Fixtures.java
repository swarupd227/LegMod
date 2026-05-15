package com.envestnet.atlas.uplift.testsupport;

import com.envestnet.atlas.uplift.domain.FindingRow;
import com.envestnet.atlas.uplift.domain.ModuleRow;
import com.envestnet.atlas.uplift.domain.ScanRunRow;
import com.envestnet.atlas.uplift.repo.FindingRepository;
import com.envestnet.atlas.uplift.repo.ModuleRepository;
import com.envestnet.atlas.uplift.repo.ScanRunRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Fluent test fixtures. Wraps the heavy upstream chain (project → scan run →
 * module → finding) so individual tests don't repeat 30+ lines of arrange.
 *
 * Each method returns the row it just inserted so tests can chain
 * (e.g., aProject() then aModule(projectId) then aFinding(moduleId)).
 */
@Component
public class Fixtures {

    private final ScanRunRepository runs;
    private final ModuleRepository modules;
    private final FindingRepository findings;
    private final JdbcTemplate jdbc;

    public Fixtures(ScanRunRepository runs,
                    ModuleRepository modules,
                    FindingRepository findings,
                    JdbcTemplate jdbc) {
        this.runs = runs;
        this.modules = modules;
        this.findings = findings;
        this.jdbc = jdbc;
    }

    /** Wipe every uplift table — call from {@code @BeforeEach}. */
    public void wipe() {
        // Order matters: child rows first.
        jdbc.execute("TRUNCATE uplift.cutover, uplift.char_case, uplift.char_run, " +
                "uplift.migration_change, uplift.migration_run, " +
                "uplift.strangler_step, uplift.recipe, " +
                "uplift.finding, uplift.module, uplift.scan_run RESTART IDENTITY CASCADE");
    }

    public UUID aProject() {
        return UUID.randomUUID();
    }

    public ScanRunRow aScanRun(UUID projectId, String sourcePath) {
        ScanRunRow row = runs.save(new ScanRunRow(
                null, projectId, OffsetDateTime.now(), null,
                "completed", sourcePath, "{}"
        ));
        // ScanRunRow's `summary` is @ReadOnlyProperty — populate via JdbcTemplate.
        jdbc.update("UPDATE uplift.scan_run SET summary = ?::jsonb, finished_at = now() WHERE id = ?",
                "{\"modules\":1,\"findings\":1}", row.id());
        return row;
    }

    public ModuleRow aModule(UUID projectId, UUID runId, String name, String packageName) {
        return aModule(projectId, runId, name, packageName, /*loc*/ 200, /*difficulty*/ 6, /*findings*/ 3);
    }

    public ModuleRow aModule(UUID projectId, UUID runId, String name, String packageName,
                              int loc, int difficulty, int findingCount) {
        return modules.save(new ModuleRow(
                null, projectId, runId,
                name, packageName,
                /*fileCount*/ 4, loc, difficulty, findingCount));
    }

    public FindingRow aFinding(UUID projectId, UUID runId, UUID moduleId,
                                String ruleId, String suggestedRecipe) {
        return findings.save(new FindingRow(
                null, projectId, runId, moduleId,
                ruleId, "label", "high",
                "src/Foo.java", 1, 1, "snippet",
                suggestedRecipe));
    }
}
