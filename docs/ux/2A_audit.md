# Atlas Migrate · UX audit (Phase 2A.1)

**Audit date:** 2026-05-05
**Scope:** every screen, every shared component, the design tokens, and the
shell layout. Read-only — no code changes accompany this document.

This audit is the input to Phase 2A.2 (foundation refactor) and 2A.3 (per-screen
polish). The goal is to take an inventory accurate enough that the refactor PR
fixes everything in one coherent pass instead of nibbling.

---

## Summary

Atlas Migrate has a coherent visual language — Inter + JetBrains Mono, a
disciplined neutral palette, well-considered shadows, and a working dark
top-bar / light content split. The problems are mechanical, not aesthetic:

- **Repeated layout markup** has drifted across screens. The same "stage header"
  is hand-coded 11 times with subtly different widths and spacing.
- **Side-rail widths use 8 different magic numbers** (`260px, 280px, 300px,
  320px, 340px, 360px, 400px, 420px`) for what should be 2-3 standardised sizes.
- **Headers overflow** on viewports under ~1280px because count chips plus
  action buttons are stacked into a single non-wrapping row. This is the
  user-reported "overlapping labels."
- **Stage pipeline + screen header** both render their own "Stage X · Name"
  strap, creating a double-header that wastes vertical real-estate and forces
  the eye to read the same information twice.
- **Accessibility is essentially absent** — 4 aria attributes total across the
  whole codebase, no skip links, no focus-visible rings on dark surfaces, no
  live regions for in-flight mutations.
- **"Demo-grade" language and stub badges** are still present in 6 places
  (workspace dashboard, code archaeology, runtime capture, reports hub, etc.).
  These need to come out per the Phase 2 production-grade mandate.
- **Empty / loading / error states** are reinvented per screen (4 distinct
  patterns for "service unreachable" alone).

**Counts at a glance**

| Metric | Value |
|---|---|
| Total screen files | 16 |
| Hand-coded stage headers (same shape) | 11 |
| Distinct side-rail widths used | 8 |
| Distinct `min-h` values on panel grids | 5 |
| `aria-*` attributes in entire frontend | 4 |
| References to "demo" / "Phase N" / "stub" in user-visible copy | 14 |
| Skeleton patterns in use | 3 |
| Empty-state visual designs | 5 |

---

## Cross-cutting issues

These each affect 5+ screens. Fixing them at the foundation layer makes the
per-screen polish (§ below) much smaller.

### C1. Stage screen header is duplicated 11 times — extract `<StageHeader />`

**Severity:** high · **Effort:** small · **Affected:** every stage screen

The exact same JSX block appears at:

| Screen | File:Line |
|---|---|
| Code Archaeology | [CodeArchaeology.tsx:62-87](frontend/src/screens/CodeArchaeology.tsx) |
| Runtime Capture  | [RuntimeCapture.tsx:71-112](frontend/src/screens/RuntimeCapture.tsx) |
| Reconciliation   | [Reconciliation.tsx:128-175](frontend/src/screens/Reconciliation.tsx) |
| Code Generation  | [CodeGeneration.tsx:94-147](frontend/src/screens/CodeGeneration.tsx) |
| Differential Lab | [DifferentialLab.tsx:128-181](frontend/src/screens/DifferentialLab.tsx) |
| Reports Hub      | [ReportsHub.tsx:96-152](frontend/src/screens/ReportsHub.tsx) |
| Inventory & Heatmap | [InventoryHeatmap.tsx:118-167](frontend/src/screens/InventoryHeatmap.tsx) |
| Recipe Authoring | [RecipeAuthoring.tsx:69-124](frontend/src/screens/RecipeAuthoring.tsx) |
| Strangler Designer | [StranglerDesigner.tsx:69-119](frontend/src/screens/StranglerDesigner.tsx) |
| Module Migration | [ModuleMigration.tsx:86-134](frontend/src/screens/ModuleMigration.tsx) |
| Characterization | [Characterization.tsx:84-130](frontend/src/screens/Characterization.tsx) |
| Cutover & Decom  | [CutoverDecommission.tsx:73-122](frontend/src/screens/CutoverDecommission.tsx) |

Each has the same skeleton: `[stage-letter-chip] [stage label + h2] [count chip
row] [action button group on the right]`. They differ only in the count chips
and the action buttons.

**Why it's a problem:** every cosmetic change requires editing 11 files. They
have already drifted: some put `text-2xs font-mono uppercase tracking-wider`
into a label, others use the `eyebrow` utility. Some wrap the action group in
`gap-1.5`, others in `gap-1`.

**Proposal:**

```tsx
<StageHeader
  stage="B" track="UPLIFT"
  title="Recipe Authoring"
  status={[
    { label: 'proposed',  value: 4, tone: 'brand' },
    { label: 'accepted',  value: 2, tone: 'ok' },
    { label: 'rejected',  value: 1, tone: 'warn' },
  ]}
  actions={
    <>
      <Button variant="ghost"  icon={<RefreshCw/>} onClick={refetch} />
      <Button variant="secondary" icon={<Sparkles/>} onClick={seed}>Re-seed</Button>
      <Button variant="primary" icon={<Lock/>} onClick={finalize}>Finalize</Button>
    </>
  }
/>
```

The component handles wrapping, status dots, vertical rhythm, and the focus
order so screens stop reinventing it.

---

### C2. Stage pipeline + screen header collide visually

**Severity:** high · **Effort:** medium · **Affected:** every project route

The `<StagePipeline />` in [ProjectDetail.tsx:261-340](frontend/src/screens/ProjectDetail.tsx)
already says "Stage B · Runtime Capture" in card form. The Stage Screen below
*also* says "Stage B" plus the title (from C1) inside its own header card.
Result: the user reads the same label twice in 100px of vertical space, and the
eye treats them as redundant cards.

**Examples that demonstrate the issue:**

- Stage A: pipeline shows "Stage A · Code Archaeology" → screen header repeats
  "Stage A · Code Archaeology"
- Same in every track-B stage too

**Options (pick one, document the choice):**

1. **Slim pipeline.** Reduce the pipeline to just the dotted progress bar (no
   stage-name label inside the pipeline). Stage names live only in the screen
   header. Pipeline becomes ~28px tall, decorative.
