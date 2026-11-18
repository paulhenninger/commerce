// The ops service's API, typed. Everything goes through api() (client.ts), which
// adds the access token and refreshes it.

import { api, ApiError, refreshSession, authHeaders } from './client';

export interface OpsConfig {
  environment: string;
  testkit: boolean;
  grafanaUrl: string;
  services: string[];
  replayRequiresMfa: boolean;
  instanceControl: boolean;
  experiments: boolean;
}

export interface InstanceRow {
  service: string;
  name: string;
  containerId: string | null;
  state: string;
  health: string | null;
  scraped: boolean;
  uptimeSeconds: number | null;
  heapBytes: number | null;
  relayLeader: boolean;
  partitions: Record<string, number[]>;
  brokerId: number | null;
  activeController: boolean | null;
  partitionsLed: number | null;
  controllable: boolean;
  address: string | null;
}

export interface Instances {
  control: boolean;
  rows: InstanceRow[];
}

export interface ServiceTile {
  name: string;
  instances: number;
  up: boolean;
  requestsPerSecond: number | null;
  errorRatio: number | null;
  p95Seconds: number | null;
  lag: number | null;
  outboxOldestSeconds: number | null;
  firingIncidents: number;
}

export interface Overview {
  environment: string;
  services: ServiceTile[];
  totals: Record<string, number | null>;
  funnel: Record<string, number | null>;
  firingIncidents: number;
  probes: Record<string, number | null>;
}

export interface Instance {
  instance: string;
  up: boolean;
  uptimeSeconds: number | null;
  heapBytes: number | null;
}

export interface ServiceDetail {
  name: string;
  instances: Instance[];
  lagByTopic: Record<string, number | null>;
  breakers: Record<string, number>;
  outboxPending: number | null;
  outboxOldestSeconds: number | null;
  links: Record<string, string>;
}

export interface Series {
  labels: Record<string, string>;
  points: [number, number | null][];
}

export interface LogLine {
  at: string;
  service: string;
  line: string;
}

export interface Broker {
  id: number;
  host: string;
  port: number;
  controller: boolean;
  leaderOf: number;
}

export interface TopicInfo {
  name: string;
  partitions: number;
  endOffset: number;
  records: number;
  underReplicated: number;
  deadLetter: boolean;
}

export interface GroupInfo {
  group: string;
  state: string;
  members: number;
  lagByTopic: Record<string, number>;
  partitionsByMember: Record<string, number[]>;
}

export interface Cluster {
  brokers: Broker[];
  topics: TopicInfo[];
  groups: GroupInfo[];
}

export interface DeadLetter {
  topic: string;
  partition: number;
  offset: number;
  at: string;
  key: string | null;
  originalTopic: string | null;
  originalOffset: string | null;
  consumerGroup: string | null;
  exception: string | null;
  reason: string | null;
  traceparent: string | null;
  payload: string;
  truncated: boolean;
}

export interface Note {
  id: number;
  author: string;
  note: string;
  at: string;
}

