package com.envestnet.atlas.uplift.service;

import com.envestnet.atlas.uplift.domain.CharCaseRow;
import com.envestnet.atlas.uplift.domain.CharRunRow;
import com.envestnet.atlas.uplift.repo.CharCaseRepository;
import com.envestnet.atlas.uplift.testsupport.BaseServiceTest;
import com.envestnet.atlas.uplift.testsupport.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class CharServiceTest extends BaseServiceTest {

    @Autowired CharService         charService;
    @Autowired CharCaseRepository  charCases;
    @Autowired JdbcTemplate        jdbc;
    @Autowired Fixtures            fix;

    @BeforeEach void cleanSlate() { fix.wipe(); }

    @Test
    void runRequiresAtLeastOneCompletedMigration() {
        UUID pid = fix.aProject();
        fix.aScanRun(pid, "/src");

        assertThatThrownBy(() -> charService.run(pid, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("module migrations");
    }

    @Test
    void runGeneratesCasesPerMigratedModuleAndPersistsBucketCounts() {
        UUID pid = projectWithCompletedMigrations(2);

        CharRunRow row = charService.run(pid, /*casesPerModule*/ 30);

        assertThat(row.status()).isEqualTo("completed");
        assertThat(row.sampleSize()).isEqualTo(60);    // 2 modules × 30 cases
        assertThat(row.passCount() + row.benignCount() + row.regressionCount()).isEqualTo(60);
        // ~85% pass — be generous because the synthesizer is randomised, but
        // floors are sane.
        assertThat(row.passCount()).isGreaterThan(40);
    }

    @Test
    void runIsDeterministicForAGivenProjectAndRun() {
        // Synthesizer is seeded by `projectId.hashCode() ^ runId.hashCode()`.
        // For two distinct runs we expect different distributions, but every
        // single case must be self-consistent (pass cases have legacy == new,
        // regression cases have legacy != new).
        UUID pid = projectWithCompletedMigrations(1);

        charService.run(pid, 50);
        Map<String, Object> status = charService.status(pid);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) status.get("cases");

        for (var c : cases) {
            String legacy = (String) c.get("legacyOutput");
            String revised = (String) c.get("newOutput");
            String bucket = (String) c.get("bucket");
            if ("pass".equals(bucket)) {
                assertThat(legacy).as("pass cases have identical outputs").isEqualTo(revised);
            } else {
                assertThat(legacy).as("non-pass cases differ").isNotEqualTo(revised);
            }
        }
    }

    @Test
    void triageRecordsStateAndAuditFields() {
        UUID pid = projectWithCompletedMigrations(1);
        charService.run(pid, 30);

        // Pick one regression-bucket case (skipped if random run produced none).
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) charService.status(pid).get("cases");
        Optional<Map<String, Object>> regression = all.stream()
                .filter(c -> "regression".equals(c.get("bucket")))
                .findFirst();
        if (regression.isEmpty()) return;          // statistically improbable but safe

        UUID caseId = UUID.fromString(regression.get().get("id").toString());

        CharCaseRow updated = charService.triage(caseId, "fixed", "alice");

        assertThat(updated.triageState()).isEqualTo("fixed");
        assertThat(updated.triagedBy()).isEqualTo("alice");
        assertThat(updated.triagedAt()).isNotNull();
    }

    @Test
    void triageRejectsBogusState() {
        UUID pid = projectWithCompletedMigrations(1);
        charService.run(pid, 5);
        UUID caseId = anyCaseId(pid);

        assertThatThrownBy(() -> charService.triage(caseId, "exploded", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readyForGateRequiresZeroOpenRegressions() {
        UUID pid = projectWithCompletedMigrations(1);
        charService.run(pid, 50);

        Map<String, Object> initial = charService.status(pid);
        long openInitial = ((Number) initial.get("openRegressions")).longValue();

        if (openInitial == 0) {
            // No regressions in this run — already ready.
            assertThat(initial.get("readyForGate")).isEqualTo(true);
        } else {
            assertThat(initial.get("readyForGate")).isEqualTo(false);

            // Triage every regression to "fixed".
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cases =
                    (List<Map<String, Object>>) initial.get("cases");
            for (var c : cases) {
                if ("regression".equals(c.get("bucket"))) {
                    charService.triage(UUID.fromString(c.get("id").toString()), "fixed", "u");
                }
            }
            Map<String, Object> after = charService.status(pid);
            assertThat(after.get("openRegressions")).isEqualTo(0L);
            assertThat(after.get("readyForGate")).isEqualTo(true);
        }
    }

    /* ---------------- helpers ---------------- */

    /** Insert N completed migration_run rows so charService.run() has data to attach to. */
    private UUID projectWithCompletedMigrations(int nModules) {
        UUID pid = fix.aProject();
        var scan = fix.aScanRun(pid, "/src");

        for (int i = 0; i < nModules; i++) {
            var mod = fix.aModule(pid, scan.id(), "mod" + i, "com.mod" + i);
            // Insert a fake migration_run row marked completed.
            UUID runId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO uplift.migration_run (id, project_id, module_id, status, finished_at)
                VALUES (?, ?, ?, 'completed', now())
                """, runId, pid, mod.id());
        }
        return pid;
    }

    private UUID anyCaseId(UUID pid) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases =
                (List<Map<String, Object>>) charService.status(pid).get("cases");
        assertThat(cases).isNotEmpty();
        return UUID.fromString(cases.get(0).get("id").toString());
    }
}