2. **Slim header.** Drop the screen-header's "Stage X · Name" text and replace
   with just the action buttons + count chips. The pipeline carries the title.
3. **Merge.** Combine pipeline + header into one composite "Stage workspace
   chrome" component that shows the pipeline strip on top and the action row
   directly under it inside one card.

I recommend **option 3** — it's the most editorial-feeling and matches the
shell layout's other dark-bar / panel pattern. It also reduces the project
detail page from 4 visual stripes (back link, Hero, Pipeline, ScreenHeader,
content) to 3.

Mock layout:

```
┌─────────────────────────────────────────────────────────┐
│ ← Workspaces                                            │
├─────────────────────────────────────────────────────────┤
│ [SOAP] [LOW] Broadridge MF Service                      │
│  Apache Axis 1.3 → JAX-WS · dev@envestnet.local · …     │
├─────────────────────────────────────────────────────────┤
│ ●━━━●━━━●━━━○━━━○━━━○                                   │
│ A   B   C   D   E   F                                   │
│ ─────────                                               │
│ Stage C · Schema Reconciliation                  [↻ ⌘R] │
│ 12 pending · 8 resolved                          [Run]  │
├─────────────────────────────────────────────────────────┤
│ <stage workspace>                                       │
└─────────────────────────────────────────────────────────┘
```

---

### C3. Side-rail widths are inconsistent (8 different magic numbers)

**Severity:** medium · **Effort:** small · **Affected:** every multi-pane stage

All current widths:

| Width | Used in |
|---:|---|
| 260px | AuditBrowser (left), DifferentialLab (left) |
| 280px | CodeArchaeology (left), CodeGeneration (left) |
| 300px | InventoryHeatmap (left), ReportsHub (left), Reconciliation (left), RuntimeCapture (left) |
| 320px | CodeGeneration (right) |
| 340px | RecipeAuthoring (left) |
| 360px | ModuleMigration (left), CodeArchaeology (right), DifferentialLab (right), Reconciliation (right), RuntimeCapture (right) |
| 400px | CutoverDecommission (left) |
| 420px | Characterization (left), StranglerDesigner (left) |

There's no design rationale — they grew organically. The right rails
(agent surface) are ~360px, the left rails (queue / list) range from 260px to
420px depending on what the queue happened to need at writing time.

**Proposal — three rail tokens:**

```css
:root {
  --rail-narrow: 260px;   /* compact filter / nav lists */
  --rail-default: 320px;  /* queues, lists with metadata */
  --rail-agent:   360px;  /* right rail for agent panel */
}
```

Then introduce `<TwoPane left={<…/>} right={<…/>} />` and
`<ThreePane left right={…} />` components that take a `rail` prop
(`'narrow' | 'default'`) so the rest of the screen never thinks about pixels.

---

### C4. Count-chip rows overflow on narrow viewports — the user-reported "overlapping labels"

**Severity:** high · **Effort:** small · **Affected:** every stage screen

Header rows are coded as a single non-wrapping flex line:

```tsx
<div className="px-5 py-4 border-b border-line flex items-center gap-3">
  <Stage chip /> <h2 /> <CountChips ml-4 /> <ButtonGroup ml-auto />
</div>
```

On viewports ≤1280px (laptops, half-screen layouts, sidebar-collapsed
browser windows), the count-chip block (which can be 4–6 items) collides with
the action button group because the parent has no `flex-wrap`. This is
exactly what you're seeing as "overlapping labels."

**Worst offenders:**

- Differential Lab — 6 chips: `Pass / Benign / Amber / Red` tile row + envelope total + byte% — at [DifferentialLab.tsx:147-153](frontend/src/screens/DifferentialLab.tsx)
- Cutover & Decommission — up to 6 state chips ([CutoverDecommission.tsx:85-92](frontend/src/screens/CutoverDecommission.tsx))
- Inventory & Heatmap — 4 metric chips with `·` separators ([InventoryHeatmap.tsx:142-152](frontend/src/screens/InventoryHeatmap.tsx))

**Fix:** the new `<StageHeader />` must use `flex-wrap` plus a small grid or
vertical-stack at smaller viewports. A simple rule: action buttons stay
right-aligned and pinned, status chips wrap underneath the title at < 1024px.

---

### C5. Color/state mapping is inconsistent across screens

**Severity:** medium · **Effort:** small · **Affected:** semantic palette use

Same conceptual state, different colors:

| Concept | Recipe Authoring | Strangler | Cutover | Char | Diff Lab |
|---|---|---|---|---|---|
| "in progress" | brand (proposed) | brand (planned) | brand (shadow) | warn (benign) | brand (benign) |
| "needs attention" | warn | — | warn (canary) | err (regression) | warn (amber) |
| "complete" | ok (accepted) | ok (ready) / agent (extracted) | ok (live) / agent (decom) | ok (pass) | ok (pass) |
| "rejected" | neutral (rejected) | — | err (rolled-back) | neutral (rejected) | — |

Worth standardising:

- **brand**: in-flight / live state, the "happening now" tone.
- **ok**: positive terminal state (accepted, passed, live).
- **warn**: needs human review (amber, benign-but-not-trivial, canary).
- **err**: failure / regression / rolled-back.
- **agent**: AI-authored output, agent-only (not for migration states).
- **neutral**: opt-out / rejected / archived (drained of color).

The Strangler Designer using `agent` for "extracted" is a bug — extracted is
the terminal good state, that should be `ok`.
[StranglerDesigner.tsx:212](frontend/src/screens/StranglerDesigner.tsx)

---

### C6. Stage-letter chip is rendered inline in 11 screens with the same markup

**Severity:** low · **Effort:** trivial · **Affected:** every stage screen

```tsx
<div className="h-7 w-7 rounded-md bg-brand-50 text-brand inline-flex
                items-center justify-center font-mono text-xs font-semibold">
  A
</div>
```

This is hardcoded in every stage screen with one of two color schemes
(brand-50/brand or agent-50/agent depending on track). Should be a 5-line
`<StageChip stage="C" track="SOAP" />` component.

---

### C7. Skeleton/loading pattern is inconsistent (3 variants)

**Severity:** low · **Effort:** small · **Affected:** every async screen

Skeleton variants in use:

