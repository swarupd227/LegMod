import { useEffect } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  GitBranch, User, Calendar, FolderOpen
} from 'lucide-react';
import { api, Project } from '../api/client';
import { ModeBadge, RiskBadge } from '../components/Badges';
import {
  StageChromeHost, ProjectBackLink,
  LoadingState, ErrorState
} from '../components/ui';
import CodeArchaeology from './CodeArchaeology';
import RuntimeCapture from './RuntimeCapture';
import Reconciliation from './Reconciliation';
import CodeGeneration from './CodeGeneration';
import DifferentialLab from './DifferentialLab';
import ReportsHub from './ReportsHub';
import InventoryHeatmap from './InventoryHeatmap';
import RecipeAuthoring from './RecipeAuthoring';
import StranglerDesigner from './StranglerDesigner';
import ModuleMigration from './ModuleMigration';
import Characterization from './Characterization';
import CutoverDecommission from './CutoverDecommission';

const SOAP_STAGES = [
  { label: 'A', name: 'Code Archaeology' },
  { label: 'B', name: 'Runtime Capture' },
  { label: 'C', name: 'Schema Reconciliation' },
  { label: 'D', name: 'Code Generation' },
  { label: 'E', name: 'Differential Validation' },
  { label: 'F', name: 'Reports & Deliverables' }
];

const UPLIFT_STAGES = [
  { label: 'A', name: 'Inventory & Heatmap' },
  { label: 'B', name: 'Recipe Authoring' },
  { label: 'C', name: 'Strangler Designer' },
  { label: 'D', name: 'Module Migration' },
  { label: 'E', name: 'Characterization Validation' },
  { label: 'F', name: 'Cutover & Decommission' }
];

function stagesFor(mode?: string) {
  return mode === 'UPLIFT' ? UPLIFT_STAGES : SOAP_STAGES;
}

function nameForStage(label: string, mode?: string) {
  return stagesFor(mode).find(s => s.label === label)?.name ?? `Stage ${label}`;
}

export default function ProjectDetail() {
  const { id } = useParams();
  const projectId = id!;
  const [searchParams, setSearchParams] = useSearchParams();

  const { data: project, isLoading, error, refetch } = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.project(projectId)
  });

  // Stage to view comes from `?stage=X` on the URL. When unset (or set to
  // the current stage) we treat it as "follow the project's actual current
  // stage cursor." Whenever the project advances on the server we clear the
  // override so the user follows along automatically.
  const stageParam = searchParams.get('stage');
  const validStages = stagesFor(project?.mode).map(s => s.label);
  const viewStage = stageParam && validStages.includes(stageParam) ? stageParam : null;

  useEffect(() => {
    if (!project) return;
    if (viewStage && viewStage === project.currentStage) {
      // No need to keep the override — collapse to the default view.
      const next = new URLSearchParams(searchParams);
      next.delete('stage');
      setSearchParams(next, { replace: true });
    }
  }, [project?.currentStage]);   // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <LoadingState />;
  if (error || !project) {
    return <ErrorState title="Project not found" onRetry={() => refetch()} />;
  }

  const activeStage = viewStage ?? project.currentStage;
  const isReviewing = viewStage !== null && viewStage !== project.currentStage;

  const onSelectStage = (label: string) => {
    const next = new URLSearchParams(searchParams);
    if (label === project.currentStage) next.delete('stage');
    else next.set('stage', label);
    setSearchParams(next);
  };

  return (
    <>
      <ProjectBackLink />
      <ProjectHero project={project} />

      <StageChromeHost
        stages={stagesFor(project.mode)}
        current={project.currentStage}
        viewing={activeStage}
        onSelectStage={onSelectStage}
        track={project.mode === 'UPLIFT' ? 'UPLIFT' : 'SOAP'}
        title={nameForStage(activeStage, project.mode)}
        reviewing={isReviewing
          ? { onReturn: () => onSelectStage(project.currentStage) }
          : undefined}
      >
        <StageScreen projectId={projectId} project={project} stage={activeStage} />
      </StageChromeHost>
    </>
  );
}

function StageScreen({
  projectId, project, stage
}: {
  projectId: string;
  project: Project;
  stage: string;
}) {
  if (project.mode === 'UPLIFT') {
    switch (stage) {
      case 'A': return <InventoryHeatmap projectId={projectId} project={project} />;
      case 'B': return <RecipeAuthoring projectId={projectId} project={project} />;
      case 'C': return <StranglerDesigner projectId={projectId} project={project} />;
      case 'D': return <ModuleMigration projectId={projectId} project={project} />;
      case 'E': return <Characterization projectId={projectId} project={project} />;
      case 'F': return <CutoverDecommission projectId={projectId} project={project} />;
      default:  return null;
    }
  }
  switch (stage) {
    case 'A': return <CodeArchaeology projectId={projectId} project={project} />;
    case 'B': return <RuntimeCapture projectId={projectId} project={project} />;
    case 'C': return <Reconciliation projectId={projectId} project={project} />;
    case 'D': return <CodeGeneration projectId={projectId} project={project} />;
    case 'E': return <DifferentialLab projectId={projectId} project={project} />;
    case 'F': return <ReportsHub projectId={projectId} project={project} />;
    default:  return null;
  }
}

function ProjectHero({ project }: { project: Project }) {
  return (
    <section className="card p-6">
      <div className="flex items-start gap-2 flex-wrap">
        <ModeBadge mode={project.mode} />
        {project.vendorPartner && (
          <span className="text-xs text-fg-3">· {project.vendorPartner}</span>
        )}
        <RiskBadge tier={project.riskTier} />
      </div>

      <h1 className="mt-2 text-3xl font-semibold tracking-tight text-fg-1 text-balance">
        {project.name}
      </h1>

      {project.description && (
        <p className="mt-2 text-sm text-fg-2 max-w-3xl">{project.description}</p>
      )}

      <dl className="mt-4 flex flex-wrap gap-x-5 gap-y-2 text-xs text-fg-3" aria-label="Project metadata">
        <Meta icon={GitBranch}
              label="Frameworks"
              value={`${project.sourceFramework ?? '—'} → ${project.targetFramework ?? '—'}`} />
        <Meta icon={User} label="Owner" value={project.owner ?? '—'} />
        {(project as any).targetJavaVersion && (
          <Meta icon={Calendar} label="Java" value={`Java ${(project as any).targetJavaVersion}`} />
        )}
        {project.sourcePath && (
          <Meta icon={FolderOpen} label="Source" value={project.sourcePath} mono />
        )}
      </dl>
    </section>
  );
}

function Meta({
  icon: Icon, label, value, mono
}: { icon: any; label: string; value: string; mono?: boolean }) {
  return (
    <div className="inline-flex items-center gap-1.5">
      <Icon size={13} className="text-fg-4" aria-hidden="true" />
      <dt className="sr-only">{label}</dt>
      <dd className={mono ? 'font-mono text-fg-2' : 'text-fg-2'}>{value}</dd>
    </div>
  );
}
