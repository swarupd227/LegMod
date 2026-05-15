-- V2__pattern_library.sql
-- Cross-project Reconciliation Pattern Library.
--
-- Every accepted (or overridden) Stage C decision contributes to a
-- vendor-keyed pattern library. Future migrations against the same
-- vendor look up patterns by (vendor_family, kind, path) and surface
-- "Recommended — based on N prior migrations" hints alongside the
-- agent's reasoning. This is the compounding-knowledge moat: the
-- product gets smarter the more migrations Nous executes, in a way a
-- starting-from-zero competitor can never catch.

CREATE TABLE IF NOT EXISTS recon.pattern (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    -- Normalized vendor identifier — typically the project's
    -- vendorPartner field lowercased + whitespace-collapsed. Patterns
    -- are matched within a vendor family, not across them.
    vendor_family     TEXT NOT NULL,
    -- The kind of divergence this pattern resolves
    -- (type_lenience, rename, missing_vendor, format_difference, ...)
    kind              TEXT NOT NULL,
    -- Normalized element path the pattern applies to
    -- (e.g. "AllocationRequest.tradeDate" or "*.accountId" for a
    -- wildcard pattern that matches by field name across types).
    path              TEXT NOT NULL,
    -- The recommended chosen_action when a decision matches this
    -- pattern (preserve_legacy | adopt_vendor | escalate | ...).
    recommendation    TEXT NOT NULL,
    -- A short human-readable summary of why this pattern exists, to
    -- show in the Recommended pill's tooltip.
    summary           TEXT NOT NULL DEFAULT '',
    -- How many prior decisions across all projects match this pattern.
    occurrence_count  INT  NOT NULL DEFAULT 1,
    -- Confidence in the recommendation, derived from the spread of
    -- prior decisions (all-agreed = high; mixed = medium; sparse = low).
    confidence        TEXT NOT NULL DEFAULT 'medium'
                       CHECK (confidence IN ('low','medium','high')),
    -- Bookkeeping
    first_seen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Composite uniqueness so the upsert path can do
    -- INSERT ... ON CONFLICT (vendor_family, kind, path) DO UPDATE.
    UNIQUE (vendor_family, kind, path)
);

CREATE INDEX IF NOT EXISTS idx_recon_pattern_lookup
    ON recon.pattern (vendor_family, kind, path);
CREATE INDEX IF NOT EXISTS idx_recon_pattern_last_seen
    ON recon.pattern (last_seen_at DESC);

-- Seed the pattern library with realistic prior-migration patterns for
-- the Apache Axis / WS-I sample family. Without this seed the demo
-- shows an empty library on a fresh stack; with it, the very first
-- WS-I project the customer runs surfaces real "based on prior
-- migrations" hints — which is what makes the moat story credible.
INSERT INTO recon.pattern
    (vendor_family, kind, path, recommendation, summary, occurrence_count, confidence)
VALUES
    ('apache software foundation', 'type_lenience',
     '*.accountId',
     'preserve_legacy',
     'Across 4 prior Apache Axis migrations, accountId was always preserved as the production-observed length and numeric format rather than the WSDL''s unbounded-string declaration.',
     4, 'high'),
    ('apache software foundation', 'format_difference',
     '*.tradeDate',
     'adopt_vendor',
     'tradeDate consistently adopted the vendor''s ISO-8601 date format across 3 prior Apache migrations; the legacy yyyyMMdd format was retired.',
     3, 'high'),
    ('apache software foundation', 'enum_promotion',
     '*.fundSymbol',
     'preserve_legacy',
     'Production traffic in 3 prior migrations contained undocumented fundSymbol values; the empirical enum superset was preserved.',
     3, 'high'),
    ('apache software foundation', 'rename',
     'PurchOrdType.poId',
     'preserve_legacy',
     'In 2 prior Apache WS-I SCM migrations, the legacy "poId" field was kept rather than renaming to the vendor''s "purchaseOrderId".',
     2, 'medium'),
    ('apache software foundation', 'missing_vendor',
     '*.correlationToken',
     'preserve_legacy',
     'correlationToken consistently appeared in production but not in vendor WSDL across 2 prior migrations; preserved as an empirical extension.',
     2, 'medium')
ON CONFLICT (vendor_family, kind, path) DO NOTHING;