1. **Card-with-skel-bars** (Characterization, Strangler, Recipe, Module
   Migration, Cutover, Diff Lab, Code Generation, Recon, Inventory, Capture)
   — 3 stacked `.skel` divs in a single card.
2. **Grid of card skeletons** (WorkspaceDashboard:279) — 3 cards each with
   ~5 skel bars.
3. **Inline list skel** (AuditBrowser:144) — 4 skel rows, no card.

All of them duplicate the skel structure. A `<LoadingState />` component with
a `variant` prop ('card' | 'grid' | 'list') would let the design team change
the rhythm in one place.

---

### C8. Error states reinvent the same red banner

**Severity:** low · **Effort:** trivial · **Affected:** every async screen

The exact phrase "service unreachable" appears at:

- [CodeArchaeology.tsx](frontend/src/screens/CodeArchaeology.tsx) — implicit (no error UI present)
- [Reconciliation.tsx:60](frontend/src/screens/Reconciliation.tsx) — "Reconciliation service unreachable."
- [CodeGeneration.tsx:44](frontend/src/screens/CodeGeneration.tsx) — "Generation service unreachable."
- [DifferentialLab.tsx:61](frontend/src/screens/DifferentialLab.tsx) — "Differential service unreachable."
- [ReportsHub.tsx:44](frontend/src/screens/ReportsHub.tsx) — "Reports service unreachable."
- [RuntimeCapture.tsx:53](frontend/src/screens/RuntimeCapture.tsx) — "Capture service unreachable."
- [InventoryHeatmap.tsx:49](frontend/src/screens/InventoryHeatmap.tsx) — "Uplift service unreachable."
- [RecipeAuthoring.tsx:51](frontend/src/screens/RecipeAuthoring.tsx) — "Uplift service unreachable."
- [StranglerDesigner.tsx:60](frontend/src/screens/StranglerDesigner.tsx) — "Uplift service unreachable."
- [ModuleMigration.tsx:62](frontend/src/screens/ModuleMigration.tsx) — "Uplift service unreachable."
- [Characterization.tsx:71](frontend/src/screens/Characterization.tsx) — "Uplift service unreachable."
- [CutoverDecommission.tsx:62](frontend/src/screens/CutoverDecommission.tsx) — "Uplift service unreachable."

12 sites with copy-pasted markup. None of them offer **what to do** — the
WorkspaceDashboard is the only screen with a recovery hint
("`.\up.ps1`"). The rest leave the user staring at a red bar.

**Proposal:** `<ErrorState title=… retry=… helpfulHint=… />` with sane
defaults: a retry button, a "view service health" link, and the dev-mode
`up.ps1` hint when in development.

---

### C9. Production language audit — strip "demo" and "stub" copy

**Severity:** high (per the production-grade mandate) · **Effort:** small ·
**Affected:** UI copy + a few badges

Live user-visible references:

| Location | Content |
|---|---|
| [App.tsx:17](frontend/src/App.tsx) | "arrives in Phase 5." |
| [Placeholder.tsx:16](frontend/src/screens/Placeholder.tsx) | "Arrives in a later phase" |
| [ProjectDetail.tsx:201](frontend/src/screens/ProjectDetail.tsx) | "This Track B stage arrives in a later phase. The Inventory & Heatmap is the only stage built so far for the Framework Uplift track." |
| [WorkspaceDashboard.tsx:251-258](frontend/src/screens/WorkspaceDashboard.tsx) | `<DemoChip>` × 2 with "Axis 1.3 demo" and "Spring 5 demo" labels |
| [CodeArchaeology.tsx:107,118](frontend/src/screens/CodeArchaeology.tsx) | Hardcoded sample paths and "Axis 1.3 demo" reference |
| [RuntimeCapture.tsx:156](frontend/src/screens/RuntimeCapture.tsx) | "Phase 1b · demo mode · synthesizes a corpus from" |
| [RuntimeCapture.tsx:216](frontend/src/screens/RuntimeCapture.tsx) | `value="demo-v1"` for PII rules |
| [ReportsHub.tsx:126,181,265](frontend/src/screens/ReportsHub.tsx) | "closure stub" badges + "deterministic (LLM stub)" copy |
| [DifferentialLab.tsx:451](frontend/src/screens/DifferentialLab.tsx) | inline stub badge |
| [Reconciliation.tsx:449](frontend/src/screens/Reconciliation.tsx) | `<StubBadge />` |
| [Badges.tsx:40-42](frontend/src/components/Badges.tsx) | The `StubBadge` component itself |

**Proposal:**

- All "stub"/"demo" badges removed. The decision of whether the LLM gateway
  is connected is an operational concern, not user-visible.
- Sample paths come from a configuration file or a workspace template, not
  hardcoded into form defaults.
- The ProjectDetail Track-B placeholder is gone once Phase 1 is shipped (it
  already is — the placeholder is dead code today).
- The Placeholder component is deleted; routes that have nothing to show
  should not be in the nav.

---

### C10. Stat tiles repeat across 4 screens with subtle variations

**Severity:** low · **Effort:** trivial

Hand-rolled "stat card" appears in:

- [WorkspaceDashboard.tsx:132-150](frontend/src/screens/WorkspaceDashboard.tsx) — `<StatCard>` (icon + number + label)
- [DifferentialLab.tsx:184-207](frontend/src/screens/DifferentialLab.tsx) — `<Tile>` (icon + number + label + sub)
- [AuditBrowser.tsx:295-313](frontend/src/screens/AuditBrowser.tsx) — `<Stat>` (icon + number + label)
- [ModuleMigration.tsx:299-316](frontend/src/screens/ModuleMigration.tsx) — `<SummaryStat>` (no icon, number + label)

Three near-identical components, three slightly different APIs. Lift to a
single shared `<MetricTile />` with optional icon and sub-label.

---

### C11. The shell layout assumes a wide viewport

**Severity:** medium · **Effort:** medium · **Affected:** every screen

Current layout in [Layout.tsx:54-84](frontend/src/components/Layout.tsx):

```tsx
<aside className="w-[var(--nav-w)] shrink-0 …">  {/* 232px nav */}
<main className="flex-1 min-w-0 overflow-y-auto">
  <div className="px-8 py-7 max-w-[1400px] mx-auto">
```

