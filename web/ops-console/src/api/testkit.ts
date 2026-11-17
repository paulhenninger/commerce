// The DEV testkit, through the ops service's proxy (/api/v1/ops/testkit/**).

import { api } from './client';

export interface ShopperSettings {
  count: number;
  ordersPerMinute: number;
  cancelRate: number;
  returnRate: number;
  declineRate: number;
  outOfStockRate: number;
}

export interface JournalEntry {
  seq: number;
  at: string;
  shopper: string;
  action: string;
  orderId: string | null;
  outcome: string;
  detail: string | null;
}

export interface ActorView {
  id: string;
  kind: 'producer' | 'consumer';
  type: string;
  topic: string;
  ratePerSecond: number;
  startedAt: string;
  running: boolean;
  counts: Record<string, number>;
  lastError: string | null;
}

export interface TestkitStatus {
  seeded: boolean;
  shoppers: { settings: ShopperSettings; ready: number; creating: number; inFlight: number; counts: Record<string, number> };
  actors: ActorView[];
  contractViolations: Record<string, number>;
  eventsChecked: Record<string, number>;
  recent: JournalEntry[];
}

export interface Fault {
  latencyMs: number;
  errorRate: number;
  outage: boolean;
}

const T = '/ops/testkit';

export const testkit = {
  status: () => api<TestkitStatus>(`${T}/status`),
  setShoppers: (s: ShopperSettings) => api<ShopperSettings>(`${T}/shoppers`, { method: 'PUT', body: JSON.stringify(s) }),
  start: (kind: string, type: string, topic: string, ratePerSecond: number) =>
    api<ActorView>(`${T}/actors`, { method: 'POST', body: JSON.stringify({ kind, type, topic, ratePerSecond }) }),
  stop: (id: string) => api<void>(`${T}/actors/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  stopAll: () => api<void>(`${T}/actors`, { method: 'DELETE' }),
  faults: () => api<Record<string, Fault>>(`${T}/faults`),
  setFault: (target: string, fault: Fault) =>
    api<Record<string, Fault>>(`${T}/faults/${target}`, { method: 'PUT', body: JSON.stringify(fault) }),
};

export const PRODUCERS: [string, string][] = [
  ['MALFORMED', 'Random bytes and broken JSON'],
  ['INVALID_SCHEMA', 'Well-formed events missing required fields'],
  ['UNKNOWN_TYPE', 'Valid events of a type nobody handles'],
  ['DUPLICATE', 'The same event, over and over'],
  ['ORPHAN', 'Valid events about orders that do not exist'],
  ['OVERSIZED', 'Records of 2 MiB'],
  ['FLOOD', 'Orphan events as fast as allowed'],
  ['UNAUTHORISED', 'A client with no rights: writes, reads, joins a service group'],
];

export const CONSUMERS: [string, string][] = [
  ['SLOW', 'Takes half a second per record'],
  ['CRASHING', 'Dies before committing, again and again'],
  ['FLAPPING', 'Joins and leaves its group every few seconds'],
  ['STUCK', 'Stops polling but keeps its connection'],
];

export const TOPICS = [
  'commerce.order.events.v1',
  'commerce.inventory.events.v1',
  'commerce.inventory.stock.v1',
  'commerce.billing.events.v1',
  'commerce.shipping.events.v1',
  'commerce.catalog.events.v1',
];

/** What the system should do about each actor (architecture, section 11). */
export const EXPECTED: Record<string, string> = {
  MALFORMED: 'Dead-lettered without retry; no consumer stalls.',
  INVALID_SCHEMA: 'Dead-lettered without retry; no consumer stalls.',
  UNKNOWN_TYPE: 'Skipped and counted as unhandled.',
  DUPLICATE: 'Applied once; the rest counted as duplicates.',
  ORPHAN: 'Counted as ignored; nothing changes.',
  OVERSIZED: 'Refused by the broker before it reaches the topic.',
  FLOOD: 'Lag rises on the consumers and recovers; the store keeps answering.',
  UNAUTHORISED: 'Refused by the broker every time.',
  SLOW: 'Its own group falls behind; nothing else slows.',
  CRASHING: 'Reprocesses in its own group forever; others unaffected.',
  FLAPPING: 'Rebalances stay inside its own group.',
  STUCK: 'Evicted from its group after 30 s; its lag grows.',
};
