import { Link, useLocation, useMatch } from 'react-router-dom';
import {
  LayoutGrid, Layers, ShieldCheck, ChevronDown, AlertTriangle, LogOut, DollarSign
} from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { logout } from '../auth/authClient';
import { LogoMark } from './Logo';
import { SkipLink, StatusDot } from './ui';

interface MeResponse {
  email: string;
  name: string;
  role: string;
  roles?: string[];
  demo?: boolean;
}

export default function Layout({ children }: { children: React.ReactNode }) {
  const { data: me } = useQuery({ queryKey: ['me'], queryFn: api.me as () => Promise<MeResponse> });
  const { data: workspaces } = useQuery({ queryKey: ['workspaces'], queryFn: api.workspaces });
  const loc = useLocation();

  const primary = [
    { to: '/',          label: 'Workspaces',   icon: LayoutGrid },
    { to: '/projects',  label: 'Projects',     icon: Layers },
    { to: '/audit',     label: 'Audit trail',  icon: ShieldCheck },
    // Phase 2L — LLM cost attribution. Per-user, per-project,
    // per-model spend rollup for the active workspace.
    { to: '/cost',      label: 'Spend',        icon: DollarSign }
  ];

  const initials = (me?.name ?? 'D U')
    .split(' ').map(p => p[0]).join('').slice(0, 2).toUpperCase();

  const activeWorkspace = workspaces?.[0];

  return (
    <div className="h-screen flex flex-col bg-canvas">
      <SkipLink />

      {/* Top bar */}
      <header className="topbar" role="banner">
        <Link to="/" className="flex items-center gap-3 pr-3 mr-1 border-r border-white/10 h-full"
              aria-label="Atlas Migrate home">
          <LogoMark />
        </Link>

        <Crumb workspaceName={activeWorkspace?.name} />

        <div className="ml-auto flex items-center gap-3">
          {me?.demo && (
            <span
              className="hidden md:inline-flex items-center gap-1.5 h-7 px-2.5 rounded-full
                         bg-warn/15 text-warn-50 border border-warn/40 text-2xs font-mono uppercase tracking-wider"
              role="status"
              aria-label="Demo identity provider — not a production sign-in">
              <AlertTriangle size={12} aria-hidden="true" />
              Demo identity
            </span>
          )}
          <span className="hidden md:inline-flex items-center gap-2 h-8 pl-1 pr-2 rounded-md text-white/85"
                aria-label={`Signed in as ${me?.name ?? 'user'}`}>
            <span aria-hidden="true"
                  className="h-6 w-6 rounded-full bg-gradient-to-br from-brand to-agent text-white text-[10px] font-semibold inline-flex items-center justify-center">
              {initials}
            </span>
            <span className="text-sm">{me?.name ?? '…'}</span>
          </span>
          <button
            type="button"
            onClick={() => { void logout(); }}
            className="inline-flex items-center gap-1.5 h-8 px-2.5 rounded-md text-white/65 hover:text-white hover:bg-white/10 text-xs"
            title="Sign out">
            <LogOut size={13} aria-hidden="true" />
            <span className="hidden sm:inline">Sign out</span>
          </button>
        </div>
      </header>

      <div className="flex-1 flex min-h-0">
        {/* Left nav */}
        <aside className="w-[var(--nav-w)] shrink-0 bg-surface border-r border-line flex flex-col"
               aria-label="Primary navigation">
          <div className="px-5 py-3 border-b border-line">
            <div className="eyebrow">Workspace</div>
            <div className="mt-1 flex items-center justify-between">
              <span className="text-sm font-semibold text-fg-1 truncate">
                {activeWorkspace?.name ?? '…'}
              </span>
              <ChevronDown size={14} className="text-fg-3" aria-hidden="true" />
            </div>
          </div>

          <nav className="py-2 flex-1 overflow-y-auto">
            <SectionLabel label="Engagement" />
            <NavList items={primary} loc={loc.pathname} />
          </nav>
        </aside>

        {/* Main */}
        <main id="main-content" tabIndex={-1} className="flex-1 min-w-0 overflow-y-auto focus:outline-none">
          <div className="px-[var(--screen-pad-x)] py-7 max-w-[var(--content-max)] mx-auto">
            {children}
          </div>
        </main>
      </div>

      {/* Status bar */}
      <ServiceStatusFooter />
    </div>
  );
}