- 232px fixed nav + 64px horizontal padding + 1400px content cap = the layout
  needs ≥1696px to look as designed; below ~1280px the rail-heavy stage
  screens get squeezed (this is the source of C4).
- There's no nav-collapse mechanism for narrow viewports.
- The 1400px max-width is applied uniformly even on screens that benefit from
  going edge-to-edge (Diff Lab, Cutover with closure drawer).

**Proposal:**

- Collapse the left nav to icon-only at < 1024px.
- Use `clamp()` padding instead of fixed `px-8`: `px-[clamp(16px,3vw,32px)]`.
- Audit which stage screens actually want the 1400px cap and which want full
  width — don't apply it globally.

---

### C12. URL state is not synced with stage view selection

**Severity:** medium · **Effort:** small · **Affected:** ProjectDetail

[ProjectDetail.tsx:60-61](frontend/src/screens/ProjectDetail.tsx) holds the
"reviewing" stage in component-local `useState`. Refresh the page, browser
back/forward, share-link → state is gone. We already have `react-router-dom`,
this should be `?stage=B`.

---

### C13. Status bar at the foot is decorative, not functional

**Severity:** low · **Effort:** small · **Affected:** Layout footer

[Layout.tsx:87-100](frontend/src/components/Layout.tsx):

```
atlas-migrate · v0.2.0 · local docker · ● all services healthy · 0 alerts
```

The "all services healthy" dot is hardcoded green, the alerts count is
hardcoded zero. In a production app this is the *exact* place a real health
check belongs. Either wire it to `/api/v1/health/live` (or the gateway's
actuator) or remove it — current state is misinformation.

---

### C14. "Agents online · 3" and the AgentPing card in the side nav

**Severity:** low · **Effort:** trivial · **Affected:** Layout side nav

[Layout.tsx:157-172](frontend/src/components/Layout.tsx):

```
Sparkles  Agents online
Code Archaeology · Reconciliation · Diff Triage
●  idle
```

This card lists 3 agent names that are no longer the only agents (we now have
Recipe, Strangler, Module Migration, Characterization, Cutover) and the "idle"
state is hardcoded. Either remove it or wire it to a real agent registry.

---

## Per-screen findings

Each screen below has its own short list of issues. Items that the
cross-cutting fixes (C1-C14) will resolve are flagged `[CN]`.

### Workspace Dashboard ([WorkspaceDashboard.tsx](frontend/src/screens/WorkspaceDashboard.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| W1 | high | [251-274](frontend/src/screens/WorkspaceDashboard.tsx) | "Demo" chips with hardcoded sample paths shown to every empty workspace [C9] |
| W2 | medium | [104,103](frontend/src/screens/WorkspaceDashboard.tsx) | "Import" button is decorative — has no handler. Either implement or remove. |
| W3 | medium | [305](frontend/src/screens/WorkspaceDashboard.tsx) | Modal has hardcoded defaults for fields (`/samples/soap-axis13-demo`, `Broadridge MF Service`). Should be empty / placeholder. [C9] |
| W4 | medium | [311-313](frontend/src/screens/WorkspaceDashboard.tsx) | `as any` cast on the create-project body bypasses our types. |
| W5 | low | [114](frontend/src/screens/WorkspaceDashboard.tsx) | "Agents online: 3" — same hardcoded value as C14. |
| W6 | low | [123-130](frontend/src/screens/WorkspaceDashboard.tsx) | `computeStats` counts gates by stage-letter index alone — projects parked at F with all 6 gates passed only get credit for 5. |
| W7 | low | [198-223](frontend/src/screens/WorkspaceDashboard.tsx) | Mini stage pipeline in project card uses the same letter-bar logic that ProjectDetail uses, but inline. Lift both to one component. |

### Project Detail (shell) ([ProjectDetail.tsx](frontend/src/screens/ProjectDetail.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| P1 | high | [261-340](frontend/src/screens/ProjectDetail.tsx) | StagePipeline + StageHeader double-header [C2] |
| P2 | medium | [60-61](frontend/src/screens/ProjectDetail.tsx) | View-stage state not in URL [C12] |
| P3 | medium | [193-206](frontend/src/screens/ProjectDetail.tsx) | `upliftPlaceholder` is dead code now that all UPLIFT stages exist [C9] |
| P4 | medium | [178-189](frontend/src/screens/ProjectDetail.tsx) | Same dead placeholder for SOAP track too [C9] |
| P5 | low | [46](frontend/src/screens/ProjectDetail.tsx) | `STAGES` const declared but never read — unused. |
| P6 | low | [115-135](frontend/src/screens/ProjectDetail.tsx) | ReviewBanner uses `card` styling on a thin info bar; conflates with content cards. Wants an `<InlineNotice tone="info" />` with no card border. |
| P7 | low | [141-142](frontend/src/screens/ProjectDetail.tsx) | Outdated comment "only Stages A & B are built so far". |
| P8 | low | [237-242](frontend/src/screens/ProjectDetail.tsx) | Hero meta row uses `gap-x-5 gap-y-2` but always shows 4 items at the same width; the calendar icon's "Java —" reads as broken when version is unset. |

### Code Archaeology ([CodeArchaeology.tsx](frontend/src/screens/CodeArchaeology.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| A1 | high | [62-87](frontend/src/screens/CodeArchaeology.tsx) | StageHeader duplication [C1] |
| A2 | medium | [89](frontend/src/screens/CodeArchaeology.tsx) | 280px / 360px rail widths not aligned with the rest of the app [C3] |
| A3 | medium | [430-444](frontend/src/screens/CodeArchaeology.tsx) | "Accept / Modify / Chat / Reject" buttons in the agent pane have no handlers — purely decorative. Either wire them or remove. |
| A4 | low | [183-225](frontend/src/screens/CodeArchaeology.tsx) | Operations list shows status dots, but the legend (the small dot+count row at top) uses different colors than the per-row icons. |
| A5 | low | [333-344](frontend/src/screens/CodeArchaeology.tsx) | `WireTab` has hardcoded "DOCUMENT / LITERAL" — no actual data binding. |
| A6 | low | [251-255](frontend/src/screens/CodeArchaeology.tsx) | "line ?" fallback when `sourceLines[0]` is missing reads as broken. |
| A7 | low | [107,118](frontend/src/screens/CodeArchaeology.tsx) | Hardcoded sample path `/samples/soap-axis13-demo` [C9] |

