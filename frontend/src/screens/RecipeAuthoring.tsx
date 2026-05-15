import { useEffect, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ChefHat, Sparkles, RefreshCw, Check, X, Plus, Trash2,
  Boxes, ScrollText, Lock, FileCode, ChevronRight, Save
} from 'lucide-react';
import { api, Project, Recipe, RecipeFinding } from '../api/client';
import { hasRole } from '../auth/authClient';
import {
  TwoPane, EmptyState, LoadingState, ErrorState, Modal,
  IconButton, useStageChrome, useAnnouncer
} from '../components/ui';

export default function RecipeAuthoring({
  projectId, project
}: { projectId: string; project: Project }) {
  const qc = useQueryClient();
  const { setStatus, setActions } = useStageChrome();
  const { announce } = useAnnouncer();

  const { data: status, isLoading, error, refetch } = useQuery({
    queryKey: ['recipes', projectId],
    queryFn: () => api.recipesStatus(projectId),
    refetchInterval: 10000
  });

  const projectQ = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.project(projectId)
  });
  const stageBPassed = (projectQ.data as any)?.gates?.find((g: any) => g.label === 'B')?.state === 'passed';

  const seedM = useMutation({
    mutationFn: () => api.seedRecipes(projectId),
    onSuccess: (r) => {
      announce(`Seeded ${r.count} recipe${r.count === 1 ? '' : 's'} from inventory findings`);
      qc.invalidateQueries({ queryKey: ['recipes', projectId] });
    }
  });

  const finalizeM = useMutation({
    mutationFn: () => api.finalizeRecipes(projectId),
    onSuccess: () => {
      announce('Stage B finalized — advancing to Strangler Designer');
      qc.invalidateQueries({ queryKey: ['recipes', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    }
  });

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [showCustomForm, setShowCustomForm] = useState(false);

  // Inject chrome content.
  useEffect(() => {
    if (!status || status.recipes.length === 0) {
      setStatus([]);
      setActions(null);
      return;
    }
    setStatus([
      { label: 'proposed', value: status.counts.proposed, tone: 'brand' },
      { label: 'accepted', value: status.counts.accepted, tone: 'ok' },
      { label: 'rejected', value: status.counts.rejected, tone: 'warn' }
    ]);
    setActions(
      <>
        <IconButton icon={<RefreshCw size={13} />} ariaLabel="Refresh recipes" onClick={() => refetch()} />
        <button
          className="btn-secondary btn-sm"
          onClick={() => seedM.mutate()}
          disabled={seedM.isPending || stageBPassed}
          title="Re-seed from latest inventory findings (preserves your decisions)"
        >
          <Sparkles size={13} aria-hidden="true" /> {seedM.isPending ? 'Seeding…' : 'Re-seed'}
        </button>
        <button
          className="btn-secondary btn-sm"
          onClick={() => { setShowCustomForm(true); setSelectedId(null); }}
          disabled={stageBPassed}
        >
          <Plus size={13} aria-hidden="true" /> Custom recipe
        </button>
        <button
          className="btn-primary btn-sm"
          onClick={() => finalizeM.mutate()}
          disabled={finalizeM.isPending || !status.readyForGate || stageBPassed || !hasRole('TECH_LEAD')}
          title={
            stageBPassed ? 'Stage B already finalized'
            : !hasRole('TECH_LEAD') ? 'Finalizing a stage requires the Tech Lead role'
            : !status.readyForGate ? 'Accept at least one recipe to finalize'
                              : 'Finalize Stage B and advance to Strangler Designer'
          }
        >
          <Lock size={13} aria-hidden="true" />
          {finalizeM.isPending ? 'Finalizing…' : stageBPassed ? 'Finalized' : 'Finalize Stage B'}
        </button>
      </>
    );
  }, [status, stageBPassed, seedM.isPending, finalizeM.isPending]); // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <LoadingState />;
  if (error) {
    return <ErrorState
      title="Uplift service unreachable"
      detail="The uplift-service didn't respond."
      onRetry={() => refetch()}
    />;
  }

  const s = status!;
  const empty = s.recipes.length === 0;
  const selected = s.recipes.find(r => r.id === selectedId) ?? s.recipes[0];

  if (empty && !showCustomForm) {
    return <NotStarted
      onSeed={() => seedM.mutate()}
      seeding={seedM.isPending}
      project={project}
      onAddCustom={() => setShowCustomForm(true)}
    />;
  }

  return (
    <>
      <Modal
        open={showCustomForm}
        onClose={() => setShowCustomForm(false)}
        title="New custom recipe"
        size="lg"
      >
        <CustomRecipeForm
          projectId={projectId}
          onClose={() => setShowCustomForm(false)}
          onCreated={(id) => { setShowCustomForm(false); setSelectedId(id); }}
        />
      </Modal>

      <TwoPane
        rail="default"
        left={
          <RecipeListPane
            recipes={s.recipes}
            selectedId={selected?.id ?? null}
            onSelect={setSelectedId}
          />
        }
      >
        {selected
          ? <RecipeDetailPane
              key={selected.id}
              projectId={projectId}
              recipe={selected}
              disabled={stageBPassed}
            />
          : <div className="p-8 text-fg-3 text-sm">Select a recipe to review.</div>}
      </TwoPane>
    </>
  );
}

/* ---------------- Empty state ---------------- */

function NotStarted({
  onSeed, seeding, project, onAddCustom
}: {
  onSeed: () => void; seeding: boolean; project: Project; onAddCustom: () => void;
}) {
  return (
    <EmptyState
      icon={ChefHat}
      accent="gradient"
      title="Author your migration recipes"
      body="Atlas pre-fills a curated recipe library from your Stage A heatmap. Each finding is grouped under the OpenRewrite (or custom) recipe that addresses it — accept the ones to run, reject the ones already handled, and add your own."
      hint={project.sourcePath
        ? <>Source: <span className="font-mono text-fg-1">{project.sourcePath}</span></>
        : undefined}
      primaryAction={
        <button className="btn-brand" disabled={seeding} onClick={onSeed}>
          <Sparkles size={14} aria-hidden="true" /> {seeding ? 'Seeding…' : 'Seed from inventory'}
        </button>
      }
      secondaryAction={
        <button className="btn-secondary" onClick={onAddCustom}>
          <Plus size={14} aria-hidden="true" /> Add custom
        </button>
      }
    />
  );
}

/* ---------------- Counts ---------------- */

/* ---------------- List pane ---------------- */

function RecipeListPane({
  recipes, selectedId, onSelect
}: {
  recipes: Recipe[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  return (
    <>
      <div className="px-4 py-3 border-b border-line">
        <div className="eyebrow">Recipe library</div>
        <div className="mt-1 text-xs text-fg-3">{recipes.length} recipes</div>
      </div>
      <ul className="flex-1 overflow-y-auto py-2" role="list">
        {recipes.map(r => {
          const active = r.id === selectedId;
          return (
            <li key={r.id}>
              <button
                onClick={() => onSelect(r.id)}
                aria-current={active ? 'true' : undefined}
                className={`w-full text-left px-4 py-3 border-b border-line/60 transition-colors
                  ${active ? 'bg-brand-50 border-l-2 border-l-brand' : 'hover:bg-surface/80'}`}
              >
                <div className="flex items-center gap-2">
                  <StatusDot status={r.status} />
                  <span className={`text-sm font-medium ${active ? 'text-brand' : 'text-fg-1'} truncate`}>
                    {r.label}
                  </span>
                  <ChevronRight size={12} className={`ml-auto ${active ? 'text-brand' : 'text-fg-4'}`} />
                </div>
                <div className="mt-1 flex items-center gap-2 text-2xs text-fg-3 ml-4">
                  <span className={`badge-mono ${r.kind === 'custom' ? 'badge-agent' : 'badge-neutral'}`}>
                    {r.kind}
                  </span>
                  <span className="font-mono truncate">{r.recipeId}</span>
                </div>
                <div className="mt-1 ml-4 flex items-center gap-3 text-2xs text-fg-3">
                  <span className="inline-flex items-center gap-1">
                    <ScrollText size={11} /> {r.findingCount} {r.findingCount === 1 ? 'finding' : 'findings'}
                  </span>
                  <span className="inline-flex items-center gap-1">
                    <Boxes size={11} /> {r.modules.length} {r.modules.length === 1 ? 'module' : 'modules'}
                  </span>
                </div>
              </button>
            </li>
          );
        })}
      </ul>
    </>
  );
}

function StatusDot({ status }: { status: Recipe['status'] }) {
  const cls =
    status === 'accepted' ? 'bg-ok'
  : status === 'rejected' ? 'bg-fg-4'
                          : 'bg-brand dot-pulse';
  return <span className={`dot ${cls}`} />;
}

/* ---------------- Detail pane ---------------- */

function RecipeDetailPane({
  projectId, recipe, disabled
}: {
  projectId: string;
  recipe: Recipe;
  disabled: boolean;
}) {
  const qc = useQueryClient();
  const [notes, setNotes] = useState(recipe.notes ?? '');
  const [notesDirty, setNotesDirty] = useState(false);

  const decisionM = useMutation({
    mutationFn: (body: { status: Recipe['status']; notes?: string }) =>
      api.decideRecipe(projectId, recipe.id, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['recipes', projectId] });
      setNotesDirty(false);
    }
  });

  const deleteM = useMutation({
    mutationFn: () => api.deleteRecipe(projectId, recipe.id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['recipes', projectId] })
  });

  const findingsQ = useQuery({
    queryKey: ['recipes', projectId, recipe.id, 'findings'],
    queryFn: () => api.recipeFindings(projectId, recipe.id)
  });

  const decide = (status: Recipe['status']) =>
    decisionM.mutate({ status, notes: notesDirty ? notes : undefined });

  return (
    <div className="flex flex-col min-w-0">
      {/* Title row */}
      <div className="px-6 py-5 border-b border-line">
        <div className="flex items-start gap-3">
          <div className="h-8 w-8 rounded-md bg-canvas border border-line flex items-center justify-center text-fg-2">
            <ChefHat size={14} />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className="text-lg font-semibold text-fg-1 leading-tight">{recipe.label}</h3>
              <span className={`badge-mono ${recipe.kind === 'custom' ? 'badge-agent' : 'badge-neutral'}`}>
                {recipe.kind}
              </span>
              <StatusBadge status={recipe.status} />
            </div>
            <div className="mt-1 font-mono text-2xs text-fg-3 truncate">{recipe.recipeId}</div>
          </div>

          {recipe.kind === 'custom' && (
            <button
              className="btn-ghost btn-sm text-err"
              disabled={disabled || deleteM.isPending}
              onClick={() => deleteM.mutate()}
              title="Delete custom recipe"
            >
              <Trash2 size={13} />
            </button>
          )}
        </div>

        {recipe.description && (
          <p className="mt-3 text-sm text-fg-2 max-w-3xl">{recipe.description}</p>
        )}

        {recipe.decidedBy && recipe.decidedAt && (
          <div className="mt-2 text-2xs text-fg-3">
            {recipe.status} by <span className="font-mono">{recipe.decidedBy}</span>{' '}
            {new Date(recipe.decidedAt).toLocaleString('en-US', { hour12: false })}
          </div>
        )}
      </div>

      {/* Actions */}
      <div className="px-6 py-4 border-b border-line bg-surface/40 flex items-center gap-2">
        <button
          className={`btn-sm ${recipe.status === 'accepted' ? 'btn-primary' : 'btn-secondary'}`}
          disabled={disabled || decisionM.isPending}
          onClick={() => decide('accepted')}
        >
          <Check size={13} /> Accept
        </button>
        <button
          className={`btn-sm ${recipe.status === 'rejected' ? 'btn-primary' : 'btn-secondary'}`}
          disabled={disabled || decisionM.isPending}
          onClick={() => decide('rejected')}
        >
          <X size={13} /> Reject
        </button>
        <button
          className={`btn-sm ${recipe.status === 'proposed' ? 'btn-primary' : 'btn-ghost'}`}
          disabled={disabled || decisionM.isPending || recipe.status === 'proposed'}
          onClick={() => decide('proposed')}
        >
          Mark as proposed
        </button>
        {disabled && <span className="text-2xs text-fg-3 ml-2">Stage finalized — read only.</span>}
      </div>

      <div className="p-6 space-y-6">
        {/* Notes */}
        <div>
          <div className="flex items-center justify-between mb-2">
            <div className="eyebrow">Notes</div>
            {notesDirty && (
              <button
                className="btn-secondary btn-sm"
                disabled={disabled || decisionM.isPending}
                onClick={() => decisionM.mutate({ status: recipe.status, notes })}
              >
                <Save size={12} /> Save notes
              </button>
            )}
          </div>
          <textarea
            className="input w-full min-h-[80px] font-mono text-sm"
            placeholder="Why are you accepting/rejecting this recipe? Any pre-conditions?"
            value={notes}
            disabled={disabled}
            onChange={e => { setNotes(e.target.value); setNotesDirty(true); }}
          />
        </div>

        {/* Modules */}
        <div>
          <div className="eyebrow mb-2">Target modules</div>
          {recipe.modules.length === 0 ? (
            <div className="text-sm text-fg-3">
              {recipe.kind === 'custom'
                ? 'Custom recipe — modules will be selected during the strangler designer in Stage C.'
                : 'No modules tagged.'}
            </div>
          ) : (
            <ul className="grid grid-cols-2 gap-2">
              {recipe.modules.map(m => (
                <li key={m.id} className="surface p-2.5 flex items-center gap-2">
                  <Boxes size={14} className="text-fg-4" />
                  <div className="min-w-0">
                    <div className="text-sm font-medium text-fg-1">{m.name}</div>
                    <div className="font-mono text-2xs text-fg-3 truncate">{m.packageName}</div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>

        {/* Affected findings */}
        <div>
          <div className="flex items-center justify-between mb-2">
            <div className="eyebrow">Affected findings</div>
            <span className="text-2xs text-fg-3">
              {findingsQ.data?.findings.length ?? recipe.findingCount}
            </span>
          </div>
          {findingsQ.isLoading ? (
            <div className="skel h-16 w-full" />
          ) : (findingsQ.data?.findings.length ?? 0) === 0 ? (
            <div className="text-sm text-fg-3">
              {recipe.kind === 'custom'
                ? 'No findings linked yet — manually attach in Stage C.'
                : 'No findings linked.'}
            </div>
          ) : (
            <FindingsTable findings={findingsQ.data!.findings} />
          )}
        </div>
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: Recipe['status'] }) {
  if (status === 'accepted') return <span className="badge-ok badge-mono">accepted</span>;
  if (status === 'rejected') return <span className="badge-neutral badge-mono">rejected</span>;
  return <span className="badge-brand badge-mono">proposed</span>;
}

function FindingsTable({ findings }: { findings: RecipeFinding[] }) {
  return (
    <div className="surface-soft overflow-hidden">
      <table className="w-full text-sm">
        <thead className="bg-canvas border-b border-line text-2xs uppercase tracking-wider text-fg-3">
          <tr>
            <th className="text-left font-medium px-3 py-2 w-20">Sev</th>
            <th className="text-left font-medium px-3 py-2 w-44">Rule</th>
            <th className="text-left font-medium px-3 py-2">File</th>
            <th className="text-right font-medium px-3 py-2 w-20">Lines</th>
          </tr>
        </thead>
        <tbody>
          {findings.map(f => (
            <tr key={f.id} className="border-b border-line last:border-0 hover:bg-canvas">
              <td className="px-3 py-1.5">
                <SeverityPill sev={f.severity} />
              </td>
              <td className="px-3 py-1.5 font-mono text-2xs text-fg-2 truncate">{f.ruleId}</td>
              <td className="px-3 py-1.5 font-mono text-2xs text-fg-3 truncate" title={f.filePath}>
                {f.filePath}
              </td>
              <td className="px-3 py-1.5 text-right font-mono text-2xs text-fg-3">
                {f.lineStart != null ? `${f.lineStart}${f.lineEnd && f.lineEnd !== f.lineStart ? '–' + f.lineEnd : ''}` : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SeverityPill({ sev }: { sev: RecipeFinding['severity'] }) {
  const cls =
    sev === 'high'   ? 'badge-err'
  : sev === 'medium' ? 'badge-warn'
                     : 'badge-neutral';
  return <span className={`${cls} badge-mono`}>{sev}</span>;
}

/* ---------------- Custom recipe form ---------------- */

function CustomRecipeForm({
  projectId, onClose, onCreated
}: {
  projectId: string;
  onClose: () => void;
  onCreated: (id: string) => void;
}) {
  const qc = useQueryClient();
  const [recipeId, setRecipeId] = useState('atlas.recipes.');
  const [label, setLabel] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<string | null>(null);

  const createM = useMutation({
    mutationFn: () => api.createCustomRecipe(projectId, {
      recipeId: recipeId.trim(),
      label: label.trim(),
      description: description.trim() || undefined
    }),
    onSuccess: (r) => {
      setError(null);
      qc.invalidateQueries({ queryKey: ['recipes', projectId] });
      onCreated(r.id);
    },
    onError: (e: any) => setError(e?.message ?? 'Failed to create recipe.')
  });

  const valid = recipeId.trim().length > 5 && label.trim().length > 0;

  return (
    <form
      onSubmit={e => { e.preventDefault(); if (valid && !createM.isPending) createM.mutate(); }}
    >
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <label className="block">
          <span className="text-2xs text-fg-3 font-mono uppercase tracking-wider">Recipe ID</span>
          <input
            className="input mt-1 w-full font-mono text-sm"
            placeholder="atlas.recipes.MyMigration"
            value={recipeId}
            onChange={e => setRecipeId(e.target.value)}
            required
          />
        </label>
        <label className="block">
          <span className="text-2xs text-fg-3 font-mono uppercase tracking-wider">Label</span>
          <input
            className="input mt-1 w-full text-sm"
            placeholder="Replace Log4j 1.x with SLF4J"
            value={label}
            onChange={e => setLabel(e.target.value)}
            required
          />
        </label>
      </div>

      <label className="block mt-3">
        <span className="text-2xs text-fg-3 font-mono uppercase tracking-wider">Description</span>
        <textarea
          className="input mt-1 w-full text-sm min-h-[80px]"
          placeholder="What does this recipe do? Which modules will it touch?"
          value={description}
          onChange={e => setDescription(e.target.value)}
        />
      </label>

      {error && (
        <div role="alert" className="mt-3 text-xs text-err">{error}</div>
      )}

      <div className="mt-4 flex items-center gap-2 flex-wrap">
        <button
          type="submit"
          className="btn-primary btn-sm"
          disabled={!valid || createM.isPending}
        >
          {createM.isPending ? 'Creating…' : 'Create recipe'}
        </button>
        <button type="button" className="btn-ghost btn-sm" onClick={onClose}>Cancel</button>
        <span className="ml-auto text-2xs text-fg-3">
          Custom recipes are auto-accepted; attach them to modules in Stage C.
        </span>
      </div>
    </form>
  );
}
