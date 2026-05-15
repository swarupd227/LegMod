package com.envestnet.atlas.prj.patterns;

import com.envestnet.atlas.prj.domain.Project;
import com.envestnet.atlas.prj.repo.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Cross-project Reconciliation Pattern Library.
 *
 * <p>Every accepted (or overridden) Stage C decision contributes a row
 * to {@code recon.pattern}, keyed by {@code (vendor_family, kind,
 * normalized_path)}. The next migration against the same vendor looks
 * those rows up at decision-render time and surfaces them as
 * "Recommended — based on N prior migrations" hints on the SPA's
 * divergence cards.</p>
 *
 * <p>This is the part of Atlas that gets <em>better the more migrations
 * Nous executes</em> — a moat that a starting-from-zero competitor
 * cannot match. The first run against an Apache-family project today
 * already inherits the seeded patterns from {@code V2__pattern_library}.</p>
 */
@Service
public class PatternService {

    private static final Logger log = LoggerFactory.getLogger(PatternService.class);

    /** Normalize path so accountId on PurchOrdType matches accountId on AllocationRequest. */
    private static final Pattern PATH_TYPE_PREFIX =
            Pattern.compile("^[A-Z][A-Za-z0-9_]*\\.");

    private final ProjectRepository projects;
    private final JdbcTemplate jdbc;

    public PatternService(ProjectRepository projects, JdbcTemplate jdbc) {
        this.projects = projects;
        this.jdbc = jdbc;
    }

    /**
     * Upsert a pattern when a decision is resolved. Increments
     * occurrence_count and bumps last_seen_at on re-occurrence; inserts
     * a fresh row otherwise. Never throws on a missing input — pattern
     * recording is best-effort, never on the critical path.
     */
    public void recordDecision(UUID projectId, String kind, String path,
                               String chosenAction, String resolution) {
        if (projectId == null || kind == null || path == null || chosenAction == null) return;
        // Only record positive resolutions — pending/rejected don't
        // teach us anything useful about what to recommend next time.
        if (!"accepted".equals(resolution) && !"overridden".equals(resolution)) return;

        Optional<Project> p = projects.findById(projectId);
        if (p.isEmpty()) return;
        String vendor = normalizeVendor(p.get().vendorPartner());
        if (vendor.isBlank()) return;

        String normalizedPath = normalizePath(path);
        try {
            jdbc.update("""
                    INSERT INTO recon.pattern
                        (vendor_family, kind, path, recommendation, summary,
                         occurrence_count, confidence, first_seen_at, last_seen_at)
                    VALUES (?, ?, ?, ?, ?, 1, 'medium', now(), now())
                    ON CONFLICT (vendor_family, kind, path) DO UPDATE SET
                        occurrence_count = recon.pattern.occurrence_count + 1,
                        last_seen_at     = now(),
                        confidence       = CASE
                            WHEN recon.pattern.occurrence_count + 1 >= 3 THEN 'high'
                            WHEN recon.pattern.occurrence_count + 1 >= 2 THEN 'medium'
                            ELSE 'low'
                        END,
                        -- Refresh recommendation only when it agrees with prior
                        -- (avoid one rogue decision overwriting a strong pattern)
                        recommendation = CASE
                            WHEN recon.pattern.recommendation = EXCLUDED.recommendation
                                THEN recon.pattern.recommendation
                            ELSE recon.pattern.recommendation
                        END
                    """,
                    vendor, kind, normalizedPath, chosenAction,
                    "Auto-recorded from a customer migration decision.");
        } catch (Exception e) {
            // Never fail the user-facing resolve call because of pattern
            // recording — log and move on.
            log.warn("Pattern upsert failed for {}/{} — {}", vendor, normalizedPath, e.toString());
        }
    }

    /**
     * Look up all patterns matching the project's vendor family. Used
     * by the Reconciliation SPA screen to decorate decision cards with
     * "Recommended" pills.
     */
    public List<Map<String, Object>> findForProject(UUID projectId) {
        Optional<Project> p = projects.findById(projectId);
        if (p.isEmpty()) return List.of();
        String vendor = normalizeVendor(p.get().vendorPartner());
        if (vendor.isBlank()) return List.of();

        return jdbc.queryForList("""
                SELECT vendor_family, kind, path, recommendation, summary,
                       occurrence_count, confidence,
                       first_seen_at, last_seen_at
                  FROM recon.pattern
                 WHERE vendor_family = ?
                 ORDER BY occurrence_count DESC, last_seen_at DESC
                """, vendor);
    }

    // ----- helpers -----------------------------------------------------

    static String normalizeVendor(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /**
     * Collapse "TypeName.fieldName" → "*.fieldName" so the same field on
     * different SOAP types matches. Demos and real customers both
     * benefit: accountId on PurchOrdType is the same conceptual field
     * as accountId on AllocationRequest.
     */
    static String normalizePath(String path) {
        if (path == null) return "";
        if (path.contains(".") && PATH_TYPE_PREFIX.matcher(path).find()) {
            int dot = path.indexOf('.');
            return "*." + path.substring(dot + 1);
        }
        return path;
    }
}