### Runtime Capture ([RuntimeCapture.tsx](frontend/src/screens/RuntimeCapture.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| B1 | high | [71-112](frontend/src/screens/RuntimeCapture.tsx) | StageHeader duplication [C1] |
| B2 | medium | [113](frontend/src/screens/RuntimeCapture.tsx) | 300px / 360px rails [C3] |
| B3 | medium | [156](frontend/src/screens/RuntimeCapture.tsx) | "Phase 1b · demo mode" label visible to user [C9] |
| B4 | medium | [216](frontend/src/screens/RuntimeCapture.tsx) | "demo-v1" PII rules version visible to user [C9] |
| B5 | low | [216](frontend/src/screens/RuntimeCapture.tsx) | "Max payload 64 KB" hardcoded into UI — pull from deployment config. |
| B6 | low | [82-89](frontend/src/screens/RuntimeCapture.tsx) | Live/paused/finalized state computed inline 3 times — extract a `captureState()` helper. |

### Reconciliation ([Reconciliation.tsx](frontend/src/screens/Reconciliation.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| C1 | high | [128-175](frontend/src/screens/Reconciliation.tsx) | StageHeader + the embedded progress bar are an unusual extra-tall variant — needs to be a `<StageHeader.WithProgress>` slot, not a one-off. |
| C2 | high | [292-302](frontend/src/screens/Reconciliation.tsx) | Three-column comparison: column headers carry distinct color (fg-3 / brand / agent) but the columns themselves all use the same warning tint when divergent → defeats the visual cue. |
| C3 | medium | [307-316](frontend/src/screens/Reconciliation.tsx) | `agreement()` function returns `'diverge'` whenever a view is present. So all populated columns show as warning; "agree" is never returned. Bug. |
| C4 | medium | [292](frontend/src/screens/Reconciliation.tsx) | Three-way comparison columns have no min-width — long type names get cut. |
| C5 | low | [215](frontend/src/screens/Reconciliation.tsx) | "Filter" segmented control uses `text-2xs` which is below ADA 12px minimum. |

### Code Generation ([CodeGeneration.tsx](frontend/src/screens/CodeGeneration.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| D1 | high | [94-147](frontend/src/screens/CodeGeneration.tsx) | StageHeader duplication [C1] — and 4 different status chips (status, files, errors, warnings) make this header particularly cramped [C4] |
| D2 | medium | [171](frontend/src/screens/CodeGeneration.tsx) | Bindings download URL is constructed with `replace('/output.zip', '/bindings')` — fragile string surgery. |
| D3 | medium | [266-272](frontend/src/screens/CodeGeneration.tsx) | `CodeBlock` is a `<pre>` with no syntax highlighting at all — the `lang-xml` class is dead. Either ship a real highlighter (Shiki at build time) or drop the class. |
| D4 | medium | [344-381](frontend/src/screens/CodeGeneration.tsx) | TreeNode keyboard navigation is missing — folders expand on click only, no Space/Enter, no arrow keys. |
| D5 | low | [302-305](frontend/src/screens/CodeGeneration.tsx) | "Click ⬇ Download ZIP in the header to retrieve the full tree" footer is verbose; redundant since the icon is already there. |

### Differential Lab ([DifferentialLab.tsx](frontend/src/screens/DifferentialLab.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| E1 | high | [128-181](frontend/src/screens/DifferentialLab.tsx) | StageHeader is by far the tallest in the app — 4 tile metrics + per-operation bars. Reads as a "supplementary panel," not a header. Move below the chrome and shrink the strap. |
| E2 | high | [262-281](frontend/src/screens/DifferentialLab.tsx) | The bucket switcher is a 4-tile control where each tile shows label-above-count; the count font uses `font-mono text-sm` which is the same weight as the label, so the eye doesn't know what to read first. Swap to count-large + label-below. |
| E3 | medium | [216-220,219](frontend/src/screens/DifferentialLab.tsx) | OperationRow renders a 4-segment progress bar but all four segments collapse to 0 width when total = 0 — the bar disappears entirely instead of showing a placeholder. |
| E4 | medium | [471-486](frontend/src/screens/DifferentialLab.tsx) | The "Auto-fix" + "Accept" two-button group ends with a single check icon that has no label — purpose unclear. |
| E5 | low | [302-329](frontend/src/screens/DifferentialLab.tsx) | DivergenceRow shows a single Check icon at end-of-row when resolved, but uses no other visual treatment — the row stays full-color. Should drain to muted on resolved. |

### Reports Hub ([ReportsHub.tsx](frontend/src/screens/ReportsHub.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| F1 | high | [96-152](frontend/src/screens/ReportsHub.tsx) | StageHeader duplication [C1] |
| F2 | medium | [126,181,265](frontend/src/screens/ReportsHub.tsx) | "stub" badges visible in user-facing copy [C9] |
| F3 | high | [281-374](frontend/src/screens/ReportsHub.tsx) | Hand-rolled "MarkdownLite" is half a markdown parser. Misses tables, code blocks, fenced code, headings inside lists, escapes. The closure document includes tables ([CutoverService.java](services/uplift-service/src/main/java/com/envestnet/atlas/uplift/service/CutoverService.java) emits `\| Module \|` etc.) which won't render. Replace with a real renderer (`react-markdown` + `remark-gfm`). |
| F4 | medium | [193-206](frontend/src/screens/ReportsHub.tsx) | Bundle manifest items show `Check` icon for every line regardless of whether the artifact actually exists — not a real status. |
| F5 | low | [165-182](frontend/src/screens/ReportsHub.tsx) | The list of bundle items is hardcoded — should be derived from the real manifest in the response. |

