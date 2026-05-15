import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';

interface AnnouncerCtx {
  announce: (message: string, priority?: 'polite' | 'assertive') => void;
}

const Ctx = createContext<AnnouncerCtx>({ announce: () => {} });

/**
 * Provides a globally-available `announce()` for in-flight state changes
 * (mutation success/failure, items reordered, etc.). Renders a pair of
 * visually hidden aria-live regions that screen readers monitor.
 */
export function AnnouncerProvider({ children }: { children: React.ReactNode }) {
  const [politeMsg, setPoliteMsg] = useState('');
  const [assertiveMsg, setAssertiveMsg] = useState('');
  const counter = useRef(0);

  const announce = useCallback((message: string, priority: 'polite' | 'assertive' = 'polite') => {
    // Each call gets a unique key by appending a tiny invisible counter so the
    // same message announced twice still triggers a re-read.
    counter.current = (counter.current + 1) % 1000;
    const tag = '​'.repeat(counter.current % 4 + 1);
    const text = `${message}${tag}`;
    if (priority === 'assertive') setAssertiveMsg(text);
    else setPoliteMsg(text);
  }, []);

  return (
    <Ctx.Provider value={{ announce }}>
      {children}
      <div className="sr-only" role="status" aria-live="polite" aria-atomic="true">{politeMsg}</div>
      <div className="sr-only" role="alert"  aria-live="assertive" aria-atomic="true">{assertiveMsg}</div>
    </Ctx.Provider>
  );
}

export function useAnnouncer() {
  return useContext(Ctx);
}