export interface Incident {
  id: number;
  alertname: string;
  severity: string;
  service: string | null;
  summary: string;
  description: string | null;
  runbookUrl: string | null;
  status: 'FIRING' | 'RESOLVED';
  startsAt: string;
  endsAt: string | null;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
  notes: Note[];
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Business {
  byStatus: Record<string, number | null>;
  lastHour: Record<string, number | null>;
  cancellationReasons: Record<string, number | null>;
  revenueLastHour: Record<string, number | null>;
  refundsLastHour: Record<string, number | null>;
  sagaSeconds: Record<string, number | null>;
  payments: Record<string, number | null>;
  lowestStock: Record<string, number | null>;
}

export interface LiveEvent {
  at: string;
  topic: string;
  type: string;
  key: string | null;
  traceId: string | null;
}

export interface Tick {
  at: string;
  perSecond: Record<string, number>;
  events: LiveEvent[];
}

const OPS = '/ops';

export const ops = {
  config: () => api<OpsConfig>(`${OPS}/config`),
  overview: () => api<Overview>(`${OPS}/overview`),
  service: (name: string) => api<ServiceDetail>(`${OPS}/services/${encodeURIComponent(name)}`),
  serviceSeries: (name: string, metric: string, range: string) =>
    api<Series[]>(`${OPS}/services/${encodeURIComponent(name)}/series?metric=${metric}&range=${range}`),
  errors: (name: string) => api<LogLine[]>(`${OPS}/services/${encodeURIComponent(name)}/errors`),
  business: () => api<Business>(`${OPS}/business`),
  businessSeries: (metric: string, range: string) => api<Series[]>(`${OPS}/business/series?metric=${metric}&range=${range}`),
  kafka: () => api<Cluster>(`${OPS}/kafka`),
  deadLetters: (topic: string) => api<DeadLetter[]>(`${OPS}/kafka/dead-letters/${encodeURIComponent(topic)}?limit=50`),
  replay: (topic: string, partition: number, offset: number) =>
    api<{ to: string; newOffset: number }>(`${OPS}/kafka/dead-letters/${encodeURIComponent(topic)}/replay`, {
      method: 'POST',
      body: JSON.stringify({ partition, offset }),
    }),
  experiments: () => api<ExperimentEntry[]>(`${OPS}/experiments`),
  runs: () => api<{ current: string; runs: ExperimentRun[] }>(`${OPS}/experiments/runs`),
  runExperiments: (names: string[]) =>
    api<ExperimentRun[]>(`${OPS}/experiments/run`, { method: 'POST', body: JSON.stringify({ names }) }),
  instances: () => api<Instances>(`${OPS}/instances`),
  act: (id: string, action: string) =>
    api<{ name: string; state: string }>(`${OPS}/instances/${encodeURIComponent(id)}/${action}`, { method: 'POST' }),
  incidents: (status: string | null, page: number) =>
    api<Page<Incident>>(`${OPS}/incidents?page=${page}&size=50${status ? `&status=${status}` : ''}`),
  incident: (id: number) => api<Incident>(`${OPS}/incidents/${id}`),
  acknowledge: (id: number) => api<Incident>(`${OPS}/incidents/${id}/ack`, { method: 'POST' }),
  note: (id: number, note: string) =>
    api<Incident>(`${OPS}/incidents/${id}/notes`, { method: 'POST', body: JSON.stringify({ note }) }),
};

/**
 * The live feed (Server-Sent Events). Read with fetch rather than EventSource,
 * which cannot send the Authorization header. Reconnects after a drop; the
 * returned function stops it.
 */
export function openLiveFeed(onTick: (tick: Tick) => void, onState: (connected: boolean) => void): () => void {
  let stopped = false;
  let controller: AbortController | null = null;

  async function connect() {
    while (!stopped) {
      controller = new AbortController();
      try {
        let res = await fetch(`/api/v1${OPS}/live`, {
          headers: authHeaders({ Accept: 'text/event-stream' }),
          credentials: 'include',
          signal: controller.signal,
        });
        if (res.status === 401 && (await refreshSession())) {
          res = await fetch(`/api/v1${OPS}/live`, {
            headers: authHeaders({ Accept: 'text/event-stream' }),
            credentials: 'include',
            signal: controller.signal,
          });
        }
        if (!res.ok || !res.body) throw new ApiError(res.status, 'Live feed unavailable');
        onState(true);
        const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
        let buffer = '';
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += value;
          let end: number;
          while ((end = buffer.indexOf('\n\n')) >= 0) {
            const block = buffer.slice(0, end);
            buffer = buffer.slice(end + 2);
            const data = block
              .split('\n')
              .filter((l) => l.startsWith('data:'))
              .map((l) => l.slice(5))
              .join('\n');
            if (data) {
              try {
                onTick(JSON.parse(data) as Tick);
              } catch {
                // A malformed frame is skipped, not fatal.
              }
            }
          }
        }
      } catch {
        if (stopped) return;
      }
      onState(false);
      await new Promise((r) => setTimeout(r, 3000));
    }
  }

  void connect();
  return () => {
    stopped = true;
    controller?.abort();
  };
}

export interface ExperimentEntry {
  name: string;
  title: string;
  hypothesis: string;
  needsHa: boolean;
  observeSeconds: number;
}

export type Verdict = 'RUNNING' | 'PASSED' | 'FAILED' | 'NOT_RUN' | 'NOT_APPLICABLE';

export interface ExperimentRun {
  id: string;
  experiment: string;
  title: string;
  hypothesis: string;
  startedAt: string;
  endedAt: string | null;
  verdict: Verdict;
  timeline: { at: string; text: string }[];
  checks: { name: string; passed: boolean; detail: string | null }[];
}