function SectionLabel({ label }: { label: string }) {
  return <div className="px-5 pt-2 pb-1 eyebrow">{label}</div>;
}

function NavList({
  items, loc
}: { items: { to: string; label: string; icon: any }[]; loc: string }) {
  return (
    <ul className="space-y-0.5" role="list">
      {items.map(({ to, label, icon: Icon }) => {
        const active = loc === to || (to !== '/' && loc.startsWith(to));
        return (
          <li key={to}>
            <Link
              to={to}
              aria-current={active ? 'page' : undefined}
              className={`nav-item ${active ? 'nav-item-active' : ''}`}
            >
              <Icon size={15} strokeWidth={1.75}
                    className={active ? 'text-fg-1' : 'text-fg-3'}
                    aria-hidden="true" />
              <span>{label}</span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}

function Crumb({ workspaceName }: { workspaceName?: string }) {
  // Reflect the actual route. Project routes are matched explicitly so we
  // can show "Workspace / Projects / <Project name>" when applicable.
  const projectMatch = useMatch('/projects/:id');
  const isProjects = useMatch('/projects');
  const isAudit = useMatch('/audit');
  const isCost = useMatch('/cost');

  const segments: string[] = [];
  if (workspaceName) segments.push(workspaceName);
  if (projectMatch) {
    segments.push('Projects');
    segments.push('Project detail');
  } else if (isProjects) {
    segments.push('Projects');
  } else if (isAudit) {
    segments.push('Audit trail');
  } else if (isCost) {
    segments.push('Spend');
  } else {
    segments.push('Workspaces');
  }

  return (
    <nav className="hidden md:flex items-center gap-2 text-sm text-white/65" aria-label="Breadcrumb">
      <ol role="list" className="flex items-center gap-2">
        {segments.map((s, i) => {
          const last = i === segments.length - 1;
          return (
            <li key={i} className="flex items-center gap-2">
              {i > 0 && <span aria-hidden="true" className="text-white/35">/</span>}
              <span className={last ? 'text-white' : 'text-white/65'} aria-current={last ? 'page' : undefined}>
                {s}
              </span>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/**
 * Reads the api-gateway's actuator/health endpoint to surface real status.
 * Falls back to a neutral state if unreachable.
 */
function ServiceStatusFooter() {
  const { data, isError } = useQuery({
    queryKey: ['health'],
    queryFn: async () => {
      // Gateway runs on the same host on port 8080 in dev/local-docker.
      // VITE_API_BASE override is honoured if set at build time.
      const base =
        (import.meta.env.VITE_API_BASE as string | undefined) ||
        `${window.location.protocol}//${window.location.hostname}:8080`;
      const res = await fetch(`${base}/actuator/health`).catch(() => null);
      if (!res || !res.ok) throw new Error('unhealthy');
      return res.json() as Promise<{ status: string }>;
    },
    refetchInterval: 30_000,
    retry: false
  });

  const healthy = !isError && data?.status?.toUpperCase() === 'UP';
  const tone = isError ? 'err' : healthy ? 'ok' : 'warn';
  const label = isError ? 'gateway unreachable' : healthy ? 'all services healthy' : 'checking…';

  return (
    <footer className="h-[var(--status-h)] bg-surface border-t border-line flex items-center px-5 text-2xs text-fg-3"
            role="contentinfo">
      <span className="font-mono">atlas-migrate</span>
      <span aria-hidden="true" className="text-fg-4 mx-2">·</span>
      <span>v0.3.0</span>
      <div className="ml-auto flex items-center gap-3">
        <span className="inline-flex items-center gap-1.5"
              aria-live="polite"
              aria-atomic="true">
          <StatusDot tone={tone} pulse={tone !== 'err'} />
          {label}
        </span>
      </div>
    </footer>
  );
}