### Inventory & Heatmap ([InventoryHeatmap.tsx](frontend/src/screens/InventoryHeatmap.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| G1 | high | [118-167](frontend/src/screens/InventoryHeatmap.tsx) | StageHeader duplication; also has the second-line "source: …" path under it which is a unique pattern [C1] |
| G2 | medium | [186-235](frontend/src/screens/InventoryHeatmap.tsx) | Treemap is a flexbox of buttons sized by `flexGrow` proportional to `sqrt(loc)`. With ≤ 4 modules the tiles are huge. With 30+ they squeeze below the `min-h-[100px]`. Needs proper treemap algorithm (squarify) or grid fallback. |
| G3 | medium | [255-259](frontend/src/screens/InventoryHeatmap.tsx) | Difficulty colour coding uses HSL interpolation `hsl(${hue}deg ${sat}% ${light}%)` directly — bypasses the design system, can't be themed. |
| G4 | low | [389-391](frontend/src/screens/InventoryHeatmap.tsx) | FindingRow shows snippet but doesn't escape HTML / Java angle brackets in `<pre>`. Safe today (React escapes), but worth confirming when we move to a real diff renderer. |

### Recipe Authoring ([RecipeAuthoring.tsx](frontend/src/screens/RecipeAuthoring.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| H1 | high | [69-124](frontend/src/screens/RecipeAuthoring.tsx) | StageHeader duplication [C1] |
| H2 | medium | [129](frontend/src/screens/RecipeAuthoring.tsx) | 340px rail [C3] |
| H3 | medium | [486-571](frontend/src/screens/RecipeAuthoring.tsx) | "Custom recipe" form is inline above the grid; on small screens it pushes the case detail off-screen with no scroll feedback. Move to side drawer or modal. |
| H4 | low | [414-460](frontend/src/screens/RecipeAuthoring.tsx) | Findings table inside the right pane has fixed column widths (`w-20`, `w-44`); on narrow rail this overflows horizontally. |

### Strangler Designer ([StranglerDesigner.tsx](frontend/src/screens/StranglerDesigner.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| I1 | high | [69-119](frontend/src/screens/StranglerDesigner.tsx) | StageHeader duplication [C1] |
| I2 | medium | [120](frontend/src/screens/StranglerDesigner.tsx) | 420px rail (largest in app) [C3] |
| I3 | high | [212](frontend/src/screens/StranglerDesigner.tsx) | "extracted" status uses agent purple instead of ok green — incorrect semantic mapping [C5] |
| I4 | medium | [196-249](frontend/src/screens/StranglerDesigner.tsx) | Up/down chevrons are inside the row's left gutter; tapping near them often triggers the row select instead. Pointer events leak. |
| I5 | medium | [203-211](frontend/src/screens/StranglerDesigner.tsx) | Up/down chevrons have `aria-label` ✓ but no live region announcement after reorder — screen reader users get no feedback that the order changed. |

### Module Migration ([ModuleMigration.tsx](frontend/src/screens/ModuleMigration.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| J1 | high | [86-134](frontend/src/screens/ModuleMigration.tsx) | StageHeader duplication [C1] |
| J2 | high | [400-446](frontend/src/screens/ModuleMigration.tsx) | DiffViewer renders `<pre>` with per-line `<div>` color classes only — works but doesn't highlight the changed *tokens* within a line. Real diff tools (a CodeMirror diff or `diff2html`) would be a meaningful upgrade. |
| J3 | medium | [347](frontend/src/screens/ModuleMigration.tsx) | Inner grid uses `grid-cols-[360px_1fr]` for "files / diff" — the 360px is just barely enough for long Java paths. Should auto-size with `minmax()` or scrollable. |
| J4 | medium | [357-374](frontend/src/screens/ModuleMigration.tsx) | FileRow truncates the directory but doesn't show a tooltip with the full path — already has `title` on the parent element. Confusing UX. |
| J5 | low | [180-208](frontend/src/screens/ModuleMigration.tsx) | StepRow runs its *own* TanStack query for the migration of that step (independent of the screen-level query). Causes 3-6 simultaneous queries and `refetchInterval: 15000` per row — wasteful. Move to a single `migrationStatus` projection. |

### Characterization ([Characterization.tsx](frontend/src/screens/Characterization.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| K1 | high | [84-130](frontend/src/screens/Characterization.tsx) | StageHeader duplication [C1] |
| K2 | high | [137-159](frontend/src/screens/Characterization.tsx) | Module filter strip is `flex overflow-x-auto` — on a typical project with 3-4 modules this works, but at 20+ modules the strip becomes a horizontal scrolljack and there's no indication you can scroll. |
| K3 | medium | [170](frontend/src/screens/Characterization.tsx) | 420px rail [C3] |
| K4 | medium | [375-389](frontend/src/screens/Characterization.tsx) | Legacy/new output blocks use `whitespace-pre-wrap break-all` — long strings break in the middle of words. Should `break-words` for prose, `break-all` only for monospace identifiers. |
| K5 | low | [274-280](frontend/src/screens/Characterization.tsx) | "Test cases" filter chips use `2xs` font (10px) — below comfortable reading. |

### Cutover & Decommission ([CutoverDecommission.tsx](frontend/src/screens/CutoverDecommission.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| L1 | high | [73-122](frontend/src/screens/CutoverDecommission.tsx) | StageHeader is the most overloaded in the app — 6 state count chips + 3 buttons + refresh = 10 elements in a non-wrapping row [C1, C4] |
| L2 | medium | [134](frontend/src/screens/CutoverDecommission.tsx) | 400px rail [C3] |
| L3 | medium | [281-310](frontend/src/screens/CutoverDecommission.tsx) | State stepper is a row of 4 buttons (planned/shadow/canary/live) followed by a "·" separator and 2 more buttons (rollback / decommission). Mixed levels of action — primary state machine and exception cases sit on the same row at the same weight. |
| L4 | medium | [466-501](frontend/src/screens/CutoverDecommission.tsx) | ClosureDocumentDrawer shows the markdown as a `<pre>` — not the rendered preview. The Reports Hub markdown renderer should be reused here, or both should use react-markdown. |
| L5 | low | [430-457](frontend/src/screens/CutoverDecommission.tsx) | Checklist categories `{test, code, docs, ops, infra}` map to badge colours but the legend isn't documented anywhere. |

