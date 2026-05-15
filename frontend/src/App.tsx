import { Routes, Route, Navigate } from 'react-router-dom';
import Layout from './components/Layout';
import WorkspaceDashboard from './screens/WorkspaceDashboard';
import ProjectDetail from './screens/ProjectDetail';
import AuditBrowser from './screens/AuditBrowser';
import WorkspaceCost from './screens/WorkspaceCost';
import { AnnouncerProvider } from './components/ui';
import { AuthGate } from './auth/AuthGate';

export default function App() {
  return (
    <AnnouncerProvider>
      <AuthGate>
        <Layout>
          <Routes>
            <Route path="/" element={<WorkspaceDashboard />} />
            <Route path="/projects" element={<WorkspaceDashboard />} />
            <Route path="/projects/:id" element={<ProjectDetail />} />
            <Route path="/audit" element={<AuditBrowser />} />
            <Route path="/cost"  element={<WorkspaceCost />} />
            {/* /auth/callback is intercepted by AuthGate before this Routes block,
                but list it so the catch-all doesn't shadow direct hits. */}
            <Route path="/auth/callback" element={<div />} />
            {/* Catch-all: send to the workspace dashboard rather than show a placeholder. */}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Layout>
      </AuthGate>
    </AnnouncerProvider>
  );
}
