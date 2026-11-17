import { useCallback, useEffect, useRef, useState } from 'react';
import { messageOf } from '../api/client';

/**
 * Fetches now and then every `intervalMs`, pausing while the tab is hidden (an
 * unwatched console should not load Prometheus). Keeps showing the last good
 * data when a fetch fails, and says how old it is.
 */
export function usePoll<T>(fetcher: () => Promise<T>, intervalMs: number, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  const load = useCallback(async () => {
    try {
      setData(await fetcherRef.current());
      setError(null);
      setUpdatedAt(Date.now());
    } catch (e) {
      setError(messageOf(e));
    }
  }, []);

  useEffect(() => {
    setData(null);
    let timer: ReturnType<typeof setTimeout> | undefined;
    let cancelled = false;
    const tick = async () => {
      if (!document.hidden) await load();
      if (!cancelled) timer = setTimeout(tick, intervalMs);
    };
    void tick();
    const onVisible = () => {
      if (!document.hidden) void load();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      cancelled = true;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, load, ...deps]);

  return { data, error, updatedAt, reload: load };
}
