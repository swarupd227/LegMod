-- Phase-1i migration: Recipe Authoring (Track B Stage B).
-- Recipes are project-scoped curated transformations derived from findings.

CREATE TABLE IF NOT EXISTS uplift.recipe (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id    UUID NOT NULL,
    recipe_id     TEXT NOT NULL,                       -- OpenRewrite FQN or atlas.* custom
    label         TEXT NOT NULL,
    description   TEXT,
    kind          TEXT NOT NULL DEFAULT 'ootb'
                    CHECK (kind IN ('ootb','custom')),
    status        TEXT NOT NULL DEFAULT 'proposed'
                    CHECK (status IN ('proposed','accepted','rejected')),
    notes         TEXT,
    finding_ids   JSONB NOT NULL DEFAULT '[]'::jsonb,  -- snapshot at seed time
    module_ids    JSONB NOT NULL DEFAULT '[]'::jsonb,  -- derived from finding modules
    decided_by    TEXT,
    decided_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, recipe_id)
);
CREATE INDEX IF NOT EXISTS idx_uplift_recipe_project ON uplift.recipe(project_id);
CREATE INDEX IF NOT EXISTS idx_uplift_recipe_status  ON uplift.recipe(project_id, status);