### Audit Browser ([AuditBrowser.tsx](frontend/src/screens/AuditBrowser.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| M1 | medium | [187-249](frontend/src/screens/AuditBrowser.tsx) | The filter rail is `sticky top-4`; on short viewports it overlaps the page footer. |
| M2 | medium | [80-97](frontend/src/screens/AuditBrowser.tsx) | Project picker is a native `<select>` styled to look custom — inconsistent with the rest of the app's pickers. |
| M3 | medium | [9-23](frontend/src/screens/AuditBrowser.tsx) | `ACTION_LABELS` is hand-maintained — every new provenance event source must remember to add a label here, otherwise the user sees a raw `snake_case` action. Several new actions from Stages 1i-1M are already missing (`recipes_seeded`, `recipe_decision`, `strangler_plan_seeded`, `strangler_step_updated`, `module_migrated`, `characterization_run_completed`, `cutover_plan_seeded`, `cutover_state_changed`). |
| M4 | low | [357-376](frontend/src/screens/AuditBrowser.tsx) | Expanded entry row uses `ml-10 mr-4` indenting — visually disconnects the detail block from its row when scrolled. |

### Layout shell ([Layout.tsx](frontend/src/components/Layout.tsx))

| # | Severity | Where | Issue |
|---|---|---|---|
| S1 | medium | [131-141](frontend/src/components/Layout.tsx) | "Crumb" breadcrumb is hardcoded "Engagement / Envestnet" — doesn't reflect the actual route. |
| S2 | medium | [143-155](frontend/src/components/Layout.tsx) | ⌘K command palette button is decorative — no handler. |
| S3 | medium | [40-42](frontend/src/components/Layout.tsx) | Bell button (notifications) has no handler. |
| S4 | medium | [44-50](frontend/src/components/Layout.tsx) | User menu chevron suggests a dropdown that doesn't exist. |
| S5 | medium | [87-100](frontend/src/components/Layout.tsx) | Footer "all services healthy / 0 alerts" is hardcoded [C13] |
| S6 | medium | [157-172](frontend/src/components/Layout.tsx) | AgentPing card lists wrong agents and is hardcoded "idle" [C14] |
| S7 | low | [56-76](frontend/src/components/Layout.tsx) | Workspace switcher chevron implies a dropdown that doesn't exist. |

---

## Design system gap analysis

### Components that should exist

These would replace the duplicated markup catalogued in C1, C6, C7, C8, C10:

- `<StageChrome stage track title actions status progress />` — combines pipeline strap with action header (resolves C1, C2, C6).
- `<MetricTile icon label value sub tone />` — replaces `<StatCard>`, `<Tile>`, `<Stat>`, `<SummaryStat>` (C10).
- `<CountChipRow chips />` — wrapping flex of label+value+tone, used in headers (C4).
- `<PaneShell layout left right rail rightRail header content />` — the `card overflow-hidden + grid-cols-[…]` pattern (C3).
- `<EmptyState icon title body actions hint />` — replaces 12 hand-coded empty cards.
- `<ErrorState title hint retry />` — replaces 12 inline error banners (C8).
- `<LoadingState variant />` — replaces 13 skel layouts (C7).
- `<InlineNotice tone="info|warn|err" icon body actions />` — for thin info bars like the "Reviewing Stage" banner (P6).
- `<KeyValuePair k v />` — replaces `<Pair>`, `<Row>`, `<Field>`, `<Meta>`, `<Stat>` micro-rows.
- `<StatusBadge state="planned|live|…" />` — kills the `state === 'live' ? 'badge-ok' : …` ternaries spread across 4 screens (C5).
- `<DiffViewer />` — proper line-and-token diff rendering (J2).
- `<MarkdownView body />` — `react-markdown` + `remark-gfm` (F3, L4).
- `<DataTable />` — for the operation lists, finding lists, file change lists (~6 instances of hand-rolled tables).
- `<SegmentedControl options value onChange />` — replaces filter pills in Reconciliation, Diff Lab, Characterization, Recipe Authoring.
- `<SidePanel side="right" open onClose />` — for custom recipe form, closure doc drawer, etc.

### Tokens to add

```css
/* Layout rails */
--rail-narrow:   260px;
--rail-default:  320px;
--rail-agent:    360px;

/* Vertical rhythm */
--screen-pad:    clamp(20px, 3vw, 40px);
--card-pad:      clamp(16px, 2vw, 24px);
--row-pad:       12px;

/* Stage chrome */
--chrome-h:      52px;        /* one consistent stage header height */
--pipeline-h:    56px;

/* Density modes */
--density:       'comfortable'; /* 'compact' for power users */
```

### Tokens to rename

The Tailwind `colors.line` and `colors.line2` are non-semantic. Rename to
`border-default` and `border-strong` so callers don't have to remember which
"line" is which.

`'fg-1' / 'fg-2' / 'fg-3' / 'fg-4'` works but isn't industry-standard. Consider
aliasing as `text-primary / secondary / tertiary / quaternary` for new code
without removing the existing tokens. Pick one in the design pass; don't keep
both in flight long-term.

---

## Accessibility baseline

**Counts:** 4 `aria-*` attributes total in the entire frontend. No skip links.
No focus traps in modals. No live regions.

### Issues found

