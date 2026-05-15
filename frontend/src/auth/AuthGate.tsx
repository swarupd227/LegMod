import { useEffect, useState } from 'react';
import { Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { getToken, login, handleCallback } from './authClient';

/**
 * Wraps the app and forces an OIDC login if no valid token is present.
 *
 * <ul>
 *   <li>No token → redirect to the issuer's /authorize (login flow).</li>
 *   <li>On /auth/callback?code=… → exchange the code for a token, then
 *       navigate to the path captured before login.</li>
 *   <li>Has a valid token → render children.</li>
 * </ul>
 */
export function AuthGate({ children }: { children: React.ReactNode }) {
  const loc = useLocation();
  const isCallback = loc.pathname === '/auth/callback';

  const [ready, setReady] = useState<boolean>(() => Boolean(getToken()) && !isCallback);

  useEffect(() => {
    if (isCallback) return;        // CallbackHandler manages this case
    if (getToken()) { setReady(true); return; }
    // Capture the deep-link target so the user lands back here after login.
    const target = loc.pathname + loc.search + loc.hash;
    void login(target === '/auth/callback' ? '/' : target);
  }, [isCallback, loc.pathname, loc.search, loc.hash]);

  if (isCallback) return <CallbackHandler />;

  if (!ready) {
    return (
      <div className="min-h-screen grid place-items-center text-fg-3 text-sm">
        Redirecting to sign-in…
      </div>
    );
  }

  return <>{children}</>;
}

function CallbackHandler() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (params.get('error')) {
      setError(params.get('error')!);
      return;
    }
    handleCallback()
      .then(returnPath => navigate(returnPath, { replace: true }))
      .catch(e => setError(String(e?.message ?? e)));
  }, [params, navigate]);

  if (error) {
    return (
      <div className="min-h-screen grid place-items-center px-6">
        <div className="card p-6 max-w-md">
          <div className="eyebrow text-err">Sign-in failed</div>
          <h2 className="text-lg font-semibold mt-1">Could not complete login</h2>
          <p className="text-sm text-fg-2 mt-2">{error}</p>
          <button className="btn-primary mt-4" onClick={() => (window.location.href = '/')}>
            Try again
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen grid place-items-center text-fg-3 text-sm">
      Completing sign-in…
    </div>
  );
}

export function RequireToken({ children }: { children: React.ReactNode }) {
  return getToken() ? <>{children}</> : <Navigate to="/" replace />;
}
