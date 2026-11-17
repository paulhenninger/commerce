import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { messageOf } from '../api/client';
import { ops, type OpsConfig } from '../api/ops';

interface State {
  config: OpsConfig | null;
  error: string | null;
}

const Context = createContext<State>({ config: null, error: null });

export function OpsConfigProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<State>({ config: null, error: null });
  useEffect(() => {
    ops
      .config()
      .then((config) => setState({ config, error: null }))
      .catch((e) => setState({ config: null, error: messageOf(e) }));
  }, []);
  return <Context.Provider value={state}>{children}</Context.Provider>;
}

export function useOpsConfig(): State {
  return useContext(Context);
}

/** A Grafana explore link for a trace id. */
export function traceLink(grafanaUrl: string, traceId: string): string {
  const left = JSON.stringify({ datasource: 'tempo', queries: [{ refId: 'A', queryType: 'traceql', query: traceId }] });
  return `${grafanaUrl.replace(/\/+$/, '')}/explore?left=${encodeURIComponent(left)}`;
}