| # | Severity | Where | Issue |
|---|---|---|---|
| AX1 | high | All modals (`NewProjectModal`, `CustomRecipeForm` overlay, ClosureDocumentDrawer) | No focus trap, no Escape-to-close, no `aria-modal`, no return-focus on close. |
| AX2 | high | All buttons that are icon-only | Most have a `title` but no `aria-label`. Examples: refresh chevron, up/down chevrons in StranglerDesigner (these *do* have aria-label, the rest don't), Bell, all icon-only secondaries. |
| AX3 | high | Empty/error states | Decorative SVG icons aren't `aria-hidden`. Screen readers announce "image" or "graphic" before the heading. |
| AX4 | high | Stage pipeline | The `<nav aria-label="Stages">` is correct but the disabled (future) stages render as `<div>` with no semantics — screen reader users can't tell they exist. Should be `<button disabled>` or an `<a>` with `aria-disabled="true"`. |
| AX5 | medium | All async mutations | No `aria-live` region — the user clicks a button, the button text changes from "Save" to "Saving…" and back, with no announcement to non-visual users. |
| AX6 | medium | Color-only state communication | "open" vs "fixed" vs "rejected" badges on Characterization cases distinguished only by colour at small sizes. Need a glyph or text suffix. |
| AX7 | medium | Stage screens with multiple panes | No landmarks (`<main>`, `<nav>`, `<aside>`). Layout has `<main>` but stage screens are flat divs. |
| AX8 | medium | ProjectDetail back link | "← Workspaces" is `<Link>` but visually styled as a button — the underline/cursor cues for "this is navigation" are mostly missing. |
| AX9 | low | Tabs (CodeArchaeology, CodeGeneration, ReportsHub) | Tabs are buttons with no `role="tab"`, no `aria-selected`, no `aria-controls`. |
| AX10 | low | Color contrast | `text-fg-3` on `bg-canvas` is `#6B7280` on `#F7F8FA` — about 4.4:1, just at AA for large text but fails AA for body text under 14pt. Several places use it for `text-2xs`. |
| AX11 | low | All color-only badges | Severity dots in DivergenceRow / ConfidenceBadge use `dot bg-current` plus the color of the badge — works visually but the dot isn't announced; redundant for screen readers but not hurting. Worth `<span aria-hidden="true">` on the dot and ensuring the badge text is enough. |
| AX12 | low | Keyboard navigation | Treemap tiles in InventoryHeatmap are buttons but lack `tab` order indication. Up/down chevrons in Strangler don't accept keyboard up/down (just click). |

### Quick wins (1-2 hours each)

1. Add `aria-hidden="true"` to every Lucide icon adjacent to text — these are decorative.
2. Add `aria-label` to every icon-only button (refresh, bell, chevrons).
3. Convert tab buttons to a `<Tabs>` component with proper roles.
4. Add a global `aria-live="polite"` region in Layout for in-flight mutations to write to.
5. Add a skip link in Layout: "Skip to main content" → focuses `<main>`.

### Structural pieces (each its own day)

1. Build a `<Modal>` primitive with focus trap, scroll lock, return-focus, and `aria-modal`. Refactor the 3 existing modals onto it.
2. Audit color contrast at all sizes. The `text-2xs` use of `fg-3` is the most common AA failure.
3. Convert the stage pipeline future-stage placeholders into `<button disabled aria-disabled="true">` with a screen-reader explanation.

---

## Proposed remediation order (Phase 2A.2 + 2A.3)

The cross-cutting items unlock most of the per-screen items, so they go first.

### Phase 2A.2 — foundation (≈ 4-5 days)

Build the missing components, refactor every screen onto them. No new
features; this is a pure quality pass. Each item below is a separate PR
ideally, so reviews are tractable.

1. **Token cleanup** — rail widths, density tokens, semantic border names. (½ day)
2. **`<StageChrome>` + `<StageChip>` + `<CountChipRow>`** — adopt across all 12 stage screens (C1, C2, C4, C6). (1 day)
3. **`<EmptyState>` + `<ErrorState>` + `<LoadingState>`** — adopt across all screens (C7, C8). (1 day)
4. **`<MetricTile>` + `<StatusBadge>` + `<KeyValuePair>`** (C5, C10). (½ day)
5. **`<PaneShell>` + responsive rail behavior** (C3, C11). (1 day)
6. **Markdown renderer + diff viewer** (F3, J2, L4). (1 day)
7. **URL state for view-stage + project router** (C12, P2). (½ day)

### Phase 2A.3 — per-screen polish (≈ 3-4 days)

After 2A.2 most per-screen items collapse into "remove duplicated header,
adopt new components." The remaining real work:

1. **WorkspaceDashboard** — remove demo chips, fix Import button or remove,
   real default values in the modal (W1, W2, W3, W4, W5, W6). (½ day)
2. **Reconciliation** — fix the `agreement()` bug (C3 in per-screen, not the
   cross-cutting C3), redesign the three-way comparison columns (½ day).
3. **Differential Lab** — re-shape the bucket switcher, fix the 0-total
   progress bar (E2, E3). (½ day)
4. **Reports Hub** — adopt real markdown renderer, derive bundle manifest
   from actual response (F3, F4, F5). (½ day)
5. **Inventory Heatmap** — implement squarify treemap (G2). (1 day)
6. **Cutover** — split state stepper from rollback/decom actions (L3),
   re-use markdown renderer for closure (L4). (½ day)
7. **Layout shell** — wire bell / breadcrumb / health to real signals or
   remove them; remove AgentPing or wire it (S1-S6, C13, C14). (½ day)
8. **Audit Browser** — auto-generate ACTION_LABELS or use a label
   resolver service (M3); replace native select (M2). (½ day)

### Phase 2A.4 — accessibility pass (≈ 2 days)

Run after both refactor passes are merged so we're applying a11y to the
final shape, not to throwaway code.

1. **Quick wins from §AX** — `aria-hidden` on decorative icons, `aria-label`
   on icon buttons, skip link, live region. (½ day)
2. **`<Modal>` primitive** with focus trap (AX1). (1 day)
3. **Tabs primitive** with proper roles (AX9). (½ day)
4. **Color contrast audit + fix** the `text-2xs text-fg-3` usages (AX10).
   (½ day, possibly automated with axe).

---

## Out of scope for 2A

These are valid issues but belong to other phases:

- **Real OpenRewrite execution / real characterization runner** — Phase 2C, 2D.
- **Auth & per-workspace isolation** — Phase 2E.
- **Test coverage** — Phase 2B.
- **Decommissioned-state guided flow** — call this product polish; revisit after auth.
- **Onboarding tour / `/help`** — Phase 2F.

---

## Open questions for you

1. **Stage pipeline collision (C2):** option 1, 2, or 3? I recommend 3
   (merged composite chrome).
2. **Closure markdown:** ship `react-markdown + remark-gfm` (full GFM tables,
   ~30KB gzipped) or write a small in-house renderer that handles the
   subset Atlas emits (tables, headings, lists, inline code)?
3. **Density mode:** add a global "compact" toggle for power users now, or
   defer to Phase 2F polish? It's a small lever that mostly affects rail
   widths and row heights.
4. **Workspace dashboard tiles:** keep the four "stats" tiles or replace
   with a richer "what needs attention right now" list (open regressions,
   gates approaching, agents running)?
5. **Drop the Placeholder route entirely?** Currently `/reports` and `/settings`
   in the left nav go to a "Arrives in a later phase" card. We'd be better
   off either implementing them or removing the nav entries until they exist.
